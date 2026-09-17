-- ipc_channel 类 + require_web_module（对应 luakit clib/ipc.c、common/clib/ipc.c、clib/web_module.c）
--
--   local ch = ipc_channel("adblock")        -- UI 进程侧
--   ch:add_signal("rules_updated", function(ch, arg, page_or_pid) end)
--   ch:emit_signal("enable", true)           -- 发到所有 web 进程（可选 page 参数只发一个）
--   require_web_module("adblock_wm")         -- 在每个 web 进程里 require 该模块
--
-- 传输层由 P4（renderer 内嵌 Lua + Mojo）提供：__lk.ipc_transport = { send = fn(channel, signame, args, target) }。
-- 在传输层就位前，emit 进队列，等 renderer 上线后回放（保留 luakit 的"web 模块在
-- 页面创建时就已加载好"的时序假设）。

local object = __lk.object
local unpack = table.unpack

local ipc_channel
local channels = {}       -- name -> 对象
local pending = {}        -- { name, signame, args, target }
local web_modules = {}    -- 有序模块名列表

local function send(name, signame, args, target)
    local t = __lk.ipc_transport
    if t and t.send then
        return t.send(name, signame, args, target)
    end
    pending[#pending + 1] = { name = name, signame = signame, args = args, target = target }
    if #pending == 1 then
        msg.verbose("ipc_channel(%s): transport not ready, queueing '%s'", name, signame)
    end
end

ipc_channel = object.class("ipc_channel", {
    props = {
        name = { get = function(obj) return object.priv(obj).name end },
    },
    methods = {
        -- 覆盖对象协议里的 emit_signal：这里是"发到 web 进程"，不是本地派发。
        -- 收到 web 进程的消息才走本地 add_signal 处理器（__lk.ipc_deliver）。
        -- luakit clib/ipc.c：ch:emit_signal([view|view_id,] signame, ...)
        -- 第一个参数是 webview 或其 id 时只发到该页面所在进程并附带 page；否则广播。
        emit_signal = function(obj, first, ...)
            local p = object.priv(obj)
            local target, signame, args
            if type(first) == "string" then
                signame = first
                args = table.pack(...)
            else
                target = first
                signame = ...
                args = table.pack(select(2, ...))
                if type(signame) ~= "string" then
                    error("ipc_channel:emit_signal expects a signal name", 2)
                end
                if type(target) ~= "widget" and type(target) ~= "number" then
                    error("ipc_channel:emit_signal: first argument must be a webview or its id", 2)
                end
            end
            send(p.name, signame, { unpack(args, 1, args.n) }, target)
        end,
    },
})

-- 构造：ipc_channel(name)，同名复用（luakit 同名会报错，这里宽松是因为热重载脚本常见）
setmetatable(ipc_channel, {
    __call = function(_, name)
        if type(name) ~= "string" then error("ipc_channel(name): name must be a string", 2) end
        local existing = channels[name]
        if existing and object.is_alive(existing) then
            return existing
        end
        local obj = object.new(ipc_channel, { name = name })
        channels[name] = obj
        return obj
    end,
    __tostring = function() return "class ipc_channel" end,
})

-- web 进程 → UI 进程投递：走本地 add_signal 处理器，附带来源 page/pid
__lk.ipc_deliver = function(name, signame, args, source)
    local obj = channels[name]
    if not obj or not object.is_alive(obj) then
        msg.verbose("ipc_channel: no channel %q for '%s'", tostring(name), tostring(signame))
        return
    end
    -- luakit clib/ipc.c ipc_channel_recv：UI 侧处理器只收 (ch, ...)，不附来源
    return object.emit_signal(obj, signame, unpack(args, 1, #args))
end

__lk.ipc_flush_pending = function()
    local t = __lk.ipc_transport
    if not (t and t.send) then return end
    local q = pending
    pending = {}
    for _, m in ipairs(q) do
        t.send(m.name, m.signame, m.args, m.target)
    end
end

__lk.web_modules = web_modules

-- 返回值是一个与模块同名的 ipc_channel（luakit 的 web_module 对象就是这样一条通道：
-- webview.lua 里 `local web_module = require_web_module("webview_wm")` 然后
-- web_module:emit_signal(...) / :add_signal(...)）。
_G.require_web_module = function(name)
    if type(name) ~= "string" then error("require_web_module expects a module name", 2) end
    local known = false
    for _, n in ipairs(web_modules) do
        if n == name then known = true break end
    end
    if not known then
        web_modules[#web_modules + 1] = name
        if __lk.on_require_web_module then
            __lk.on_require_web_module(name)
        else
            msg.verbose("require_web_module(%s): renderer Lua not attached yet, will load on attach", name)
        end
    end
    return ipc_channel(name)
end

_G.ipc_channel = ipc_channel
return ipc_channel
