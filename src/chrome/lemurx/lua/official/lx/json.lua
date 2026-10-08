-- lx.json —— JSON 编解码。
--
-- 浏览器进程里优先走原生 __luakit.json_encode/json_decode（C++ base::JSON，快），
-- 渲染进程里走 __luakit_web.json_*；两者都没有（比如在电脑上跑单测）时用下面的纯 Lua 实现。
-- 纯 Lua 版语义与原生版一致：连续整数键 1..n 的表编成数组，空表编成 {}，
-- 解码时 null → nil（对象里的键消失，数组里出现洞——和原生一致，调用方别依赖 null）。

local M = {}

local native_encode, native_decode
do
    local N = rawget(_G, "__luakit") or rawget(_G, "__luakit_web")
    if type(N) == "table" and type(N.json_encode) == "function" and type(N.json_decode) == "function" then
        native_encode, native_decode = N.json_encode, N.json_decode
    end
end

-- ===== 编码 =====
local escapes = {
    ['"'] = '\\"', ['\\'] = '\\\\', ['\b'] = '\\b', ['\f'] = '\\f',
    ['\n'] = '\\n', ['\r'] = '\\r', ['\t'] = '\\t',
}

local function escape_str(s)
    return (s:gsub('[%c"\\]', function(c)
        return escapes[c] or string.format("\\u%04x", c:byte())
    end))
end

local function is_array(t)
    local n = 0
    for k in pairs(t) do
        if math.type(k) ~= "integer" or k < 1 then return false end
        n = n + 1
    end
    if n == 0 then return false end
    for i = 1, n do
        if t[i] == nil then return false end
    end
    return true, n
end

local encode_value

