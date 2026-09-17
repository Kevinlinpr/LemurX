-- LemurX · luakit-compatible library · webview
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "webview" module API. No luakit code is used.
--
-- 包装内核的 widget{type="webview"}：
--   webview.new(opts) 建视图并广播模块级 init 信号，其他模块在 init 里挂自己的处理器；
--   webview.methods.* 是 fn(view, w, ...) 形式的方法，窗口表找不到方法时会落到这里
--   （w:scroll{...} / w:back() / w:zoom_in() 都走这条路）；
--   webview.set_location 把用户输入解析成 URI（搜索关键词、本地文件、about:/luakit: 直通）；
--   load block 让模块在初始化期间暂停导航；
--   settings.webview.* 注册并在视图上生效。

local lousy = require("lousy")
local util = lousy.util
local join = util.table.join

local _M = {}
lousy.signal.setup(_M, true)
_M.methods = {}

-- ---------------------------------------------------------------------------
-- settings 守卫
-- ---------------------------------------------------------------------------
local settings = package.loaded["settings"]
if not settings then
    local ok, mod = pcall(require, "settings")
    if ok and type(mod) == "table" then settings = mod end
end

local function get_setting(key, default, domain)
    if settings and settings.get_setting then
        local ok, v
        if domain then ok, v = pcall(settings.get_setting, key, domain) end
        if not ok or v == nil then ok, v = pcall(settings.get_setting, key) end
        if ok and v ~= nil then return v end
    end
    return default
end

local function window_mod()
    return package.loaded["window"] or require("window")
end

-- ---------------------------------------------------------------------------
-- settings.webview.* —— 名字与 WebKitSettings 一致，内核 lk_webview 直接支持这些属性
-- ---------------------------------------------------------------------------
local bool_keys = {
    "enable_javascript", "auto_load_images", "enable_plugins", "enable_java", "enable_webgl",
    "enable_webaudio", "enable_page_cache", "enable_smooth_scrolling", "enable_fullscreen",
    "enable_html5_local_storage", "enable_html5_database", "enable_mediasource",
    "enable_media_stream", "enable_developer_extras", "enable_caret_browsing",
    "enable_spatial_navigation", "enable_tabs_to_links", "enable_dns_prefetching",
    "enable_frame_flattening", "enable_hyperlink_auditing", "enable_resizable_text_areas",
    "enable_site_specific_quirks", "enable_xss_auditor", "enable_write_console_messages_to_stdout",
    "enable_accelerated_2d_canvas", "javascript_can_access_clipboard",
    "javascript_can_open_windows_automatically", "media_playback_requires_gesture",
    "media_playback_allows_inline", "allow_modal_dialogs", "allow_file_access_from_file_urls",
    "allow_universal_access_from_file_urls", "draw_compositing_indicators", "print_backgrounds",
    "zoom_text_only",
}
local number_keys = {
    default_font_size = 16, default_monospace_font_size = 13, minimum_font_size = 0,
}
local string_keys = {
    default_charset = "utf-8", default_font_family = "sans-serif", serif_font_family = "serif",
    sans_serif_font_family = "sans-serif", monospace_font_family = "monospace",
    cursive_font_family = "cursive", fantasy_font_family = "fantasy",
    pictograph_font_family = "sans-serif", user_agent = "",
}
local bool_defaults = {
    enable_plugins = false, enable_java = false, enable_caret_browsing = false,
    enable_spatial_navigation = false, enable_frame_flattening = false, enable_xss_auditor = false,
    enable_write_console_messages_to_stdout = false, javascript_can_access_clipboard = false,
    javascript_can_open_windows_automatically = false, allow_modal_dialogs = false,
    allow_file_access_from_file_urls = false, allow_universal_access_from_file_urls = false,
    draw_compositing_indicators = false, print_backgrounds = false, zoom_text_only = false,
}

