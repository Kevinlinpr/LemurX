-- 案例 60 · 导航守门人：每一次跳转在提交之前先问 Lua
--
-- 扩展做不到的点：
--   * 扩展的 webNavigation 只是“事后通知”，不能否决；Android Chrome 连 webRequest 阻塞都没有。
--     这里挂的是 Chromium 的 NavigationThrottle：Lua 返回 false，导航就不会发生，
--     页面不会白一下、历史不会多一条。
--   * 弹窗/新窗口（window.open、target=_blank）同样先问 Lua（new-window-decision）。
--   * 决策可以用浏览器进程里的任何东西：sqlite 记账、当前时间、剪贴板、本地文件……
--
-- 这份脚本做了四件事：
--   1. 拆中间跳转页（link.zhihu.com/?target=、www.google.com/url?q= 等）——直接改去真实地址
--   2. AMP 页面自动跳回原站
--   3. 夜间 23:00–06:00 打开短视频站要先“冷静 3 秒”（可点继续）
--   4. 所有被拦/被改写的导航记进 sqlite，lua://gate 可查（配合案例 40 的 scheme，没装也不影响）

local webview = require("webview")
-- soup 是 luakit 全局（soup.parse_uri）

-- ---------- sqlite 记账（luakit 原生 sqlite3 类） ----------
local db = sqlite3{ filename = luakit.data_dir .. "/gatekeeper.db" }
db:exec([[CREATE TABLE IF NOT EXISTS log (
    ts INTEGER, kind TEXT, from_uri TEXT, to_uri TEXT
)]])
local function log(kind, from, to)
    db:exec("INSERT INTO log VALUES (?, ?, ?, ?)", { os.time(), kind, from or "", to or "" })
end

-- ---------- 规则 ----------
-- 中间页：pattern → 取真实地址的参数名
local REDIRECTORS = {
    { host = "link%.zhihu%.com",         param = "target" },
    { host = "www%.google%.com",         path = "^/url",   param = "q" },
    { host = "link%.csdn%.net",          param = "target" },
    { host = "gate%.sc%.qq%.com",        param = "url" },
    { host = "c%.pc%.qq%.com",           param = "url" },
    { host = "steamcommunity%.com",      path = "^/linkfilter", param = "url" },
}

local COOL_DOWN_HOSTS = { "douyin%.com", "kuaishou%.com", "bilibili%.com", "tiktok%.com" }

local function unwrap_redirector(uri)
    local u = soup.parse_uri(uri)
    if not u or not u.host then return nil end
    for _, r in ipairs(REDIRECTORS) do
        if u.host:match(r.host) and (not r.path or (u.path or ""):match(r.path)) then
            local target = (u.query or ""):match("[?&]?" .. r.param .. "=([^&]+)")
            if target then
                target = luakit.uri_decode(target)
                if target:match("^https?://") then return target end
            end
        end
    end
end

local function unamp(uri)
    -- https://xxx.cdn.ampproject.org/c/s/example.com/path → https://example.com/path
    local rest = uri:match("^https?://[%w%-%.]+%.cdn%.ampproject%.org/[cv]/s/(.+)$")
    if rest then return "https://" .. rest end
    rest = uri:match("^https?://www%.google%.com/amp/s/(.+)$")
    if rest then return "https://" .. rest end
    -- 站内 amp 路径：/amp/ 或 ?amp=1 / .amp
    if uri:match("/amp/") or uri:match("[?&]amp=1") or uri:match("%.amp$") then
        local out = uri:gsub("/amp/", "/"):gsub("[?&]amp=1", ""):gsub("%.amp$", "")
        if out ~= uri then return out end
    end
end

local function is_cool_down_time()
    local h = tonumber(os.date("%H"))
    return h >= 23 or h < 6
end

local cooled = {}   -- host -> 放行到的时间戳

