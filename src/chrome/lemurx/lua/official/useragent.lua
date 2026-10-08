-- @name UA 切换
-- @description 按站点或全局切换 User-Agent（Chrome 桌面 / Edge / Firefox / Safari / iPhone / iPad / 微信 / 搜索引擎爬虫 / 自定义），请求头、navigator.userAgent、客户端提示一起变。
-- @version 1.0.0
-- @icon 🎭
-- @category 开发
-- @page lemurx://useragent/
-- @replaces User-Agent Switcher for Chrome · User-Agent Switcher and Manager
--
-- 依赖的底层能力（本次补的）：
--   lemurx.tabs.setUserAgent(id, ua, {platform=, mobile=, reload=})
--     → WebContents::SetUserAgentOverride：请求头 / navigator.userAgent / Sec-CH-UA* 一致；
--       TabImpl 打了补丁，Chrome 自己的"桌面版网站"逻辑不会把它覆盖回去，换 WebContents 也会重设。
--   lemurx.chrome.userAgent() → 本机默认 UA（取 Chrome 版本号）
--
-- 规则优先级：站点规则 > 全局设置 > 不动。改了规则后当前正在看该站点的标签立即重载生效。
local lx = require("lx")
local json = require("lx.json")
local util = require("lx.util")

local ID = "useragent"
local S

-- 本机 Chrome 大版本
local function chrome_version()
    local ok, ua = pcall(function() return lemurx.chrome.userAgent() end)
    local v = ok and type(ua) == "string" and ua:match("Chrome/([%d%.]+)") or nil
    return v or "140.0.0.0"
end
local CV = chrome_version()
local CV_MAJOR = CV:match("^(%d+)") or "140"

