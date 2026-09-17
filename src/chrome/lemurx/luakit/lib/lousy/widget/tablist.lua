-- LemurX · luakit-compatible library · lousy.widget.tablist
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.tablist" module API. No luakit code is used.
--
-- 标签栏：跟随一个 notebook，为每一页建一个 lousy.widget.tab。
--   local tl = lousy.widget.tablist(w, { orientation = "horizontal" })
--   local tl = lousy.widget.tablist(notebook, "vertical")        -- luakit 原签名
--   tl.widget（scrolled）  tl.tabs（按页序的 tab 表数组）  tl.visible  tl.orientation
--   tl:update()  tl:set_notebook(nb)  tl:destroy()  tl:get_tab(view)
--   tl:add_signal("tab-clicked", fn(tl, index, mods, button))
--   tl:add_signal("tab-double-clicked", fn(tl, index, mods, button))
-- 显隐由 settings 的 tablist.visibility（always/multiple/never）与 tablist.always_visible
-- 决定；settings 模块不存在时用模块字段 tablist.visibility / tablist.always_visible。

local signal = require("lousy.signal")
local common = require("lousy.widget.common")
local tab = require("lousy.widget.tab")

local M = {}

-- 模块级默认值（settings 不可用时生效）
M.min_width = 100
M.max_width = 250
M.always_visible = false
M.visibility = "multiple"

local instances = setmetatable({}, { __mode = "k" })
local settings_registered = false

local function settings_module()
    local s = package.loaded["settings"]
    if type(s) == "table" then return s end
    local ok, mod = pcall(require, "settings")
    if ok and type(mod) == "table" then return mod end
    return nil
end

local function refresh_all()
    for tl in pairs(instances) do
        if tl.widget and tl.widget.is_alive then tl:update() end
    end
end

local function register_settings()
    if settings_registered then return end
    local s = settings_module()
    if not s or type(s.register_settings) ~= "function" then return end
    settings_registered = true
    pcall(s.register_settings, {
        ["tablist.always_visible"] = {
            type = "boolean",
            default = M.always_visible,
            desc = "Keep the tab bar on screen even when only one tab is open.",
        },
        ["tablist.visibility"] = {
            type = "enum",
            options = {
                always = "Tab bar is always shown.",
                multiple = "Tab bar appears once there is more than one tab.",
                never = "Tab bar is never shown.",
            },
            default = M.visibility,
            desc = "When the tab bar should be displayed.",
        },
    })
    if type(s.add_signal) == "function" then
        pcall(s.add_signal, "setting-changed", function(a, b)
            local e = type(b) == "table" and b or a
            if type(e) == "table" and type(e.key) == "string" and e.key:match("^tablist%.") then
                refresh_all()
            end
        end)
    end
end

local function get_setting(key, fallback)
    local s = package.loaded["settings"]
    if type(s) == "table" and type(s.get_setting) == "function" then
        local ok, v = pcall(s.get_setting, key)
        if ok and v ~= nil then return v end
    end
    return fallback
end

local function should_show(count)
    if get_setting("tablist.always_visible", M.always_visible) == true then return true end
    local mode = get_setting("tablist.visibility", M.visibility)
    if mode == "never" then return false end
    if mode == "always" then return true end
    return (count or 0) > 1
end

