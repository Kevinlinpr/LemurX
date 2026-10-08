-- @name 网页暗色
-- @description Dark Reader 替代：全站暗色模式，三种引擎（Blink 原生强制暗色 / 反色滤镜 / 静态样式），亮度对比度调节，定时与跟随系统，站点例外与自定义 CSS
-- @version 1.0.0
-- @icon 🌙
-- @category 外观
-- @replaces Dark Reader
-- @page lemurx://darkmode/
--
-- 引擎：
--   native  Chromium 自带的 Force Dark（Chrome 安卓「网页自动深色」背后的引擎，会分析图片、保留品牌色）。
--           Chrome 只在应用夜间模式下开它；LemurX 补了 lemurx.chrome.setForceDark()，脚本可独立控制并带站点例外。
--   filter  CSS invert + hue-rotate，图片再反回来。所有页面都能用，fixed 元素可能错位。
--   static  通用暗色样式表。最稳，最不好看。
-- 渲染端（official/darkmode/web.lua）负责 filter / static 的样式注入、亮度等附加滤镜、站点自定义 CSS、已暗站点检测。

local lx = require("lx")
local util, json = lx.util, lx.json
local esc = lx.html.escape

local ID = "darkmode"
local CHANNEL = "lx.darkmode"

local S
local auto_dark_sites = {}   -- 渲染端检测到"本来就是暗色"的站（只作展示）
local active = false         -- 当前是否处于暗色（enabled + 定时/系统判断后的结果）

S = lx.register({
    id = ID, name = "网页暗色", version = "1.0.0", icon = "🌙",
    description = "给所有网页套上暗色。默认用 Chromium 原生强制暗色引擎，也可切换反色滤镜或静态样式；支持定时、跟随系统、站点例外、亮度对比度、站点自定义 CSS。",
    replaces = "Dark Reader",
    settings = {
        enabled = false,
        engine = "native",
        schedule = "always",        -- always | night | system
        night_from = "20:00", night_to = "07:00",
        list_mode = false,          -- true: 只对 exceptions 里的站开
        exceptions = {},
        brightness = 100, contrast = 100, sepia = 0, grayscale = 0,
        detect_dark = true,
        site_css = {},              -- host -> css
        menu_toggle = true,
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用网页暗色", section = "总开关" },
        { key = "engine", type = "select", label = "引擎", options = { { "native", "原生强制暗色（推荐）" }, { "filter", "反色滤镜" }, { "static", "静态样式表" } }, desc = "原生引擎质量最好；反色滤镜兼容一切页面；静态样式最保守" },
        { key = "schedule", type = "select", label = "何时开启", options = { { "always", "始终" }, { "night", "夜间时段" }, { "system", "跟随系统深色模式" } }, section = "时间" },
        { key = "night_from", type = "string", label = "夜间开始", placeholder = "20:00" },
        { key = "night_to", type = "string", label = "夜间结束", placeholder = "07:00" },
        { key = "brightness", type = "number", label = "亮度 %", min = 50, max = 150, step = 5, section = "调节（原生引擎下仅作附加滤镜）" },
        { key = "contrast", type = "number", label = "对比度 %", min = 50, max = 150, step = 5 },
        { key = "sepia", type = "number", label = "褐色 %", min = 0, max = 100, step = 5 },
        { key = "grayscale", type = "number", label = "灰度 %", min = 0, max = 100, step = 5 },
        { key = "detect_dark", type = "bool", label = "跳过本来就是暗色的站", desc = "反色 / 静态引擎下，页面加载后检测背景亮度，已经是暗色的撤掉", section = "站点" },
        { key = "list_mode", type = "bool", label = "反转名单", desc = "开：只对下面列表里的站开暗色；关：列表里的站保持原样" },
        { key = "exceptions", type = "list", label = "站点名单", desc = "一行一个域名；*.example.com 含子域", placeholder = "github.com" },
        { key = "menu_toggle", type = "bool", label = "三点菜单里显示开关", section = "其他" },
    },
})
local settings = S.settings

-- ===== 定时 / 系统 =====
local function parse_hm(s)
    local h, m = tostring(s or ""):match("^(%d%d?):(%d%d)$")
    if not h then return nil end
    return tonumber(h) * 60 + tonumber(m)
end
local function in_night()
    local from = parse_hm(settings:get("night_from")) or 20 * 60
    local to = parse_hm(settings:get("night_to")) or 7 * 60
    local t = os.date("*t")
    local now = t.hour * 60 + t.min
    if from == to then return true end
    if from < to then return now >= from and now < to end
    return now >= from or now < to
