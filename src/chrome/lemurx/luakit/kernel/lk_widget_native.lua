-- P3：luakit 控件树（widgets/*.c）→ Android View 树
--
-- 这里给 lk_widget.lua 注册除 webview 以外的全部控件类型：
--   window hbox vbox hpaned vpaned notebook overlay stack eventbox scrolled
--   label entry image spinner drawing_area
-- 每个对象对应 Java LemurXWidgetHost 里一个保留的 View（整数 native_id），
-- 属性/方法经 __luakit.widget(op, id, args) 同步过去，事件经
-- __luakit_dispatch("widget", id, json) 回来；key-press 走 __luakit_dispatch_sync，
-- 因为 GTK 语义里处理器返回 true 表示“吃掉这个键”，UI 线程要立刻知道。
--
-- webview 进容器（notebook:insert(view) / box:pack(view)）时会在原生侧配一个
-- 透明占位 View，宿主把原生 Tab 内容挪进占位矩形；notebook 切页即切原生 Tab。
--
-- 尺寸：luakit 的 px 在这里按 dp；字号按 sp（宿主换算）。

local object = __lk.object
local N = __luakit

if type(N.widget) ~= "function" then
    msg.warn("widget host primitive missing; only webview widgets are available")
    return
end

local by_id = {}        -- native_id → widget 对象
local windows = {}      -- window 对象列表（luakit.windows）

local function nat(op, id, args)
    local v, err = N.widget(op, id, args)
    if v == nil and err ~= nil then
        error(("widget %s: %s"):format(op, tostring(err)), 3)
    end
    return v
end

-- 任意 widget 对象 → 原生 id；webview 的占位懒建
local function id_of(w)
    if not object.is_object(w) then
        error("expected a widget, got " .. type(w), 3)
    end
    local p = object.priv(w)
    if p.native_id then return p.native_id end
    if p.type == "webview" then
        p.native_id = nat("create", 0, { type = "webview", tab = p.tab or -1 })
        by_id[p.native_id] = w
        return p.native_id
    end
    error(("widget(%s) has no native view"):format(tostring(p.type)), 3)
end
__lk.widget_native_id = id_of

local function obj_of(id)
    if id == nil then return nil end
    local w = by_id[id]
    if w and object.is_alive(w) then return w end
    return nil
end

-- ===== 父子关系（Lua 侧镜像，parent/child/children 属性从这里读）=====

local function set_parent(child, parent)
    local cp = object.priv(child)
    if cp.type == "webview" then
        cp.parent = parent
    else
        cp.parent = parent
    end
end

local function unlink(child)
    local cp = object.priv(child)
    local old = cp.parent
    if old and object.is_alive(old) then
        local op = object.priv(old)
        if op.children then
            for i, c in ipairs(op.children) do
                if c == child then table.remove(op.children, i) break end
            end
        end
        if op.child == child then op.child = nil end
    end
    cp.parent = nil
end

local function link(parent, child, index)
    local cp = object.priv(child)
    local pp = object.priv(parent)
    pp.children = pp.children or {}
    if cp.parent ~= parent then
        unlink(child)
    else
        -- 宿主事件（parent-set）可能已把 parent 记上了：只从列表里摘掉旧位置
        for i, c in ipairs(pp.children) do
            if c == child then table.remove(pp.children, i) break end
        end
    end
    if index and index >= 1 and index <= #pp.children + 1 then
        table.insert(pp.children, index, child)
    else
        pp.children[#pp.children + 1] = child
    end
    set_parent(child, parent)
end

-- ===== 原生属性表 =====
-- 直接透传给宿主的属性（读写都走原生）
local native_props = {}
for _, k in ipairs({
    "visible", "focused", "width", "height", "min_size", "align", "tooltip", "css",
    "margin", "margin_left", "margin_right", "margin_top", "margin_bottom", "can_focus",
    "bg", "fg", "font", "text", "textwidth", "selectable", "padding", "position", "show_frame",
    "homogeneous", "spacing", "show_tabs", "show_border", "scrollbars", "scroll",
    "title", "decorated", "urgency_hint", "fullscreen", "maximized", "screen", "root_win_xid",
    "started", "opacity",
}) do native_props[k] = true end

-- 需要对象↔id 翻译的属性
local object_props = { visible_child = true, top = true, bottom = true, left = true, right = true }

-- 事件名 → 宿主监听开关
local wants = {
    ["key-press"] = "wants_key_press",
    ["button-press"] = "wants_button",
    ["button-release"] = "wants_button",
    ["button-double-click"] = "wants_button",
    ["mouse-enter"] = "wants_enter",
    ["mouse-leave"] = "wants_enter",
    ["scroll"] = "wants_scroll",
}

-- ===== 各类型专属方法 =====
-- 与 lk_widget.lua 的 common_props 同名的“方法”（count/current/filename/icon/scale）
-- 从 impl.get 返回函数，才不会被属性协议截成 nil。

local methods = {}

methods.notebook = {
    count = function(w) return nat("call", id_of(w), { method = "count" }) or 0 end,
    current = function(w) return nat("call", id_of(w), { method = "current" }) or 0 end,
    atindex = function(w, i)
        if type(i) ~= "number" then return nil end
        -- luakit：-1 是最后一页
        if i == -1 then i = methods.notebook.count(w) end
        return obj_of(nat("call", id_of(w), { method = "atindex", args = { i } }))
    end,
    indexof = function(w, child)
        if not object.is_object(child) then return nil end
        local i = nat("call", id_of(w), { method = "indexof", args = { id_of(child) } })
        -- luakit：找不到返回 nil
        if not i or i < 1 then return nil end
        return i
    end,
    insert = function(w, a, b)
        local index, child
        if b ~= nil then index, child = a, b else index, child = -1, a end
        if type(index) ~= "number" then index = -1 end
        local r = nat("call", id_of(w), { method = "insert", args = { index, id_of(child) } })
        link(w, child, r)
        return r
    end,
    append = function(w, child)
        return methods.notebook.insert(w, child)
    end,
    switch = function(w, i)
        return nat("call", id_of(w), { method = "switch", args = { i } })
    end,
    reorder = function(w, child, i)
        nat("call", id_of(w), { method = "reorder", args = { id_of(child), i } })
        link(w, child, i)
    end,
    set_title = function(w, child, title)
        nat("call", id_of(w), { method = "set_title", args = { id_of(child), tostring(title or "") } })
    end,
    get_title = function(w, child)
        return nat("call", id_of(w), { method = "get_title", args = { id_of(child) } }) or ""
    end,
    remove = function(w, child)
        nat("call", id_of(w), { method = "remove", args = { id_of(child) } })
        unlink(child)
    end,
}

local function box_pack(w, child, opts)
    local o = {}
    if type(opts) == "table" then
        for k, v in pairs(opts) do o[k] = v end
    end
    local idx = nat("call", id_of(w), { method = "pack", args = { id_of(child), o } })
    link(w, child, (idx or 0) + 1)
    return child
end

local function container_remove(w, child)
    nat("call", id_of(w), { method = "remove", args = { id_of(child) } })
    unlink(child)
end

local function container_reorder(w, child, i)
    nat("call", id_of(w), { method = "reorder", args = { id_of(child), i } })
    link(w, child, i + 1)
end

methods.hbox = { pack = box_pack, remove = container_remove, reorder = container_reorder }
methods.vbox = methods.hbox
methods.overlay = { pack = box_pack, remove = container_remove, reorder = container_reorder }
methods.stack = { pack = box_pack, remove = container_remove }

local function paned_pack(slot)
    return function(w, child, opts)
        local o = {}
        if type(opts) == "table" then for k, v in pairs(opts) do o[k] = v end end
        nat("call", id_of(w), { method = slot, args = { id_of(child), o } })
        local p = object.priv(w)
        p.slots = p.slots or {}
        local old = p.slots[slot]
        if old and old ~= child and object.is_alive(old) then unlink(old) end
        p.slots[slot] = child
        link(w, child)
        return child
    end
end
methods.hpaned = { pack1 = paned_pack("pack1"), pack2 = paned_pack("pack2"), remove = container_remove }
methods.vpaned = methods.hpaned

methods.entry = {
    insert = function(w, a, b)
        local pos, text
        if b ~= nil then pos, text = a, b else pos, text = nil, a end
        local args = pos ~= nil and { pos, tostring(text) } or { tostring(text) }
        nat("call", id_of(w), { method = "insert_text", args = args })
    end,
    select_region = function(w, s, e)
        nat("call", id_of(w), { method = "select_region", args = { s or 0, e or -1 } })
    end,
}

methods.image = {
    filename = function(w, path, size)
        nat("call", id_of(w), { method = "filename", args = size and { tostring(path), size } or { tostring(path) } })
    end,
    icon = function(w, name, size)
        nat("call", id_of(w), { method = "icon", args = { tostring(name), size or 16 } })
    end,
    scale = function(w, width, height)
        nat("call", id_of(w), { method = "scale", args = { width, height or width } })
    end,
    set_favicon_for_uri = function(w, uri)
        return nat("call", id_of(w), { method = "set_favicon_for_uri", args = { tostring(uri) } })
    end,
}

methods.spinner = {
    start = function(w) nat("call", id_of(w), { method = "start" }) end,
    stop = function(w) nat("call", id_of(w), { method = "stop" }) end,
}

methods.drawing_area = {
    invalidate = function(w) nat("call", id_of(w), { method = "invalidate" }) end,
}

methods.window = {
    set_default_size = function(w, width, height)
        nat("call", id_of(w), { method = "set_default_size", args = { width, height } })
    end,
    set_dark_mode = function(w, on)
        nat("call", id_of(w), { method = "set_dark_mode", args = { on and true or false } })
    end,
    remove = container_remove,
}

methods.eventbox = { remove = container_remove }
methods.scrolled = { remove = container_remove }

-- 所有类型共有的方法（lk_widget.common_methods 之外的）
local common = {
    replace = function(w, other)
        local p = object.priv(w)
        local parent = p.parent
        nat("call", id_of(w), { method = "replace", args = { id_of(other) } })
        if parent and object.is_alive(parent) then
            local pp = object.priv(parent)
            if pp.children then
                for i, c in ipairs(pp.children) do
                    if c == w then pp.children[i] = other break end
                end
            end
            if pp.child == w then pp.child = other end
            set_parent(other, parent)
        end
        p.parent = nil
    end,
    send_key = function(w, key, mods)
        nat("call", id_of(w), { method = "send_key", args = { tostring(key), mods or {} } })
    end,
    query_tooltip = function(w) return nat("call", id_of(w), { method = "query_tooltip" }) end,
    css_reset = function(w) nat("call", id_of(w), { method = "css_reset" }) end,
    -- LemurX扩展：屏幕矩形（dp）
    rect = function(w) return nat("call", id_of(w), { method = "rect" }) end,
}

-- ===== impl =====

local impl = {}

function impl.get(w, key)
    local p = object.priv(w)
    if key == "parent" then return p.parent end
    if key == "child" then return p.child end
    local tm = methods[p.type]
    if tm and tm[key] then return tm[key] end
    if common[key] then return common[key] end
    if object_props[key] then
        return obj_of(nat("get", p.native_id, { key = key }))
    end
    if native_props[key] then
        return nat("get", p.native_id, { key = key })
    end
    return nil
end

function impl.set(w, key, v)
    local p = object.priv(w)
    if key == "child" then
        -- bin 容器：window / eventbox / scrolled / overlay 主子项
        local old = p.child
        if old and object.is_alive(old) and old ~= v then unlink(old) end
        if v == nil or v == false then
            nat("set", p.native_id, { child = false })
            p.child = nil
            return true
        end
        nat("set", p.native_id, { child = id_of(v) })
        p.child = v
        link(w, v)
        return true
    end
    if key == "parent" then
        p.parent = v
        return true
    end
    if key == "visible_child" then
        nat("set", p.native_id, { visible_child = v and id_of(v) or false })
        return true
    end
    if key == "type" or key == "id" then return true end
    if native_props[key] or key == "wants_key_press" then
        local val = v
        if val == nil then val = false end
        nat("set", p.native_id, { [key] = val })
        return true
    end
    -- 未知属性：存 props（lk_widget 兜底）
    return false
end

function impl.index(w, key)
    local p = object.priv(w)
    -- notebook[i] → 第 i 页（luakit notebook.c 的数字下标）
    if type(key) == "number" then
        if p.type == "notebook" then return methods.notebook.atindex(w, key) end
        return nil
    end
    local tm = methods[p.type]
    if tm and tm[key] then return tm[key] end
    if common[key] then return common[key] end
    -- 不在 lk_widget 通用属性表里的原生属性（spinner.started 等）
    if native_props[key] and p.native_id then
        return nat("get", p.native_id, { key = key })
    end
    return nil
end

function impl.children(w)
    local p = object.priv(w)
    if p.type == "notebook" then
        -- notebook 的页顺序以宿主为准
        local ids = nat("get", p.native_id, { key = "children" }) or {}
        local out = {}
        for _, id in ipairs(ids) do
            local c = obj_of(id)
            if c then out[#out + 1] = c end
        end
        return out
    end
    local out = {}
    for _, c in ipairs(p.children or {}) do
        if object.is_alive(c) then out[#out + 1] = c end
    end
    return out
end

function impl.focus(w)
    nat("call", id_of(w), { method = "focus" })
end

function impl.pack(w, child, opts)
    local p = object.priv(w)
    local tm = methods[p.type]
    if tm and tm.pack then return tm.pack(w, child, opts) end
    if p.type == "notebook" then return methods.notebook.insert(w, child) end
    -- bin 容器：pack 等价于 child =
    impl.set(w, "child", child)
    return child
end

function impl.insert(w, ...)
    local p = object.priv(w)
    local tm = methods[p.type]
    if tm and tm.insert then return tm.insert(w, ...) end
    error(("widget(%s) has no method insert"):format(p.type), 3)
end

function impl.append(w, child)
    local p = object.priv(w)
    if p.type == "notebook" then return methods.notebook.append(w, child) end
    return impl.pack(w, child)
end

function impl.remove(w, child)
    if not object.is_object(child) then return end
    return container_remove(w, child)
end

function impl.reorder(w, child, i)
    local p = object.priv(w)
    local tm = methods[p.type]
    if tm and tm.reorder then return tm.reorder(w, child, i) end
end

function impl.indexof(w, child)
    local p = object.priv(w)
    if p.type == "notebook" then return methods.notebook.indexof(w, child) end
    for i, c in ipairs(p.children or {}) do
        if c == child then return i end
    end
    return -1
end

function impl.atindex(w, i)
    local p = object.priv(w)
    if p.type == "notebook" then return methods.notebook.atindex(w, i) end
    return (p.children or {})[i]
end

function impl.switch(w, i)
    local p = object.priv(w)
    if p.type == "notebook" then return methods.notebook.switch(w, i) end
end

function impl.pack1(w, child, opts) return methods.hpaned.pack1(w, child, opts) end
function impl.pack2(w, child, opts) return methods.hpaned.pack2(w, child, opts) end
function impl.set_default_size(w, a, b) return methods.window.set_default_size(w, a, b) end
function impl.select_region(w, s, e) return methods.entry.select_region(w, s, e) end
function impl.insert_text(w, ...) return methods.entry.insert(w, ...) end
function impl.set_icon(w, name, size) return methods.image.icon(w, name, size) end
function impl.send_key(w, key, mods) return common.send_key(w, key, mods) end
function impl.query_tooltip(w) return common.query_tooltip(w) end
function impl.css_reset(w) return common.css_reset(w) end

function impl.scroll(w, ...)
    -- scrolled 的 scroll 是属性；这里给 scroll{x=,y=} 的方法式写法兜底
    local p = object.priv(w)
    local t = ...
    if type(t) == "table" then nat("set", p.native_id, { scroll = t }) end
end

function impl.destroy(w)
    local p = object.priv(w)
    -- 先把子树也销毁（GTK 销毁容器会级联）
    for _, c in ipairs(impl.children(w)) do
        if object.is_alive(c) and object.priv(c).type ~= "webview" then
            c:destroy()
        elseif object.is_alive(c) then
            unlink(c)
        end
    end
    if p.child and object.is_alive(p.child) and object.priv(p.child).type ~= "webview" then
        p.child:destroy()
    end
    unlink(w)
    if p.native_id then
        pcall(nat, "destroy", p.native_id, {})
        by_id[p.native_id] = nil
        p.native_id = nil
    end
    if p.type == "window" then
        for i, win in ipairs(windows) do
            if win == w then table.remove(windows, i) break end
        end
    end
end

-- ===== 注册类型 =====

local function make_ctor(type_name)
    return function(props)
        local id = nat("create", 0, { type = type_name })
        local w = __lk.new_widget(type_name, impl, {})
        local p = object.priv(w)
        p.native_id = id
        p.children = {}
        by_id[id] = w
        if type_name == "window" then
            windows[#windows + 1] = w
        end
        return w
    end
end

for _, t in ipairs({ "window", "hbox", "vbox", "hpaned", "vpaned", "notebook", "overlay", "stack",
                     "eventbox", "scrolled", "label", "entry", "image", "spinner", "drawing_area" }) do
    __lk.register_widget_type(t, make_ctor(t))
end

__lk.list_windows = function()
    local out = {}
    for _, w in ipairs(windows) do
        if object.is_alive(w) then out[#out + 1] = w end
    end
    return out
end

-- webview 被 destroy 时释放占位
__lk.on_webview_destroyed = function(view)
    local p = object.priv(view)
    if p.native_id then
        -- 先让宿主销毁：宿主随之回投的 page-removed/remove 事件还需要通过 by_id
        -- 找到这个 webview 对象，否则 notebook 收不到 page-removed（tablist 依赖它）
        pcall(nat, "destroy", p.native_id, {})
        by_id[p.native_id] = nil
        p.native_id = nil
    end
end

-- ===== 事件监听开关：对象上一挂 key-press / button-* 就通知宿主 =====

local prev_hook = object.on_add_signal
object.on_add_signal = function(obj, name)
    if prev_hook then prev_hook(obj, name) end
    local flag = wants[name]
    if not flag then return end
    local p = object.priv(obj)
    if not p or not p.native_id then return end
    pcall(nat, "set", p.native_id, { [flag] = true })
end

-- ===== 原生事件 =====

local function mods_of(ev)
    local mods = {}
    for _, m in ipairs(ev.mods or {}) do mods[#mods + 1] = m end
    return mods
end

__lk.dispatchers.widget = function(id, json)
    local ev = N.json_decode(json)
    if type(ev) ~= "table" then return end
    local w = obj_of(id)
    if not w then return end
    local p = object.priv(w)
    local kind = ev.ev
    if kind == "property" then
        object.property_signal(w, ev.name)
    elseif kind == "resize" then
        object.emit_ignore(w, "resize", ev.width, ev.height)
    elseif kind == "focus" or kind == "unfocus" then
        object.emit_signal(w, kind)
    elseif kind == "changed" or kind == "activate" then
        object.emit_signal(w, kind)
    elseif kind == "key-press" then
        -- 只有 send_key 合成的走异步；真实按键走 sync
        object.emit_signal(w, "key-press", mods_of(ev), ev.key, ev.synthetic == true)
    elseif kind == "button-press" or kind == "button-release" or kind == "button-double-click" then
        object.emit_signal(w, kind, mods_of(ev), ev.button or 1)
    elseif kind == "mouse-enter" or kind == "mouse-leave" then
        object.emit_signal(w, kind, mods_of(ev))
    elseif kind == "scroll" then
        object.emit_signal(w, "scroll", mods_of(ev), ev.dx or 0, ev.dy or 0)
    elseif kind == "add" or kind == "remove" then
        local child = obj_of(ev.child)
        if child then object.emit_ignore(w, kind, child) end
    elseif kind == "parent-set" then
        local parent = obj_of(ev.parent)
        p.parent = parent
        object.emit_ignore(w, "parent-set", parent)
    elseif kind == "page-added" or kind == "page-reordered" then
        local child = obj_of(ev.child)
        if child then
            if kind == "page-added" then set_parent(child, w) end
            object.emit_ignore(w, kind, child, ev.index)
        end
    elseif kind == "page-removed" then
        local child = obj_of(ev.child)
        if child then object.emit_ignore(w, "page-removed", child) end
    elseif kind == "switch-page" then
        local child = obj_of(ev.child)
        if child then object.emit_ignore(w, "switch-page", child, ev.index) end
    elseif kind == "delete-event" then
        local ret = object.emit_signal(w, "delete-event")
        if ret ~= true then w:destroy() end
    elseif kind == "destroy" then
        -- 宿主侧已释放（Lua 发起的 destroy 走 impl.destroy，这里只处理原生先没了的情况）
        if p.native_id == id then
            by_id[id] = nil
            p.native_id = nil
            object.destroy(w)
        end
    end
end

-- 同步答复：key-press 是否被吃掉
local prev_sync = rawget(_G, "__luakit_dispatch_sync")
_G.__luakit_dispatch_sync = function(kind, id, json)
    if kind ~= "widget" then
        if prev_sync then return prev_sync(kind, id, json) end
        return ""
    end
    local ev = N.json_decode(json)
    if type(ev) ~= "table" then return "false" end
    local w = obj_of(id)
    if not w then return "false" end
    if ev.ev == "key-press" then
        local ok, ret = xpcall(object.emit_signal, debug.traceback, w, "key-press", mods_of(ev), ev.key, ev.synthetic == true)
        if not ok then
            msg.warn("key-press handler failed: %s", tostring(ret))
            return "false"
        end
        return ret and "true" or "false"
    end
    return "false"
end

msg.verbose("widget tree host ready (%d types)", 15)
