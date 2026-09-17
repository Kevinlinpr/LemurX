-- luakit 对象协议（对应 luakit common/luaobject.c + luaclass.c 的 Lua 侧语义）
--
--  * 每个类实例是一张空表 + 类专属元表；type(obj) 返回类名（"widget"/"download"…）
--  * 属性读写走 __index/__newindex，写成功后发 "property::<name>" 信号
--  * obj:add_signal(name, fn) / obj:emit_signal(name, ...) / obj:remove_signal(name, fn)
--    / obj:remove_signals(name)；emit 时按注册顺序调用 fn(obj, ...)，
--    第一个返回非 nil 的处理器终止传播并把返回值原样返回（luakit LUA_MULTRET 语义）
--  * 类本身也有 add_signal/emit_signal（模块式，不传对象），例如 widget.add_signal("create")
--  * 销毁后只允许读 is_alive，其余访问报错
--
-- 这里不拷 luakit 的 C 实现，是按其文档与 lib/ 的用法重写。

local rawtype = type
local unpack = table.unpack

local M = {}

-- obj -> { class=, signals={name={fn,...}}, extra={}, alive=bool, priv={} }
local ndata = setmetatable({}, { __mode = "k" })
local classes = {}

local function get_data(obj, what)
    local d = ndata[obj]
    if not d then
        error(("%s: not a luakit object (%s)"):format(what or "object", tostring(obj)), 3)
    end
    return d
end

local function check_signame(name)
    if rawtype(name) ~= "string" then
        error("invalid signame type: " .. rawtype(name), 3)
    end
    if not name:match("^[%w_%-:]+$") then
        error("invalid chars in signame: " .. name, 3)
    end
end

-- ===== 信号（实例级）=====

