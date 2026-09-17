-- 原生 → Lua 回投总入口。C++ 的 LemurXLuakitDispatch(kind, id, a, b) 最终调这里。

local handlers = {}

handlers.spawn = function(id, reason, status)
    if __lk.on_spawn_exit then __lk.on_spawn_exit(id, reason, status) end
end

handlers.post = function(id)
    if __lk.on_post then __lk.on_post(id) end
end

__lk.dispatchers = handlers

_G.__luakit_dispatch = function(kind, id, a, b)
    local h = handlers[kind]
    if not h then
        msg.verbose("__luakit_dispatch: no handler for %s", tostring(kind))
        return
    end
    local ok, err = xpcall(h, debug.traceback, id, a, b)
    if not ok then
        msg.warn("dispatch %s failed: %s", tostring(kind), tostring(err))
    end
end
