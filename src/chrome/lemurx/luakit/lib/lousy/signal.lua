-- LemurX · luakit-compatible library · lousy.signal
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.signal" module API. No luakit code is used.
--
-- 给普通 Lua 表补上和内核对象一样的信号协议：
--   add_signal / emit_signal / remove_signal / remove_signals
-- emit 时按注册顺序调用，第一个返回非 nil 的处理器终止传播并把返回值原样返回。
-- setup(obj, true) 表示“模块式”对象：处理器不接收 obj 作为第一个参数。

local M = {}

local unpack = table.unpack

-- obj -> { module = bool, handlers = { name = { fn, ... } } }
local registry = setmetatable({}, { __mode = "k" })

local function state_of(obj, what)
    local st = registry[obj]
    if not st then
        error(("lousy.signal.%s: object was not prepared with lousy.signal.setup()"):format(what), 3)
    end
    return st
end

local function check_name(name, what)
    if type(name) ~= "string" or name == "" then
        error(("lousy.signal.%s: signal name must be a non-empty string"):format(what), 3)
    end
end

function M.add_signal(obj, name, fn)
    local st = state_of(obj, "add_signal")
    check_name(name, "add_signal")
    if type(fn) ~= "function" then
        error(("lousy.signal.add_signal: handler for %q must be a function"):format(name), 2)
    end
    local list = st.handlers[name]
    if not list then
        list = {}
        st.handlers[name] = list
    end
    list[#list + 1] = fn
end

function M.emit_signal(obj, name, ...)
    local st = state_of(obj, "emit_signal")
    check_name(name, "emit_signal")
    local list = st.handlers[name]
    if not list or #list == 0 then return end
    -- 处理器可能在执行中增删同名处理器：遍历快照
    local snapshot = { unpack(list) }
    for _, fn in ipairs(snapshot) do
        local ret
        if st.module then
            ret = table.pack(fn(...))
        else
            ret = table.pack(fn(obj, ...))
        end
        if ret.n > 0 and ret[1] ~= nil then
            return unpack(ret, 1, ret.n)
        end
    end
end

function M.remove_signal(obj, name, fn)
    local st = state_of(obj, "remove_signal")
    check_name(name, "remove_signal")
    local list = st.handlers[name]
    if not list then return nil end
    for i, f in ipairs(list) do
        if f == fn then
            table.remove(list, i)
            if #list == 0 then st.handlers[name] = nil end
            return fn
        end
    end
    return nil
end

function M.remove_signals(obj, name)
    local st = state_of(obj, "remove_signals")
    check_name(name, "remove_signals")
    st.handlers[name] = nil
end

-- 是否有人在监听某个信号
function M.has_signal(obj, name)
    local st = registry[obj]
    if not st then return false end
    local list = st.handlers[name]
    return list ~= nil and #list > 0
end

-- 对象是否已经 setup 过
function M.is_setup(obj)
    return registry[obj] ~= nil
end

function M.setup(obj, is_module)
    if type(obj) ~= "table" then
        error("lousy.signal.setup: only plain tables can be prepared for signals", 2)
    end
    if registry[obj] then
        error("lousy.signal.setup: object already prepared for signals", 2)
    end
    registry[obj] = { module = is_module == true, handlers = {} }
    if is_module then
        -- 模块式：方法不带 self，直接以模块表为对象
        obj.add_signal = function(name, fn) return M.add_signal(obj, name, fn) end
        obj.emit_signal = function(name, ...) return M.emit_signal(obj, name, ...) end
        obj.remove_signal = function(name, fn) return M.remove_signal(obj, name, fn) end
        obj.remove_signals = function(name) return M.remove_signals(obj, name) end
    else
        obj.add_signal = M.add_signal
        obj.emit_signal = M.emit_signal
        obj.remove_signal = M.remove_signal
        obj.remove_signals = M.remove_signals
    end
    return obj
end

return M