-- 预设：id, 名称, UA 模板, 客户端提示 platform（"" = 不发提示）, mobile 位, 是否要桌面视口
local PRESETS = {
    { "default",       "浏览器默认（不切换）", nil, nil, nil, false },
    { "chrome_win",    "Chrome · Windows",  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{cv} Safari/537.36", "Windows", false, true },
    { "chrome_mac",    "Chrome · macOS",    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{cv} Safari/537.36", "macOS", false, true },
    { "chrome_linux",  "Chrome · Linux",    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{cv} Safari/537.36", "Linux", false, true },
    { "edge_win",      "Edge · Windows",    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{cv} Safari/537.36 Edg/{cv}", "Windows", false, true },
    { "firefox_win",   "Firefox · Windows", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0", "", false, true },
    { "safari_mac",    "Safari · macOS",    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15", "", false, true },
    { "safari_iphone", "Safari · iPhone",   "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1", "", true, false },
    { "safari_ipad",   "Safari · iPad",     "Mozilla/5.0 (iPad; CPU OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1", "", false, true },
    { "chrome_android","Chrome · Android 手机", "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{cv} Mobile Safari/537.36", "Android", true, false },
    { "chrome_tablet", "Chrome · Android 平板", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{cv} Safari/537.36", "Android", false, true },
    { "wechat",        "微信内置浏览器",      "Mozilla/5.0 (Linux; Android 15; Pixel 8 Build/AP4A.250105.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/{cv} Mobile Safari/537.36 XWEB/1300197 MMWEBSDK/20241202 MMWEBID/1234 MicroMessenger/8.0.56.2800(0x28003835) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64", "Android", true, false },
    { "googlebot",     "Googlebot",         "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Googlebot/2.1; +http://www.google.com/bot.html) Chrome/{cv} Safari/537.36", "", false, true },
    { "bingbot",       "Bingbot",           "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm) Chrome/{cv} Safari/537.36", "", false, true },
    { "ie11",          "IE 11（老网银 / 老系统）", "Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko", "", false, true },
    { "custom",        "自定义字符串",        "{custom}", "", nil, false },
}
local PRESET_BY_ID = {}
for _, p in ipairs(PRESETS) do PRESET_BY_ID[p[1]] = p end

S = lx.register({
    id = ID, name = "UA 切换", version = "1.0.0", icon = "🎭",
    description = "按站点或全局切换 User-Agent：Chrome 桌面 / Edge / Firefox / Safari / iPhone / iPad / 微信 / Googlebot / 自定义。请求头、navigator.userAgent、客户端提示（Sec-CH-UA）一起变，Chrome 自己的桌面站点开关不会把它覆盖回去。",
    replaces = "User-Agent Switcher for Chrome · User-Agent Switcher and Manager",
    settings = {
        enabled = true,
        global = "default",
        custom_ua = "",
        rules = {},              -- { {host="*.example.com", preset="chrome_win"}, ... }
        desktop_viewport = true, -- 桌面 UA 时同时切桌面视口（tabs.setDesktop）
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用", section = "全局" },
        { key = "global", type = "select", label = "全局 UA（站点规则优先）", options = (function() local o = {} for _, p in ipairs(PRESETS) do o[#o + 1] = { p[1], p[2] } end return o end)() },
        { key = "custom_ua", type = "text", label = "自定义 UA 字符串", placeholder = "选「自定义字符串」时用这一条" },
        { key = "desktop_viewport", type = "bool", label = "桌面 UA 同时切桌面视口" },
    },
    menu = {
        { id = "site", title = "切换当前网站的 UA", onClick = function() S.pick_for_current() end },
        { id = "open", title = "UA 切换设置", page = "main" },
    },
    api = {},
})
local settings = S.settings

-- ===== 解析 =====
local function resolve_preset(id)
    local p = PRESET_BY_ID[id]
    if not p or p[1] == "default" then return nil end
    local ua = p[3]
    if p[1] == "custom" then
        ua = (settings:get("custom_ua") or ""):gsub("^%s+", ""):gsub("%s+$", "")
        if ua == "" then return nil end
        return { id = "custom", ua = ua, platform = "", mobile = ua:find("Mobile", 1, true) ~= nil, desktop = not ua:find("Mobile", 1, true) }
    end
    ua = ua:gsub("{cv}", CV)
    return { id = p[1], ua = ua, platform = p[4] or "", mobile = p[5] and true or false, desktop = p[6] and true or false }
end
S.resolve_preset = resolve_preset

-- 站点 → 预设 id（规则 > 全局）
function S.preset_for(host)
    if not settings:get("enabled") then return "default" end
    if host and host ~= "" then
        for _, r in ipairs(settings:get("rules") or {}) do
            if type(r) == "table" and r.host and util.host_matches(host, r.host) then return r.preset or "default" end
        end
    end
    return settings:get("global") or "default"
end

-- ===== 应用到标签 =====
local applied = {}   -- tab_id -> preset id 已应用（"default" 表示已清除）
local function apply(tab_id, preset_id, reload)
    local cur = applied[tab_id]
    if cur == preset_id then return false end
    local p = resolve_preset(preset_id)
    if not p then
        if cur == nil then applied[tab_id] = "default" return false end -- 从未设过，不用清
        pcall(lemurx.tabs.setUserAgent, tab_id, "", { reload = reload })
        applied[tab_id] = "default"
        return true
    end
    pcall(lemurx.tabs.setUserAgent, tab_id, p.ua, { platform = p.platform, mobile = p.mobile, reload = reload })
    if settings:get("desktop_viewport") then pcall(lemurx.tabs.setDesktop, tab_id, p.desktop) end
    applied[tab_id] = preset_id
    return true
end
S.apply = apply

-- 导航时：目标站点规则和标签当前 UA 不一致 → 先改 UA，否决这次导航再重发（改后的 UA 才会进请求头）
local renav = {}
lx.on_navigation(function(view, uri, ev)
    if ev.main_frame == false or not uri:match("^https?://") then return nil end
    local host = util.host_of(uri)
    local want = S.preset_for(host)
    if applied[view.id] == want or (applied[view.id] == nil and want == "default") then return nil end
    if renav[view.id] == uri then renav[view.id] = nil return nil end -- 我们自己重发的那次
    apply(view.id, want, false)
    renav[view.id] = uri
    local id = view.id
    lx.after(0, function() pcall(lemurx.tabs.navigate, id, uri) end)
    return false
end, 10)

pcall(lemurx.tabs.on, "closed", function(t) if t and t.id then applied[t.id] = nil renav[t.id] = nil end end)

-- 规则 / 全局变了：正在看的标签立即重载
local function reapply_all()
    for _, t in ipairs(lx.tabs.list()) do
        if (t.url or ""):match("^https?://") then
            apply(t.id, S.preset_for(util.host_of(t.url)), true)
        end
    end
end
settings:on_change(function() reapply_all() end)

-- ===== 操作 =====
function S.set_rule(host, preset)
    local rules = settings:get("rules") or {}
    local found
    for i, r in ipairs(rules) do if r.host == host then found = i break end end
    if preset == nil or preset == "default" or preset == "" then
        if found then table.remove(rules, found) end
    elseif found then
        rules[found].preset = preset
    else
        rules[#rules + 1] = { host = host, preset = preset }
    end
    settings:set("rules", rules)
end

function S.pick_for_current()
    local t = lx.tabs.current()
    local host = t and util.host_of(t.url or "") or ""
    if host == "" or not (t.url or ""):match("^https?://") then lx.toast("当前不是网页") return end
    local pattern = "*." .. host:gsub("^www%.", "")
    local labels, ids = {}, {}
    local cur = S.preset_for(host)
    for _, p in ipairs(PRESETS) do
        labels[#labels + 1] = (p[1] == cur and "✓ " or "") .. p[2]
        ids[#ids + 1] = p[1]
    end
    local ok = pcall(lemurx.ui.dialog, {
        title = host .. " 用什么 UA", items = labels,
        onSelect = function(ev)
            local pid = ids[(ev.index or 0) + 1]
            if not pid then return end
            if pid == "custom" and (settings:get("custom_ua") or "") == "" then lx.tabs.open("lemurx://useragent/") return end
            S.set_rule(pattern, pid)
            lx.toast(host .. " → " .. PRESET_BY_ID[pid][2])
        end,
        negative = "取消",
    })
    if not ok then lx.tabs.open("lemurx://useragent/") end
end

-- ===== API / 页面 =====
S.api.presets = function()
    local o = {}
    for _, p in ipairs(PRESETS) do local r = resolve_preset(p[1]) o[#o + 1] = { id = p[1], name = p[2], ua = r and r.ua or "" } end
    return { presets = o, chrome = CV }
end
S.api["rule.set"] = function(args)
    if type(args.host) ~= "string" or args.host == "" then return nil, "host required" end
    S.set_rule(args.host, args.preset)
    return { ok = true, message = "已保存", reload = true }
end
S.api["rule.remove"] = function(args) S.set_rule(args.host, nil) return { ok = true, message = "已删除", reload = true } end
S.api.status = function()
    local t = lx.tabs.current()
    local host = t and util.host_of(t.url or "") or ""
    local pid = S.preset_for(host)
    local r = resolve_preset(pid)
    return { host = host, preset = pid, ua = r and r.ua or "", applied = applied[t and t.id or -1] }
end

local esc = lx.html.escape
S.summary = function()
    local opts = {}
    for _, p in ipairs(PRESETS) do opts[#opts + 1] = ("<option value=\"%s\">%s</option>"):format(esc(p[1]), esc(p[2])) end
    local rows = {}
    for _, r in ipairs(settings:get("rules") or {}) do
        local sel = {}
        for _, p in ipairs(PRESETS) do sel[#sel + 1] = ("<option value=\"%s\"%s>%s</option>"):format(esc(p[1]), p[1] == r.preset and " selected" or "", esc(p[2])) end
        rows[#rows + 1] = ("<div class=\"row\"><div class=\"l\"><div class=\"t\">%s</div></div><select data-rule=\"%s\">%s</select><button class=\"sec\" data-api=\"rule.remove\" data-args='%s'>删</button></div>")
            :format(esc(r.host), esc(r.host), table.concat(sel), esc(json.encode({ host = r.host })))
    end
    local st = S.api.status()
    return ([[
<div class="card"><div class="row"><div class="l"><div class="t">当前：%s</div><div class="d">%s</div><div class="d" style="word-break:break-all;opacity:.7">%s</div></div></div>
 <div class="row" style="display:block"><div class="t">添加站点规则</div><div style="display:flex;gap:8px;margin-top:8px"><input id="rh" placeholder="*.example.com" style="flex:1"><select id="rp">%s</select><button id="radd">加</button></div></div></div>
<div class="card list"><div class="row"><div class="l"><div class="t">站点规则</div><div class="d">优先于全局设置。本机 Chrome 版本 %s，伪装桌面 Chrome / Edge 时沿用它。</div></div></div>%s</div>]])
        :format(esc(st.host ~= "" and st.host or "（非网页）"), esc(PRESET_BY_ID[st.preset] and PRESET_BY_ID[st.preset][2] or st.preset), esc(st.ua ~= "" and st.ua or "浏览器默认 UA"), table.concat(opts), esc(CV),
            #rows > 0 and table.concat(rows) or "<div class=\"row\"><div class=\"d\">还没有规则。三点菜单「切换当前网站的 UA」可以快速加。</div></div>")
end
S.page_js = [[
lx.q('#radd').onclick=function(){var h=lx.q('#rh').value.trim();if(!h)return;lx.api('rule.set',{host:h,preset:lx.q('#rp').value}).then(function(){location.reload()}).catch(function(e){lx.toast(e.message)})};
document.querySelectorAll('select[data-rule]').forEach(function(s){s.onchange=function(){lx.api('rule.set',{host:s.dataset.rule,preset:s.value}).then(function(){lx.toast('已保存')})}});
]]

lx.after(500, reapply_all)
return S
