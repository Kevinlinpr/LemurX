-- LemurX · luakit-compatible library · lousy.widget.tab
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.tab" module API. No luakit code is used.
--
-- 单个标签页头：eventbox > hbox( image 图标, spinner 加载指示, label )。
--   local tl = lousy.widget.tab(view, index)
--   tl.widget tl.label tl.icon tl.view tl.index tl.current tl.title
--   tl:update() tl:destroy()
-- 文本由 tab.label_format 决定（默认 "{index}: {title}"），{name} 占位符对应
-- tab.label_subs.name(view, tl) 返回的字串；{index} 会用 tab_ntheme 颜色高亮。
-- 模块信号 build(tl, view) 在每个标签头建好后发出，供第三方模块改样式。

local util = require("lousy.util")
local signal = require("lousy.signal")
local common = require("lousy.widget.common")

local M = {}
signal.setup(M, true)

M.label_format = "{index}: {title}"
M.label_subs = {}
M.untitled = "(Untitled)"

M.label_subs.index = function(_, tl) return tostring(tl.index or "") end
M.label_subs.title = function(view)
    local t = view.title
    if t == nil or t == "" then t = view.uri end
    if t == nil or t == "" then t = M.untitled end
    return t
end
M.label_subs.uri = function(view) return view.uri or "" end
M.label_subs.private = function(view) return view.private and "[P] " or "" end
M.label_subs.audio = function(view) return view.is_playing_audio and "[A] " or "" end

-- 内置图标名（luakit.resource_path/icons/<name>.png）
M.icons = {
    page = "tab-icon-page.png",
    chrome = "tab-icon-chrome.png",
    private = "tab-icon-private.png",
    error = "tab-icon-error.png",
    crash = "tab-icon-crash.png",
    security_error = "tab-icon-security-error.png",
}

local function icon_path(name)
    local L = rawget(_G, "luakit")
    if not L or not L.resource_path then return nil end
    local base = L.resource_path
    if type(base) == "string" then base = base:match("^[^;]+") end
    return base .. "/icons/" .. name
end

local function pick_icon(tl)
    local view = tl.view
    if tl.crashed then return M.icons.crash end
    if tl.load_failed then return M.icons.error end
    local uri = view.uri or ""
    if uri:match("^luakit://") or uri:match("^lemurx://") then return M.icons.chrome end
    if view.private then return M.icons.private end
    return M.icons.page
end

local function format_label(tl)
    local view = tl.view
    local text = M.label_format:gsub("{(%w+)}", function(name)
        local fn = M.label_subs[name]
        if type(fn) ~= "function" then return "" end
        local ok, v = pcall(fn, view, tl)
        if not ok or v == nil then return "" end
        v = util.escape(tostring(v))
        if name == "index" then
            local theme = common.theme()
            local col = tl.current and theme.selected_ntheme or theme.tab_ntheme
            if col then v = ('<span foreground="%s">%s</span>'):format(col, v) end
        end
        return v
    end)
    return text
end

local function new(view, index)
    if not common.is_widget(view, "webview") then
        error("lousy.widget.tab: expected a webview widget", 2)
    end
    local theme = common.theme()
    local tl = {
        view = view,
        index = index or 0,
        current = false,
        hovered = false,
        loading = false,
        load_failed = false,
        crashed = false,
        title = "",
    }
    tl.widget = widget{ type = "eventbox" }
    tl.box = widget{ type = "hbox" }
    tl.icon = widget{ type = "image" }
    tl.spinner = widget{ type = "spinner" }
    tl.label = widget{ type = "label" }
    tl.widget.child = tl.box
    tl.box:pack(tl.icon, { expand = false, fill = false, padding = 2 })
    tl.box:pack(tl.spinner, { expand = false, fill = false, padding = 2 })
    tl.box:pack(tl.label, { expand = true, fill = true, padding = 4 })
    tl.label.font = theme.tab_font
    tl.spinner:hide()
    tl.icon.tooltip = nil

    local function alive()
        return tl.widget.is_alive and tl.view and tl.view.is_alive
    end

    local function paint_icon()
        local ok, has = pcall(function()
            local uri = tl.view.uri
            if type(uri) ~= "string" or uri == "" then return false end
            return tl.icon:set_favicon_for_uri(uri) ~= false
        end)
        if ok and has and not tl.load_failed and not tl.crashed then return end
        local path = icon_path(pick_icon(tl))
        if path then pcall(tl.icon.filename, tl.icon, path, 16) end
    end

    function tl:update()
        if not alive() then return end
        local v = self.view
        local okl, loading = pcall(function() return v.is_loading end)
        self.loading = okl and loading == true
        self.title = M.label_subs.title(v)
        self.label.text = format_label(self)

        local fg, bg
        local private = v.private
        if self.current then
            fg = theme.tab_selected_fg
            bg = private and theme.selected_private_tab_bg or theme.tab_selected_bg
        else
            fg = theme.tab_fg
            bg = private and theme.private_tab_bg or theme.tab_bg
        end
        if self.loading then fg = theme.tab_loading_fg or fg end
        if self.hovered and not self.current then bg = theme.tab_hover_bg or bg end
        self.label.fg = fg
        self.label.bg = bg
        self.box.bg = bg
        self.widget.bg = bg
        self.widget.tooltip = v.uri

        if self.loading then
            pcall(self.spinner.start, self.spinner)
            self.spinner:show()
        else
            pcall(self.spinner.stop, self.spinner)
            self.spinner:hide()
        end
        paint_icon()
    end

    function tl:set_current(flag)
        self.current = flag and true or false
        self:update()
    end

    function tl:set_index(i)
        self.index = i
        self:update()
    end

    function tl:destroy()
        if self.widget.is_alive then self.widget:destroy() end
    end

    local function on_view(name, fn)
        common.on_obj(view, name, function(v, ...)
            if v == tl.view and alive() then fn(...) end
        end)
    end
    on_view("property::title", function() tl:update() end)
    on_view("property::uri", function() tl:update() end)
    on_view("property::progress", function() tl:update() end)
    on_view("favicon", function() paint_icon() end)
    on_view("property::favicon", function() paint_icon() end)
    on_view("load-status", function(status)
        if status == "provisional" then
            tl.load_failed = false
            tl.crashed = false
        elseif status == "failed" then
            tl.load_failed = true
        end
        tl:update()
    end)
    on_view("crashed", function()
        tl.crashed = true
        tl:update()
    end)
    common.on_obj(tl.widget, "mouse-enter", function()
        tl.hovered = true
        tl:update()
    end)
    common.on_obj(tl.widget, "mouse-leave", function()
        tl.hovered = false
        tl:update()
    end)
    common.on_obj(view, "destroy", function() tl:destroy() end)

    M.emit_signal("build", tl, view)
    tl:update()
    return tl
end

return common.callable(M, new)