local function new(arg1, arg2)
    local nb, orientation, w
    if common.is_widget(arg1, "notebook") then
        nb = arg1
        orientation = type(arg2) == "string" and arg2 or (type(arg2) == "table" and arg2.orientation) or nil
    else
        w = arg1
        local args = arg2
        if type(args) == "string" then args = { orientation = args } end
        args = args or {}
        nb = args.notebook or common.notebook(w)
        orientation = args.orientation
    end
    orientation = orientation == "vertical" and "vertical" or "horizontal"
    register_settings()

    local theme = common.theme()
    local tlist = {
        tabs = {},
        orientation = orientation,
        visible = false,
        notebook = nil,
        window = w,
    }
    signal.setup(tlist)
    instances[tlist] = true

    tlist.box = widget{ type = orientation == "vertical" and "vbox" or "hbox" }
    tlist.box.homogeneous = false
    tlist.widget = widget{ type = "scrolled" }
    tlist.widget.child = tlist.box
    if orientation == "vertical" then
        tlist.widget.scrollbars = { h = "never", v = "automatic" }
    else
        tlist.widget.scrollbars = { h = "external", v = "never" }
    end
    local bg = theme.tablist_bg or theme.tab_list_bg
    if bg then
        tlist.widget.bg = bg
        tlist.box.bg = bg
    end
    tlist.widget:hide()

    local by_view = setmetatable({}, { __mode = "k" })
    local hooks = {}

    local function current_index()
        if not tlist.notebook then return 0 end
        local ok, cur = pcall(tlist.notebook.current, tlist.notebook)
        return ok and tonumber(cur) or 0
    end

    local function views_of(nbk)
        local out = {}
        local ok, kids = pcall(function() return nbk.children end)
        if ok and type(kids) == "table" then
            for _, v in ipairs(kids) do
                if common.is_widget(v, "webview") then out[#out + 1] = v end
            end
        end
        return out
    end

    local function wire_tab(tl)
        common.on_obj(tl.widget, "button-release", function(_, mods, button)
            tlist:emit_signal("tab-clicked", tl.index, mods, button)
        end)
        common.on_obj(tl.widget, "button-double-click", function(_, mods, button)
            tlist:emit_signal("tab-double-clicked", tl.index, mods, button)
        end)
        common.on_obj(tl.widget, "button-press", function(_, mods, button)
            tlist:emit_signal("tab-pressed", tl.index, mods, button)
        end)
        if M.min_width and M.min_width > 0 then
            pcall(function() tl.widget.min_size = { w = M.min_width } end)
        end
    end

    local function rebuild()
        if not tlist.widget.is_alive then return end
        local nbk = tlist.notebook
        local views = nbk and views_of(nbk) or {}
        local cur = current_index()
        local present = {}
        local ordered = {}
        for i, view in ipairs(views) do
            local tl = by_view[view]
            if not tl or not tl.widget.is_alive then
                tl = tab(view, i)
                by_view[view] = tl
                wire_tab(tl)
                tlist.box:pack(tl.widget, { expand = false, fill = orientation == "vertical", padding = 0 })
            end
            present[tl] = true
            ordered[#ordered + 1] = tl
        end
        -- 移除已消失的页
        for view, tl in pairs(by_view) do
            if not present[tl] then
                by_view[view] = nil
                if tl.widget.is_alive then
                    pcall(tlist.box.remove, tlist.box, tl.widget)
                end
                tl:destroy()
            end
        end
        -- 顺序对齐（box 的 reorder 用 0 基下标）
        local okk, kids = pcall(function() return tlist.box.children end)
        kids = okk and kids or {}
        for i, tl in ipairs(ordered) do
            if kids[i] ~= tl.widget then
                pcall(tlist.box.reorder, tlist.box, tl.widget, i - 1)
            end
        end
        tlist.tabs = ordered
        for i, tl in ipairs(ordered) do
            tl.index = i
            tl.current = (i == cur)
            tl:update()
        end
        local show = should_show(#ordered)
        if show then tlist.widget:show() else tlist.widget:hide() end
        tlist.visible = show
    end

    local function set_current(view)
        local cur = current_index()
        for i, tl in ipairs(tlist.tabs) do
            local flag = (view ~= nil and tl.view == view) or (view == nil and i == cur)
            if tl.current ~= flag then
                tl.current = flag
                tl:update()
            end
        end
    end

    function tlist:update()
        rebuild()
    end

    function tlist:get_tab(view)
        return by_view[view]
    end

    function tlist:set_notebook(nbk)
        -- 老 notebook 上的处理器摘掉
        for _, h in ipairs(hooks) do
            if h.obj.is_alive then pcall(h.obj.remove_signal, h.obj, h.name, h.fn) end
        end
        hooks = {}
        self.notebook = common.is_widget(nbk, "notebook") and nbk or nil
        if self.notebook then
            local function hook(name, fn)
                local wrapped = function(...) fn(...) return nil end
                if pcall(self.notebook.add_signal, self.notebook, name, wrapped) then
                    hooks[#hooks + 1] = { obj = self.notebook, name = name, fn = wrapped }
                end
            end
            hook("page-added", function() rebuild() end)
            hook("page-removed", function() rebuild() end)
            hook("page-reordered", function() rebuild() end)
            hook("switch-page", function(_, view) set_current(view) end)
        end
        rebuild()
    end

    function tlist:destroy()
        for _, h in ipairs(hooks) do
            if h.obj.is_alive then pcall(h.obj.remove_signal, h.obj, h.name, h.fn) end
        end
        hooks = {}
        for _, tl in pairs(by_view) do tl:destroy() end
        by_view = setmetatable({}, { __mode = "k" })
        self.tabs = {}
        instances[self] = nil
        if self.widget.is_alive then self.widget:destroy() end
    end

    tlist:set_notebook(nb)
    if w then
        common.on_w(w, "init", function() rebuild() end)
        common.on_w(w, "tab-count-changed", function() rebuild() end)
    end
    return tlist
end

return common.callable(M, new)
