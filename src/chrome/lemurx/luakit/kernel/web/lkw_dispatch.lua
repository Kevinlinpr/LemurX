-- 渲染进程原生 → Lua 派发入口：_G.__luakit_web_dispatch(tag, ...)
--
--   "page-created"(rid, page_id)          "page-destroyed"(rid)
--   "document-loaded"(rid)                "window-object-cleared"(rid, uri)
--   "ipc"(channel, rid|nil, signame, args_json)
--   "eval"(rid, script, source, cb_id)    -- UI 侧 webview:eval_js，回 eval_js_reply
--   "event"(cb_id, event_handle)          -- DOM 事件
--   "call"(cb_id, ...) -> ret             -- JS 调了 Lua 包装函数
--   "fn"(rid, name, resolver, ...)        -- luakit.register_function 暴露的函数被调
--   "request"(rid, uri, headers, info) -> verdict, headers   -- 子资源请求（send-request）
--        info = {type="script"|"image"|..., destination, method, initiator, main_frame, mode}
--   "require"(name)                       -- require_web_module

local N = __luakit_web
local js = __lk.js
local unpack = table.unpack

local D = {}

D["page-created"] = function(rid, page_id)
    __lk.page_created(rid, page_id)
end

D["page-destroyed"] = function(rid)
    __lk.page_destroyed(rid)
end

D["document-loaded"] = function(rid)
    __lk.page_document_loaded(rid)
end

D["window-object-cleared"] = function(rid, uri)
    __lk.page_window_cleared(rid, uri)
end

D.ipc = function(channel, rid, signame, args_json)
    local args = N.json_decode(args_json or "[]") or {}
    return __lk.ipc_deliver(channel, rid, signame, args)
end

D.eval = function(rid, script, source, cb_id)
    js.current_rid = rid
    local v, err = N.page_eval(rid, script, source)
    if v == nil and err then
        N.eval_js_reply(cb_id, "null", tostring(err))
        return
    end
    -- 句柄类值（节点/函数）无法跨进程，退化为字符串描述
    local function plain(x, depth)
        depth = depth or 0
        if js.is_handle(x) then
            local kind = rawget(x, "__jskind")
            local s = kind == "node" and ("[object Node]") or ("[" .. tostring(kind) .. "]")
            pcall(N.js_release, x)
            return s
        end
        if type(x) == "table" and depth < 16 then
            for k, y in pairs(x) do x[k] = plain(y, depth + 1) end
        end
        return x
    end
    v = plain(v)
    local ok, json = pcall(N.json_encode, v)
    N.eval_js_reply(cb_id, ok and json or "null", nil)
end

D.event = function(cb_id, evh)
    if not js.is_handle(evh) then return end
    __lk.on_dom_event(cb_id, evh)
end

D.call = function(cb_id, ...)
    return js.on_call(cb_id, ...)
end

D.fn = function(rid, name, resolver, ...)
    __lk.call_registered(rid, name, resolver, ...)
end

D.request = function(rid, uri, headers, info)
    return __lk.page_send_request(rid, uri, headers or {}, info)
end

D.require = function(name)
    local ok, err = xpcall(require, debug.traceback, name)
    if not ok then
        msg.warn("require_web_module(%s) failed: %s", tostring(name), tostring(err))
    end
end

_G.__luakit_web_dispatch = function(tag, ...)
    local fn = D[tag]
    if not fn then
        msg.verbose("web dispatch: unknown tag %q", tostring(tag))
        return
    end
    local results = table.pack(xpcall(fn, debug.traceback, ...))
    if not results[1] then
        msg.warn("web dispatch %s failed: %s", tostring(tag), tostring(results[2]))
        return
    end
    return unpack(results, 2, results.n)
end

__lk.dispatchers = D
return D
