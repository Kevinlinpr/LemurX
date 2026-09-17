-- LemurX · luakit-compatible library · lousy.widget.menu
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.menu" module API. No luakit code is used.
--
-- 补全 / 列表菜单：一个 vbox，每行一个 hbox，每列一个 label。
--   local m = lousy.widget.menu()        -- 也接受 menu(w)
--   m:build({ { "标题1", "标题2", title = true }, { "a", "b" }, { "c", "d", selectable = false } })
--   m:show() m:hide() m:move_up() m:move_down() m:get() m:nrows() m:del() m:update()
--   m.widget  m.hidden  m.max_rows  m:add_signal("changed", fn(m, row))
-- 行表里的其它字段（uri/desc/...）原样保留，m:get() 返回的就是传入的那张行表。
-- 可见行数受 max_rows 限制，选中行移动时窗口跟随滚动；标题行始终钉在顶部。

local util = require("lousy.util")
local signal = require("lousy.signal")
local common = require("lousy.widget.common")

local M = {}

M.default_max_rows = 10

local function is_title(row)
    return type(row) == "table" and row.title == true
end

local function is_selectable(row)
    return type(row) == "table" and row.title ~= true and row.selectable ~= false
end

local function new(_w)
    local theme = common.theme()
    local menu = {
        widget = widget{ type = "vbox" },
        rows = {},
        hidden = true,
        max_rows = M.default_max_rows,
        selected = nil,   -- rows 下标
        offset = 1,       -- 第一条可见的非标题行下标
        _row_widgets = {},
    }
    signal.setup(menu)
    menu.widget.bg = theme.menu_bg
    menu.widget:hide()

    local function clear_widgets()
        for _, rw in ipairs(menu._row_widgets) do
            if rw.is_alive then
                pcall(menu.widget.remove, menu.widget, rw)
                pcall(rw.destroy, rw)
            end
        end
        menu._row_widgets = {}
    end

    local function ncols(rows)
        local n = 0
        for _, row in ipairs(rows) do
            local c = 0
            for i, v in ipairs(row) do if type(v) == "string" or type(v) == "number" then c = i end end
            if c > n then n = c end
        end
        return math.max(n, 1)
    end

    local function make_row(row, index, cols)
        local box = widget{ type = "hbox" }
        box.homogeneous = false
        local selected = index == menu.selected
        local bg, fg
        if is_title(row) then
            bg = row.bg or theme.menu_title_bg
        elseif selected then
            bg = row.bg or theme.menu_selected_bg
            fg = row.fg or theme.menu_selected_fg
        elseif not is_selectable(row) then
            bg = row.bg or theme.menu_disabled_bg
            fg = row.fg or theme.menu_disabled_fg
        else
            bg = row.bg or theme.menu_bg
            fg = row.fg or theme.menu_fg
        end
        box.bg = bg
        for c = 1, cols do
            local cell = row[c]
            local label = widget{ type = "label" }
            local text = cell == nil and "" or tostring(cell)
            if row.markup ~= true then text = util.escape(text) end
            label.text = text
            label.font = row.font or theme.menu_font
            label.bg = bg
            if is_title(row) then
                label.fg = row.fg or (c == 1 and theme.menu_primary_title_fg or theme.menu_secondary_title_fg)
            else
                label.fg = fg
            end
            label.align = { h = "left", v = "center" }
            box:pack(label, { expand = c == cols, fill = true, padding = 4 })
        end
        return box
    end

    local function visible_range()
        local rows = menu.rows
        local first = 1
        if is_title(rows[1]) then first = 2 end
        local max = math.max(tonumber(menu.max_rows) or M.default_max_rows, 1)
        local body_max = max - (first == 2 and 1 or 0)
        if body_max < 1 then body_max = 1 end
        if menu.offset < first then menu.offset = first end
        if menu.selected then
            if menu.selected < menu.offset then menu.offset = menu.selected end
            if menu.selected > menu.offset + body_max - 1 then menu.offset = menu.selected - body_max + 1 end
        end
        local last = math.min(#rows, menu.offset + body_max - 1)
        return first, menu.offset, last
    end

    local function render()
        if not menu.widget.is_alive then return end
        clear_widgets()
        local rows = menu.rows
        if #rows == 0 then return end
        local cols = ncols(rows)
        local first, from, to = visible_range()
        if first == 2 then
            local rw = make_row(rows[1], 1, cols)
            menu.widget:pack(rw, { expand = false, fill = true })
            menu._row_widgets[#menu._row_widgets + 1] = rw
        end
        for i = from, to do
            local rw = make_row(rows[i], i, cols)
            menu.widget:pack(rw, { expand = false, fill = true })
            menu._row_widgets[#menu._row_widgets + 1] = rw
        end
    end

    local function first_selectable(from, step)
        local rows = menu.rows
        local i = from
        while i >= 1 and i <= #rows do
            if is_selectable(rows[i]) then return i end
            i = i + step
        end
        return nil
    end

    function menu:build(rows)
        if type(rows) ~= "table" then rows = {} end
        self.rows = rows
        self.offset = is_title(rows[1]) and 2 or 1
        self.selected = first_selectable(1, 1)
        render()
        self:emit_signal("changed", self:get())
    end

    function menu:update()
        render()
    end

    function menu:get(index)
        local i = index or self.selected
        if not i then return nil end
        return self.rows[i]
    end

    function menu:nrows()
        return #self.rows
    end

    -- 只算可选择的行
    function menu:nselectable()
        local n = 0
        for _, row in ipairs(self.rows) do if is_selectable(row) then n = n + 1 end end
        return n
    end

    function menu:move_up()
        if not self.selected then
            self.selected = first_selectable(#self.rows, -1)
        else
            self.selected = first_selectable(self.selected - 1, -1) or first_selectable(#self.rows, -1)
        end
        render()
        self:emit_signal("changed", self:get())
        return self:get()
    end

    function menu:move_down()
        if not self.selected then
            self.selected = first_selectable(1, 1)
        else
            self.selected = first_selectable(self.selected + 1, 1) or first_selectable(1, 1)
        end
        render()
        self:emit_signal("changed", self:get())
        return self:get()
    end

    function menu:select(index)
        if type(index) == "number" and is_selectable(self.rows[index]) then
            self.selected = index
            render()
            self:emit_signal("changed", self:get())
            return true
        end
        return false
    end

    -- 删除一行（默认当前选中行），返回被删除的行表
    function menu:del(index)
        local i = index or self.selected
        if not i or not self.rows[i] then return nil end
        local removed = table.remove(self.rows, i)
        if self.selected then
            if self.selected > #self.rows then self.selected = #self.rows end
            if self.selected and self.selected >= 1 and not is_selectable(self.rows[self.selected]) then
                self.selected = first_selectable(self.selected, 1) or first_selectable(self.selected, -1)
            end
            if self.selected == 0 then self.selected = nil end
        end
        render()
        self:emit_signal("changed", self:get())
        return removed
    end

    function menu:show()
        if not self.widget.is_alive then return end
        self.widget:show()
        self.hidden = false
    end

    function menu:hide()
        if not self.widget.is_alive then return end
        self.widget:hide()
        self.hidden = true
    end

    function menu:destroy()
        clear_widgets()
        if self.widget.is_alive then self.widget:destroy() end
    end

    return menu
end

return common.callable(M, new)
