-- LemurX · luakit-compatible library · lousy.pickle
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.pickle" module API. No luakit code is used.
--
-- 表序列化。输出是一段可被 load() 执行的 Lua 源码，形态为：
--   local R={} R[1]={} R[2]={} ... R[1]["k"]=R[2] ... return R[1]
-- 每张表先登记再填内容，因此共享引用和环都能还原。支持的值类型：
-- nil/boolean/number/string/table；函数、userdata 等会被跳过。
-- 格式是内部细节，可能随版本变化，外部只应通过 pickle/unpickle 使用。

local M = {}

local function is_plain_table(v)
    -- 内核对象 type() 返回类名，这里只序列化真正的 Lua 表
    return type(v) == "table"
end

local function serial_scalar(v)
    local t = type(v)
    if t == "string" then
        return string.format("%q", v)
    elseif t == "number" then
        if v ~= v then return "(0/0)" end
        if v == math.huge then return "math.huge" end
        if v == -math.huge then return "-math.huge" end
        if math.type(v) == "integer" then return string.format("%d", v) end
        return string.format("%.17g", v)
    elseif t == "boolean" then
        return v and "true" or "false"
    elseif t == "nil" then
        return "nil"
    end
    return nil
end

function M.pickle(root)
    if not is_plain_table(root) then
        error("lousy.pickle.pickle: only tables can be pickled (got " .. type(root) .. ")", 2)
    end
    local ids = {}       -- table -> ref index
    local order = {}     -- ref index -> table
    local queue = { root }
    ids[root] = 1
    order[1] = root
    local head = 1
    -- 广度优先登记所有可达表
    while head <= #queue do
        local t = queue[head]
        head = head + 1
        for k, v in pairs(t) do
            for _, x in ipairs({ k, v }) do
                if is_plain_table(x) and not ids[x] then
                    order[#order + 1] = x
                    ids[x] = #order
                    queue[#queue + 1] = x
                end
            end
        end
    end

    local out = { "local R={}" }
    for i = 1, #order do
        out[#out + 1] = string.format("R[%d]={}", i)
    end
    local function ref(v)
        if is_plain_table(v) then return string.format("R[%d]", ids[v]) end
        return serial_scalar(v)
    end
    for i, t in ipairs(order) do
        -- 键排序保证同一张表每次输出一致
        local keys = {}
        for k in pairs(t) do keys[#keys + 1] = k end
        table.sort(keys, function(a, b)
            local ta, tb = type(a), type(b)
            if ta ~= tb then return ta < tb end
            if ta == "number" or ta == "string" then return a < b end
            return tostring(a) < tostring(b)
        end)
        for _, k in ipairs(keys) do
            local ks, vs = ref(k), ref(t[k])
            if ks and vs and vs ~= "nil" then
                out[#out + 1] = string.format("R[%d][%s]=%s", i, ks, vs)
            end
        end
    end
    out[#out + 1] = "return R[1]"
    return table.concat(out, "\n")
end

function M.unpickle(s)
    if type(s) ~= "string" then
        error("lousy.unpickle: expected a string", 2)
    end
    if s == "" then return {} end
    local env = { math = { huge = math.huge } }
    local chunk, err = load(s, "=pickle", "t", env)
    if not chunk then
        error("lousy.unpickle: corrupt data: " .. tostring(err), 2)
    end
    local ok, result = pcall(chunk)
    if not ok then
        error("lousy.unpickle: corrupt data: " .. tostring(result), 2)
    end
    if type(result) ~= "table" then return {} end
    return result
end

return M
