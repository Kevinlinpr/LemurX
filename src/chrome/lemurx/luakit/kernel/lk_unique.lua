-- unique 模块（对应 luakit clib/unique.c）：单实例控制
--   unique.new(name) ；unique.is_running() ；unique.send_message(str)
--   信号 "message"(msg, screen)
-- Android 一个包只有一个进程实例，is_running 永远 false；new/send_message 只记账。
-- unique_instance.lua 依赖的语义（首个实例接 message 打开 URI）在这里通过
-- lemurx.tabs.on("intent") 一类入口回放（P1 接）。

local object = __lk.object

local state = { name = nil }

local unique = object.module("unique", {
    funcs = {
        new = function(name)
            if type(name) ~= "string" then error("unique.new expects a string", 2) end
            if state.name then
                msg.warn("unique.new: instance name already set to %s", state.name)
                return
            end
            state.name = name
        end,
        is_running = function()
            return false
        end,
        send_message = function(message)
            if type(message) ~= "string" then error("unique.send_message expects a string", 2) end
            -- 没有别的实例；按 luakit 语义自己也不该收到。记录方便调试。
            msg.verbose("unique.send_message(%q) dropped: single instance", message)
        end,
    },
})

-- 外部入口（如 Intent 打开链接）想模拟"另一个实例发来的消息"时调这个
__lk.unique_deliver = function(message, screen)
    unique.emit_signal("message", message, screen)
end

_G.unique = unique
return unique
