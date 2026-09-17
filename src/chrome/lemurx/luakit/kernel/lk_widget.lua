-- widget 类入口（对应 luakit clib/widget.c）
--   local w = widget{ type = "webview" | "window" | "box" | "notebook" | "label" | "entry" | ... }
--   通用属性：type parent visible margin margin_left/right/top/bottom child(ren) focused
--   通用方法：show hide focus destroy
--   类信号：widget.add_signal("create", fn(w))（webview.lua/window.lua 靠它初始化每个新控件）
--
-- 各 type 的实现由 P1（webview → Tab）与 P3（其余 → Android View 树）通过
-- __lk.register_widget_type(type, ctor) 注册。ctor(props) 需返回一个由
-- object.new(widget, priv) 造的对象；priv.type 必填。

local object = __lk.object

local widget
local types = {}

-- 通用属性由各 type 的 priv.impl 提供 get/set(obj, key[, v])；没有就走默认存储
local function impl_get(obj, key)
    local p = object.priv(obj)
    local impl = p.impl
    if impl and impl.get then
        local v = impl.get(obj, key)
        if v ~= nil then return v end
    end
    return p.props and p.props[key]
end

local function impl_set(obj, key, v)
    local p = object.priv(obj)
    local impl = p.impl
    if impl and impl.set and impl.set(obj, key, v) then return true end
    p.props = p.props or {}
    p.props[key] = v
    return true
end

local function impl_call(obj, name, ...)
    local p = object.priv(obj)
    local impl = p.impl
    if impl and impl[name] then return impl[name](obj, ...) end
    return nil
end

local common_props = {}
for _, k in ipairs({ "visible", "margin", "margin_left", "margin_right", "margin_top", "margin_bottom",
                     "min_size", "tooltip", "align", "expand", "fill", "padding", "homogeneous",
                     "width", "height", "bg", "fg", "font", "text", "textwidth", "selectable",
                     "position", "top", "bottom", "left", "right", "orientation", "show_tabs",
                     "show_border", "page", "current", "count", "image", "filename", "icon_name",
                     "resource", "scale", "selected", "columns", "rows", "text_position", "gravity",
                     "urgent", "screen", "fullscreen", "maximized", "decorated", "title", "id",
                     "icon", "fixed_height", "editable", "cursor_position", "css", "opacity" }) do
    common_props[k] = {
        get = function(obj) return impl_get(obj, k) end,
        set = function(obj, v) impl_set(obj, k, v) end,
    }
end
common_props.type = { get = function(obj) return object.priv(obj).type end }
common_props.parent = { get = function(obj) return impl_get(obj, "parent") end }
common_props.child = {
    get = function(obj) return impl_get(obj, "child") end,
    set = function(obj, v) impl_set(obj, "child", v) end,
}
common_props.children = { get = function(obj) return impl_call(obj, "children") or {} end }
common_props.focused = { get = function(obj) return impl_get(obj, "focused") == true end }
common_props.is_alive = nil -- 由对象协议处理

-- 通用方法。放在 index 兜底里而不是 class.methods，好让各 type 的专属成员
--（webview.scroll 是表、notebook:insert 是方法）优先于同名通用项。
local common_methods = {
        show = function(obj) impl_set(obj, "visible", true) end,
        hide = function(obj) impl_set(obj, "visible", false) end,
        focus = function(obj) impl_call(obj, "focus") end,
        destroy = function(obj)
            impl_call(obj, "destroy")
            object.destroy(obj)
        end,
        -- 容器
        pack = function(obj, child, opts) return impl_call(obj, "pack", child, opts) end,
        insert = function(obj, ...) return impl_call(obj, "insert", ...) end,
        append = function(obj, ...) return impl_call(obj, "append", ...) end,
        remove = function(obj, child) return impl_call(obj, "remove", child) end,
        reorder = function(obj, child, index) return impl_call(obj, "reorder", child, index) end,
        indexof = function(obj, child) return impl_call(obj, "indexof", child) end,
        atindex = function(obj, index) return impl_call(obj, "atindex", index) end,
        switch = function(obj, index) return impl_call(obj, "switch", index) end,
        pack1 = function(obj, child, opts) return impl_call(obj, "pack1", child, opts) end,
        pack2 = function(obj, child, opts) return impl_call(obj, "pack2", child, opts) end,
        set_default_size = function(obj, w, h) return impl_call(obj, "set_default_size", w, h) end,
        set_position = function(obj, x, y) return impl_call(obj, "set_position", x, y) end,
        scroll = function(obj, ...) return impl_call(obj, "scroll", ...) end,
        select_region = function(obj, ...) return impl_call(obj, "select_region", ...) end,
        set_icon = function(obj, ...) return impl_call(obj, "set_icon", ...) end,
        set_child = function(obj, c) impl_set(obj, "child", c) end,
        get_child = function(obj) return impl_get(obj, "child") end,
        set_title = function(obj, t) impl_set(obj, "title", t) end,
        insert_text = function(obj, ...) return impl_call(obj, "insert_text", ...) end,
        replace_text = function(obj, ...) return impl_call(obj, "replace_text", ...) end,
        query_tooltip = function(obj, ...) return impl_call(obj, "query_tooltip", ...) end,
        css_reset = function(obj) return impl_call(obj, "css_reset") end,
        send_key = function(obj, ...) return impl_call(obj, "send_key", ...) end,
}

widget = object.class("widget", {
    props = common_props,
    index = function(obj, key)
        -- type 专属属性/方法（webview 的 uri/title/eval_js…）优先
        local p = object.priv(obj)
        local impl = p.impl
        if impl and impl.index then
            local v = impl.index(obj, key)
            if v ~= nil then return v end
        end
        return common_methods[key]
    end,
    newindex = function(obj, key, v)
        local p = object.priv(obj)
        local impl = p.impl
        if impl and impl.newindex then
            return impl.newindex(obj, key, v)
        end
        return false
    end,
    new = function(props)
        if type(props) ~= "table" or type(props.type) ~= "string" then
            error("widget{} requires a 'type' string", 3)
        end
        local ctor = types[props.type]
        if not ctor then
            error(("widget type %q is not implemented on this platform yet"):format(props.type), 3)
        end
        local obj = ctor(props)
        if not object.is_object(obj) then
            error("widget ctor for " .. props.type .. " returned a non-object", 3)
        end
        -- 构造参数里除了 type 之外的键当属性写入（luakit 语义）
        for k, v in pairs(props) do
            if k ~= "type" then obj[k] = v end
        end
        widget.emit_signal("create", obj)
        return obj
    end,
    tostring = function(obj)
        local p = object.priv(obj)
        return ("widget(%s)"):format(p.type or "?")
    end,
})

-- 供 P1/P3 注册具体控件
__lk.register_widget_type = function(name, ctor)
    types[name] = ctor
end
__lk.widget_types = types
__lk.new_widget = function(type_name, impl, initial)
    return object.new(widget, { type = type_name, impl = impl, props = initial or {} })
end

_G.widget = widget
return widget