-- ---------- 挂到每个 webview ----------
local function hook(view)
    view:add_signal("navigation-request", function(v, uri, reason)
        -- 只管主框架 + 用户可见的导航；子框架资源不在这层
        if not uri:match("^https?://") then return end

        -- 1) 中间页 → 直接换目标
        local real = unwrap_redirector(uri)
        if real then
            log("redirector", uri, real)
            v.uri = real
            return false
        end

        -- 2) AMP → 原站
        local canon = unamp(uri)
        if canon then
            log("amp", uri, canon)
            v.uri = canon
            return false
        end

        -- 3) 夜间冷静
        if is_cool_down_time() then
            local host = uri:match("^%a+://([^/]+)")
            for _, pat in ipairs(COOL_DOWN_HOSTS) do
                if host and host:match(pat) then
                    if (cooled[host] or 0) > os.time() then return end   -- 已放行
                    log("cooldown", v.uri, uri)
                    -- 用 Lua 现场生成一张页面替代目标（load_string 不进历史）
                    v:load_string(([[
<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<body style="background:#0b1f33;color:#d9e2ec;font:16px sans-serif;display:flex;align-items:center;
justify-content:center;height:100vh;margin:0;text-align:center">
<div><h2>现在是 %s</h2><p>你要去 <b>%s</b></p><p id="c">3</p>
<button id="go" disabled style="padding:10px 24px;border:0;border-radius:8px;background:#3e8ed0;color:#fff;font-size:16px">继续</button>
<script>let n=3;const t=setInterval(()=>{n--;document.getElementById('c').textContent=n>0?n:'';
if(n<=0){clearInterval(t);const b=document.getElementById('go');b.disabled=false;
b.onclick=()=>location.href=%q;}},1000);</script></div></body>]]):format(os.date("%H:%M"), host, uri), uri)
                    cooled[host] = os.time() + 600   -- 放行 10 分钟
                    return false
                end
            end
        end
    end)

    -- 弹窗决策：广告位常用 window.open；只允许同站新窗口
    view:add_signal("new-window-decision", function(v, uri, reason)
        local from = (v.uri or ""):match("^%a+://([^/]+)") or ""
        local to = (uri or ""):match("^%a+://([^/]+)") or ""
        local same = from ~= "" and (to == from or to:sub(-#from) == from)
        if not same then
            log("popup-blocked", v.uri, uri)
            lemurx.toast("拦了一个弹窗：" .. to)
            return false
        end
    end)

    -- 查账页 lua://gate（若案例 40 已注册 lua://，这里只是多挂一个处理器）
    view:add_signal("scheme-request::lua", function(v, uri, request)
        if not uri:match("^lua://gate") then return end
        local rows = db:exec("SELECT ts, kind, from_uri, to_uri FROM log ORDER BY ts DESC LIMIT 200")
        local out = { "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>",
                      "<style>body{font:13px monospace;background:#0b1f33;color:#d9e2ec;padding:10px}",
                      "td{padding:4px 6px;vertical-align:top;word-break:break-all}</style>",
                      "<h3>导航守门人 · 最近 200 条</h3><table>" }
        for _, r in ipairs(rows or {}) do
            out[#out + 1] = ("<tr><td>%s</td><td>%s</td><td>%s<br>→ %s</td></tr>"):format(
                os.date("%m-%d %H:%M", r.ts), r.kind, r.from_uri, r.to_uri)
        end
        out[#out + 1] = "</table>"
        request:finish(table.concat(out), "text/html")
    end)
end

pcall(luakit.register_scheme, "lua")   -- 重复注册只会 warn
webview.add_signal("init", hook)
for _, t in ipairs(lemurx.tabs.list() or {}) do
    local v = __lk.webview_for_tab(t.id)
    if v then hook(v) else __lk.webview_for_tab(t.id, true) end
end

lemurx.menu.add({ id = "gate_log", title = "守门人记录", page = "main",
    onClick = function() lemurx.tabs.open("lua://gate") end })

lemurx.log("[案例60] 导航守门人已就位（中间页/AMP/夜间冷静/弹窗）")
