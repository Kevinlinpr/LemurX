-- 自定义 scheme 管线（对应 luakit widgets/webview.c 的 luakit_uri_scheme_request_cb
-- + clib/request.c）。
--
--   luakit.register_scheme("luakit")            → 原生 __luakit.scheme_register
--   导航 / 子资源命中该 scheme                    → 原生投 __luakit_dispatch("scheme", id, json)
--   这里造 request 对象，在对应 webview 上发 "scheme-request::<name>"(view, uri, request)
--   request:finish(data, mime)                   → __luakit.scheme_reply(id, data, mime)
--
-- 与 luakit 的差异：
--   * luakit 里没人 finish 的请求会一直挂着；这里若 webview 上根本没挂该信号的处理器，
--     直接回 404，避免页面永久 loading。挂了处理器但异步 finish 的照旧等待（原生 30s 超时）。
--   * 原生找不到发起请求的 Tab（tab=-1，例如 worker）时，退到当前选中 Tab 的 webview。

local object = __lk.object
local N = __luakit

-- 未完成的请求：id -> request 对象（Lua 侧兜底，原生 cancel 时清）
local pending = {}

local function reply(id, data, mime, status)
    pending[id] = nil
    N.scheme_reply(id, data, mime, status)
end

local function fail(id, err)
    pending[id] = nil
    N.scheme_error(id, err)
end

-- 找请求所属的 webview
local function view_for(tab)
    if tab and tab >= 0 then
        local v = __lk.webview_for_tab(tab, true)
        if v then return v end
    end
    -- 兜底：当前选中 Tab
    local ok, cur = pcall(lemurx.tabs.current)
    if ok and type(cur) == "number" then
        local v = __lk.webview_for_tab(cur, true)
        if v then return v end
    end
    -- 再兜底：任意活着的 webview
    for _, v in pairs(__lk.webviews) do
        if object.is_alive(v) then return v end
    end
    return nil
end

-- LemurX 扩展：全局 scheme 处理器 —— 不挂在某个 webview 上，任何标签（含 worker、
-- 尚未包装的标签）命中该 scheme 都走它。__lk.scheme_handlers[scheme] = fn(uri, request, ev)
-- 有 webview 级处理器时 webview 级优先（保持 luakit 语义）。
__lk.scheme_handlers = __lk.scheme_handlers or {}

local function on_request(ev)
    local id = ev.id
    local view = view_for(ev.tab)
    local sig = "scheme-request::" .. tostring(ev.scheme)
    local global_handler = __lk.scheme_handlers[tostring(ev.scheme)]
    local use_view = view and object.has_signal(view, sig)
    if not use_view and not global_handler then
        if not view then
            msg.warn("scheme-request %s: no webview to deliver to", tostring(ev.uri))
        else
            msg.verbose("scheme-request %s: no handler for %s", tostring(ev.uri), sig)
        end
        return fail(id)
    end

    local request = __lk.new_request(ev.uri, function(data, mime, status)
        reply(id, data, mime, status)
    end)
    pending[id] = request

    local ok, err = xpcall(function()
        if use_view then
            object.emit_signal(view, sig, ev.uri, request)
        else
            global_handler(ev.uri, request, ev)
        end
    end, debug.traceback)
    if not ok then
        msg.warn("scheme-request::%s handler error: %s", tostring(ev.scheme), tostring(err))
        if pending[id] then
            -- 出错且没 finish：给一个可读的错误页，而不是空转
            local html = ("<html><body><h1>Chrome handler error</h1><pre>%s</pre></body></html>")
                :format((tostring(err):gsub("&", "&amp;"):gsub("<", "&lt;")))
            reply(id, html, "text/html")
        end
    end
end

__lk.dispatchers.scheme = function(id, json, tab)
    local ev = N.json_decode(json)
    if type(ev) ~= "table" then return end
    if ev.ev == "cancel" then
        local r = pending[id]
        pending[id] = nil
        if r then
            -- 对端已走：后续 finish 变成空操作而不是报错
            local p = object.priv(r)
            p.cancelled = true
            p.on_finish = nil
        end
        return
    end
    if ev.ev == "request" then
        ev.id = ev.id or id
        if ev.tab == nil then ev.tab = tab end
        on_request(ev)
    end
end

-- luakit.register_scheme 的钩子：进原生表
__lk.on_register_scheme = function(name)
    local ok, err = N.scheme_register(name)
    if not ok and err ~= "builtin scheme" then
        msg.warn("register_scheme(%s): %s", tostring(name), tostring(err))
    end
end

return true
