-- LemurX · luakit-compatible library · undoclose
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "undoclose" module API. No luakit code is used.
--
-- 恢复关掉的标签。窗口的 close-tab 信号触发时把 { uri, title, session_state, private, index }
-- 压进 w.closed_tabs（最新在末尾，最多 settings.undoclose.max_saved_tabs 条）。
--   u / :undo [n]   重开最近（或倒数第 n 个）关掉的标签
--   :undolist       进入 undolist 菜单模式：<Return> 重开选中项，d 删除条目，t 后台重开
-- 模块级信号：undoclose.add_signal("save", fn(w, entry)) 关标签入栈时；
--             undoclose.add_signal("undo-close", fn(w, view, entry)) 重开时。
-- session.lua 会把 w.closed_tabs 一并存进会话文件。

local lousy = require("lousy")
local modes = require("modes")
local window = require("window")
local binds = require("binds")
local util = lousy.util
local escape = util.escape

local _M = {}
lousy.signal.setup(_M, true)

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
        ["undoclose.max_saved_tabs"] = {
            type = "number", default = 10, min = 0,
            desc = "How many closed tabs are kept per window for u / :undo.",
        },
    })
end

local function max_saved() return math.floor(tonumber(get_setting("undoclose.max_saved_tabs", 10)) or 10) end

function _M.remember(w, view)
    if not (view and view.is_alive) then return nil end
    local ok_state, state = pcall(function() return view.session_state end)
    local entry = {
        uri = view.uri,
        title = view.title,
        session_state = ok_state and type(state) == "string" and state or nil,
        private = view.private == true,
        index = w.tabs:indexof(view),
        time = os.time(),
    }
    if not entry.uri or entry.uri == "" or entry.uri == "about:blank" then return nil end
    w.closed_tabs = w.closed_tabs or {}
    w.closed_tabs[#w.closed_tabs + 1] = entry
    local limit = max_saved()
    while #w.closed_tabs > limit do table.remove(w.closed_tabs, 1) end
    _M.emit_signal("save", w, entry)
    return entry
end

local function reopen(w, entry, switch)
    local arg = entry.uri
    if entry.session_state then arg = { session_state = entry.session_state, uri = entry.uri } end
    local order = entry.index and function(ww)
        local n = ww.tabs:count()
        return math.max(1, math.min(entry.index, n + 1))
    end or nil
    local view = w:new_tab(arg, { switch = switch ~= false, private = entry.private, order = order })
    _M.emit_signal("undo-close", w, view, entry)
    return view
end

--- 重开倒数第 n 个关掉的标签（默认最近的）
window.methods.undo_close_tab = function(w, n)
    local list = w.closed_tabs or {}
    if #list == 0 then
        w:notify("no closed tabs to restore")
        return nil
    end
    n = tonumber(n) or 1
    local idx = #list - n + 1
    if idx < 1 then idx = 1 end
    local entry = table.remove(list, idx)
    return reopen(w, entry, true)
end

window.add_signal("init", function(w)
    w.closed_tabs = w.closed_tabs or {}
    w:add_signal("close-tab", function(_, view) _M.remember(w, view) end)
end)

-- ---------------------------------------------------------------------------
-- 菜单
-- ---------------------------------------------------------------------------
local function rows_for(w)
    local rows = { { "Title", "Address", title = true, selectable = false } }
    local list = w.closed_tabs or {}
    for i = #list, 1, -1 do
        local e = list[i]
        rows[#rows + 1] = { escape(e.title ~= "" and e.title or e.uri), escape(e.uri), entry = e, pos = i }
    end
    return rows
end

local function refresh(w)
    if not w.menu then return end
    if #(w.closed_tabs or {}) == 0 then
        w:set_mode()
        w:notify("no closed tabs to restore")
        return
    end
    w.menu:build(rows_for(w))
    w.menu:show()
    local first = w.menu:get()
    if first and first.title then w.menu:move_down() end
end

modes.new_mode("undolist", "Pick a closed tab to reopen.", {
    enter = function(w)
        if not w.menu then
            w:warning("menu widget unavailable")
            w:set_mode()
            return
        end
        refresh(w)
        w:set_prompt("Return: reopen · t: reopen in background · d: forget")
    end,
    leave = function(w)
        w:hide_menu()
        w:set_prompt()
    end,
})

local function selected(w)
    if not w.menu then return nil end
    local row = w.menu:get()
    if not row or row.title then return nil end
    return row
end

modes.add_binds("undolist", util.table.join(binds.menu_binds, {
    { "<Return>", "Reopen the selected tab.", function(w)
        local row = selected(w)
        w:set_mode()
        if row then
            table.remove(w.closed_tabs, row.pos)
            reopen(w, row.entry, true)
        end
    end },
    { "t", "Reopen the selected tab in the background.", function(w)
        local row = selected(w)
        if not row then return end
        table.remove(w.closed_tabs, row.pos)
        reopen(w, row.entry, false)
        refresh(w)
    end },
    { "d", "Forget the selected tab.", function(w)
        local row = selected(w)
        if not row then return end
        table.remove(w.closed_tabs, row.pos)
        refresh(w)
    end },
}))

modes.add_binds("normal", {
    { "u", "Reopen the last closed tab (with a count: the Nth last).", function(w, m)
        w:undo_close_tab(m and m.count)
    end },
})

modes.add_cmds({
    { ":undo", "Reopen the last closed tab, or the Nth last.", function(w, o)
        local n = tonumber((o.arg or ""):match("%d+"))
        w:undo_close_tab(n)
    end },
    { ":undolist", "List closed tabs and pick one to reopen.", function(w) w:set_mode("undolist") end },
})

return _M
