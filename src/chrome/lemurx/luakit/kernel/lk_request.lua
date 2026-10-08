-- request 类（对应 luakit clib/request.c）：自定义 scheme 的一次请求
--   webview 信号 "scheme-request::<name>" (view, uri, request)
--   request:finish(data [, mime]) ；request.finished 只读
-- 对象由 P2 的 scheme 处理管线创建（__lk.new_request）；Lua 不能直接构造。

local object = __lk.object

local request

request = object.class("request", {
    props = {
        finished = { get = function(obj) return object.priv(obj).finished == true end },
        uri = { get = function(obj) return object.priv(obj).uri end },
    },
    methods = {
        -- finish(data [, mime [, status]])：status 是 LemurX 扩展（默认 200；404/302 之类）
        finish = function(obj, data, mime, status)
            local p = object.priv(obj)
            if p.finished then
                error("request:finish(): request already finished", 2)
            end
            if type(data) ~= "string" then
                error("request:finish(): data must be a string", 2)
            end
            p.finished = true
            -- 对端（页面）已经关掉：静默丢弃
            if p.cancelled then return end
            p.data = data
            p.mime = mime or "text/html"
            if p.on_finish then
                p.on_finish(data, p.mime, status)
            end
        end,
    },
})

-- P2：scheme 管线造请求对象。on_finish(data, mime) 把响应交回原生层。
__lk.new_request = function(uri, on_finish)
    return object.new(request, { uri = uri, finished = false, on_finish = on_finish })
end

_G.request = request
return request
