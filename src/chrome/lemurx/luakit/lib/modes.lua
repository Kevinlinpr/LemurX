-- LemurX · luakit-compatible library · modes
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "modes" module API. No luakit code is used.
--
-- 模式注册表与按键绑定表的管理：
--   modes.new_mode(name, desc, tbl[, replace])   定义/扩展一个模式（enter/leave/changed/activate…）
--   modes.get_mode(name) / modes.get_modes()
--   modes.add_binds(mode|{modes}, binds[, before]) / modes.remove_binds / modes.remap_binds
--   modes.add_cmds(cmds[, before])                往 command 模式加 ":cmd" 绑定
--   modes.get_cmds()                               供补全用的命令名清单（独立于 lousy.bind 内部结构）
-- 并把 w:set_mode(name, ...) / w:is_mode(name) / w:update_binds() 装进 window.methods。
-- 内置模式：all normal insert passthrough command completion lua。

local lousy = require("lousy")
local window = require("window")
local util = lousy.util

local _M = {}
lousy.signal.setup(_M, true)

local registry = {}     -- name -> mode table
local order = {}        -- 注册顺序

local function each_mode_name(mode)
    if type(mode) == "string" then return { mode } end
    if type(mode) == "table" then
        local out = {}
        for _, n in ipairs(mode) do out[#out + 1] = n end
        return out
    end
    error("modes: expected a mode name or an array of names, got " .. type(mode), 3)
end

-- ---------------------------------------------------------------------------
-- 模式表
-- ---------------------------------------------------------------------------
function _M.new_mode(name, desc, mode, replace)
    assert(type(name) == "string", "modes.new_mode: name must be a string")
    if type(desc) == "table" then
        desc, mode, replace = nil, desc, mode
    end
    mode = mode or {}
    local existing = registry[name]
    if existing and not replace then
        for k, v in pairs(mode) do
            if k ~= "binds" and k ~= "commands" then existing[k] = v end
        end
        if desc then existing.desc = desc end
        _M.emit_signal("mode-changed", name, existing)
        return existing
    end
    local m = {}
    for k, v in pairs(mode) do m[k] = v end
    m.name = name
    m.desc = desc or m.desc
    m.binds = (existing and existing.binds) or m.binds or {}
    m.commands = (existing and existing.commands) or {}
    if not existing then order[#order + 1] = name end
    registry[name] = m
    _M.emit_signal(existing and "mode-changed" or "mode-added", name, m)
    return m
end

function _M.get_mode(name)
    return registry[name]
end

function _M.get_modes()
    local out = {}
    for _, n in ipairs(order) do out[n] = registry[n] end
    return out
end

function _M.mode_names()
    local out = {}
    for _, n in ipairs(order) do out[#out + 1] = n end
    return out
end

local function ensure_mode(name)
    return registry[name] or _M.new_mode(name, nil, {})
end

-- ---------------------------------------------------------------------------
-- 命令名解析：":o[pen], :t" → { "open", "o", "t" }
-- ---------------------------------------------------------------------------
local function expand_cmd_names(trigger)
    local names = {}
    for piece in tostring(trigger):gmatch("[^,]+") do
        piece = piece:gsub("^%s*:?", ""):gsub("%s+$", "")
        if piece ~= "" then
            local base, opt = piece:match("^([^%[]+)%[([^%]]*)%]$")
            if base then
                names[#names + 1] = base .. opt
                names[#names + 1] = base
            else
                names[#names + 1] = piece
            end
        end
    end
    return names
end
_M.expand_cmd_names = expand_cmd_names

local function is_cmd_trigger(trigger)
    return type(trigger) == "string" and trigger:match("^%s*:") ~= nil
end

local function refresh_windows()
    for _, w in pairs(window.bywidget) do
        if w.update_binds then pcall(w.update_binds, w) end
    end
end

-- ---------------------------------------------------------------------------
-- 绑定
-- ---------------------------------------------------------------------------
local function normalize_bind(b)
    if type(b) ~= "table" then error("modes: bind entry must be a table", 4) end
    local trigger, desc, func, opts = b[1], b[2], b[3], b[4]
    if type(desc) == "function" then
        desc, func, opts = nil, desc, func
    end
    if type(trigger) ~= "string" then error("modes: bind trigger must be a string", 4) end
    if type(func) ~= "function" then error(("modes: bind %q has no action function"):format(trigger), 4) end
    return trigger, desc, func, opts or {}
end

local function record_command(m, trigger, desc, func, opts)
    local names = expand_cmd_names(trigger)
    for i = #m.commands, 1, -1 do
        if m.commands[i].trigger == trigger then table.remove(m.commands, i) end
    end
    m.commands[#m.commands + 1] = { trigger = trigger, names = names, desc = desc, func = func, opts = opts }
end

local function forget_command(m, trigger)
    for i = #m.commands, 1, -1 do
        if m.commands[i].trigger == trigger then table.remove(m.commands, i) end
    end
end

function _M.add_binds(mode, binds, before)
    assert(type(binds) == "table", "modes.add_binds: binds must be a table")
    for _, name in ipairs(each_mode_name(mode)) do
        local m = ensure_mode(name)
        for _, b in ipairs(binds) do
            local trigger, desc, func, opts = normalize_bind(b)
            lousy.bind.remove_bind(m.binds, trigger)
            lousy.bind.add_bind(m.binds, trigger, { func = func, desc = desc }, opts)
            if before and #m.binds > 1 then
                table.insert(m.binds, 1, table.remove(m.binds))
            end
            if is_cmd_trigger(trigger) then record_command(m, trigger, desc, func, opts) end
        end
    end
    refresh_windows()
end

function _M.remove_binds(mode, names)
    assert(type(names) == "table", "modes.remove_binds: binds must be a table")
    for _, name in ipairs(each_mode_name(mode)) do
        local m = registry[name]
        if m then
            for _, trigger in ipairs(names) do
                lousy.bind.remove_bind(m.binds, trigger)
                forget_command(m, trigger)
            end
        end
    end
    refresh_windows()
end

function _M.remap_binds(mode, remaps)
    assert(type(remaps) == "table", "modes.remap_binds: binds must be a table")
    for _, name in ipairs(each_mode_name(mode)) do
        local m = ensure_mode(name)
        for _, r in ipairs(remaps) do
            local new, old, keep = r[1], r[2], r[3]
            lousy.bind.remap_bind(m.binds, new, old, keep)
            for _, c in ipairs(m.commands) do
                if c.trigger == old then
                    record_command(m, new, c.desc, c.func, c.opts)
                    if not keep then forget_command(m, old) end
                    break
                end
            end
        end
    end
    refresh_windows()
end

-- add_cmds(cmds[, before]) 或 add_cmds(mode, cmds)
function _M.add_cmds(a, b)
    if type(a) == "string" or (type(a) == "table" and type(a[1]) == "string") then
        return _M.add_binds(a, b)
    end
    return _M.add_binds("command", a, b)
end

function _M.get_cmds(mode)
    local m = registry[mode or "command"]
    local out = {}
    for _, c in ipairs(m and m.commands or {}) do out[#out + 1] = c end
    return out
end

function _M.get_binds(mode)
    local m = registry[mode]
    return m and m.binds or nil
end

-- ---------------------------------------------------------------------------
-- 窗口方法
-- ---------------------------------------------------------------------------
window.methods.is_mode = function(w, name)
    return w.mode == name
end

window.methods.set_mode = function(w, name, ...)
    name = name or "normal"
    local target = registry[name]
    if not target then
        msg.warn("modes: unknown mode %q, falling back to normal", tostring(name))
        name, target = "normal", registry["normal"]
    end
    local old = w.mode
    local oldm = old and registry[old]
    if oldm and oldm.leave then
        local ok, err = xpcall(oldm.leave, debug.traceback, w)
        if not ok then msg.error("mode %s leave: %s", old, tostring(err)) end
    end
    if old then w:emit_signal("mode-left", old) end

    w.mode = name
    w:update_binds(name)

    -- lousy.mode 负责记录并发 mode-changed；没发就自己补
    w.__mode_changed_seen = false
    local okset = pcall(lousy.mode.set, w, name, ...)
    if not okset or not w.__mode_changed_seen then
        w:emit_signal("mode-changed", name, ...)
    end
    w.__mode_changed_seen = nil

    if target.enter then
        local ok, err = xpcall(target.enter, debug.traceback, w, ...)
        if not ok then msg.error("mode %s enter: %s", name, tostring(err)) end
    end
    w:emit_signal("mode-entered", name)
end

window.methods.update_binds = function(w, mode_name)
    local m = registry[mode_name or w.mode or "normal"]
    local out = {}
    for _, b in ipairs(m and m.binds or {}) do out[#out + 1] = b end
    if not (m and m.passthrough) then
        local all = registry["all"]
        if all and all ~= m then
            for _, b in ipairs(all.binds) do out[#out + 1] = b end
        end
    end
    w.binds = out
end

window.add_signal("init", function(w)
    w:add_signal("mode-changed", function(ww) ww.__mode_changed_seen = true end)
end)

-- ---------------------------------------------------------------------------
-- 内置模式
-- ---------------------------------------------------------------------------
_M.new_mode("all", "Bindings active in every mode.", {})

_M.new_mode("normal", "Default mode: keys drive the browser.", {
    enable_buffer = true,
    enter = function(w)
        w.buffer = nil
        w:update_buf()
        w:set_prompt()
        w:set_input()
        w:set_ibar_theme()
        w:hide_menu()
    end,
})

_M.new_mode("insert", "Keys go to the page (form fields).", {
    enter = function(w)
        w:set_prompt("-- INSERT --")
        w:set_input()
        w:set_ibar_theme("insert")
        local view = w.view
        if view and view.is_alive then pcall(view.focus, view) end
    end,
})

_M.new_mode("passthrough", "Every key except Escape goes to the page.", {
    passthrough = true,
    enter = function(w)
        w:set_prompt("-- PASS THROUGH --")
        w:set_input()
        w:set_ibar_theme("passthrough")
        local view = w.view
        if view and view.is_alive then pcall(view.focus, view) end
    end,
})

_M.new_mode("command", "Type a : command in the input bar.", {
    has_input = true,
    enter = function(w)
        w:set_prompt()
        w:set_input(":")
        w:set_ibar_theme("command")
    end,
    changed = function(w, text)
        if not w:is_mode("command") then return end
        if text:sub(1, 1) ~= ":" then w:set_mode() end
    end,
    activate = function(w, text)
        w:set_mode()
        w:run_cmd(text)
    end,
    leave = function(w)
        w:set_input()
    end,
})

-- completion.lua 会用 new_mode 往这里补 enter/leave/changed
_M.new_mode("completion", "Pick a completion for the command being typed.", { has_input = true })

local function eval_lua(w, code)
    local env = setmetatable({ w = w, view = w.view, window = window, modes = _M }, { __index = _G })
    local fn, err = load("return " .. code, "=lua", "t", env)
    if not fn then fn, err = load(code, "=lua", "t", env) end
    if not fn then
        w:error(tostring(err))
        return
    end
    local results = table.pack(xpcall(fn, debug.traceback))
    if not results[1] then
        w:error(tostring(results[2]))
        return
    end
    if results.n > 1 then
        local parts = {}
        for i = 2, results.n do parts[#parts + 1] = tostring(results[i]) end
        w:notify(table.concat(parts, "\t"))
    end
end
_M.eval_lua = eval_lua

_M.new_mode("lua", "Evaluate Lua in the browser process.", {
    has_input = true,
    enter = function(w)
        w:set_prompt(">")
        w:set_input("", { show = true })
        w:set_ibar_theme("lua")
    end,
    activate = function(w, text)
        w:set_mode()
        if text ~= "" then eval_lua(w, text) end
    end,
    leave = function(w)
        w:set_input()
    end,
})

return _M
