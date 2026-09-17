-- LemurX · luakit-compatible library · lousy.mode
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.mode" module API. No luakit code is used.
--
-- 任意“可发信号”的对象（有 emit_signal 方法）都可以挂一个模式名。
-- set() 依次：在对象上发 mode-left(old) → 旧模式 leave → 记录新模式 → 新模式 enter
-- → mode-entered(new, ...) → mode-changed(new, ...)。
-- new() 只是一个可选的模式登记表；没有登记的模式照样能 set，只是没有 enter/leave 回调。

local M = {}

-- obj -> 当前模式名（弱键，不影响对象回收）
local current = setmetatable({}, { __mode = "k" })
-- 已登记的模式：name -> { name, desc, enter, leave, ... }
local modes = {}
-- 模块级信号（mode-registered 等）
local signal = require("lousy.signal")
signal.setup(M, true)

local DEFAULT_MODE = "normal"

function M.is_modeable(obj)
    local t = type(obj)
    if t ~= "table" and t ~= "userdata" and t ~= "widget" then
        -- 内核对象 type() 返回类名；任何能取到 emit_signal 的都算
        local ok, fn = pcall(function() return obj.emit_signal end)
        return ok and type(fn) == "function"
    end
    local ok, fn = pcall(function() return obj.emit_signal end)
    return ok and type(fn) == "function"
end

local function default_mode_of(obj)
    local ok, dm = pcall(function() return obj.default_mode end)
    if ok and type(dm) == "string" and dm ~= "" then return dm end
    return DEFAULT_MODE
end

function M.get(obj)
    if obj == nil then return DEFAULT_MODE end
    return current[obj] or default_mode_of(obj)
end

function M.is(obj, name)
    return M.get(obj) == name
end

function M.set(obj, name, ...)
    if not M.is_modeable(obj) then
        error("lousy.mode.set: object cannot hold a mode (no emit_signal method)", 2)
    end
    if name == nil or name == "" then name = default_mode_of(obj) end
    if type(name) ~= "string" then
        error("lousy.mode.set: mode name must be a string", 2)
    end
    local old = current[obj]
    local old_def = old and modes[old]
    if old then
        obj:emit_signal("mode-left", old)
        if old_def and type(old_def.leave) == "function" then
            old_def.leave(obj)
        end
    end
    current[obj] = name
    local def = modes[name]
    if def and type(def.enter) == "function" then
        def.enter(obj, ...)
    end
    obj:emit_signal("mode-entered", name, ...)
    obj:emit_signal("mode-changed", name, ...)
    return name
end

-- 登记模式。tbl 可以是 { enter = fn, leave = fn, ... } 或直接一个 enter 函数。
-- 重复登记同名模式时合并字段（后者覆盖）。
function M.new(name, desc, tbl, ...)
    if type(name) ~= "string" or name == "" then
        error("lousy.mode.new: mode name must be a non-empty string", 2)
    end
    if type(desc) ~= "string" and tbl == nil then
        tbl, desc = desc, nil
    end
    if type(tbl) == "function" then tbl = { enter = tbl } end
    tbl = tbl or {}
    local def = modes[name] or { name = name }
    def.desc = desc or def.desc
    for k, v in pairs(tbl) do def[k] = v end
    def.name = name
    modes[name] = def
    M.emit_signal("mode-registered", name, def, ...)
    return def
end

function M.get_mode(name)
    return modes[name]
end

function M.get_modes()
    local out = {}
    for k, v in pairs(modes) do out[k] = v end
    return out
end

function M.remove_mode(name)
    local def = modes[name]
    modes[name] = nil
    return def
end

-- 让对象忘掉模式（销毁时用）
function M.clear(obj)
    current[obj] = nil
end

return M