end
local function compute_active()
    if not settings:get("enabled") then return false end
    local sch = settings:get("schedule")
    if sch == "night" then return in_night() end
    if sch == "system" then
        local ok, dark = pcall(lemurx.chrome.isDarkMode)
        return ok and dark == true
    end
    return true
end

-- ===== 下发 =====
local function config()
    return {
        engine = settings:get("engine"), active = active, exceptions = settings:get("exceptions") or {},
        list_mode = settings:get("list_mode") and true or false,
        brightness = tonumber(settings:get("brightness")) or 100, contrast = tonumber(settings:get("contrast")) or 100,
        sepia = tonumber(settings:get("sepia")) or 0, grayscale = tonumber(settings:get("grayscale")) or 0,
        detect_dark = settings:get("detect_dark") and true or false, site_css = settings:get("site_css") or {},
    }
end
local config_json = nil
local function push_pid(pid) lx.web.send_pid(CHANNEL, pid, "config", config_json or json.encode(config())) end

local function apply_native()
    if not (lemurx.chrome and lemurx.chrome.setForceDark) then
        if settings:get("engine") == "native" and active then lx.log("darkmode: lemurx.chrome.setForceDark missing (old build); fall back to filter engine") end
        return false
    end
    if not settings:get("enabled") then
        -- 脚本关掉：交还 Chrome 自己的逻辑
        pcall(lemurx.chrome.setForceDark, nil)
    elseif settings:get("engine") == "native" then
        local exceptions = settings:get("exceptions") or {}
        if settings:get("list_mode") then
            -- 只对名单开：全局关，名单为反例
            pcall(lemurx.chrome.setForceDark, false, { exceptions = active and exceptions or {} })
        else
            pcall(lemurx.chrome.setForceDark, active, { exceptions = exceptions })
        end
    else
        pcall(lemurx.chrome.setForceDark, nil)
    end
    return true
end

local menu_added = false
local function update_menu()
    if not settings:get("menu_toggle") then
        if menu_added then pcall(lemurx.menu.remove, "lx.darkmode.toggle") pcall(lemurx.menu.remove, "lx.darkmode.site") menu_added = false end
        return
    end
    menu_added = true
    pcall(lemurx.menu.add, {
        id = "lx.darkmode.toggle", title = settings:get("enabled") and "🌙 网页暗色：开" or "🌙 网页暗色：关", page = "main",
        onClick = function() settings:set("enabled", not settings:get("enabled")) lx.toast(settings:get("enabled") and "网页暗色已开启" or "网页暗色已关闭") end,
    })
    pcall(lemurx.menu.add, {
        id = "lx.darkmode.site", title = "🌙 本站暗色开关", page = "expand",
        onClick = function() S.toggle_site() end,
    })
end

local function refresh(reason)
    local was = active
    active = compute_active()
    config_json = json.encode(config())
    local native_ok = apply_native()
    if settings:get("engine") == "native" and not native_ok then
        -- 老构建没有原生接口：临时按 filter 下发
        local c = config() c.engine = "filter" config_json = json.encode(c)
    end
    lx.web.broadcast(CHANNEL, "config", config_json)
    update_menu()
    if was ~= active then lx.log("darkmode: %s (%s)", active and "on" or "off", reason or "") end
end

-- ===== 站点开关 =====
local function current_host()
    local t = lx.tabs.current()
    return t and util.host_of(t.url or "") or ""
end
local function site_listed(host)
    for i, pat in ipairs(settings:get("exceptions") or {}) do
        if util.host_matches(host, pat) then return i end
    end
    return nil