local function add_signal(obj, name, fn)
    local d = get_data(obj, "add_signal")
    check_signame(name)
    if rawtype(fn) ~= "function" then
        error("invalid handler function for signal " .. name, 2)
    end
    local list = d.signals[name]
    if not list then
        list = {}
        d.signals[name] = list
    end
    list[#list + 1] = fn
    -- 控件树宿主要知道对象上挂了哪些事件（key-press/button-* 才去原生侧监听）
    if M.on_add_signal then M.on_add_signal(obj, name) end
end

local function remove_signal(obj, name, fn)
    local d = get_data(obj, "remove_signal")
    local list = d.signals[name]
    if not list then return nil end
    for i, f in ipairs(list) do
        if f == fn then
            table.remove(list, i)
            if #list == 0 then d.signals[name] = nil end
            return fn
        end
    end
    return nil
end

local function remove_signals(obj, name)
    local d = get_data(obj, "remove_signals")
    d.signals[name] = nil
end

-- 多返回值语义：第一个返回非 nil 的处理器胜出
local function emit_signal(obj, name, ...)
    local d = get_data(obj, "emit_signal")
    local list = d.signals[name]
    if not list then return end
    -- 处理器执行中可能改列表，先拷一份
    local copy = { unpack(list) }
    for _, fn in ipairs(copy) do
        local ret = table.pack(fn(obj, ...))
        if ret.n > 0 and ret[1] ~= nil then
            return unpack(ret, 1, ret.n)
        end
    end
end

-- nret==0 语义：全部处理器都跑，返回值全丢（property:: 信号用）
local function emit_ignore(obj, name, ...)
    local d = ndata[obj]
    if not d then return end
    local list = d.signals[name]
    if not list then return end
    local copy = { unpack(list) }
    for _, fn in ipairs(copy) do
        fn(obj, ...)
    end
end

local signal_methods = {
    add_signal = add_signal,
    emit_signal = emit_signal,
    remove_signal = remove_signal,
    remove_signals = remove_signals,
}

M.add_signal = add_signal
M.emit_signal = emit_signal
M.emit_ignore = emit_ignore
-- 对象上是否挂了某个信号的处理器（scheme 管线用来判定“无人处理”）
function M.has_signal(obj, name)
    local d = ndata[obj]
    if not d then return false end
    local list = d.signals[name]
    return list ~= nil and #list > 0
end
M.remove_signal = remove_signal
M.remove_signals = remove_signals

function M.property_signal(obj, key)
    emit_ignore(obj, "property::" .. key)
end

-- ===== 模块式信号（类 / 模块级，不传对象）=====

function M.setup_module_signals(tbl)
    local signals = {}
    tbl.add_signal = function(name, fn)
        check_signame(name)
        if rawtype(fn) ~= "function" then
            error("invalid handler function for signal " .. name, 2)
        end
        local list = signals[name]
        if not list then
            list = {}
            signals[name] = list
        end
        list[#list + 1] = fn
    end
    tbl.remove_signal = function(name, fn)
        local list = signals[name]
        if not list then return nil end
        for i, f in ipairs(list) do
            if f == fn then
                table.remove(list, i)
                if #list == 0 then signals[name] = nil end
                return fn
            end
        end
    end
    tbl.remove_signals = function(name)
        signals[name] = nil
    end
    tbl.emit_signal = function(name, ...)
        local list = signals[name]
        if not list then return end
        local copy = { unpack(list) }
        for _, fn in ipairs(copy) do
            local ret = table.pack(fn(...))
            if ret.n > 0 and ret[1] ~= nil then
                return unpack(ret, 1, ret.n)
            end
        end
    end
    -- 内核用：判断有没有人在听
    tbl.__has_signal = function(name)
        local list = signals[name]
        return list ~= nil and #list > 0
    end
    return tbl
end

-- ===== 类 =====

-- def = {
--   props    = { name = { get = fn(obj) -> v, set = fn(obj, v) } }  -- 没有 set 即只读
--   methods  = { name = fn(obj, ...) }
--   index    = fn(obj, key) -> v            -- 动态属性兜底（如 webview 的几十个 WebKit 设置）
--   newindex = fn(obj, key, v) -> handled    -- 动态属性写；返回 true 表示已处理（会发 property::）
--   new      = fn(props) -> obj              -- Class{ ... } 构造
--   tostring = fn(obj) -> string
--   gc       = fn(obj)
-- }
function M.class(name, def)
    def = def or {}
    local class = {
        __name = name,
        __props = def.props or {},
        __methods = def.methods or {},
        __def = def,
    }
    M.setup_module_signals(class)

    local mt = {}
    mt.__type = name
    mt.__name = name

    mt.__index = function(obj, key)
        local d = ndata[obj]
        if not d then return nil end
        if key == "is_alive" then return d.alive end
        if not d.alive then
            error(("attempt to access %s.%s on a destroyed %s"):format(name, tostring(key), name), 2)
        end
        local m = class.__methods[key]
        if m ~= nil then return m end
        local sm = signal_methods[key]
        if sm then return sm end
        local p = class.__props[key]
        if p then
            if p.get then return p.get(obj, key) end
            return nil
        end
        if def.index then
            local v = def.index(obj, key)
            if v ~= nil then return v end
        end
        local v = d.extra[key]
        if v ~= nil then return v end
        if class.__has_signal("debug::index::miss") then
            class.emit_signal("debug::index::miss", obj, key)
        end
        return nil
    end

    mt.__newindex = function(obj, key, value)
        local d = ndata[obj]
        if not d then
            rawset(obj, key, value)
            return
        end
        if not d.alive then
            error(("attempt to set %s.%s on a destroyed %s"):format(name, tostring(key), name), 2)
        end
        local p = class.__props[key]
        if p then
            if not p.set then
                error(("%s.%s is read-only"):format(name, tostring(key)), 2)
            end
            p.set(obj, value)
            M.property_signal(obj, key)
            return
        end
        if def.newindex and def.newindex(obj, key, value) then
            M.property_signal(obj, key)
            return
        end
        -- luakit 对象是 userdata，塞不进未知键；这里放宽：存到 extra，
        -- 让 lib/ 里偶尔往对象上挂私有状态的写法不炸，同时发 miss 信号便于排查。
        d.extra[key] = value
        if class.__has_signal("debug::newindex::miss") then
            class.emit_signal("debug::newindex::miss", obj, key, value)
        end
    end

    mt.__tostring = function(obj)
        if def.tostring then
            local ok, s = pcall(def.tostring, obj)
            if ok and s then return s end
        end
        local d = ndata[obj]
        local addr = tostring(rawget(obj, "__addr") or d and d.addr or "?")
        return name .. ": " .. addr
    end

    if def.gc then
        mt.__gc = function(obj)
            local d = ndata[obj]
            if d and d.alive then
                pcall(def.gc, obj)
            end
        end
    end

    -- 让 rawequal 之外的比较和 table 键行为保持默认（对象身份即表身份）
    class.__mt = mt

    setmetatable(class, {
        __call = function(_, props)
            if not def.new then
                error(name .. " is not constructible", 2)
            end
            return def.new(props or {})
        end,
        __tostring = function() return "class " .. name end,
    })

    classes[name] = class
    return class
end

-- 造实例。priv 是内核私有状态（Lua 侧拿不到）。
local addr_counter = 0
function M.new(class, priv)
    local obj = setmetatable({}, class.__mt)
    addr_counter = addr_counter + 1
    ndata[obj] = {
        class = class,
        signals = {},
        extra = {},
        alive = true,
        priv = priv or {},
        addr = ("0x%08x"):format(addr_counter),
    }
    return obj
end

function M.priv(obj)
    local d = ndata[obj]
    return d and d.priv
end

function M.class_of(obj)
    local d = ndata[obj]
    return d and d.class
end

function M.is_object(obj)
    return ndata[obj] ~= nil
end

function M.is_alive(obj)
    local d = ndata[obj]
    return d ~= nil and d.alive
end

-- 销毁：先发 destroy 信号，再标记死亡
function M.destroy(obj)
    local d = ndata[obj]
    if not d or not d.alive then return end
    emit_ignore(obj, "destroy")
    d.alive = false
    d.signals = {}
end

function M.get_class(name)
    return classes[name]
end

-- ===== 模块（luakit / soup / xdg / msg 这类有属性的全局表）=====
-- def = { props = {name={get=,set=}}, funcs = {name=fn}, index=fn, newindex=fn }
function M.module(name, def)
    def = def or {}
    local t = {}
    for k, v in pairs(def.funcs or {}) do t[k] = v end
    M.setup_module_signals(t)
    local props = def.props or {}
    setmetatable(t, {
        __type = "table",
        __index = function(_, key)
            local p = props[key]
            if p then
                if p.get then return p.get(key) end
                return nil
            end
            if def.index then return def.index(key) end
            return nil
        end,
        __newindex = function(_, key, value)
            local p = props[key]
            if p then
                if not p.set then
                    error(("%s.%s is read-only"):format(name, key), 2)
                end
                p.set(value)
                return
            end
            if def.newindex and def.newindex(key, value) then return end
            rawset(t, key, value)
        end,
        __tostring = function() return name end,
    })
    return t
end

-- ===== type() 替换：对象返回类名 =====
_G.type = function(v)
    if rawtype(v) == "table" then
        local mt = getmetatable(v)
        if rawtype(mt) == "table" then
            local tn = rawget(mt, "__type")
            if tn ~= nil then return tn end
        end
    end
    return rawtype(v)
end
M.rawtype = rawtype

-- luakit 把 debug.traceback 换成去 ANSI 色的版本；我们的日志本来就没颜色，直接透传
-- 但保留一个钩子位。
M.traceback = debug.traceback

return M
