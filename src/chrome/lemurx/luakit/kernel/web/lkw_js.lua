-- JS 句柄层：把原生 __luakit_web.js_* 的“句柄表”{__jsh=id, __jskind=kind}
-- 包成好用的 Lua 值，并把 Lua 值反向拆回原生能懂的形状。
--
--   kind = "node"     → dom_element / dom_document（lkw_dom 提供 wrap_node）
--   kind = "function" → 可直接调用的 Lua 函数（调回 JS）
--   kind = "object"   → 代理表：读属性 = js_get，写 = js_set，调方法 = obj:m(...)
--
-- 句柄由 __gc 释放（Lua 5.4 表支持 __gc）。

local N = __luakit_web
local unpack = table.unpack

local M = {}

local function is_handle(v)
    return type(v) == "table" and rawget(v, "__jsh") ~= nil and rawget(v, "__jskind") ~= nil
end
M.is_handle = is_handle

-- 代理对象的元表
local proxy_mt = {}
local proxies = setmetatable({}, { __mode = "k" })   -- proxy -> handle table

function M.handle_of(v)
    if is_handle(v) then return v end
    local h = proxies[v]
    if h then return h end
    if type(v) == "table" then
        local raw = rawget(v, "__js")
        if raw then return raw end
    end
    return nil
end

-- Lua → 原生参数：把内核对象拆成句柄表，把 Lua 函数包成 JS 函数
function M.unwrap(rid, v, depth)
    depth = depth or 0
    local tv = type(v)
    if tv == "function" then
        return M.function_handle(rid, v)
    end
    if tv ~= "table" then return v end
    local h = M.handle_of(v)
    if h then return h end
    if depth > 12 then return nil end
    local out = {}
    for k, x in pairs(v) do out[k] = M.unwrap(rid, x, depth + 1) end
    return out
end

-- 原生返回值 → Lua：递归把句柄表包起来
function M.wrap(v, depth)
    depth = depth or 0
    if type(v) ~= "table" then return v end
    if is_handle(v) then
        local kind = rawget(v, "__jskind")
        if kind == "node" then
            return M.wrap_node(v)
        elseif kind == "function" then
            return M.wrap_function(v)
        else
            return M.wrap_object(v)
        end
    end
    if depth > 12 then return v end
    for k, x in pairs(v) do v[k] = M.wrap(x, depth + 1) end
    return v
end

-- 由 lkw_dom 覆盖
M.wrap_node = function(h) return M.wrap_object(h) end

local function release(h)
    pcall(N.js_release, h)
end

function M.retain_gc(h)
    -- 句柄表自身挂 __gc，表被回收时释放 V8 侧引用
    if getmetatable(h) == nil then
        setmetatable(h, { __gc = release })
    end
    return h
end

function M.get(h, key)
    local v, err = N.js_get(h, key)
    if v == nil and err then return nil, err end
    return M.wrap(v)
end

function M.set(h, key, value)
    local rid = M.rid_of(h)
    local ok, err = N.js_set(h, key, M.unwrap(rid, value))
    if not ok then error(tostring(err), 3) end
    return true
end

function M.call(h, method, ...)
    local rid = M.rid_of(h)
    local args = table.pack(...)
    for i = 1, args.n do args[i] = M.unwrap(rid, args[i]) end
    local v, err = N.js_call(h, method, unpack(args, 1, args.n))
    if v == nil and err then return nil, err end
    return M.wrap(v)
end

-- 句柄属于哪个页面：原生不直接暴露，这里在 wrap 时记录
local rids = setmetatable({}, { __mode = "k" })
function M.rid_of(h) return rids[h] or M.current_rid or 0 end
function M.set_rid(h, rid) rids[h] = rid end
M.current_rid = nil

-- ===== 函数句柄 → Lua 可调用 =====
function M.wrap_function(h)
    M.retain_gc(h)
    return function(...)
        local v, err = M.call(h, nil, ...)
        if v == nil and err then error(tostring(err), 2) end
        return v
    end
end

-- Lua 函数 → JS 函数句柄（同一个 Lua 函数复用同一个句柄）
local fn_handles = setmetatable({}, { __mode = "k" })
local callbacks = {}          -- cb_id -> fn
local next_cb = 1

function M.function_handle(rid, fn)
    local h = fn_handles[fn]
    if h then return h end
    local id = next_cb
    next_cb = next_cb + 1
    callbacks[id] = fn
    local nh, err = N.js_function(rid, id)
    if not nh then error("cannot wrap Lua function for JS: " .. tostring(err), 3) end
    M.retain_gc(nh)
    M.set_rid(nh, rid)
    fn_handles[fn] = nh
    return nh
end

-- 原生 "call" 派发：JS 调了包装函数
function M.on_call(cb_id, ...)
    local fn = callbacks[cb_id]
    if not fn then return nil end
    local args = table.pack(...)
    for i = 1, args.n do args[i] = M.wrap(args[i]) end
    return fn(unpack(args, 1, args.n))
end

-- ===== 通用对象代理 =====
proxy_mt.__index = function(p, key)
    local h = proxies[p]
    if key == "__js" then return h end
    local v, err = M.get(h, key)
    if v == nil and err then return nil end
    if type(v) == "function" and M.is_handle(M.handle_of(v)) == false then
        return v
    end
    -- JS 方法：返回“绑定 this”的调用器，让 obj:method(...) 与 obj.method(...) 都能用
    local fh = type(v) == "table" and M.handle_of(v)
    if fh and rawget(fh, "__jskind") == "function" then
        return function(self_or_first, ...)
            if self_or_first == p then
                return M.call(h, key, ...)
            end
            return M.call(h, key, self_or_first, ...)
        end
    end
    return v
end
proxy_mt.__newindex = function(p, key, value)
    M.set(proxies[p], key, value)
end
proxy_mt.__tostring = function(p) return "js_object: " .. tostring(rawget(proxies[p], "__jsh")) end
proxy_mt.__eq = function(a, b)
    local ha, hb = proxies[a], proxies[b]
    if not (ha and hb) then return false end
    return N.js_eq(ha, hb)
end

function M.wrap_object(h)
    M.retain_gc(h)
    local p = setmetatable({}, proxy_mt)
    proxies[p] = h
    return p
end

-- Lua 函数的 JS 化返回：wrap_function 返回的是裸函数，handle_of 认不出来；
-- 记一下映射让 unwrap 能还回句柄
local wrapped_fns = setmetatable({}, { __mode = "k" })
local orig_wrap_function = M.wrap_function
M.wrap_function = function(h)
    local f = orig_wrap_function(h)
    wrapped_fns[f] = h
    return f
end
local orig_handle_of = M.handle_of
M.handle_of = function(v)
    if type(v) == "function" then return wrapped_fns[v] end
    return orig_handle_of(v)
end
local orig_unwrap = M.unwrap
M.unwrap = function(rid, v, depth)
    if type(v) == "function" and wrapped_fns[v] then return wrapped_fns[v] end
    return orig_unwrap(rid, v, depth)
end

__lk.js = M
return M