local view_prop_keys = {}   -- 直接映射到 view 属性的 settings 名
local function register_webview_settings()
    if not (settings and settings.register_settings) then return end
    local tbl = {}
    for _, k in ipairs(bool_keys) do
        local d = bool_defaults[k]
        if d == nil then d = true end
        tbl["webview." .. k] = { type = "boolean", default = d, domain_specific = true,
            desc = ("Engine switch `%s` (applied to every webview; accepted for luakit compatibility)."):format(k) }
        view_prop_keys[#view_prop_keys + 1] = k
    end
    for k, d in pairs(number_keys) do
        tbl["webview." .. k] = { type = "number", default = d, min = 0, domain_specific = true,
            desc = ("Engine value `%s`."):format(k) }
        view_prop_keys[#view_prop_keys + 1] = k
    end
    for k, d in pairs(string_keys) do
        tbl["webview." .. k] = { type = "string", default = d, domain_specific = true,
            desc = ("Engine value `%s`."):format(k) }
        view_prop_keys[#view_prop_keys + 1] = k
    end
    tbl["webview.hardware_acceleration_policy"] = {
        type = "enum", options = { ["on-demand"] = {}, always = {}, never = {} },
        default = "on-demand", desc = "GPU compositing policy (recorded only on Chromium).",
    }
    view_prop_keys[#view_prop_keys + 1] = "hardware_acceleration_policy"
    tbl["webview.zoom_level"] = {
        type = "number", default = 100, min = 10, max = 500, domain_specific = true,
        desc = "Initial zoom in percent for new pages.",
    }
    tbl["webview.zoom_step"] = {
        type = "number", default = 0.1, min = 0.01,
        desc = "Zoom delta used by w:zoom_in / w:zoom_out when window.zoom_step is not set.",
    }
    local ok, err = pcall(settings.register_settings, tbl)
    if not ok then msg.warn("webview: settings registration failed: %s", tostring(err)) end
end
register_webview_settings()

local function domain_of(uri)
    if type(uri) ~= "string" then return nil end
    local p = soup.parse_uri(uri)
    return p and p.host or nil
end

-- 把 settings.webview.* 现值写到视图上（domain 为 nil 时用全局值）
local function apply_settings(view, domain)
    if not (settings and settings.get_setting) then return end
    for _, k in ipairs(view_prop_keys) do
        local v = get_setting("webview." .. k, nil, domain)
        if v ~= nil and not (k == "user_agent" and v == "") then
            pcall(function() view[k] = v end)
        end
    end
    local zoom = tonumber(get_setting("webview.zoom_level", nil, domain))
    if zoom and zoom > 0 then pcall(function() view.zoom_level = zoom / 100 end) end
end
_M.apply_settings = apply_settings

if settings and settings.add_signal then
    -- settings 可能以 fn(event) 或 lousy.signal 风格 fn(module, event) 回调
    pcall(settings.add_signal, "setting-changed", function(a, b)
        local e = (type(b) == "table") and b or a
        if type(e) ~= "table" then return end
        local name = tostring(e.key or ""):match("^webview%.(.+)$")
        if not name then return end
        for _, view in pairs(__lk and __lk.webviews or {}) do
            if view.is_alive then
                local dom = domain_of(view.uri)
                if e.domain == nil or e.domain == dom then
                    if name == "zoom_level" then
                        local z = tonumber(e.value)
                        if z then pcall(function() view.zoom_level = z / 100 end) end
                    elseif name ~= "zoom_step" then
                        pcall(function() view[name] = e.value end)
                    end
                end
            end
        end
    end)
end

-- ---------------------------------------------------------------------------
-- 位置解析
-- ---------------------------------------------------------------------------
local function trim(s) return (tostring(s or ""):gsub("^%s+", ""):gsub("%s+$", "")) end

local function has_scheme(s)
    return s:match("^%a[%w+.-]*:") ~= nil
end

local function looks_like_host(s)
    if s:find("%s") then return false end
    if s == "localhost" or s:match("^localhost:%d+") then return true end
    if s:match("^%d+%.%d+%.%d+%.%d+") then return true end
    if s:match("^%[[%x:]+%]") then return true end
    local host = s:match("^([^/?#]+)")
    if not host then return false end
    host = host:gsub(":%d+$", "")
    if not host:match("^[%w%-%.]+$") then return false end
    if not host:find("%.") then return false end
    if host:match("%.$") or host:match("^%.") then return false end
    local tld = host:match("%.([%w%-]+)$")
    return tld ~= nil and tld:match("%a") ~= nil
end

local function local_file_uri(s)
    if not get_setting("window.check_filepath", true) then return nil end
    local path = s
    if path:sub(1, 2) == "~/" then path = (os.getenv("HOME") or luakit.data_dir) .. path:sub(2) end
    if path:sub(1, 1) ~= "/" then
        if path:sub(1, 2) == "./" then path = path:sub(3) end
        local ok, cwd = pcall(lfs.currentdir)
        if not ok or type(cwd) ~= "string" then return nil end
        path = cwd .. "/" .. path
    end
    local ok, attr = pcall(lfs.attributes, path)
    if ok and attr then return "file://" .. path end
    return nil
end

local function search_engines()
    local engines = get_setting("window.search_engines", nil)
    if type(engines) ~= "table" then engines = {} end
    return engines
end

local function engine_uri(template, query)
    if template:find("%%s") then
        return (template:gsub("%%s", function() return luakit.uri_encode(query) end))
    end
    return template .. luakit.uri_encode(query)
end

--- 把用户输入变成可导航的 URI。
function _M.resolve_location(text)
    local s = trim(text)
    if s == "" then return "about:blank" end
    if has_scheme(s) then
        -- "localhost:8080" 这类 host:port 不是 scheme
        local sch = s:match("^(%a[%w+.-]*):")
        if not (sch == "localhost" and s:match("^localhost:%d+")) then return s end
    end
    local file = (s:sub(1, 1) == "/" or s:sub(1, 2) == "~/" or s:sub(1, 2) == "./") and local_file_uri(s) or nil
    if file then return file end

    local engines = search_engines()
    local kw, rest = s:match("^(%S+)%s+(.+)$")
    if kw and engines[kw] then return engine_uri(engines[kw], rest) end
    if engines[s] then return engine_uri(engines[s], "") end

    if looks_like_host(s) then return "http://" .. s end

    local def = get_setting("window.default_search_engine", "duckduckgo")
    local template = engines[def]
    if not template then
        for _, t in pairs(engines) do template = t break end
    end
    if not template then template = "https://duckduckgo.com/?q=%s" end
    return engine_uri(template, s)
end

-- ---------------------------------------------------------------------------
-- load block
-- ---------------------------------------------------------------------------
local blocks = setmetatable({}, { __mode = "k" })    -- view -> { name = true }
local pending = setmetatable({}, { __mode = "k" })   -- view -> location arg

function _M.has_load_block(view)
    local b = blocks[view]
    return b ~= nil and next(b) ~= nil
end

function _M.modify_load_block(view, name, enable)
    assert(type(name) == "string", "load block name must be a string")
    blocks[view] = blocks[view] or {}
    if enable then
        blocks[view][name] = true
    else
        blocks[view][name] = nil
        if not _M.has_load_block(view) and pending[view] ~= nil then
            local arg = pending[view]
            pending[view] = nil
            _M.set_location(view, arg)
        end
    end
end

local function navigate(view, arg)
    if type(arg) == "table" then
        if arg.session_state then
            local ok = pcall(function() view.session_state = arg.session_state end)
            -- 会话状态恢复成功且已经带出了 URI 就结束；否则退回到直接加载 uri
            local cur = ok and view.uri or nil
            if ok and cur and cur ~= "" and cur ~= "about:blank" then return end
        end
        if arg.uri then view.uri = arg.uri end
        return
    end
    if type(arg) ~= "string" then return end
    if arg:match("^javascript:") then
        view:eval_js(luakit.uri_decode(arg:sub(12)), { source = "location" })
        return
    end
    view.uri = arg
end

function _M.set_location(view, arg)
    if not (view and view.is_alive) then return end
    if type(arg) == "string" then
        arg = _M.resolve_location(arg)
    elseif type(arg) == "table" and arg.uri and not arg.session_state then
        arg = join(arg, { uri = _M.resolve_location(arg.uri) })
    end
    if _M.has_load_block(view) then
        pending[view] = arg
        return
    end
    navigate(view, arg)
end

--- 解析滚动量描述："+40" / "-40" 相对、"50%" 百分比、"120" 绝对、"top"/"bottom"
function _M.scroll_parse(s)
    if type(s) == "number" then return { kind = "abs", value = s } end
    s = trim(s)
    if s == "top" then return { kind = "pct", value = 0 } end
    if s == "bottom" then return { kind = "pct", value = 100 } end
    local sign, num, pct = s:match("^([+-]?)(%d+%.?%d*)(%%?)$")
    if not num then return nil end
    local n = tonumber(num)
    if sign == "-" then n = -n end
    if pct == "%" then return { kind = "pct", value = n } end
    if sign ~= "" then return { kind = "rel", value = n } end
    return { kind = "abs", value = n }
end

-- ---------------------------------------------------------------------------
-- webview.methods —— fn(view, w, ...)
-- ---------------------------------------------------------------------------
local function clamp(v, lo, hi)
    if v < lo then return lo end
    if hi and v > hi then return hi end
    return v
end

local function axis(cur, max, page, new, key)
    local v = cur
    if new[key] ~= nil then
        v = new[key]
        if v < 0 then v = max end
    elseif new[key .. "rel"] ~= nil then
        v = cur + new[key .. "rel"]
    elseif new[key .. "pct"] ~= nil then
        v = max * clamp(new[key .. "pct"], 0, 100) / 100
    elseif new[key .. "pagerel"] ~= nil then
        v = cur + page * new[key .. "pagerel"]
    elseif new[key .. "page"] ~= nil then
        v = page * new[key .. "page"]
    end
    return math.floor(clamp(v, 0, max) + 0.5)
end

function _M.methods.scroll(view, w, new)
    if type(new) == "string" then
        local p = _M.scroll_parse(new)
        if not p then return end
        new = p.kind == "rel" and { yrel = p.value } or p.kind == "pct" and { ypct = p.value } or { y = p.value }
    end
    new = new or {}
    local s = view.scroll
    local cur = { x = s.x or 0, y = s.y or 0, xmax = s.xmax or 0, ymax = s.ymax or 0,
                  xpage = s.xpage_size or 0, ypage = s.ypage_size or 0 }
    local nx = axis(cur.x, cur.xmax, cur.xpage, new, "x")
    local ny = axis(cur.y, cur.ymax, cur.ypage, new, "y")
    view.scroll = { x = nx, y = ny }
    return nx, ny
end

local function zoom_step()
    return tonumber(get_setting("window.zoom_step", nil)) or tonumber(get_setting("webview.zoom_step", 0.1)) or 0.1
end

function _M.methods.zoom_in(view, w, step)
    view.zoom_level = (view.zoom_level or 1) + (step or zoom_step())
end
function _M.methods.zoom_out(view, w, step)
    view.zoom_level = math.max(0.1, (view.zoom_level or 1) - (step or zoom_step()))
end
function _M.methods.zoom_set(view, w, level)
    level = tonumber(level)
    if not level then
        level = tonumber(get_setting("webview.zoom_level", 100)) / 100
    end
    view.zoom_level = level
end
function _M.methods.back(view, w, n) view:go_back(tonumber(n) or 1) end
function _M.methods.forward(view, w, n) view:go_forward(tonumber(n) or 1) end
function _M.methods.reload(view, w, bypass)
    if bypass then view:reload_bypass_cache() else view:reload() end
end
function _M.methods.stop(view) view:stop() end
function _M.methods.eval_js(view, w, ...) return view:eval_js(...) end
function _M.methods.toggle_inspector(view)
    if view.inspector then view:close_inspector() else view:show_inspector() end
end
function _M.methods.toggle_source(view, w)
    local uri = view.uri or ""
    if uri:match("^view%-source:") then
        view.uri = uri:sub(13)
    else
        view.uri = "view-source:" .. uri
    end
end

-- 让 w:scroll / w:back … 落到当前视图
local wrapper_cache = setmetatable({}, { __mode = "k" })
local function install_window_index()
    local ok, window = pcall(window_mod)
    if not ok or type(window) ~= "table" or not window.indexes then return end
    for _, fn in ipairs(window.indexes) do
        if fn == _M.window_index then return end
    end
    _M.window_index = function(w, k)
        local f = _M.methods[k]
        if type(f) ~= "function" then return nil end
        wrapper_cache[w] = wrapper_cache[w] or {}
        local wrapped = wrapper_cache[w][k]
        if not wrapped then
            wrapped = function(_, ...)
                local view = w.view
                if not (view and view.is_alive) then return nil end
                return _M.methods[k](view, w, ...)
            end
            wrapper_cache[w][k] = wrapped
        end
        return wrapped
    end
    table.insert(window.indexes, _M.window_index)
end

-- ---------------------------------------------------------------------------
-- 查找所属窗口
-- ---------------------------------------------------------------------------
function _M.window(view)
    local ok, window = pcall(window_mod)
    if not ok then return nil end
    return window.ancestor(view)
end

-- ---------------------------------------------------------------------------
-- 默认 init 处理器
-- ---------------------------------------------------------------------------
local displayable = {
    ["text/"] = true, ["image/"] = true, ["video/"] = true, ["audio/"] = true,
    ["application/xhtml+xml"] = true, ["application/xml"] = true, ["application/json"] = true,
    ["application/javascript"] = true, ["application/pdf"] = true, ["application/x-javascript"] = true,
    ["multipart/x-mixed-replace"] = true,
}
local function can_display(mime)
    mime = tostring(mime or ""):lower():gsub(";.*$", "")
    if mime == "" then return true end
    if displayable[mime] then return true end
    local prefix = mime:match("^(%a+/)")
    return prefix ~= nil and displayable[prefix] == true
end

local function start_download(uri, view)
    local dl = package.loaded["downloads"]
    if dl and type(dl.download) == "function" then
        local ok = pcall(dl.download, uri, { view = view })
        if ok then return true end
    end
    local ok, d = pcall(download, { uri = uri })
    if ok and d then pcall(d.start, d) return true end
    return false
end

_M.add_signal("init", function(view)
    view:add_signal("link-hover", function(v, uri)
        local w = _M.window(v)
        if w and w.sbar and w.sbar.l and w.sbar.l.uri and w.sbar.l.uri.is_alive then
            w.sbar.l.uri.text = "Link: " .. util.escape(tostring(uri))
        end
    end)
    view:add_signal("link-unhover", function(v)
        local w = _M.window(v)
        if w and w.sbar and w.sbar.l and w.sbar.l.uri and w.sbar.l.uri.is_alive then
            w.sbar.l.uri.text = util.escape(tostring(v.uri or ""))
        end
    end)

    view:add_signal("load-status", function(v, status, uri)
        local w = _M.window(v)
        if status == "committed" then apply_settings(v, domain_of(uri or v.uri)) end
        if w and w.view == v then w:update_win_title() end
    end)
    view:add_signal("property::title", function(v)
        local w = _M.window(v)
        if w then w:update_win_title() end
    end)
    view:add_signal("property::uri", function(v)
        local w = _M.window(v)
        if w and w.view == v then w:update_win_title() end
    end)

    view:add_signal("navigation-request", function(v, uri)
        if _M.has_load_block(v) then
            pending[v] = uri
            return false
        end
    end)

    view:add_signal("new-window-decision", function(v, uri)
        local w = _M.window(v)
        if w then
            w:new_tab(uri, { switch = false, private = v.private })
        else
            local ok, window = pcall(window_mod)
            if ok then window.new({ uri }) end
        end
        return false
    end)

    view:add_signal("mime-type-decision", function(v, uri, mime)
        if can_display(mime) then return end
        start_download(uri, v)
        return false
    end)

    view:add_signal("download-request", function(v, d)
        local dl = package.loaded["downloads"]
        if dl and type(dl.add) == "function" then
            local ok = pcall(dl.add, d, { view = v })
            if ok then return true end
        end
        pcall(d.start, d)
        return true
    end)

    view:add_signal("key-press", function(v, mods, key, synthetic)
        local w = _M.window(v)
        if not w then return end
        if synthetic and not get_setting("window.act_on_synthetic_keys", false) then return end
        return w:hit(mods, key)
    end)

    view:add_signal("button-press", function(v, mods, button)
        local w = _M.window(v)
        if not w then return end
        if button == 2 and v.hovered_uri then
            w:new_tab(v.hovered_uri, { switch = false, private = v.private })
            return true
        end
        local m = w.mode
        if m ~= "normal" and m ~= "insert" and m ~= "passthrough" then w:set_mode() end
    end)

    view:add_signal("crashed", function(v)
        local ep = package.loaded["error_page"]
        if ep and type(ep.show_error_page) == "function" then
            pcall(ep.show_error_page, v, {
                heading = "The page stopped responding",
                content = "The renderer for this tab was terminated. Reload to try again.",
                buttons = { { label = "Reload", callback = function(vv) vv:reload() end } },
                style = "crash",
            })
        else
            msg.warn("webview: tab %s crashed", tostring(v.id))
        end
    end)

    apply_settings(view, nil)
end)

-- ---------------------------------------------------------------------------
-- 渲染进程侧 webview_wm：输入框聚焦 → insert，失焦 → normal
-- ---------------------------------------------------------------------------
local function view_by_id(id)
    if __lk and __lk.webview_for_tab then return __lk.webview_for_tab(id, false) end
    return nil
end

do
    local ok, wm = pcall(require_web_module, "webview_wm")
    if ok and wm then
        wm:add_signal("form-active", function(_, view_id)
            local view = view_by_id(view_id)
            if not view then return end
            view:emit_signal("form-active")
            local w = _M.window(view)
            if w and w.view == view and w.mode == "normal" then w:set_mode("insert") end
        end)
        wm:add_signal("root-active", function(_, view_id)
            local view = view_by_id(view_id)
            if not view then return end
            view:emit_signal("root-active")
            local w = _M.window(view)
            if w and w.view == view and w.mode == "insert" then w:set_mode() end
        end)
    end
end

-- ---------------------------------------------------------------------------
-- 构造
-- ---------------------------------------------------------------------------
function _M.new(opts)
    opts = opts or {}
    install_window_index()
    local view = widget{ type = "webview", private = opts.private == true }
    _M.emit_signal("init", view)
    return view
end

install_window_index()

return _M