local function encode_table(t, out, seen)
    if seen[t] then error("lx.json.encode: cyclic table") end
    seen[t] = true
    local arr, n = is_array(t)
    if arr then
        out[#out + 1] = "["
        for i = 1, n do
            if i > 1 then out[#out + 1] = "," end
            encode_value(t[i], out, seen)
        end
        out[#out + 1] = "]"
    else
        out[#out + 1] = "{"
        local first = true
        -- 键排序：输出稳定，方便做缓存比对
        local keys = {}
        for k in pairs(t) do keys[#keys + 1] = k end
        table.sort(keys, function(a, b) return tostring(a) < tostring(b) end)
        for _, k in ipairs(keys) do
            if not first then out[#out + 1] = "," end
            first = false
            out[#out + 1] = '"' .. escape_str(tostring(k)) .. '":'
            encode_value(t[k], out, seen)
        end
        out[#out + 1] = "}"
    end
    seen[t] = nil
end

encode_value = function(v, out, seen)
    local tv = type(v)
    if v == nil then
        out[#out + 1] = "null"
    elseif tv == "boolean" then
        out[#out + 1] = v and "true" or "false"
    elseif tv == "number" then
        if v ~= v or v == math.huge or v == -math.huge then
            out[#out + 1] = "null"
        elseif math.type(v) == "integer" then
            out[#out + 1] = tostring(v)
        else
            out[#out + 1] = string.format("%.14g", v)
        end
    elseif tv == "string" then
        out[#out + 1] = '"' .. escape_str(v) .. '"'
    elseif tv == "table" then
        encode_table(v, out, seen)
    else
        out[#out + 1] = '"' .. escape_str(tostring(v)) .. '"'
    end
end

local function pure_encode(v)
    local out = {}
    encode_value(v, out, {})
    return table.concat(out)
end

-- ===== 解码 =====
local function decode_error(s, i, msg)
    local line = 1
    for _ in s:sub(1, i):gmatch("\n") do line = line + 1 end
    error(("lx.json.decode: %s at line %d (byte %d)"):format(msg, line, i), 0)
end

local function skip_ws(s, i)
    return s:find("[^ \t\r\n]", i) or (#s + 1)
end

local function utf8_char(cp)
    return utf8.char(cp)
end

local parse_value

local function parse_string(s, i)
    -- s[i] == '"'
    local out = {}
    local j = i + 1
    while true do
        local c = s:sub(j, j)
        if c == "" then decode_error(s, j, "unterminated string") end
        if c == '"' then return table.concat(out), j + 1 end
        if c == "\\" then
            local e = s:sub(j + 1, j + 1)
            if e == "u" then
                local hex = s:sub(j + 2, j + 5)
                if not hex:match("^%x%x%x%x$") then decode_error(s, j, "bad \\u escape") end
                local cp = tonumber(hex, 16)
                j = j + 6
                -- 代理对
                if cp >= 0xD800 and cp <= 0xDBFF and s:sub(j, j + 1) == "\\u" then
                    local lo = tonumber(s:sub(j + 2, j + 5), 16)
                    if lo and lo >= 0xDC00 and lo <= 0xDFFF then
                        cp = 0x10000 + (cp - 0xD800) * 0x400 + (lo - 0xDC00)
                        j = j + 6
                    end
                end
                out[#out + 1] = utf8_char(cp)
            else
                local map = { ['"'] = '"', ["\\"] = "\\", ["/"] = "/", b = "\b", f = "\f", n = "\n", r = "\r", t = "\t" }
                if not map[e] then decode_error(s, j, "bad escape") end
                out[#out + 1] = map[e]
                j = j + 2
            end
        else
            -- 一口气吃到下一个特殊字符
            local k = s:find('["\\]', j)
            if not k then decode_error(s, j, "unterminated string") end
            out[#out + 1] = s:sub(j, k - 1)
            j = k
        end
    end
end

local function parse_number(s, i)
    local num = s:match("^-?%d+%.?%d*[eE]?[-+]?%d*", i)
    if not num or num == "" then decode_error(s, i, "bad number") end
    local v = tonumber(num)
    if not v then decode_error(s, i, "bad number") end
    if math.type(v) == "float" and v == math.floor(v) and not num:find("[%.eE]") then
        v = math.tointeger(v) or v
    end
    return v, i + #num
end

local function parse_array(s, i)
    local out = {}
    i = skip_ws(s, i + 1)
    if s:sub(i, i) == "]" then return out, i + 1 end
    local n = 0
    while true do
        local v
        v, i = parse_value(s, i)
        n = n + 1
        out[n] = v
        i = skip_ws(s, i)
        local c = s:sub(i, i)
        if c == "]" then return out, i + 1 end
        if c ~= "," then decode_error(s, i, "expected , or ]") end
        i = skip_ws(s, i + 1)
    end
end

local function parse_object(s, i)
    local out = {}
    i = skip_ws(s, i + 1)
    if s:sub(i, i) == "}" then return out, i + 1 end
    while true do
        if s:sub(i, i) ~= '"' then decode_error(s, i, "expected string key") end
        local k
        k, i = parse_string(s, i)
        i = skip_ws(s, i)
        if s:sub(i, i) ~= ":" then decode_error(s, i, "expected :") end
        i = skip_ws(s, i + 1)
        local v
        v, i = parse_value(s, i)
        out[k] = v
        i = skip_ws(s, i)
        local c = s:sub(i, i)
        if c == "}" then return out, i + 1 end
        if c ~= "," then decode_error(s, i, "expected , or }") end
        i = skip_ws(s, i + 1)
    end
end

parse_value = function(s, i)
    i = skip_ws(s, i)
    local c = s:sub(i, i)
    if c == "{" then return parse_object(s, i) end
    if c == "[" then return parse_array(s, i) end
    if c == '"' then return parse_string(s, i) end
    if c == "t" and s:sub(i, i + 3) == "true" then return true, i + 4 end
    if c == "f" and s:sub(i, i + 4) == "false" then return false, i + 5 end
    if c == "n" and s:sub(i, i + 3) == "null" then return nil, i + 4 end
    if c == "-" or c:match("%d") then return parse_number(s, i) end
    decode_error(s, i, "unexpected character '" .. c .. "'")
end

local function pure_decode(s)
    if type(s) ~= "string" then return nil, "not a string" end
    local ok, v, i = pcall(parse_value, s, 1)
    if not ok then return nil, v end
    i = skip_ws(s, i)
    if i <= #s then return nil, "trailing garbage at byte " .. i end
    return v
end

-- ===== 对外 =====
function M.encode(v)
    if native_encode then
        local ok, s = pcall(native_encode, v)
        if ok and type(s) == "string" then return s end
    end
    return pure_encode(v)
end

-- 返回 value | nil, err
function M.decode(s)
    if type(s) ~= "string" or s == "" then return nil, "empty" end
    if native_decode then
        local ok, v, err = pcall(native_decode, s)
        if ok and v ~= nil then return v end
        if ok and err then return nil, err end
        -- 原生版把 "null" 解成 nil 且无错误：与纯 Lua 版一致
        if ok then return nil end
    end
    return pure_decode(s)
end

M.pure = { encode = pure_encode, decode = pure_decode }
M.native = native_encode ~= nil

return M
