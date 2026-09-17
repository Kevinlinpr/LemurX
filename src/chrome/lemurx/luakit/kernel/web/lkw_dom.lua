-- dom_document / dom_element（对应 luakit extension/clib/dom_document.c、dom_element.c）
--
-- 每个对象背后是一个主世界 V8 节点句柄（rawget(obj, "__js")），所有 DOM 操作都是
-- 同步的 js_get / js_set / js_call。同一节点在同一页面内返回同一个 Lua 对象
-- （节点上挂隐藏标识做身份缓存），这样 elem == other、用元素做表键都成立。
--
-- dom_element 属性：tag_name text_content inner_html child_count src href value checked
--   type parent first_child last_child prev_sibling next_sibling rect attr style
--   document owner_document
-- 方法：query(css) append(child) remove() click() focus() submit()
--   add_event_listener(type, capture, fn) remove_event_listener(type, capture, fn)
--   client_rects()
-- 信号："destroy"（页面卸载时对该页所有元素发）
--
-- dom_document 属性：body window{scroll_x scroll_y inner_width inner_height}
-- 方法：create_element(tag, attrs, inner_text) element_from_point(x, y) query(css)
-- 信号："destroy"

local N = __luakit_web
local object = __lk.object
local js = __lk.js
local unpack = table.unpack

local ID_KEY = "__lkid"

local elements = setmetatable({}, { __mode = "v" })   -- "rid:id" -> dom_element
local documents = {}                                   -- rid -> dom_document
local per_page = {}                                    -- rid -> { [key]=true }
local next_id = 1

local dom_element, dom_document

local function H(obj) return rawget(obj, "__js") end

local function rid_of(obj) return object.priv(obj).rid end

local function jget(obj, key)
    local v, err = N.js_get(H(obj), key)
    if v == nil and err then error(("dom: %s"):format(tostring(err)), 3) end
    return v
end

local function jset(obj, key, value)
    local ok, err = N.js_set(H(obj), key, js.unwrap(rid_of(obj), value))
    if not ok then error(("dom: %s"):format(tostring(err)), 3) end
end

local function jcall(obj, method, ...)
    local rid = rid_of(obj)
    local args = table.pack(...)
    for i = 1, args.n do args[i] = js.unwrap(rid, args[i]) end
    local v, err = N.js_call(H(obj), method, unpack(args, 1, args.n))
    if v == nil and err then error(("dom: %s.%s: %s"):format(tostring(obj), tostring(method), tostring(err)), 3) end
    return v
end

-- 节点句柄 → dom_element（带身份缓存）
local function element_from_handle(h, rid)
    if not h then return nil end
    rid = rid or js.rid_of(h)
    local id = N.js_get(h, ID_KEY)
    if type(id) ~= "number" then
        id = next_id
        next_id = next_id + 1
        N.js_set(h, ID_KEY, id)
    end
    local key = tostring(rid) .. ":" .. tostring(id)
    local obj = elements[key]
    if obj and object.is_alive(obj) then
        -- 旧句柄换新句柄没有意义，释放新的
        pcall(N.js_release, h)
        return obj
    end
    js.retain_gc(h)
    js.set_rid(h, rid)
    obj = object.new(dom_element, { rid = rid, key = key })
    rawset(obj, "__js", h)
    elements[key] = obj
    per_page[rid] = per_page[rid] or {}
    per_page[rid][key] = true
    return obj
end

local function wrap_value(v, rid)
    if js.is_handle(v) then
        if rawget(v, "__jskind") == "node" then
            local t = N.js_get(v, "nodeType")
            if t == 9 then
                pcall(N.js_release, v)
                return documents[rid] or __lk.document_for(rid)
            end
            return element_from_handle(v, rid)
        end
        js.set_rid(v, rid)
        return js.wrap(v)
    end
    if type(v) == "table" then
        for k, x in pairs(v) do v[k] = wrap_value(x, rid) end
    end
    return v
end

local function node_list(obj, v)
    local rid = rid_of(obj)
    local out = {}
    if type(v) == "table" then
        for i, h in ipairs(v) do out[i] = wrap_value(h, rid) end
    end
    return out
