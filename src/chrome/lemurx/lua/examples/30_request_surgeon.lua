-- 案例 30 · 每一个子资源请求都过 Lua 的手：同步改写 / 拦截 / 改请求头
--
-- 扩展做不到的点：
--   * MV3 只剩 declarativeNetRequest，静态规则表，不能写逻辑；webRequest 阻塞式早被砍了，
--     Android 上更是从来没有过。
--   * 这里是渲染进程里的 luakit "send-request" 信号：每个 img/script/xhr/fetch/iframe 请求
--     发出前同步进 Lua，返回字符串 = 重定向，返回 false = 拦截，headers 表随便增删改。
--   * 规则是活的：UI 进程里的 Lua 随时 emit 新规则表，所有渲染进程即刻生效，不用重载页面。
--
-- 效果：去掉所有请求里的 utm_*/fbclid/gclid 追踪参数；把 http 图片升级成 https；
--       拦截一批统计脚本；给每个请求带上 DNT: 1 并抹掉 Referer 的路径部分；
--       在网页正上方显示这一页拦了多少、改了多少。

local WM_NAME = "request_surgeon_wm"

local WM_SOURCE = [==[
local ui = ipc_channel("request_surgeon_wm")

local rules = {
    block = { "google%-analytics%.com", "doubleclick%.net", "googlesyndication", "hm%.baidu%.com/hm%.js",
              "cnzz%.com", "umeng%.com", "growingio", "sentry%-cdn", "hotjar" },
    strip_params = { "utm_%w+", "fbclid", "gclid", "spm", "from", "share_token", "srcid" },
    upgrade_http = true,
    dnt = true,
    trim_referer = true,
}

-- UI 侧随时可以整表替换（热更新）
ui:add_signal("rules", function(_, _, r) rules = r end)

local function strip_params(uri)
    local base, query, frag = uri:match("^([^?#]*)%??([^#]*)(#?.*)$")
    if not query or query == "" then return uri end
    local kept = {}
    for pair in query:gmatch("[^&]+") do
        local key = pair:match("^([^=]*)")
        local drop = false
        for _, pat in ipairs(rules.strip_params) do
            if key:match("^" .. pat .. "$") then drop = true break end
        end
        if not drop then kept[#kept + 1] = pair end
    end
    if #kept == 0 then return base .. frag end
    return base .. "?" .. table.concat(kept, "&") .. frag
end

local counters = {}   -- page.id -> { blocked=, rewritten= }

luakit.add_signal("page-created", function(page)
    page:add_signal("send-request", function(p, uri, headers)
        local c = counters[p.id]
        if not c then c = { blocked = 0, rewritten = 0 } counters[p.id] = c end

        -- 1) 拦截
        for _, pat in ipairs(rules.block) do
            if uri:find(pat) then
                c.blocked = c.blocked + 1
                ui:emit_signal("count", p.id, c)
                return false
            end
        end

        -- 2) 请求头
        if rules.dnt then headers["DNT"] = "1" end
        if rules.trim_referer and headers["Referer"] then
            headers["Referer"] = headers["Referer"]:match("^(%a+://[^/]+)") .. "/"
        end

        -- 3) 改 URL
        local out = strip_params(uri)
        if rules.upgrade_http and out:sub(1, 7) == "http://" then
            out = "https://" .. out:sub(8)
        end
        if out ~= uri then
            c.rewritten = c.rewritten + 1
            ui:emit_signal("count", p.id, c)
            return out
        end
    end)
    page:add_signal("document-loaded", function(p)
        -- 新文档开始：清零
        counters[p.id] = nil
    end)
end)
]==]

local function install_wm(name, src)
    local path = luakit.config_dir .. "/" .. name .. ".lua"
    local f = io.open(path, "r")
    if f then
        local old = f:read("a")
        f:close()
        if old == src then return end
    end
    f = assert(io.open(path, "w"))
    f:write(src)
    f:close()
end
install_wm(WM_NAME, WM_SOURCE)

local wm = require_web_module(WM_NAME)
local h = lemurx.ui.h

-- 渲染进程汇报计数 → 只在“当前标签”那一页显示
wm:add_signal("count", function(_, page_id, c)
    local cur = lemurx.tabs.current()
    if not cur or cur.id ~= page_id then return end
    lemurx.ui.render("page.top", h("row", { id = "surgeon_strip", background = "#E63B0764", padding = { 10, 3 } }, {
        h("text", {
            id = "surgeon_text", weight = 1, color = "#FFFFFFFF", size = 11,
            text = ("请求手术：拦截 %d · 改写 %d（追踪参数/HTTP 升级）"):format(c.blocked, c.rewritten),
        }),
        h("button", {
            id = "surgeon_off", text = "关掉拦截", size = 11,
            onClick = function()
                -- 热更新：只关拦截，其它照旧。不用刷新页面，所有渲染进程同时生效
                wm:emit_signal("rules", {
                    block = {}, strip_params = { "utm_%w+", "fbclid", "gclid" },
                    upgrade_http = true, dnt = true, trim_referer = false,
                })
                lemurx.toast("拦截已关（改写仍在）")
            end,
        }),
    }))
end)

-- 底栏菜单：一键切「白名单本站」——从 UI 进程往渲染进程推规则
lemurx.menu.add({
    id = "surgeon_allow_site", title = "本站不拦截", page = "main",
    onClick = function()
        local t = lemurx.tabs.current()
        if not t then return end
        local host = t.url:match("^%a+://([^/]+)")
        lemurx.storage.set("surgeon_allow_" .. host, "1")
        -- 只推给这个标签所在的渲染进程
        local view = __lk.webview_for_tab(t.id)
        if view then
            wm:emit_signal(view, "rules", { block = {}, strip_params = {}, upgrade_http = false, dnt = false, trim_referer = false })
        end
        lemurx.tabs.reload(t.id)
        lemurx.toast(host .. " 已放行")
    end,
})

lemurx.log("[案例30] 请求手术已装载：所有子资源请求经 Lua 同步裁决")
