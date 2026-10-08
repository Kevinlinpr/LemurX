-- widget{type="webview"}：luakit 的 WebKitWebView 控件映射到LemurX的一个 Tab
--（对应 luakit widgets/webview.c + widgets/webview/*.c）
--
-- 属性：uri(rw) title progress is_loading is_playing_audio private id web_process_id
--       hovered_uri source session_state(rw) stylesheets history scroll(rw) favicon
--       certificate inspector zoom_level(rw) user_agent(rw) enable_javascript(rw) editable(rw)
--       allow_file_access_from_file_urls allow_universal_access_from_file_urls
--       hardware_acceleration_policy + 全部 WebKitSettings 键（default_font_size 等）
-- 方法：search search_next search_previous clear_search go_back go_forward can_go_back
--       can_go_forward eval_js load_string reload reload_bypass_cache stop ssl_trusted
--       show_inspector close_inspector allow_certificate crash save loading
-- 信号：load-status(status, uri, err) navigation-request(uri, reason)->false 否决
--       new-window-decision(uri, reason)->false 否决  favicon  crashed  property::*
--       scheme-request::<name>(uri, request)（P2）  web-extension-loaded（P4）
--
-- 原生半边：__luakit.wv_*（lemurx_luakit_webview.cc），事件经 __luakit_dispatch("webview", tab, json)。

local object = __lk.object
local N = __luakit
local unpack = table.unpack

local by_tab = {}      -- tab_id -> view
local settings_defaults = {
    enable_javascript = true, auto_load_images = true, enable_plugins = false, enable_java = false,
    enable_webgl = true, enable_webaudio = true, enable_page_cache = true, enable_smooth_scrolling = true,
    enable_fullscreen = true, enable_html5_local_storage = true, enable_html5_database = true,
    enable_mediasource = true, enable_media_stream = true, enable_developer_extras = true,
    enable_caret_browsing = false, enable_spatial_navigation = false, enable_tabs_to_links = true,
    enable_dns_prefetching = true, enable_frame_flattening = false, enable_hyperlink_auditing = true,
    enable_resizable_text_areas = true, enable_site_specific_quirks = true, enable_xss_auditor = false,
    enable_write_console_messages_to_stdout = false, enable_accelerated_2d_canvas = true,
    javascript_can_access_clipboard = false, javascript_can_open_windows_automatically = false,
    media_playback_requires_gesture = true, media_playback_allows_inline = true,
    allow_modal_dialogs = false, allow_file_access_from_file_urls = false,
    allow_universal_access_from_file_urls = false, draw_compositing_indicators = false,
    print_backgrounds = false, hardware_acceleration_policy = "on-demand", zoom_text_only = false,
    default_font_size = 16, default_monospace_font_size = 13, minimum_font_size = 0,
    default_charset = "utf-8", default_font_family = "sans-serif", serif_font_family = "serif",
    sans_serif_font_family = "sans-serif", monospace_font_family = "monospace",
    cursive_font_family = "cursive", fantasy_font_family = "fantasy", pictograph_font_family = "sans-serif",
    user_agent = nil,
}

-- WebKitSettings → Chromium prefs（能映射的映射，其余只记值）
local pref_map = {
    default_font_size = "webkit.webprefs.default_font_size",
    default_monospace_font_size = "webkit.webprefs.default_fixed_font_size",
    minimum_font_size = "webkit.webprefs.minimum_font_size",
    auto_load_images = "webkit.webprefs.loads_images_automatically",
    default_charset = "webkit.webprefs.default_encoding",
    default_font_family = "webkit.webprefs.fonts.standard.Zyyy",
    serif_font_family = "webkit.webprefs.fonts.serif.Zyyy",
    sans_serif_font_family = "webkit.webprefs.fonts.sansserif.Zyyy",
    monospace_font_family = "webkit.webprefs.fonts.fixed.Zyyy",
    cursive_font_family = "webkit.webprefs.fonts.cursive.Zyyy",
    fantasy_font_family = "webkit.webprefs.fonts.fantasy.Zyyy",
    enable_javascript = "webkit.webprefs.javascript_enabled",
    enable_dns_prefetching = "net.network_prediction_options",
}

local function tab_of(view)
    local p = object.priv(view)
    if not p.tab then error("webview: tab already closed", 3) end
    return p.tab
end

local function info(view)
    local p = object.priv(view)
    local i = N.wv_info(p.tab)
    if i then p.info = i end
    return p.info or {}
end

local function eval(view, js)
    local ok, ret = pcall(lemurx.tabs.eval, tab_of(view), js)
    if ok then return ret end
    return nil, tostring(ret)
end

-- ===== scroll 代理表：view.scroll.x / .y 可读写 =====
local function make_scroll(view)
    local function read()
        local r = eval(view, [[(function(){var d=document.documentElement,b=document.body||d;
            return {x:window.scrollX|0,y:window.scrollY|0,
                    xmax:Math.max(0,(d.scrollWidth||b.scrollWidth)-window.innerWidth)|0,
                    ymax:Math.max(0,(d.scrollHeight||b.scrollHeight)-window.innerHeight)|0,
                    xpage_size:window.innerWidth|0,ypage_size:window.innerHeight|0}})()]])
        if type(r) == "table" then return r end
        return { x = 0, y = 0, xmax = 0, ymax = 0, xpage_size = 0, ypage_size = 0 }
    end
    return setmetatable({}, {
        __index = function(_, k) return read()[k] end,
        __newindex = function(_, k, v)
            if k == "x" then
                eval(view, ("window.scrollTo(%d, window.scrollY)"):format(math.floor(tonumber(v) or 0)))
            elseif k == "y" then
                eval(view, ("window.scrollTo(window.scrollX, %d)"):format(math.floor(tonumber(v) or 0)))
            else
                error("webview.scroll." .. tostring(k) .. " is read-only", 2)
            end
        end,
        __pairs = function() return pairs(read()) end,
    })
end

-- ===== stylesheets 代理表：view.stylesheets[ss] = true/false =====
local function apply_stylesheets(view)
    local p = object.priv(view)
    local parts = {}
    for ss, on in pairs(p.stylesheets) do
        if on and object.is_alive(ss) then
            parts[#parts + 1] = ss.source or ""
        end
    end
    local css = table.concat(parts, "\n")
    local js = ([[(function(){var id='__luakit_user_styles';var s=document.getElementById(id);
        if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s);}
        s.textContent=%s;})()]]):format(N.json_encode(css))
    pcall(lemurx.tabs.inject, p.tab, js, { world = "isolated", frames = "main" })
    p.styles_js = js
end

local function make_stylesheets(view)
    local p = object.priv(view)
    return setmetatable({}, {
        __index = function(_, ss) return p.stylesheets[ss] == true end,
        __newindex = function(_, ss, on)
            if type(ss) ~= "stylesheet" then error("webview.stylesheets key must be a stylesheet", 2) end
            p.stylesheets[ss] = on and true or nil
            apply_stylesheets(view)
        end,
        __pairs = function() return pairs(p.stylesheets) end,
    })
end

__lk.on_stylesheet_changed = function(ss)
    for _, view in pairs(by_tab) do
        local p = object.priv(view)
        if p and p.stylesheets[ss] then apply_stylesheets(view) end
    end
end

-- ===== 属性表 =====
local props = {}

props.uri = {
    get = function(view)
        -- 原生侧用 GetVisibleURL()，navigate 后同步反映 pending entry；拿不到时才用缓存
        local p = object.priv(view)
        local i = N.wv_info(p.tab)
        if i then p.info = i end
        return (p.info and p.info.uri) or "about:blank"
    end,
    set = function(view, v)
        if type(v) ~= "string" then error("webview.uri must be a string", 3) end
        local p = object.priv(view)
        p.info = p.info or {}
        p.info.uri = v
        lemurx.tabs.navigate(tab_of(view), v)
    end,
}
props.title = { get = function(view) return info(view).title or "" end }
props.progress = { get = function(view) return object.priv(view).progress or info(view).progress or 0 end }
props.is_loading = { get = function(view) return info(view).is_loading == true end }
props.is_playing_audio = { get = function(view) return info(view).audible == true end }
props.private = { get = function(view) return object.priv(view).private == true end }
props.id = { get = function(view) return object.priv(view).tab end }
props.web_process_id = { get = function(view) return info(view).process_id or 0 end }
props.hovered_uri = { get = function(view) return object.priv(view).hovered_uri end }
props.source = {
    get = function(view)
        local ok, html = pcall(lemurx.tabs.html, tab_of(view))
        if ok and type(html) == "string" then return html end
        if ok and type(html) == "table" then return html.html or html.source end
        return nil
    end,
}
props.session_state = {
    get = function(view) return N.wv_session_state(tab_of(view)) end,
    set = function(view, v)
        if type(v) ~= "string" then error("webview.session_state must be a string", 3) end
        N.wv_restore_session_state(tab_of(view), v)
    end,
}
props.stylesheets = { get = function(view) return object.priv(view).stylesheets_proxy end }
props.history = { get = function(view) return N.wv_history(tab_of(view)) or { index = 0, items = {} } end }
props.scroll = {
    get = function(view) return object.priv(view).scroll_proxy end,
    set = function(view, v)
        if type(v) ~= "table" then error("webview.scroll must be a table", 3) end
        local s = object.priv(view).scroll_proxy
        if v.x then s.x = v.x end
        if v.y then s.y = v.y end
    end,
}
props.favicon = { get = function(view) return object.priv(view).favicon_uri end }
props.favicon_uri = { get = function(view) return object.priv(view).favicon_uri end }
props.certificate = {
    get = function(view)
        local pem = N.wv_certificate(tab_of(view))
        return pem
    end,
}
props.inspector = { get = function(view) return object.priv(view).inspector == true end }
props.zoom_level = {
    get = function(view)
        local ok, pct = pcall(lemurx.tabs.getZoom, tab_of(view))
        if ok and type(pct) == "number" and pct > 0 then return pct / 100 end
        return 1.0
    end,
    set = function(view, v)
        if type(v) ~= "number" then error("webview.zoom_level must be a number", 3) end
        pcall(lemurx.tabs.setZoom, tab_of(view), v * 100)
    end,
}
props.user_agent = {
    get = function(view) return object.priv(view).settings.user_agent end,
    set = function(view, v)
        object.priv(view).settings.user_agent = v
        pcall(lemurx.tabs.setUserAgent, tab_of(view), v or "")
    end,
}
props.editable = {
    get = function(view) return object.priv(view).editable == true end,
    set = function(view, v)
        object.priv(view).editable = v and true or false
        eval(view, ("document.designMode=%q"):format(v and "on" or "off"))
    end,
}
props.enable_javascript = {
    get = function(view) return object.priv(view).settings.enable_javascript ~= false end,
    set = function(view, v)
        object.priv(view).settings.enable_javascript = v and true or false
        pcall(lemurx.tabs.setJavaScript, v and true or false)
    end,
}
props.enable_scripts = props.enable_javascript

-- 其余 WebKitSettings：记值 + 能映射的写 prefs
for name, default in pairs(settings_defaults) do
    if not props[name] then
        props[name] = {
            get = function(view)
                local s = object.priv(view).settings
                if s[name] ~= nil then return s[name] end
                return default
            end,
            set = function(view, v)
                object.priv(view).settings[name] = v
                local pref = pref_map[name]
                if pref then pcall(lemurx.prefs.set, pref, v) end
            end,
        }
    end
end

-- ===== 方法 =====
local methods = {}

function methods.search(view, text, case_sensitive, forward, wrap)
    local p = object.priv(view)
    if type(text) ~= "string" then error("webview:search expects text", 2) end
    p.search = { text = text, case = case_sensitive and true or false }
    if text == "" then
        N.wv_stop_find(p.tab, false)
        return
    end
    N.wv_find(p.tab, text, p.search.case, forward ~= false, true)
end
function methods.search_next(view)
    local p = object.priv(view)
    if p.search and p.search.text ~= "" then N.wv_find(p.tab, p.search.text, p.search.case, true, false) end
end
function methods.search_previous(view)
    local p = object.priv(view)
    if p.search and p.search.text ~= "" then N.wv_find(p.tab, p.search.text, p.search.case, false, false) end
end
function methods.clear_search(view)
    local p = object.priv(view)
    p.search = nil
    N.wv_stop_find(p.tab, false)
end

function methods.go_back(view, n)
    n = tonumber(n) or 1
    return N.wv_go_offset(tab_of(view), -n)
end
function methods.go_forward(view, n)
    n = tonumber(n) or 1
    return N.wv_go_offset(tab_of(view), n)
end
function methods.can_go_back(view) return info(view).can_go_back == true end
function methods.can_go_forward(view) return info(view).can_go_forward == true end

function methods.eval_js(view, script, opts)
    if type(script) ~= "string" then error("webview:eval_js expects a script string", 2) end
    opts = opts or {}
    local ret, err = eval(view, script)
    if opts.no_return == false then return end
    if opts.callback then
        local ok, cerr = xpcall(opts.callback, debug.traceback, ret, err)
        if not ok then msg.warn("eval_js callback error (%s): %s", tostring(opts.source or "?"), tostring(cerr)) end
    elseif err then
        msg.warn("eval_js error (%s): %s", tostring(opts.source or "?"), tostring(err))
    end
    return ret
end

function methods.load_string(view, html, content_uri)
    if type(html) ~= "string" then error("webview:load_string expects html string", 2) end
    N.wv_load_string(tab_of(view), html, content_uri or "about:blank")
end
function methods.reload(view) lemurx.tabs.reload(tab_of(view)) end
function methods.reload_bypass_cache(view) lemurx.tabs.reloadBypassCache(tab_of(view)) end
function methods.stop(view) lemurx.tabs.stop(tab_of(view)) end
function methods.ssl_trusted(view)
    local _, trusted = N.wv_certificate(tab_of(view))
    return trusted
end
function methods.show_inspector(view)
    local p = object.priv(view)
    p.inspector = true
    if lemurx.cdp and lemurx.cdp.inspect then pcall(lemurx.cdp.inspect, p.tab, 0, 0) end
    object.property_signal(view, "inspector")
end
function methods.close_inspector(view)
    local p = object.priv(view)
    p.inspector = false
    object.property_signal(view, "inspector")
end
function methods.allow_certificate(view, cert)
    local uri = view.uri or ""
    local parsed = soup.parse_uri(uri)
    local host = parsed and parsed.host
    if not host then return end
    local pem = cert or N.wv_certificate(tab_of(view)) or ""
    N.wv_allow_certificate(host, pem)
    luakit.allow_certificate(host, pem)
end
function methods.crash(view) N.wv_crash(tab_of(view)) end
function methods.loading(view) return info(view).is_loading == true end
function methods.save(view, path)
    -- WebKit 的 MHTML 保存；这里退化为存 outerHTML
    local html = view.source
    if type(html) ~= "string" then return false end
    local f, err = io.open(path, "wb")
    if not f then return false, err end
    f:write(html)
    f:close()
    return true
end
function methods.set_pdfjs() end

-- ===== impl（widget 通用接口）=====
local impl = {}

function impl.get(view, key)
    local p = object.priv(view)
    if key == "visible" then return p.visible ~= false end
    if key == "focused" then
        local ok, cur = pcall(lemurx.tabs.current)
        return ok and type(cur) == "table" and cur.id == p.tab
    end
    if key == "parent" then return p.parent end
    -- 与 widget 通用属性同名的 webview 专属属性（id/title 等）以 webview 语义为准
    local pr = props[key]
    if pr and pr.get then return pr.get(view) end
    return nil
end

function impl.set(view, key, v)
    local p = object.priv(view)
    local pr = props[key]
    if pr then
        if not pr.set then error(("webview.%s is read-only"):format(key), 3) end
        pr.set(view, v)
        return true
    end
    if key == "visible" then
        p.visible = v and true or false
        if v then pcall(lemurx.tabs.show, p.tab) else pcall(lemurx.tabs.hide, p.tab) end
        return true
    end
    if key == "parent" then p.parent = v return true end
    if key == "private" or key == "tab_id" then return true end
    return false
end

function impl.focus(view)
    pcall(lemurx.tabs.select, tab_of(view))
end

function impl.destroy(view)
    local p = object.priv(view)
    if __lk.on_webview_destroyed then __lk.on_webview_destroyed(view) end
    if p.tab then
        N.wv_detach(p.tab)
        pcall(lemurx.tabs.close, p.tab)
        by_tab[p.tab] = nil
        p.tab = nil
    end
end

function impl.index(view, key)
    local m = methods[key]
    if m then return m end
    local pr = props[key]
    if pr and pr.get then return pr.get(view) end
    return nil
end

function impl.newindex(view, key, v)
    -- 构造参数里的 private / tab_id 在 wrap() 里已处理
    if key == "private" or key == "tab_id" then return true end
    local pr = props[key]
    if pr then
        if not pr.set then error(("webview.%s is read-only"):format(key), 3) end
        pr.set(view, v)
        return true
    end
    return false
end

-- ===== 构造 =====
__lk.webview_created_hooks = __lk.webview_created_hooks or {}
local function wrap(tab_id, private)
    local view = __lk.new_widget("webview", impl, {})
    local p = object.priv(view)
    p.tab = tab_id
    p.private = private and true or false
    p.settings = {}
    p.stylesheets = {}
    p.visible = true
    p.stylesheets_proxy = make_stylesheets(view)
    p.scroll_proxy = make_scroll(view)
    by_tab[tab_id] = view
    if not N.wv_attach(tab_id) then
        msg.warn("webview: cannot attach observer to tab %d", tab_id)
    end
    info(view)
    -- LemurX 扩展：内核级"新 webview"钩子（luakit 里对应 lib/webview.lua 的 "init" 信号，
    -- 但不加载 luakit lib 的官方脚本也需要在每个标签上挂 navigation-request 等处理器）
    for _, hook in ipairs(__lk.webview_created_hooks) do
        local ok, err = pcall(hook, view)
        if not ok then msg.warn("webview created hook error: %s", tostring(err)) end
    end
    return view
end

__lk.register_widget_type("webview", function(cfg)
    local tab_id = cfg.tab_id or __lk.pending_tab_for_webview
    __lk.pending_tab_for_webview = nil
    if tab_id and by_tab[tab_id] and object.is_alive(by_tab[tab_id]) then
        return by_tab[tab_id]
    end
    if not tab_id then
        local ok, id = pcall(lemurx.tabs.open, "about:blank", { background = true, incognito = cfg.private == true })
        if not ok or not id or id < 0 then
            error("webview: failed to open a tab: " .. tostring(id), 3)
        end
        tab_id = id
    end
    return wrap(tab_id, cfg.private)
end)

-- 已有的原生 Tab 包成 webview（优先走 webview.lua 的 new，让 lib 的 init 钩子跑一遍）
__lk.wrap_tab = function(tab_id, private)
    if by_tab[tab_id] and object.is_alive(by_tab[tab_id]) then return by_tab[tab_id] end
    local wv = package.loaded["webview"]
    __lk.pending_tab_for_webview = tab_id
    if type(wv) == "table" and wv.new then
        local ok, view = pcall(wv.new, { private = private })
        __lk.pending_tab_for_webview = nil
        if ok then return view end
        msg.warn("webview.new failed for tab %d: %s", tab_id, tostring(view))
    end
    __lk.pending_tab_for_webview = nil
    return widget{ type = "webview", tab_id = tab_id, private = private }
end

__lk.webview_for_tab = function(tab_id, create)
    local v = by_tab[tab_id]
    if v and object.is_alive(v) then return v end
    if create then return __lk.wrap_tab(tab_id) end
    return nil
end

__lk.webviews = by_tab

-- ===== 原生事件 =====
__lk.dispatchers.webview = function(tab_id, json, nav_id)
    local ev = N.json_decode(json)
    if type(ev) ~= "table" then return end
    local view = by_tab[tab_id]
    if not view or not object.is_alive(view) then
        -- 未包装的 tab 上有节流器在等：放行
        if ev.ev == "navigation-request" then N.wv_navigation_reply(ev.id, true) end
        return
    end
    local p = object.priv(view)
    local kind = ev.ev
    if kind == "load-status" then
        if ev.status == "committed" or ev.status == "provisional" or ev.status == "redirected" then
            p.info = p.info or {}
            if ev.uri then p.info.uri = ev.uri end
        end
        if ev.status == "committed" then apply_stylesheets(view) end
        object.emit_ignore(view, "load-status", ev.status, ev.uri, ev.err)
    elseif kind == "property" then
        if ev.name == "uri" or ev.name == "title" then info(view) end
        object.property_signal(view, ev.name)
    elseif kind == "progress" then
        p.progress = ev.progress
        object.property_signal(view, "progress")
        object.property_signal(view, "estimated_load_progress")
    elseif kind == "favicon" then
        p.favicon_uri = ev.urls and ev.urls[1] or nil
        object.property_signal(view, "favicon")
        object.emit_ignore(view, "favicon")
    elseif kind == "crashed" then
        object.emit_ignore(view, "crashed")
    elseif kind == "audio" then
        object.property_signal(view, "is_playing_audio")
    elseif kind == "navigation-request" then
        -- 原生侧的导航正 DEFER 等这个答复；脚本抛错也必须回话，否则每次导航
        -- 都要等 4 秒超时才放行
        -- 第三个参数是 LemurX 扩展：{main_frame, redirect, renderer_initiated, user_gesture, tab}
        -- luakit 原版处理器只看前两个，多传一个不影响
        local ok, ret = pcall(object.emit_signal, view, "navigation-request", ev.uri, ev.reason, ev)
        if not ok then
            msg.warn("navigation-request handler error: %s", tostring(ret))
            ret = nil
        end
        N.wv_navigation_reply(ev.id, ret ~= false)
    elseif kind == "opened-url" then
        if ev.new_tab then
            local ret = object.emit_signal(view, "new-window-decision", ev.uri, ev.reason)
            if ret == false then
                pcall(lemurx.tabs.close, ev.new_tab)
            elseif __lk.autowrap_tabs ~= false then
                local child = __lk.wrap_tab(ev.new_tab, p.private)
                if child then object.priv(child).parent_view = view end
            end
        end
    elseif kind == "destroyed" then
        by_tab[tab_id] = nil
        p.tab = nil
        if __lk.on_webview_destroyed then __lk.on_webview_destroyed(view) end
        object.destroy(view)
    end
end

-- 原生 Tab 生命周期 → 自动包装（让 luakit 模块看见用户在原生 UI 里开的标签）
__lk.autowrap_tabs = lemurx.storage.get("luakit_autowrap", "1") == "1"
if __lk.autowrap_tabs then
    pcall(lemurx.tabs.on, "created", function(t)
        if type(t) == "table" and t.id and not by_tab[t.id] then
            local ok, err = pcall(__lk.wrap_tab, t.id, t.incognito)
            if not ok then msg.warn("autowrap tab %s failed: %s", tostring(t.id), tostring(err)) end
        end
    end)
    pcall(lemurx.tabs.on, "closed", function(t)
        local v = t and by_tab[t.id]
        if v and object.is_alive(v) then
            by_tab[t.id] = nil
            object.priv(v).tab = nil
            object.destroy(v)
        end
    end)
end

return true