end
function S.toggle_site(host)
    host = host or current_host()
    if host == "" then lx.toast("当前页没有域名") return end
    local list = settings:get("exceptions") or {}
    local idx = site_listed(host)
    if idx then table.remove(list, idx) else list[#list + 1] = host end
    settings:set("exceptions", list)
    local dark_now = active and ((settings:get("list_mode") and site_listed(host) ~= nil) or (not settings:get("list_mode") and site_listed(host) == nil))
    lx.toast(host .. (dark_now and "：暗色开" or "：暗色关"))
end

-- ===== 渲染进程 =====
local ch = lx.web.require("darkmode/web")
if ch then
    ch:add_signal("hello", function(_, pid) if type(pid) == "number" then push_pid(pid) end end)
    lx.web.on_process(push_pid)
    ch:add_signal("detected", function(_, page_id, host, is_dark)
        if type(host) == "string" and host ~= "" then auto_dark_sites[host] = is_dark and util.now_ms() or nil end
    end)
end

settings:on_change(function(key, value)
    refresh("settings " .. tostring(key))
end)

-- 定时：每分钟看一次时段 / 系统
lx.every(60 * 1000, function()
    local sch = settings:get("schedule")
    if sch == "night" or sch == "system" then
        if compute_active() ~= active then refresh("schedule") end
    end
end)

-- ===== API / 页面 =====
S.api.state = function()
    local host = current_host()
    return {
        active = active, host = host, listed = site_listed(host) ~= nil, list_mode = settings:get("list_mode") and true or false,
        engine = settings:get("engine"), native_available = (lemurx.chrome and lemurx.chrome.setForceDark) and true or false,
        auto_dark = (function() local t = {} for h in pairs(auto_dark_sites) do t[#t + 1] = h end table.sort(t) return t end)(),
        site_css = settings:get("site_css") or {},
    }
end
S.api.toggle_site = function(args) S.toggle_site(args.host) return { ok = true, reload = true } end
S.api.site_css_set = function(args)
    if type(args.host) ~= "string" or args.host == "" then return nil, "host required" end
    local t = settings:get("site_css") or {}
    if args.css == nil or args.css == "" then t[args.host] = nil else t[args.host] = args.css end
    settings:set("site_css", t)
    return { ok = true, message = "已保存" }
end

S.summary = function(ctx)
    local st = S.api.state()
    local rows = {
        ("<div class=\"card\"><div class=\"row\"><div class=\"l\"><div class=\"t\">现在：%s</div><div class=\"d\">引擎 %s%s</div></div><button data-api=\"settings.set\" data-args='%s'>%s</button></div>")
            :format(st.active and "🌙 暗色中" or "☀️ 未开启", esc(st.engine), (st.engine == "native" and not st.native_available) and "（此构建无原生接口，实际用反色滤镜）" or "",
                esc(json.encode({ key = "enabled", value = not settings:get("enabled") })), settings:get("enabled") and "关闭" or "开启"),
    }
    if st.host ~= "" then
        rows[#rows + 1] = ("<div class=\"row\"><div class=\"l\"><div class=\"t\">%s</div><div class=\"d\">%s</div></div><button class=\"sec\" data-api=\"toggle_site\" data-args='%s'>%s</button></div>")
            :format(esc(st.host), st.listed and (st.list_mode and "在名单里：开暗色" or "在名单里：保持原样") or (st.list_mode and "不在名单：保持原样" or "不在名单：开暗色"),
                esc(json.encode({ host = st.host })), st.listed and "移出名单" or "加入名单")
    end
    rows[#rows + 1] = "</div>"
    -- 站点自定义 CSS
    local css_rows = {}
    for host, css in pairs(st.site_css) do
        css_rows[#css_rows + 1] = ("<div class=\"row\"><div class=\"l\"><div class=\"t\">%s</div><div class=\"d\">%s</div></div><button class=\"sec\" data-api=\"site_css_set\" data-args='%s'>删</button></div>")
            :format(esc(host), esc(css:sub(1, 80)), esc(json.encode({ host = host, css = "" })))
    end
    rows[#rows + 1] = "<div class=\"sec\">站点自定义 CSS</div><div class=\"card\">" .. table.concat(css_rows)
        .. ("<div class=\"row\" style=\"display:block\"><div style=\"margin:8px 0\"><input type=\"text\" id=\"csshost\" placeholder=\"域名，如 example.com\" value=\"%s\"></div><div style=\"margin:8px 0\"><textarea id=\"csstext\" placeholder=\".sidebar { background: #111 !important }\"></textarea></div><button id=\"csssave\">保存</button></div></div>"):format(esc(st.host))
    if #st.auto_dark > 0 then
        rows[#rows + 1] = "<div class=\"sec\">检测到本来就是暗色的站</div><div class=\"card\"><div class=\"row\"><div class=\"l muted\">" .. esc(table.concat(st.auto_dark, "、")) .. "</div></div></div>"
    end
    return table.concat(rows)
end
S.page_js = [[
var b=lx.q('#csssave');if(b)b.onclick=function(){lx.api('site_css_set',{host:lx.q('#csshost').value.trim(),css:lx.q('#csstext').value}).then(function(r){lx.toast(r.message);setTimeout(function(){location.reload()},500)}).catch(function(e){lx.toast(e.message)})};
]]

-- ===== 启动 =====
refresh("startup")
return S
