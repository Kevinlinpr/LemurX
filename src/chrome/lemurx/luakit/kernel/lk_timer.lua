-- timer 类（对应 luakit common/clib/timer.c）
--   local t = timer{ interval = 1000 }   -- 毫秒
--   t:start() / t:stop() ；t.started 只读；t.interval 可读写（运行中改会重启）
--   信号 "timeout"：每个周期发一次，直到 stop
-- 底层 lemurx.timer.every / cancel。

local object = __lk.object

local timer

local function stop(obj)
    local p = object.priv(obj)
    if p.handle then
        lemurx.timer.cancel(p.handle)
        p.handle = nil
    end
    p.started = false
end

local function start(obj)
    local p = object.priv(obj)
    if p.started then
        msg.warn("timer already started")
        return
    end
    if type(p.interval) ~= "number" or p.interval <= 0 then
        error("timer: invalid interval " .. tostring(p.interval), 3)
    end
    p.started = true
    p.handle = lemurx.timer.every(math.floor(p.interval), function()
        if not object.is_alive(obj) then
            stop(obj)
            return
        end
        if not p.started then return end
        object.emit_ignore(obj, "timeout")
    end)
end

timer = object.class("timer", {
    props = {
        interval = {
            get = function(obj) return object.priv(obj).interval end,
            set = function(obj, v)
                if type(v) ~= "number" then error("timer.interval must be a number", 3) end
                local p = object.priv(obj)
                p.interval = v
                if p.started then
                    stop(obj)
                    start(obj)
                end
            end,
        },
        started = { get = function(obj) return object.priv(obj).started == true end },
    },
    methods = {
        start = function(obj) start(obj) end,
        stop = function(obj)
            local p = object.priv(obj)
            if not p.started then
                msg.warn("timer not started")
                return
            end
            stop(obj)
        end,
    },
    new = function(props)
        local obj = object.new(timer, { interval = 1000, started = false })
        for k, v in pairs(props or {}) do
            obj[k] = v
        end
        return obj
    end,
    gc = function(obj) stop(obj) end,
})

_G.timer = timer
return timer
