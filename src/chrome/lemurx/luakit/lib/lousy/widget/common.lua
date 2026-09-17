-- LemurX · luakit-compatible library · lousy.widget.common
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.common" module API. No luakit code is used.
--
-- 状态栏 / 标签栏小部件共用的粘合代码：
--   * 从窗口表 w 找到 notebook 与当前 webview（w.view 优先，其次 w.tabs 当前页）
--   * follow_view()：给 notebook 里每个 webview 挂信号，并在 switch-page / page-added 时
--     自动接管新页面，只把“当前页”的事件转给调用者
--   * make_label()：按主题着色的状态栏 label
--   * add_widget / update_widgets_on_w：按 luakit 的老接口维护“窗口 → 小部件”登记表

local util = require("lousy.util")
local theme_mod = require("lousy.theme")

local common = {}

common.escape = util.escape

function common.theme()
    return theme_mod.get()
end

local function is_widget(v, kind)
    if type(v) ~= "widget" then return false end
    if not v.is_alive then return false end
    if kind and v.type ~= kind then return false end
    return true
end
common.is_widget = is_widget

-- 窗口的 notebook
function common.notebook(w)
    if type(w) ~= "table" then return nil end
    local nb = rawget(w, "tabs")
    if nb == nil then nb = w.tabs end
    if is_widget(nb, "notebook") then return nb end
    return nil
end

-- 当前 webview
function common.current_view(w)
    if type(w) ~= "table" then return nil end
    local ok, v = pcall(function() return w.view end)
    if ok and is_widget(v, "webview") then return v end
    local nb = common.notebook(w)
    if nb then
        local okc, cur = pcall(nb.current, nb)
        if okc and type(cur) == "number" and cur >= 1 then
            local oka, page = pcall(nb.atindex, nb, cur)
            if oka and is_widget(page, "webview") then return page end
        end
    end
    return nil
end

-- notebook 里的全部 webview（按页序）
function common.views(w)
    local out = {}
    local nb = common.notebook(w)
    if not nb then return out end
    local ok, kids = pcall(function() return nb.children end)
    if ok and type(kids) == "table" then
        for _, v in ipairs(kids) do
            if is_widget(v, "webview") then out[#out + 1] = v end
        end
    end
    return out
end

-- 安全地在 w 上挂信号（w 可能还没 lousy.signal.setup）
function common.on_w(w, name, fn)
    if type(w) ~= "table" or type(w.add_signal) ~= "function" then return false end
    local ok = pcall(w.add_signal, w, name, function(_, ...)
        fn(...)
        return nil
    end)
    return ok
end

-- 安全地在内核对象上挂信号，处理器返回值被吞掉（不打断传播）
function common.on_obj(obj, name, fn)
    if not obj or type(obj.add_signal) ~= "function" then return false end
    local ok = pcall(obj.add_signal, obj, name, function(...)
        fn(...)
        return nil
    end)
    return ok
end

-- handlers: { ["signal-name"] = fn(view, ...), switch = fn(view), added = fn(view), removed = fn(view) }
-- 只有当前页的事件才会转给 handlers（switch/added/removed 除外）。
-- alive_fn() 返回 false 时停止转发（例如 label 已销毁）。
function common.follow_view(w, handlers, alive_fn)
    local hooked = setmetatable({}, { __mode = "k" })
    local state = { current = nil }
    local function alive()
        return alive_fn == nil or alive_fn() ~= false
    end
    local function is_current(v)
        if state.current and state.current.is_alive then return state.current == v end
        return common.current_view(w) == v
    end
    local function hook(view)
        if not is_widget(view, "webview") or hooked[view] then return end
        hooked[view] = true
        for name, fn in pairs(handlers) do
            if name ~= "switch" and name ~= "added" and name ~= "removed" then
                common.on_obj(view, name, function(v, ...)
                    if alive() and is_current(v) then fn(v, ...) end
                end)
            end
        end
    end
    local nb = common.notebook(w)
    if nb then
        for _, v in ipairs(common.views(w)) do hook(v) end
        common.on_obj(nb, "page-added", function(_, view)
            hook(view)
            if alive() and handlers.added then handlers.added(view) end
        end)
        common.on_obj(nb, "page-removed", function(_, view)
            if alive() and handlers.removed then handlers.removed(view) end
        end)
        common.on_obj(nb, "switch-page", function(_, view)
            hook(view)
            state.current = view
            if alive() and handlers.switch then handlers.switch(view) end
        end)
    end
    local cv = common.current_view(w)
    if cv then
        state.current = cv
        hook(cv)
    end
    -- 窗口级别的 “new-tab / attach-tab” 也可能带来新 view
    common.on_w(w, "attach-tab", function(view) hook(view) end)
    common.on_w(w, "new-tab", function(view) hook(view) end)
    state.hook = hook
    state.current_view = function()
        if state.current and state.current.is_alive then return state.current end
        return common.current_view(w)
    end
    return state
end

-- 状态栏 label：颜色/字体取 <name>_sbar_fg / <name>_sbar_font（主题会逐级回退）
function common.make_label(name, opts)
    local theme = theme_mod.get()
    local label = widget{ type = "label" }
    local fg = theme[name .. "_sbar_fg"]
    local font = theme[name .. "_sbar_font"]
    if fg then label.fg = fg end
    if font then label.font = font end
    if opts and opts.padding then label.padding = opts.padding end
    label.text = ""
    return label
end

-- 尝试把一个 update 方法挂到内核 widget 上（存进对象的扩展槽）
function common.attach_update(wi, fn)
    pcall(function() wi.update = fn end)
end

-- =====================================================================
-- 旧式登记表接口
-- =====================================================================

-- widgets: { [widget] = w }。widget 销毁时自动摘掉
function common.add_widget(widgets, wi, w)
    widgets[wi] = w or true
    common.on_obj(wi, "destroy", function(x) widgets[x] = nil end)
    return wi
end

-- 对属于窗口 w 的每个小部件调用 fn(widget, w, ...)
function common.update_widgets_on_w(widgets, w, fn, ...)
    for wi, owner in pairs(widgets) do
        if owner == w or owner == true then
            if wi.is_alive == false then
                widgets[wi] = nil
            elseif type(fn) == "function" then
                fn(wi, w, ...)
            elseif type(wi.update) == "function" then
                wi.update(w, ...)
            end
        end
    end
end

-- 让一个工厂模块既能当函数调用（lousy.widget.uri(w)），又能带字段
-- luakit 的状态栏控件构造器是无参的（rc.lua 里写 widgets.uri()），控件自己找
-- 所属窗口。这里用 window.lua 在 emit "build" 期间登记的“正在构建的窗口”兜底：
-- 传了 w 就用 w，没传就用 building_w。
local building_w = nil

function common.set_building(w)
    building_w = w
end

function common.building()
    return building_w
end

function common.callable(tbl, ctor)
    return setmetatable(tbl, { __call = function(_, w, ...)
        if type(w) == "table" or is_widget(w) then
            -- 显式给了窗口表 / 控件（tablist(notebook, orientation)）：原样传下去
            return ctor(w, ...)
        end
        if w ~= nil then
            -- 第一个参数是别的东西（例如 tablist 的方向字符串）：补上正在构建的窗口
            return ctor(building_w, w, ...)
        end
        -- 不在 build 期间且没给窗口：交给构造器自己处理（menu 等控件不需要窗口）
        return ctor(building_w, ...)
    end })
end

return common
