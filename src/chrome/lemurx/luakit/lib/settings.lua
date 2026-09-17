-- LemurX · luakit-compatible library · settings
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "settings" module API. No luakit code is used.
--
-- 统一设置中心。各模块用 register_settings 登记 "组.名" 形式的键（类型 / 默认值 / 说明 /
-- 校验器 / 是否允许按域名覆盖），之后可以通过
--     settings.get_setting / set_setting            读写全局值
--     settings.<组>.<名>                             读写代理
--     settings.on["example.com"].<组>.<名>            按域名覆盖
--     settings.add_signal("setting-changed", fn)      监听变化
-- 显式设置的值落到 luakit.data_dir/settings.db（sqlite3），启动后首次访问时懒加载；
-- 数据库打不开时退化为纯内存，不影响运行。

local M = {}

-- ====================================================================
-- 极简模块级信号（不依赖 lousy，settings 是所有模块的基础）
-- ====================================================================
local handlers = {}
function M.add_signal(name, fn)
    assert(type(name) == "string", "signal name must be a string")
    assert(type(fn) == "function", "signal handler must be a function")
    local list = handlers[name] or {}
    handlers[name] = list
    list[#list + 1] = fn
end
function M.remove_signal(name, fn)
    local list = handlers[name]
    if not list then return end
    for i = #list, 1, -1 do
        if list[i] == fn then table.remove(list, i) return fn end
    end
end
function M.remove_signals(name) handlers[name] = nil end
function M.emit_signal(name, ...)
    local list = handlers[name]
    if not list then return end
    for _, fn in ipairs({ table.unpack(list) }) do
        local ret = table.pack(fn(...))
        if ret.n > 0 and ret[1] ~= nil then return table.unpack(ret, 1, ret.n) end
    end
end

-- ====================================================================
-- 内部状态
-- ====================================================================
local registry = {}        -- key -> meta
local values = {}          -- key -> 显式全局值
local overrides = {}       -- key -> 运行期覆盖（不落盘）
local domain_values = {}   -- domain -> key -> 值
local view_overrides = setmetatable({}, { __mode = "k" }) -- view -> key -> 值
local stored = {}          -- 数据库里尚未登记的键：key -> domain -> 序列化串
local group_proxies = {}   -- group -> 代理表
local domain_proxies = {}  -- domain -> 代理表
local db = nil             -- false = 已尝试且失败
local loaded = false

M.migration_warnings = {}

local function warn(fmt, ...)
    if msg and msg.warn then msg.warn(fmt, ...) end
end

local function split_key(key)
    if type(key) ~= "string" then return nil end
    local group, name = key:match("^([%w_]+)%.([%w_%.]+)$")
    return group, name
end

-- ====================================================================
-- 序列化：Lua 字面量，load 回读；只接受 boolean/number/string/table
-- ====================================================================
local serialize
serialize = function(v, depth)
    depth = depth or 0
    if depth > 16 then error("settings: value nesting too deep") end
    local t = type(v)
    if t == "nil" then return "nil"
    elseif t == "boolean" then return v and "true" or "false"
    elseif t == "number" then
        if v ~= v or v == math.huge or v == -math.huge then return "0" end
        if math.type(v) == "integer" then return string.format("%d", v) end
        return string.format("%.17g", v)
    elseif t == "string" then return string.format("%q", v)
    elseif t == "table" then
        local parts = {}
        local n = #v
        for i = 1, n do parts[#parts + 1] = serialize(v[i], depth + 1) end
        local keys = {}
        for k in pairs(v) do
            if not (math.type(k) == "integer" and k >= 1 and k <= n) then keys[#keys + 1] = k end
        end
        table.sort(keys, function(a, b) return tostring(a) < tostring(b) end)
        for _, k in ipairs(keys) do
            local ks = type(k) == "string" and string.format("[%q]", k) or ("[" .. serialize(k, depth + 1) .. "]")
            parts[#parts + 1] = ks .. "=" .. serialize(v[k], depth + 1)
        end
        return "{" .. table.concat(parts, ",") .. "}"
    end
    error("settings: cannot persist a value of type " .. t)
end

local function deserialize(s)
    if type(s) ~= "string" then return nil end
    local fn = load("return " .. s, "=settings", "t", {})
    if not fn then return nil end
    local ok, v = pcall(fn)
    if ok then return v end
    return nil
end

local function clone(v)
    if type(v) ~= "table" then return v end
    local out = {}
    for k, x in pairs(v) do out[k] = clone(x) end
    return out
end

-- ====================================================================
-- 校验
-- ====================================================================
local check_type

local function in_options(meta, v)
    local opts = meta.options
    if type(opts) ~= "table" then return true end
    if opts[v] ~= nil then return true end
    for _, o in ipairs(opts) do if o == v then return true end end
    return false
end

check_type = function(tname, v, meta)
    if tname == "boolean" then
        return type(v) == "boolean"
    elseif tname == "string" then
        return type(v) == "string"
    elseif tname == "number" then
        if type(v) ~= "number" then return false end
        if meta and meta.min and v < meta.min then return false, "below minimum " .. tostring(meta.min) end
        if meta and meta.max and v > meta.max then return false, "above maximum " .. tostring(meta.max) end
        return true
    elseif tname == "integer" then
        if type(v) ~= "number" or v % 1 ~= 0 then return false end
        return check_type("number", v, meta)
    elseif tname == "enum" then
        if not in_options(meta or {}, v) then return false, "not one of the allowed options" end
        return true
    elseif tname == "table" or tname == "any" then
        return tname == "any" or type(v) == "table"
    end
    local container, inner = tname:match("^([%w_]+):(.+)$")
    if container then
        if type(v) ~= "table" then return false end
        if container == "array" then
            local n = 0
            for _ in pairs(v) do n = n + 1 end
            if n ~= #v then return false, "not a sequence" end
        end
        for _, item in pairs(v) do
            local ok, err = check_type(inner, item, nil)
            if not ok then return false, err or ("element is not a " .. inner) end
        end
        return true
    end
    -- 未知类型名：不做结构校验，交给 validator
    return true
end

local function validate(meta, v)
    if v == nil then return true end
    local ok, err = check_type(meta.type, v, meta)
    if not ok then
        return false, ("setting %s expects %s%s"):format(meta.key, meta.type, err and (" (" .. err .. ")") or "")
    end
    if meta.validator then
        local vok, verr = meta.validator(v)
        if vok == false then
            return false, ("setting %s rejected by validator%s"):format(meta.key, verr and (": " .. tostring(verr)) or "")
        end
    end
    return true
end

-- ====================================================================
-- 持久化
-- ====================================================================
local DB_NAME = "settings.db"

local function open_db()
    if db ~= nil then return db end
    if not rawget(_G, "sqlite3") or not rawget(_G, "luakit") then
        db = false
        return db
    end
    local dir = luakit.data_dir
    pcall(function() if lfs and lfs.mkdir then lfs.mkdir(dir) end end)
    M.db_path = M.db_path or (dir .. "/" .. DB_NAME)
    local ok, handle = pcall(sqlite3, { filename = M.db_path })
    if not ok then
        warn("settings: cannot open %s (%s); running without persistence", tostring(M.db_path), tostring(handle))
        db = false
        return db
    end
    local ok2, err = pcall(handle.exec, handle, [[
        CREATE TABLE IF NOT EXISTS kv (
            key    TEXT NOT NULL,
            domain TEXT NOT NULL DEFAULT '',
            value  TEXT NOT NULL,
            PRIMARY KEY (key, domain)
        );
    ]])
    if not ok2 then
        warn("settings: schema init failed: %s", tostring(err))
        db = false
        return db
    end
    db = handle
    return db
end

local apply_loaded

local function load_all()
    if loaded then return end
    loaded = true
    local h = open_db()
    if not h then return end
    local ok, rows = pcall(h.exec, h, "SELECT key, domain, value FROM kv")
    if not ok or type(rows) ~= "table" then return end
    for _, row in ipairs(rows) do
        if type(row.key) == "string" and type(row.value) == "string" then
            local domain = row.domain
            if domain == nil or domain == "" then domain = "" end
            stored[row.key] = stored[row.key] or {}
            stored[row.key][domain] = row.value
        end
    end
    for key in pairs(registry) do apply_loaded(key) end
end

apply_loaded = function(key)
    local meta = registry[key]
    local per_domain = stored[key]
    if not meta or not per_domain then return end
    stored[key] = nil
    for domain, raw in pairs(per_domain) do
        local v = deserialize(raw)
        local ok, err = validate(meta, v)
        if ok and v ~= nil then
            if domain == "" then
                values[key] = v
            else
                domain_values[domain] = domain_values[domain] or {}
                domain_values[domain][key] = v
            end
        else
            warn("settings: dropping stored value for %s%s: %s", key,
                domain ~= "" and (" @" .. domain) or "", tostring(err or "unreadable"))
        end
    end
end

local function persist(key, domain, v)
    local h = open_db()
    if not h then return end
    domain = domain or ""
    local ok, err
    if v == nil then
        ok, err = pcall(h.exec, h, "DELETE FROM kv WHERE key = :k AND domain = :d", { [":k"] = key, [":d"] = domain })
    else
        ok, err = pcall(h.exec, h, "INSERT OR REPLACE INTO kv (key, domain, value) VALUES (:k, :d, :v)",
            { [":k"] = key, [":d"] = domain, [":v"] = serialize(v) })
    end
    if not ok then warn("settings: persist %s failed: %s", key, tostring(err)) end
end

-- ====================================================================
-- 公共 API
-- ====================================================================
local function need_meta(key, level)
    local meta = registry[key]
    if not meta then
        error(("settings: unknown setting %q"):format(tostring(key)), (level or 1) + 1)
    end
    return meta
end

local VALID_TYPES = { boolean = true, string = true, number = true, integer = true, enum = true, table = true, any = true }

function M.register_settings(list)
    if type(list) ~= "table" then error("settings.register_settings expects a table", 2) end
    load_all()
    for key, spec in pairs(list) do
        local group, name = split_key(key)
        if not group then
            error(("settings.register_settings: bad key %q (want group.name)"):format(tostring(key)), 2)
        end
        if type(spec) ~= "table" then
            error(("settings.register_settings: %s needs a table"):format(key), 2)
        end
        local tname = spec.type or "any"
        if type(tname) ~= "string" then error("settings: type of " .. key .. " must be a string", 2) end
        if not VALID_TYPES[tname] and not tname:find(":", 1, true) then
            error(("settings: %s has unknown type %q"):format(key, tname), 2)
        end
        if spec.type == "enum" and type(spec.options) ~= "table" then
            error(("settings: enum %s needs an options table"):format(key), 2)
        end
        local meta = {
            key = key, group = group, name = name,
            type = tname,
            default = clone(spec.default),
            desc = spec.desc or "",
            validator = spec.validator,
            domain_specific = spec.domain_specific,
            options = clone(spec.options),
            min = spec.min, max = spec.max,
            formatter = spec.formatter,
        }
        if registry[key] then
            -- 重复登记：保留已有值，更新元数据
            meta.default = meta.default == nil and registry[key].default or meta.default
        end
        local ok, err = validate(meta, meta.default)
        if not ok then error("settings: default value invalid: " .. tostring(err), 2) end
        registry[key] = meta
        apply_loaded(key)
    end
end

local function parse_opts(opts)
    if opts == nil then return nil end
    if type(opts) == "string" then return opts ~= "" and opts or nil end
    if type(opts) == "table" then
        local d = opts.domain
        if d ~= nil and type(d) ~= "string" then error("settings: domain must be a string", 3) end
        return d ~= "" and d or nil
    end
    error("settings: bad options argument", 3)
end

function M.get_setting(key, opts)
    local meta = need_meta(key, 2)
    load_all()
    local domain = parse_opts(opts)
    if domain then
        local dv = domain_values[domain]
        if dv and dv[key] ~= nil then return clone(dv[key]) end
    end
    if overrides[key] ~= nil then return clone(overrides[key]) end
    if values[key] ~= nil then return clone(values[key]) end
    return clone(meta.default)
end

function M.set_setting(key, value, opts)
    local meta = need_meta(key, 2)
    load_all()
    local domain = parse_opts(opts)
    if domain and meta.domain_specific == false then
        error(("settings: %s cannot be set per-domain"):format(key), 2)
    end
    local ok, err = validate(meta, value)
    if not ok then error(err, 2) end
    value = clone(value)
    if domain then
        domain_values[domain] = domain_values[domain] or {}
        domain_values[domain][key] = value
    else
        values[key] = value
    end
    persist(key, domain, value)
    M.emit_signal("setting-changed", { key = key, value = clone(value), domain = domain })
end

-- 运行期覆盖（不落盘，比显式值优先）
function M.override_setting(key, value)
    local meta = need_meta(key, 2)
    local ok, err = validate(meta, value)
    if not ok then error(err, 2) end
    overrides[key] = clone(value)
    M.emit_signal("setting-changed", { key = key, value = clone(value), domain = nil, override = true })
end

function M.override_setting_for_view(view, key, value)
    local meta = need_meta(key, 2)
    local ok, err = validate(meta, value)
    if not ok then error(err, 2) end
    view_overrides[view] = view_overrides[view] or {}
    view_overrides[view][key] = clone(value)
end

local function host_chain(uri)
    if type(uri) ~= "string" then return {} end
    local host = uri:match("^%a[%w+.-]*://([^/?#:@]+)") or uri:match("^%a[%w+.-]*://[^@/]*@([^/?#:]+)")
    if not host then return {} end
    local chain = { host }
    while true do
        local rest = host:match("^[^.]+%.(.+)$")
        if not rest or not rest:find(".", 1, true) then break end
        chain[#chain + 1] = rest
        host = rest
    end
    return chain
end

function M.get_setting_for_view(view, key)
    need_meta(key, 2)
    local vo = view_overrides[view]
    if vo and vo[key] ~= nil then return clone(vo[key]) end
    local uri = nil
    pcall(function() uri = view.uri end)
    for _, host in ipairs(host_chain(uri)) do
        local dv = domain_values[host]
        if dv and dv[key] ~= nil then return clone(dv[key]) end
    end
    return M.get_setting(key)
end

local function record(meta)
    local domains = {}
    for domain, dv in pairs(domain_values) do
        if dv[meta.key] ~= nil then domains[domain] = clone(dv[meta.key]) end
    end
    return {
        key = meta.key, group = meta.group, name = meta.name,
        type = meta.type, desc = meta.desc,
        default = clone(meta.default),
        value = M.get_setting(meta.key),
        options = clone(meta.options),
        min = meta.min, max = meta.max,
        domain_specific = meta.domain_specific,
        domains = domains,
        is_default = values[meta.key] == nil and overrides[meta.key] == nil,
    }
end

function M.get_settings()
    load_all()
    local out = {}
    for key, meta in pairs(registry) do out[key] = record(meta) end
    return out
end

function M.get_setting_info(key)
    local meta = need_meta(key, 2)
    load_all()
    return record(meta)
end

function M.is_registered(key) return registry[key] ~= nil end

-- 把字符串（来自命令行 / 设置页）按登记类型解析成值
function M.coerce(key, text)
    local meta = need_meta(key, 2)
    if type(text) ~= "string" then return text end
    local t = meta.type
    if t == "boolean" then
        local l = text:lower()
        if l == "true" or l == "1" or l == "yes" or l == "on" then return true end
        if l == "false" or l == "0" or l == "no" or l == "off" then return false end
        return nil, "expected true/false"
    elseif t == "number" or t == "integer" then
        local n = tonumber(text)
        if not n then return nil, "expected a number" end
        return n
    elseif t == "string" or t == "enum" then
        return text
    elseif t:match("^array:") then
        local out = {}
        for item in text:gmatch("[^,\n]+") do
            item = item:match("^%s*(.-)%s*$")
            if item ~= "" then
                local inner = t:match("^array:(.+)$")
                if inner == "number" then out[#out + 1] = tonumber(item) or item
                elseif inner == "boolean" then out[#out + 1] = (item == "true")
                else out[#out + 1] = item end
            end
        end
        return out
    end
    local v = deserialize(text)
    if v == nil then return nil, "cannot parse value" end
    return v
end

function M.add_migration_warning(text)
    M.migration_warnings[#M.migration_warnings + 1] = tostring(text)
    warn("settings migration: %s", tostring(text))
end

-- migrate_global(setting_key, global_name)：老式全局变量还在时搬进设置并提醒
function M.migrate_global(key, global_name)
    if not registry[key] and type(global_name) == "string" and registry[global_name] then
        key, global_name = global_name, key
    end
    if type(global_name) ~= "string" then return end
    local v = rawget(_G, global_name)
    if v == nil then return end
    M.add_migration_warning(("global '%s' is obsolete, use settings.%s instead"):format(global_name, key))
    local ok, err = pcall(M.set_setting, key, v)
    if not ok then warn("settings: migrate_global(%s) failed: %s", key, tostring(err)) end
end

-- ====================================================================
-- 代理：settings.<group>.<name>  与  settings.on[domain].<group>.<name>
-- ====================================================================
local function group_exists(group)
    for key in pairs(registry) do
        if key:sub(1, #group + 1) == group .. "." then return true end
    end
    return false
end

local function make_group_proxy(group, domain)
    return setmetatable({}, {
        __index = function(_, name)
            local key = group .. "." .. tostring(name)
            if not registry[key] then
                error(("settings: unknown setting %q"):format(key), 2)
            end
            return M.get_setting(key, domain)
        end,
        __newindex = function(_, name, v)
            local key = group .. "." .. tostring(name)
            if not registry[key] then
                error(("settings: unknown setting %q"):format(key), 2)
            end
            M.set_setting(key, v, domain)
        end,
        __pairs = function()
            local out = {}
            for key, meta in pairs(registry) do
                if meta.group == group then out[meta.name] = M.get_setting(key, domain) end
            end
            return next, out, nil
        end,
    })
end

local function domain_proxy(domain)
    if domain_proxies[domain] then return domain_proxies[domain] end
    local groups = {}
    local proxy = setmetatable({}, {
        __index = function(_, k)
            if type(k) ~= "string" then return nil end
            if k:find(".", 1, true) then
                if not registry[k] then error(("settings: unknown setting %q"):format(k), 2) end
                return M.get_setting(k, domain)
            end
            if not group_exists(k) then return nil end
            groups[k] = groups[k] or make_group_proxy(k, domain)
            return groups[k]
        end,
        __newindex = function(_, k, v)
            if type(k) == "string" and k:find(".", 1, true) then
                M.set_setting(k, v, domain)
                return
            end
            error("settings.on[domain]: assign to settings.on[domain].<group>.<name>", 2)
        end,
    })
    domain_proxies[domain] = proxy
    return proxy
end

M.on = setmetatable({}, {
    __index = function(_, domain)
        if type(domain) ~= "string" or domain == "" then
            error("settings.on[domain]: domain must be a non-empty string", 2)
        end
        return domain_proxy(domain)
    end,
    __newindex = function() error("settings.on is read-only; use settings.on[domain].group.name = value", 2) end,
})

-- 按域名列出覆盖（settings_chrome 用）
function M.get_domains()
    load_all()
    local out = {}
    for domain in pairs(domain_values) do out[#out + 1] = domain end
    table.sort(out)
    return out
end

setmetatable(M, {
    __index = function(_, k)
        if type(k) == "string" and group_exists(k) then
            group_proxies[k] = group_proxies[k] or make_group_proxy(k, nil)
            return group_proxies[k]
        end
        return nil
    end,
    __newindex = function(t, k, v)
        if type(k) == "string" and group_exists(k) then
            error(("settings.%s is a settings group; assign to settings.%s.<name>"):format(k, k), 2)
        end
        rawset(t, k, v)
    end,
})

return M
