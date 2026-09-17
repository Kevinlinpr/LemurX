-- ipc_channel（渲染进程版，对应 luakit extension/clib/ipc.c + common/clib/ipc.c）
--
--   local ui = ipc_channel("adblock_wm")
--   ui:add_signal("update_rules", function(ch, page, rules) end)   -- 来自 UI 进程
--   ui:emit_signal("rules_updated", luakit.web_process_id)          -- 发到 UI 进程
--
-- UI 进程发来的信号，第二个参数是目标 page（广播时为 nil），与 luakit 一致。

local N = __luakit_web
local object = __lk.object
local js = __lk.js
local unpack = table.unpack

local channels = {}

local function to_wire(rid, args)
    -- 传给 UI 进程的参数经 JSON；page 对象换成其 id
    local out = {}
    for i = 1, args.n do
        local v = args[i]
        if type(v) == "page" then
            v = v.id
        elseif type(v) == "dom_element" or type(v) == "dom_document" then
            v = tostring(v)
        end
        out[i] = v
    end
    return out, args.n
end

local ipc_channel = object.class("ipc_channel", {
    props = {
        name = { get = function(o) return object.priv(o).name end },
    },
    methods = {
        emit_signal = function(o, signame, ...)
            if type(signame) ~= "string" then error("ipc_channel:emit_signal expects a signal name", 2) end
            local args = table.pack(...)
            local wire, n = to_wire(nil, args)
            -- JSON 数组不能带尾部 nil；用显式长度编码
            local arr = {}
            for i = 1, n do
                local v = wire[i]
                if v == nil then v = N.json_decode("null") end
                arr[i] = v
            end
            N.ipc_send(object.priv(o).name, signame, N.json_encode(arr))
        end,
    },
    tostring = function(o) return "ipc_channel: " .. object.priv(o).name end,
})

setmetatable(ipc_channel, {
    __call = function(_, name)
        if type(name) ~= "string" then error("ipc_channel(name): name must be a string", 2) end
        local existing = channels[name]
        if existing and object.is_alive(existing) then return existing end
        local obj = object.new(ipc_channel, { name = name })
        channels[name] = obj
        return obj
    end,
    __tostring = function() return "class ipc_channel" end,
})

-- UI 进程 → 这里
function __lk.ipc_deliver(name, rid, signame, args)
    local ch = channels[name]
    if not (ch and object.is_alive(ch)) then
        msg.verbose("ipc_channel: no web channel %q for '%s'", tostring(name), tostring(signame))
        return
    end
    local target = nil
    if rid then target = __lk.page_for(rid) end
    local n = 0
    if type(args) == "table" then n = #args end
    return object.emit_signal(ch, signame, target, unpack(args or {}, 1, n))
end

_G.ipc_channel = ipc_channel
return ipc_channel