end

-- ===== attr / style 代理 =====
local attr_mt = {
    __index = function(t, k)
        local obj = rawget(t, "__obj")
        local v = jcall(obj, "getAttribute", k)
        if v == nil then return nil end
        return v
    end,
    __newindex = function(t, k, v)
        local obj = rawget(t, "__obj")
        if v == nil then
            jcall(obj, "removeAttribute", k)
        else
            jcall(obj, "setAttribute", k, tostring(v))
        end
    end,
}

local style_mt = {
    __index = function(t, k)
        local obj = rawget(t, "__obj")
        local rid = rid_of(obj)
        local cs = N.page_eval(rid, "window.getComputedStyle", "luakit-dom")
        local st, err = N.js_call(cs, nil, H(obj))
        pcall(N.js_release, cs)
        if not st then error("dom: getComputedStyle: " .. tostring(err), 2) end
        local v = N.js_call(st, "getPropertyValue", k)
        if v == nil or v == "" then
            -- 也接受驼峰/直接属性
            v = N.js_get(st, k)
        end
        pcall(N.js_release, st)
        return v
    end,
    __newindex = function(t, k, v)
        local obj = rawget(t, "__obj")
        local st = jget(obj, "style")
        N.js_set(st, k, v)
        pcall(N.js_release, st)
    end,
}

-- ===== 事件监听 =====
-- add_event_listener(type, capture, fn)：fn(elem, event_table)
-- 事件表字段与 luakit 一致：target type phase button key code ctrl_key alt_key shift_key meta_key；
-- 处理器把 event.prevent_default / event.cancel 置 true 分别对应 preventDefault / stopPropagation。
local listeners = setmetatable({}, { __mode = "k" })   -- elem -> { [type.."/"..cap] = { fn -> {cb_id, bound_id} } }
local next_listener = 1

local function event_table(evh, rid)
    local ev = {}
    local function g(k) local v = N.js_get(evh, k) return v end
    local target = g("target")
    ev.target = target and wrap_value(target, rid) or nil
    ev.type = g("type")
    ev.phase = g("eventPhase")
    ev.button = g("button")
    ev.key = g("key")
    ev.code = g("code")
    ev.ctrl_key = g("ctrlKey")
    ev.alt_key = g("altKey")
    ev.shift_key = g("shiftKey")
    ev.meta_key = g("metaKey")
    ev.client_x = g("clientX")
    ev.client_y = g("clientY")
    return ev
end

local function on_dom_event(listener_id, evh)
    local entry = __lk.dom_listeners_by_id[listener_id]
    if not entry then return end
    local elem, fn = entry.elem, entry.fn
    if not object.is_alive(elem) then return end
    local rid = rid_of(elem)
    js.set_rid(evh, rid)
    local ev = event_table(evh, rid)
    local ok, err = xpcall(fn, debug.traceback, elem, ev)
    if not ok then msg.warn("dom event handler error: %s", tostring(err)) end
    if ev.prevent_default then pcall(N.js_call, evh, "preventDefault") end
    if ev.cancel then pcall(N.js_call, evh, "stopPropagation") end
    pcall(N.js_release, evh)
end
__lk.dom_listeners_by_id = {}
__lk.on_dom_event = on_dom_event

local function add_event_listener(obj, etype, capture, fn)
    if type(etype) ~= "string" then error("add_event_listener: type must be a string", 2) end
    if type(fn) ~= "function" then error("add_event_listener: handler must be a function", 2) end
    capture = capture and true or false
    local per = listeners[obj]
    if not per then per = {} listeners[obj] = per end
    local k = etype .. "/" .. tostring(capture)
    per[k] = per[k] or {}
    if per[k][fn] then return end
    local id = next_listener
    next_listener = next_listener + 1
    local ok, bound = N.js_listen(H(obj), etype, capture, id)
    if not ok then error("add_event_listener: " .. tostring(bound), 2) end
    per[k][fn] = { id, bound }
    __lk.dom_listeners_by_id[id] = { elem = obj, fn = fn }
