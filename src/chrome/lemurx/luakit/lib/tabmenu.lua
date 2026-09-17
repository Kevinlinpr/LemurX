-- LemurX · luakit-compatible library · tabmenu
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "tabmenu" module API. No luakit code is used.
--
-- :tabmenu（别名 :tabs）进入 "tabmenu" 模式：窗口菜单（w.menu，lousy.widget.menu）列出所有
-- 标签页，<Return> 切换，<Delete> 关闭，j/k 或方向键移动，<Escape> 退出。
--   tabmenu.hide_box   为 true 时进入模式不显示菜单（只保留按键行为）

local modes = require("modes")

local M = {}

M.hide_box = false

local function rows_for(w)
    local rows = { { "Tab", "URI", title = true } }
    local count = w.tabs:count()
    for i = 1, count do
        local view = w.tabs:atindex(i)
        local title, uri = "", ""
        pcall(function()
            title = (w.get_tab_title and w:get_tab_title(view)) or view.title or ""
            uri = view.uri or ""
        end)
        if title == "" then title = uri end
        local mark = (view == w.view) and "▶ " or "  "
        rows[#rows + 1] = { ("%s%d  %s"):format(mark, i, title), uri, index = i, view = view }
    end
    return rows
end

local function refresh(w)
    if not w.menu then return end
    w.menu:build(rows_for(w))
    if not M.hide_box then w.menu:show() end
end

modes.new_mode("tabmenu", "Pick an open tab from a list.", {
    enter = function(w)
        refresh(w)
        w:set_prompt("Tabs: <Return> switch, <Delete> close, <Escape> cancel")
    end,
    leave = function(w)
        if w.menu then w.menu:hide() end
    end,
})

local binds = {
    { "<Return>", "Switch to the selected tab.", function(w)
        local row = w.menu and w.menu:get()
        if row and row.index then
            w:set_mode()
            w:goto_tab(row.index)
        end
    end },
    { "<Delete>", "Close the selected tab.", function(w)
        local row = w.menu and w.menu:get()
        if row and row.view then
            w:close_tab(row.view)
            if w.tabs:count() == 0 then return end
            refresh(w)
        end
    end },
    { "<Escape>", "Leave the tab menu.", function(w) w:set_mode() end },
}

-- 有 binds.menu_binds（上下移动等）就一并挂上，否则给一套自己的
do
    local ok, b = pcall(require, "binds")
    if ok and type(b) == "table" and type(b.menu_binds) == "table" then
        for _, x in ipairs(b.menu_binds) do binds[#binds + 1] = x end
    else
        for _, spec in ipairs({
            { "j", "move_down" }, { "<Down>", "move_down" }, { "<Tab>", "move_down" },
            { "k", "move_up" }, { "<Up>", "move_up" }, { "<Shift-Tab>", "move_up" },
        }) do
            local method = spec[2]
            binds[#binds + 1] = { spec[1], "Move the selection " .. (method == "move_up" and "up" or "down") .. ".",
                function(w) if w.menu and w.menu[method] then w.menu[method](w.menu) end end }
        end
    end
end

modes.add_binds("tabmenu", binds)

modes.add_cmds({
    { ":tabmenu, :tabs", "List open tabs in a menu.", function(w) w:set_mode("tabmenu") end },
})

return M
