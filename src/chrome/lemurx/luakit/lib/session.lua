-- LemurX · luakit-compatible library · session
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "session" module API. No luakit code is used.
--
-- 会话保存/恢复。文件用 lousy.pickle 序列化，默认路径 luakit.data_dir/session，
-- 崩溃恢复副本 luakit.data_dir/recovery.session。
--   session.save([file])          把所有窗口的标签写盘；先发模块信号 save(state) 让别的模块塞数据
--   session.load([delete[, file]]) 读文件返回 state 表（不恢复）；非恢复文件时先备份到 recovery_file
--   session.restore([delete])     按 state 重建窗口，返回最后一个窗口；发 restore(state)
--   w:save_session()              窗口方法
-- state = { version = 1, windows = { { tabs = { {uri, title, session_state, private} }, current = i,
--           private = bool, closed_tabs = {...} } }, extra = {} }
-- settings.session.always_save 为真时窗口关闭 / luakit.quit 前自动保存；
-- settings.session.recovery_save_interval（秒，0 关闭）定时写恢复文件。
-- 命令：:sess[ion] 保存，:restore 恢复。

local lousy = require("lousy")
local window = require("window")
local modes = require("modes")
local util = lousy.util

local _M = {}
lousy.signal.setup(_M, true)

_M.session_file = luakit.data_dir .. "/session"
_M.recovery_file = luakit.data_dir .. "/recovery.session"

local settings = package.loaded["settings"]
if not settings then
    local ok, mod = pcall(require, "settings")
    if ok and type(mod) == "table" then settings = mod end
end
local function get_setting(key, default)
    if settings and settings.get_setting then
        local ok, v = pcall(settings.get_setting, key)
        if ok and v ~= nil then return v end
    end
    return default
end
if settings and settings.register_settings then
    pcall(settings.register_settings, {
        ["session.always_save"] = {
            type = "boolean", default = false,
            desc = "Write the session whenever a window closes or the browser quits.",
        },
        ["session.recovery_save_interval"] = {
            type = "number", default = 30, min = 0,
            desc = "Seconds between automatic recovery-file saves (0 disables).",
        },
    })
end

-- ---------------------------------------------------------------------------
-- 采集
-- ---------------------------------------------------------------------------
local function tab_entry(view)
    local ok_state, state = pcall(function() return view.session_state end)
    return {
        uri = view.uri or "about:blank",
        title = view.title or "",
        session_state = (ok_state and type(state) == "string" and state ~= "") and state or nil,
        private = view.private == true,
    }
end

local function window_entry(w)
    local tabs = {}
    w:each_tab(function(view)
        if view.is_alive then tabs[#tabs + 1] = tab_entry(view) end
    end)
    return {
        tabs = tabs,
        current = w.tabs:current(),
        private = w.private == true,
        closed_tabs = util.table.clone(w.closed_tabs or {}),
    }
end

function _M.collect()
    local state = { version = 1, windows = {}, extra = {}, time = os.time() }
    -- 按 luakit.windows 的顺序，稳定
    local seen = {}
    for _, win in ipairs(luakit.windows) do
        local w = window.bywidget[win]
        if w and w.tabs then
            local e = window_entry(w)
            if #e.tabs > 0 then state.windows[#state.windows + 1] = e end
            seen[w] = true
        end
    end
    for _, w in pairs(window.bywidget) do
        if not seen[w] and w.tabs and w.tabs.is_alive then
            local e = window_entry(w)
            if #e.tabs > 0 then state.windows[#state.windows + 1] = e end
        end
    end
    return state
end

-- ---------------------------------------------------------------------------
-- 读写
-- ---------------------------------------------------------------------------
local function write_file(path, data)
    local f, err = io.open(path, "wb")
    if not f then return false, err end
    f:write(data)
    f:close()
    return true
end

local function read_file(path)
    if lousy.load then
        local ok, data = pcall(lousy.load, path)
        if ok and type(data) == "string" then return data end
    end
    local f = io.open(path, "rb")
    if not f then return nil end
    local data = f:read("a")
    f:close()
    return data
end

function _M.save(file)
    file = file or _M.session_file
    local state = _M.collect()
    _M.emit_signal("save", state)
    if #state.windows == 0 then
        os.remove(file)
        return false
    end
    local ok, blob = pcall(lousy.pickle.pickle, state)
    if not ok then
        msg.error("session: pickle failed: %s", tostring(blob))
        return false
    end
    local okw, err = write_file(file, blob)
    if not okw then
        msg.error("session: cannot write %s: %s", file, tostring(err))
        return false
    end
    return true
end

function _M.load(delete, file)
    file = file or _M.session_file
    local blob = read_file(file)
    if not blob or blob == "" then return nil end
    local ok, state = pcall(lousy.pickle.unpickle, blob)
    if not ok or type(state) ~= "table" then
        msg.warn("session: %s is not a valid session file", file)
        return nil
    end
    if delete ~= false then
        if file ~= _M.recovery_file then pcall(write_file, _M.recovery_file, blob) end
        os.remove(file)
    end
    return state
end

function _M.restore(delete)
    local state = _M.load(delete)
    if not state then state = _M.load(delete, _M.recovery_file) end
    if not state or type(state.windows) ~= "table" or #state.windows == 0 then return nil end
    local last
    for _, we in ipairs(state.windows) do
        local args = { private = we.private == true }
        for _, t in ipairs(we.tabs or {}) do
            if t.session_state then
                args[#args + 1] = { session_state = t.session_state, uri = t.uri }
            else
                args[#args + 1] = t.uri or "about:blank"
            end
        end
        if #args > 0 then
            local w = window.new(args)
            if we.closed_tabs then w.closed_tabs = we.closed_tabs end
            if type(we.current) == "number" and we.current >= 1 and we.current <= w.tabs:count() then
                w.tabs:switch(we.current)
            end
            last = w
        end
    end
    _M.emit_signal("restore", state)
    return last
end

window.methods.save_session = function(w, file) return _M.save(file) end

-- ---------------------------------------------------------------------------
-- 自动保存
-- ---------------------------------------------------------------------------
local function always_save() return get_setting("session.always_save", false) == true end

window.add_signal("init", function(w)
    w:add_signal("close", function()
        if always_save() then pcall(_M.save) end
    end)
end)

luakit.add_signal("can-close", function()
    if always_save() then pcall(_M.save) end
end)

local recovery_timer
function _M.start_recovery_timer()
    local secs = tonumber(get_setting("session.recovery_save_interval", 30)) or 0
    if recovery_timer then pcall(recovery_timer.stop, recovery_timer) recovery_timer = nil end
    if secs <= 0 then return end
    local ok, t = pcall(timer, { interval = math.floor(secs * 1000) })
    if not ok or not t then return end
    t:add_signal("timeout", function()
        if next(window.bywidget) ~= nil then pcall(_M.save, _M.recovery_file) end
    end)
    t:start()
    recovery_timer = t
end
function _M.stop_recovery_timer()
    if recovery_timer then pcall(recovery_timer.stop, recovery_timer) recovery_timer = nil end
end
_M.start_recovery_timer()

modes.add_cmds({
    { ":sess[ion]", "Save the session now.", function(w)
        if _M.save() then w:notify("session saved to " .. _M.session_file) else w:warning("nothing to save") end
    end },
    { ":restore", "Restore the last saved session into new windows.", function(w)
        local last = _M.restore(false)
        if not last then w:warning("no saved session found") end
    end },
})

return _M
