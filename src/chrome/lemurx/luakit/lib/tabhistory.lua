-- LemurX · luakit-compatible library · tabhistory
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "tabhistory" module API. No luakit code is used.
--
-- 当前标签的前进/后退列表菜单（tabhistory 模式）。
--   w:tab_history() / :tabhistory 打开；<Return> 跳到选中的历史项（按索引差值 go_back/go_forward），
--   t 在新标签打开该地址，w 在新窗口打开。列表数据来自 view.history = { index, items = { {uri, title} } }。

local lousy = require("lousy")
local modes = require("modes")
local window = require("window")
local binds = require("binds")
local util = lousy.util
local escape = util.escape

local _M = {}

local function history_of(view)
    local ok, h = pcall(function() return view.history end)
    if ok and type(h) == "table" and type(h.items) == "table" then return h end
    return { index = 0, items = {} }
end

local function rows_for(view)
    local h = history_of(view)
    local rows = { { "Title", "Address", title = true, selectable = false } }
    for i, item in ipairs(h.items) do
        local title = item.title
        if title == nil or title == "" then title = item.uri or "" end
        local marker = (i == h.index) and "▸ " or "  "
        rows[#rows + 1] = { escape(marker .. title), escape(item.uri or ""), uri = item.uri, index = i }
    end
    return rows, h.index
end

--- 跳到历史里的第 index 项
function _M.go_to(view, index)
    local h = history_of(view)
    local delta = index - (h.index or 0)
    if delta == 0 then return true end
    if type(view.go_back) ~= "function" then return false end
    if delta < 0 then return view:go_back(-delta) end
    return view:go_forward(delta)
end

window.methods.tab_history = function(w)
    local view = w.view
    if not (view and view.is_alive) then return end
    local h = history_of(view)
    if #h.items == 0 then
        w:notify("this tab has no history yet")
        return
    end
    w:set_mode("tabhistory")
end

modes.new_mode("tabhistory", "Browse this tab's back/forward list.", {
    enter = function(w)
        local view = w.view
        if not (view and view.is_alive) then w:set_mode() return end
        local rows, current = rows_for(view)
        if not w.menu then
            w:warning("menu widget unavailable")
            w:set_mode()
            return
        end
        w.menu:build(rows)
        w.menu:show()
        -- 光标停在当前项
        for _ = 1, (current or 1) - 1 do w.menu:move_down() end
        w:set_prompt("Return: go there · t: new tab · w: new window")
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

modes.add_binds("tabhistory", util.table.join(binds.menu_binds, {
    { "<Return>", "Go to the selected history entry.", function(w)
        local row = selected(w)
        local view = w.view
        w:set_mode()
        if row and view and view.is_alive then _M.go_to(view, row.index) end
    end },
    { "t", "Open the selected entry in a new tab.", function(w)
        local row = selected(w)
        w:set_mode()
        if row and row.uri then w:new_tab(row.uri) end
    end },
    { "w", "Open the selected entry in a new window.", function(w)
        local row = selected(w)
        w:set_mode()
        if row and row.uri then w:new_window(row.uri) end
    end },
}))

modes.add_cmds({
    { ":tabhistory", "Show this tab's back/forward list.", function(w) w:tab_history() end },
})

return _M