end

local function remove_event_listener(obj, etype, capture, fn)
    capture = capture and true or false
    local per = listeners[obj]
    if not per then return end
    local k = etype .. "/" .. tostring(capture)
    local set = per[k]
    if not set then return end
    local rec = set[fn]
    if not rec then return end
    set[fn] = nil
    __lk.dom_listeners_by_id[rec[1]] = nil
    pcall(N.js_unlisten, H(obj), etype, capture, rec[1], rec[2])
end

local function rect_of(obj)
    -- luakit 用 offsetLeft/Top 累加得到文档坐标；这里用 getBoundingClientRect + 滚动量
    local r = jcall(obj, "getBoundingClientRect")
    local rid = rid_of(obj)
    local sx = N.page_eval(rid, "window.scrollX", "luakit-dom") or 0
    local sy = N.page_eval(rid, "window.scrollY", "luakit-dom") or 0
    local out = {
        left = (N.js_get(r, "left") or 0) + sx,
        top = (N.js_get(r, "top") or 0) + sy,
        width = N.js_get(r, "width") or 0,
        height = N.js_get(r, "height") or 0,
    }
    pcall(N.js_release, r)
    return out
end

local function client_rects(obj)
    local list = jcall(obj, "getClientRects")
    local out = {}
    if type(list) == "table" then
        for i, r in ipairs(list) do
            out[i] = {
                left = N.js_get(r, "left"), top = N.js_get(r, "top"),
                width = N.js_get(r, "width"), height = N.js_get(r, "height"),
            }
            pcall(N.js_release, r)
        end
    elseif js.is_handle(list) then
        local n = N.js_get(list, "length") or 0
        for i = 0, n - 1 do
            local r = N.js_get(list, tostring(i))
            if r then
                out[#out + 1] = {
                    left = N.js_get(r, "left"), top = N.js_get(r, "top"),
                    width = N.js_get(r, "width"), height = N.js_get(r, "height"),
                }
                pcall(N.js_release, r)
            end
        end
        pcall(N.js_release, list)
    end
    return out
end

local function query(obj, css)
    if type(css) ~= "string" then error("query expects a css selector string", 2) end
    return node_list(obj, jcall(obj, "querySelectorAll", css))
end

local function document_of(obj)
    return documents[rid_of(obj)] or __lk.document_for(rid_of(obj))
end

dom_element = object.class("dom_element", {
    props = {
        tag_name = { get = function(o) return jget(o, "tagName") end },
        text_content = { get = function(o) return jget(o, "textContent") end },
        inner_html = {
            get = function(o) return jget(o, "innerHTML") end,
            set = function(o, v) jset(o, "innerHTML", tostring(v)) end,
        },
        child_count = { get = function(o) return jget(o, "childElementCount") end },
        src = { get = function(o) return jcall(o, "getAttribute", "src") end },
        href = { get = function(o) return jcall(o, "getAttribute", "href") end },
        value = {
            get = function(o) return jget(o, "value") end,
            set = function(o, v) jset(o, "value", v) end,
        },
        checked = {
            get = function(o) return jget(o, "checked") and true or false end,
            set = function(o, v) jset(o, "checked", v and true or false) end,
        },
        type = { get = function(o) return jcall(o, "getAttribute", "type") end },
        parent = { get = function(o) return wrap_value(jget(o, "parentElement"), rid_of(o)) end },
        first_child = { get = function(o) return wrap_value(jget(o, "firstElementChild"), rid_of(o)) end },
        last_child = { get = function(o) return wrap_value(jget(o, "lastElementChild"), rid_of(o)) end },
        prev_sibling = { get = function(o) return wrap_value(jget(o, "previousElementSibling"), rid_of(o)) end },
        next_sibling = { get = function(o) return wrap_value(jget(o, "nextElementSibling"), rid_of(o)) end },
        rect = { get = rect_of },
        attr = { get = function(o)
            local p = object.priv(o)
            if not p.attr then p.attr = setmetatable({ __obj = o }, attr_mt) end
            return p.attr
        end },
        style = { get = function(o)
            local p = object.priv(o)
            if not p.style then p.style = setmetatable({ __obj = o }, style_mt) end
            return p.style
        end },
        document = { get = document_of },
        owner_document = { get = document_of },
    },
    methods = {
        query = query,
        append = function(o, child)
            if type(child) ~= "dom_element" then error("append expects a dom_element", 2) end
            jcall(o, "appendChild", child)
        end,
        remove = function(o) jcall(o, "remove") end,
        click = function(o) jcall(o, "click") end,
        focus = function(o) jcall(o, "focus") end,
        submit = function(o)
            local tag = jget(o, "tagName")
            if tag and tag:lower() == "form" then
                jcall(o, "submit")
            else
                local form = jget(o, "form")
                if form then
                    N.js_call(form, "submit")
                    pcall(N.js_release, form)
                end
            end
        end,
        add_event_listener = add_event_listener,
        remove_event_listener = remove_event_listener,
        client_rects = client_rects,
    },
    tostring = function(o)
        local ok, tag = pcall(jget, o, "tagName")
        return "dom_element: " .. (ok and tostring(tag):lower() or "?")
    end,
})

