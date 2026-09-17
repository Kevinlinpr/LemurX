-- LemurX · luakit-compatible library · follow
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "follow" module API. No luakit code is used.
--
-- 链接提示（vimperator 风格）。进入 follow 模式后，渲染进程的 follow_wm/select_wm
-- 给页面上可交互元素贴上短标签；输入标签或元素文字过滤，唯一命中即触发。
--   f      点击元素            F      在后台新标签打开链接
--   ;<x>   ex-follow 子模式    g;<x>  持续的 ex-follow（跟随后不退出）
-- 公开接口：follow.selectors / evaluators / modes / pattern_styles / pattern_maker /
--   stylesheet / site_specific_selectors / ignore_case / ignore_delay / start(w, mode)
-- 设置：follow.ignore_case follow.ignore_delay follow.sort_labels follow.persist
-- 模式：follow、ex-follow（模式表 enter/changed/leave 由 modes 模块调用）
-- IPC (follow_wm)：→ enter(view, opts) changed(view, label_pat, text_pat) focus(view, step)
--                  follow(view) leave(view)   ← matches(page_id, n) follow_func(page_id, evaluator, result)

local lousy = require("lousy")
local settings = require("settings")
local modes = require("modes")
local webview = require("webview")
local window = require("window")

local _M = {}

local wm = require_web_module("follow_wm")

-- ---------------------------------------------------------------------------
-- 设置
-- ---------------------------------------------------------------------------
settings.register_settings({
    ["follow.ignore_case"] = {
        type = "boolean", default = true,
        desc = "Ignore letter case when matching hint text.",
    },
    ["follow.ignore_delay"] = {
        type = "number", default = 200, min = 0,
        desc = "Milliseconds during which key presses are swallowed after a hint was followed.",
    },
    ["follow.sort_labels"] = {
        type = "boolean", default = true,
        desc = "Sort the default numeric hint labels so they read top to bottom.",
    },
    ["follow.persist"] = {
        type = "boolean", default = false,
        desc = "Stay in follow mode after a hint was followed (multi-follow).",
    },
})

-- ---------------------------------------------------------------------------
-- 选择器 / 求值器 / 匹配风格
-- ---------------------------------------------------------------------------
_M.selectors = {
    clickable = "a[href], area[href], button, input:not([type=hidden]), select, textarea, summary, "
        .. "[onclick], [role=button], [role=link], [role=menuitem], [role=tab], [tabindex], [contenteditable]",
    focus = "input:not([type=hidden]):not([type=submit]):not([type=button]), select, textarea, [contenteditable]",
    uri = "a[href], area[href]",
    desc = "[title], [alt], a[href], img",
    image = "img",
    thumbnail = "a img, a picture, a video",
}

-- 值是 follow_wm 里求值器的名字（deviation: luakit 用 JS 源码字符串）
_M.evaluators = {
    click = "click",
    focus = "focus",
    uri = "uri",
    desc = "desc",
    src = "src",
}

_M.site_specific_selectors = {}

local function escape_pat(s)
    return (tostring(s):gsub("[%^%$%(%)%%%.%[%]%*%+%-%?]", "%%%0"))
end

_M.pattern_styles = {
    -- 只按标签前缀匹配
    match_label = function(text) return "^" .. escape_pat(text), nil end,
    -- 只按元素文字匹配
    match_text = function(text) return nil, escape_pat(text) end,
    -- 两者都试（文字按字面）
    match_both = function(text) return "^" .. escape_pat(text), escape_pat(text) end,
    -- 标签按前缀、文字按 Lua pattern（默认）
    match_label_re_text = function(text) return "^" .. escape_pat(text), text end,
}
_M.pattern_maker = _M.pattern_styles.match_label_re_text

_M.stylesheet = [[
#luakit_select_overlay .hint_label { letter-spacing: .02em; text-transform: uppercase; }
#luakit_select_overlay .hint_label.hint_selected { outline: 2px solid #1e6fd9; }
]]

-- ---------------------------------------------------------------------------
-- 工具
-- ---------------------------------------------------------------------------
local function host_of(uri)
    return (tostring(uri or ""):match("^%a[%w+.-]*://([^/?#:]+)")) or ""
end