-- ===== dom_document =====
local window_mt = {
    __index = function(t, k)
        local rid = rawget(t, "__rid")
        local map = { scroll_x = "window.scrollX", scroll_y = "window.scrollY",
                      inner_width = "window.innerWidth", inner_height = "window.innerHeight" }
        local expr = map[k]
        if not expr then return nil end
        return N.page_eval(rid, expr, "luakit-dom")
    end,
}

dom_document = object.class("dom_document", {
    props = {
        body = { get = function(o) return wrap_value(jget(o, "body"), rid_of(o)) end },
        window = { get = function(o)
            local p = object.priv(o)
            if not p.window then p.window = setmetatable({ __rid = p.rid }, window_mt) end
            return p.window
        end },
        uri = { get = function(o) return jget(o, "URL") end },
    },
    methods = {
        create_element = function(o, tag, attrs, inner_text)
            local el = jcall(o, "createElement", tag)
            if type(attrs) == "table" then
                for k, v in pairs(attrs) do N.js_call(el, "setAttribute", k, tostring(v)) end
            end
            if type(inner_text) == "string" then N.js_set(el, "innerText", inner_text) end
            return wrap_value(el, rid_of(o))
        end,
        element_from_point = function(o, x, y)
            return wrap_value(jcall(o, "elementFromPoint", x, y), rid_of(o))
        end,
        query = query,
        add_event_listener = add_event_listener,
        remove_event_listener = remove_event_listener,
    },
    tostring = function(o) return "dom_document: " .. tostring(rid_of(o)) end,
})

-- rid → dom_document（懒建）
function __lk.document_for(rid)
    local doc = documents[rid]
    if doc and object.is_alive(doc) then return doc end
    local win, err = N.page_global(rid)
    if not win then return nil, err end
    local h, gerr = N.js_get(win, "document")
    pcall(N.js_release, win)
    if not h then return nil, gerr end
    js.retain_gc(h)
    js.set_rid(h, rid)
    doc = object.new(dom_document, { rid = rid })
    rawset(doc, "__js", h)
    documents[rid] = doc
    return doc
end

-- 页面文档卸载/销毁：对该页所有 DOM 对象发 destroy，然后作废
function __lk.dom_destroy_page(rid)
    local keys = per_page[rid]
    per_page[rid] = nil
    if keys then
        for key in pairs(keys) do
            local el = elements[key]
            if el and object.is_alive(el) then
                pcall(object.emit_signal, el, "destroy")
                listeners[el] = nil
                object.destroy(el)
            end
            elements[key] = nil
        end
    end
    local doc = documents[rid]
    documents[rid] = nil
    if doc and object.is_alive(doc) then
        pcall(object.emit_signal, doc, "destroy")
        object.destroy(doc)
    end
end

js.wrap_node = function(h) return wrap_value(h, js.rid_of(h)) end
__lk.wrap_value = wrap_value

_G.dom_element = dom_element
_G.dom_document = dom_document
return dom_element