local function resolve_selector(sel, uri)
    local css = _M.selectors[sel] or sel or _M.selectors.clickable
    local host = host_of(uri)
    for domain, extra in pairs(_M.site_specific_selectors) do
        if host == domain or host:sub(-(#domain + 1)) == "." .. domain then
            local more = type(extra) == "table" and (extra[sel] or extra.clickable) or extra
            if type(more) == "string" and more ~= "" then css = css .. ", " .. more end
        end
    end
    return css
end

local THEME_KEYS = { "hint_font", "hint_fg", "hint_bg", "hint_border", "hint_opacity",
    "hint_overlay_bg", "hint_overlay_border", "hint_overlay_selected_bg", "hint_overlay_selected_border" }

local function hint_theme()
    local out = {}
    local ok, theme = pcall(lousy.theme.get)
    if ok and type(theme) == "table" then
        for _, k in ipairs(THEME_KEYS) do
            local v = theme[k]
            if v ~= nil then out[k] = tostring(v) end
        end
    end
    return out
end

local function window_for_page(page_id)
    for _, w in pairs(window.bywidget or {}) do
        local st = rawget(w, "follow_state")
        if st and st.view and st.view.is_alive and st.view.id == page_id then return w, st end
    end
    return nil
end

local function download_uri(uri, w)
    local downloads = package.loaded["downloads"]
    if downloads and downloads.download then
        downloads.download(uri, { window = w })
    elseif rawget(_G, "download") then
        local d = download{ uri = uri }
        d:start()
    elseif lemurx and lemurx.downloads and lemurx.downloads.enqueue then
        lemurx.downloads.enqueue(uri)
    end
    if w then w:notify("Downloading " .. uri) end
end

local function yank(text, w, what)
    if not text then return end
    luakit.selection.clipboard = text
    pcall(function() luakit.selection.primary = text end)
    if w then w:notify(("Yanked %s: %s"):format(what or "text", text)) end
end

local function activate_state(result, w)
    if result == "form-active" then
        w:set_mode("insert")
    elseif result == "root-active" then
        w:set_mode()
    end
end

-- ---------------------------------------------------------------------------
-- 预定义 follow 配置
-- ---------------------------------------------------------------------------
_M.modes = {
    follow = { selector = "clickable", evaluator = "click", prompt = "Follow:",
        func = activate_state },
    focus = { selector = "focus", evaluator = "focus", prompt = "Focus:",
        func = activate_state },
    tab = { selector = "uri", evaluator = "uri", prompt = "Follow (new tab):",
        func = function(uri, w) if uri then w:new_tab(uri, { switch = true }) end end },
    bg_tab = { selector = "uri", evaluator = "uri", prompt = "Follow (background tab):",
        func = function(uri, w) if uri then w:new_tab(uri, { switch = false }) end end },
    open = { selector = "uri", evaluator = "uri", prompt = "Open:",
        func = function(uri, w) if uri then w:navigate(uri) end end },
    window = { selector = "uri", evaluator = "uri", prompt = "Open (new window):",
        func = function(uri, w) if uri then w:new_window(uri) end end },
    yank = { selector = "uri", evaluator = "uri", prompt = "Yank URI:",
        func = function(uri, w) yank(uri, w, "URI") end },
    yank_desc = { selector = "desc", evaluator = "desc", prompt = "Yank text:",
        func = function(text, w) yank(text, w, "text") end },
    image = { selector = "image", evaluator = "src", prompt = "Open image:",
        func = function(src, w) if src then w:navigate(src) end end },
    image_tab = { selector = "image", evaluator = "src", prompt = "Open image (new tab):",
        func = function(src, w) if src then w:new_tab(src, { switch = true }) end end },
    download = { selector = "uri", evaluator = "uri", prompt = "Download:",
        func = function(uri, w) if uri then download_uri(uri, w) end end },
    download_image = { selector = "image", evaluator = "src", prompt = "Download image:",
        func = function(src, w) if src then download_uri(src, w) end end },
    cmd_open = { selector = "uri", evaluator = "uri", prompt = "Open (edit):",
        func = function(uri, w) if uri then w:enter_cmd(":open " .. uri) end end },
    cmd_tabopen = { selector = "uri", evaluator = "uri", prompt = "Tab open (edit):",
        func = function(uri, w) if uri then w:enter_cmd(":tabopen " .. uri) end end },
    cmd_winopen = { selector = "uri", evaluator = "uri", prompt = "Win open (edit):",
        func = function(uri, w) if uri then w:enter_cmd(":winopen " .. uri) end end },
}

-- ---------------------------------------------------------------------------
-- 模式实现
-- ---------------------------------------------------------------------------
local function begin_ignore(w)
    local delay = tonumber(settings.get_setting("follow.ignore_delay")) or 0
    if delay <= 0 then return end
    w.follow_ignoring = true
    local ok, t = pcall(function() return timer{ interval = delay } end)
    if not ok then w.follow_ignoring = false return end
    t:add_signal("timeout", function(tm)
        w.follow_ignoring = false
        tm:stop()
    end)
    t:start()
end

local function send_enter(w, st)
    wm:emit_signal(st.view, "enter", {
        selector = resolve_selector(st.mode.selector, st.view.uri),
        evaluator = _M.evaluators[st.mode.evaluator] or st.mode.evaluator or "click",
        ignore_case = settings.get_setting("follow.ignore_case") ~= false,
        theme = hint_theme(),
        stylesheet = _M.stylesheet,
    })
end

-- follow.start(w, mode[, persist])：mode 为 follow.modes 的键或同结构的表
function _M.start(w, mode, persist)
    if type(mode) == "string" then
        local m = _M.modes[mode]
        if not m then error("follow: unknown follow mode " .. mode, 2) end
        mode = m
    end
    mode = mode or _M.modes.follow
    w:set_mode("follow", mode, persist)
end

modes.new_mode("follow", "Pick a page element by typing its hint label or text.", {
    enter = function(w, mode, persist)
        if type(mode) == "string" then mode = _M.modes[mode] end
        mode = mode or _M.modes.follow
        local view = w.view
        if not view then
            w:set_mode()
            return
        end
        local st = { mode = mode, view = view, text = "", persist = persist or mode.persist or false }
        w.follow_state = st
        w:set_prompt(mode.prompt or "Follow:")
        w:set_input("")
        send_enter(w, st)
    end,
    changed = function(w, text)
        local st = rawget(w, "follow_state")
        if not st then return end
        st.text = text or ""
        if st.text == "" then
            wm:emit_signal(st.view, "changed", nil, nil)
            return
        end
        local label_pat, text_pat = _M.pattern_maker(st.text)
        wm:emit_signal(st.view, "changed", label_pat, text_pat)
    end,
    activate = function(w)
        local st = rawget(w, "follow_state")
        if st then wm:emit_signal(st.view, "follow") end
    end,
    leave = function(w)
        local st = rawget(w, "follow_state")
        if st then
            w.follow_state = nil
            if st.view and st.view.is_alive then wm:emit_signal(st.view, "leave") end
        end
    end,
})

modes.new_mode("ex-follow", "Choose what to do with the element you are about to hint.", {
    enter = function(w, persist)
        w.follow_ex_persist = persist and true or false
        w:set_prompt(persist and "Follow (multi) …" or "Follow …")
        w:set_input("")
    end,
    leave = function(w)
        w.follow_ex_persist = nil
    end,
})

wm:add_signal("matches", function(_, page_id, count)
    local w, st = window_for_page(page_id)
    if not w then return end
    st.matches = count
    local base = st.mode.prompt or "Follow:"
    if count == 0 then
        w:set_prompt(base .. " (no matches)")
    else
        w:set_prompt(("%s [%d]"):format(base, count))
    end
end)

wm:add_signal("follow_func", function(_, page_id, evaluator, result)
    local w, st = window_for_page(page_id)
    if not w then return end
    local persist = st.persist or settings.get_setting("follow.persist") == true
    if not persist then
        w:set_mode()
    end
    begin_ignore(w)
    local fn = st.mode.func
    if type(fn) == "function" then
        local ok, err = xpcall(fn, debug.traceback, result, w, evaluator)
        if not ok then
            msg.warn("follow: handler failed: %s", tostring(err))
            w:error("follow: " .. tostring(err))
        end
    end
    if persist and w:is_mode("follow") then
        -- 重新贴标签，继续多选
        w:set_input("")
        local cur = rawget(w, "follow_state")
        if cur then send_enter(w, cur) end
    end
end)

-- 跟随后的短暂按键忽略
window.add_signal("init", function(w)
    if w.win and w.win.add_signal then
        w.win:add_signal("key-press", function()
            if rawget(w, "follow_ignoring") then return true end
        end)
    end
end)

-- follow.sort_labels=false 时换成不排序的默认标签生成器
local function apply_sort_labels()
    local ok, select = pcall(require, "select")
    if not ok then return end
    if settings.get_setting("follow.sort_labels") == false then
        select.label_maker = "function (s) return s.trim(s.reverse(s.numbers())) end"
    elseif select.label_maker == "function (s) return s.trim(s.reverse(s.numbers())) end" then
        select.label_maker = nil
    end
end
settings.add_signal("setting-changed", function(a, b)
    local ev = type(a) == "table" and a or b -- C 组 settings 是模块信号：handler(ev)；也兼容 (obj, ev)
    if ev and ev.key == "follow.sort_labels" then apply_sort_labels() end
end)
apply_sort_labels()

-- ---------------------------------------------------------------------------
-- 绑定
-- ---------------------------------------------------------------------------
local function ex(w, name)
    local persist = rawget(w, "follow_ex_persist")
    _M.start(w, name, persist)
end

modes.add_binds("normal", {
    { "f", "Hint clickable elements and click the chosen one.", function (w) _M.start(w, "follow") end },
    { "F", "Hint links and open the chosen one in a background tab.", function (w) _M.start(w, "bg_tab") end },
    { ";", "Enter ex-follow: choose an action, then hint.", function (w) w:set_mode("ex-follow") end },
    { "g;", "Enter persistent ex-follow (keep hinting after each pick).", function (w) w:set_mode("ex-follow", true) end },
})

modes.add_binds("ex-follow", {
    { ";", "Hint focusable elements and focus the chosen one.", function (w) ex(w, "focus") end },
    { "y", "Yank the chosen link's URI to the clipboard.", function (w) ex(w, "yank") end },
    { "Y", "Yank the chosen element's text/description.", function (w) ex(w, "yank_desc") end },
    { "i", "Open the chosen image in the current tab.", function (w) ex(w, "image") end },
    { "I", "Open the chosen image in a new tab.", function (w) ex(w, "image_tab") end },
    { "s", "Download (save) the chosen link.", function (w) ex(w, "download") end },
    { "S", "Download (save) the chosen image.", function (w) ex(w, "download_image") end },
    { "x", "Download the chosen link (alias of s).", function (w) ex(w, "download") end },
    { "X", "Download the chosen image (alias of S).", function (w) ex(w, "download_image") end },
    { "o", "Open the chosen link in the current tab.", function (w) ex(w, "open") end },
    { "t", "Open the chosen link in a new tab.", function (w) ex(w, "tab") end },
    { "b", "Open the chosen link in a background tab.", function (w) ex(w, "bg_tab") end },
    { "w", "Open the chosen link in a new window.", function (w) ex(w, "window") end },
    { "O", "Put the chosen link into an :open command line.", function (w) ex(w, "cmd_open") end },
    { "T", "Put the chosen link into a :tabopen command line.", function (w) ex(w, "cmd_tabopen") end },
    { "W", "Put the chosen link into a :winopen command line.", function (w) ex(w, "cmd_winopen") end },
})

modes.add_binds("follow", {
    { "<Tab>", "Move focus to the next hint.", function (w)
        local st = rawget(w, "follow_state")
        if st then wm:emit_signal(st.view, "focus", 1) end
    end },
    { "<Shift-Tab>", "Move focus to the previous hint.", function (w)
        local st = rawget(w, "follow_state")
        if st then wm:emit_signal(st.view, "focus", -1) end
    end },
    { "<Return>", "Follow the focused hint.", function (w)
        local st = rawget(w, "follow_state")
        if st then wm:emit_signal(st.view, "follow") end
    end },
})

-- ---------------------------------------------------------------------------
-- 模块属性代理：ignore_case / ignore_delay → settings
-- ---------------------------------------------------------------------------
setmetatable(_M, {
    __index = function(_, k)
        if k == "ignore_case" then return settings.get_setting("follow.ignore_case") ~= false end
        if k == "ignore_delay" then return settings.get_setting("follow.ignore_delay") end
        return nil
    end,
    __newindex = function(t, k, v)
        if k == "ignore_case" then
            settings.set_setting("follow.ignore_case", v and true or false)
        elseif k == "ignore_delay" then
            settings.set_setting("follow.ignore_delay", tonumber(v) or 0)
        else
            rawset(t, k, v)
        end
    end,
})

return _M
