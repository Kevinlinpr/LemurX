-- LemurX · luakit-compatible library · lousy.uri
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.uri" module API. No luakit code is used.
--
-- URI 解析与拆分。parse() 优先走内核 soup.parse_uri（GURL），拿不到的字段
-- （query/fragment/port/user）用这里的纯 Lua 解析补齐；返回的表带 __tostring，
-- 修改 .opts（查询参数表）后 tostring() 会重新拼出 query。

local util = require("lousy.util")

local M = {}

local function soup_parse(s)
    local sp = rawget(_G, "soup")
    if sp and sp.parse_uri then
        local ok, t = pcall(sp.parse_uri, s)
        if ok and type(t) == "table" then return t end
    end
    return nil
end

-- 纯 Lua 的 RFC 3986 风格切分
local function local_parse(s)
    local rest = s
    local out = {}
    local frag_at = rest:find("#", 1, true)
    if frag_at then
        out.fragment = rest:sub(frag_at + 1)
        rest = rest:sub(1, frag_at - 1)
    end
    local q_at = rest:find("?", 1, true)
    if q_at then
        out.query = rest:sub(q_at + 1)
        rest = rest:sub(1, q_at - 1)
    end
    local scheme, after = rest:match("^(%a[%w+.-]*):(.*)$")
    if scheme then
        out.scheme = scheme:lower()
        rest = after
    end
    if rest:sub(1, 2) == "//" then
        rest = rest:sub(3)
        local authority, path = rest:match("^([^/]*)(.*)$")
        out.path = path
        local userinfo, hostport = authority:match("^(.*)@(.*)$")
        if userinfo then
            local user, pass = userinfo:match("^([^:]*):(.*)$")
            out.user = user or userinfo
            out.password = pass
        else
            hostport = authority
        end
        local host6, port6 = hostport:match("^(%[[^%]]*%])(:?%d*)$")
        if host6 then
            out.host = host6
            if port6 ~= "" then out.port = tonumber(port6:sub(2)) end
        else
            local host, port = hostport:match("^(.-):(%d+)$")
            if host then
                out.host = host
                out.port = tonumber(port)
            else
                out.host = hostport
            end
        end
        if out.host then out.host = out.host:lower() end
    else
        out.path = rest
    end
    if out.path == "" and out.host then out.path = "/" end
    return out
end

-- =====================================================================
-- 查询串
-- =====================================================================
local function decode_component(s)
    s = s:gsub("+", " ")
    return (s:gsub("%%(%x%x)", function(h) return string.char(tonumber(h, 16)) end))
end

local function encode_component(s)
    return (tostring(s):gsub("[^%w%-_%.~]", function(c)
        return string.format("%%%02X", c:byte())
    end))
end

function M.parse_query(query)
    local out = {}
    if type(query) ~= "string" or query == "" then return out end
    for pair in query:gmatch("[^&;]+") do
        local k, v = pair:match("^([^=]*)=(.*)$")
        if not k then k, v = pair, "" end
        k = decode_component(k)
        if k ~= "" then out[k] = decode_component(v) end
    end
    return out
end

function M.build_query(t)
    if type(t) ~= "table" then return "" end
    local parts = {}
    for _, k in ipairs(util.table.keys(t)) do
        local v = t[k]
        if v ~= nil and v ~= false then
            if v == true then v = "" end
            parts[#parts + 1] = encode_component(k) .. "=" .. encode_component(v)
        end
    end
    return table.concat(parts, "&")
end

-- =====================================================================
-- URI 表
-- =====================================================================
local uri_mt = {}

local function assemble(t)
    local scheme = t.scheme
    local out = {}
    if scheme and scheme ~= "" then
        out[#out + 1] = scheme .. ":"
    end
    if t.host ~= nil or (scheme == "file") then
        out[#out + 1] = "//"
        if t.user and t.user ~= "" then
            out[#out + 1] = t.user
            if t.password and t.password ~= "" then out[#out + 1] = ":" .. t.password end
            out[#out + 1] = "@"
        end
        out[#out + 1] = t.host or ""
        local port = tonumber(t.port)
        if port and port > 0 then out[#out + 1] = ":" .. string.format("%d", port) end
    end
    local path = t.path or ""
    if t.host ~= nil and path ~= "" and path:sub(1, 1) ~= "/" then path = "/" .. path end
    out[#out + 1] = path
    -- 有 opts 表时以它为准（允许调用方增删查询参数后重新 tostring）
    local query = t.query
    if type(t.opts) == "table" then
        local built = M.build_query(t.opts)
        query = built ~= "" and built or nil
    end
    if query and query ~= "" then out[#out + 1] = "?" .. query end
    if t.fragment and t.fragment ~= "" then out[#out + 1] = "#" .. t.fragment end
    return table.concat(out)
end

uri_mt.__tostring = assemble
uri_mt.__index = { tostring = assemble }

function M.tostring(t)
    if type(t) ~= "table" then return tostring(t) end
    return assemble(t)
end

function M.parse(uri)
    if type(uri) ~= "string" then return nil end
    local s = util.string.strip(uri)
    if s == "" then return nil end
    local t = local_parse(s)
    local sp = soup_parse(s)
    if not sp then
        if not t.scheme and not t.host then return nil end
    else
        -- 本地解析为主；内核（GURL）结果只用来补本地没拿到、且看起来干净的字段
        local function clean(v)
            return type(v) == "string" and v ~= "" and not v:find("[?#@]")
        end
        for _, k in ipairs({ "scheme", "host", "path" }) do
            if (t[k] == nil or t[k] == "") and clean(sp[k]) then t[k] = sp[k] end
        end
        for _, k in ipairs({ "query", "fragment", "user", "password" }) do
            if t[k] == nil and type(sp[k]) == "string" and sp[k] ~= "" then t[k] = sp[k] end
        end
        if t.port == nil and tonumber(sp.port) then t.port = tonumber(sp.port) end
    end
    if t.query == "" then t.query = nil end
    if t.fragment == "" then t.fragment = nil end
    t.opts = M.parse_query(t.query)
    return setmetatable(t, uri_mt)
end

function M.copy(t)
    if type(t) ~= "table" then return nil end
    local out = {}
    for k, v in pairs(t) do
        if type(v) == "table" then
            local inner = {}
            for ik, iv in pairs(v) do inner[ik] = iv end
            out[k] = inner
        else
            out[k] = v
        end
    end
    return setmetatable(out, getmetatable(t) or uri_mt)
end

-- =====================================================================
-- 判定与拆分
-- =====================================================================
local known_schemes = {
    http = true, https = true, ftp = true, file = true, about = true, luakit = true,
    chrome = true, data = true, javascript = true, mailto = true, magnet = true,
    tel = true, sms = true, ["view-source"] = true, gopher = true, gemini = true,
    ws = true, wss = true, lemurx = true, intent = true, market = true, blob = true,
}

local function expand_home(p)
    if p:sub(1, 1) ~= "~" then return p end
    local home = os.getenv("HOME")
    if not home then
        local L = rawget(_G, "luakit")
        home = L and L.data_dir or "/"
    end
    return home .. p:sub(2)
end

-- 本地路径（/、./、../、~）且文件存在 → file:// URI；否则 nil
function M.file_uri_for_path(s)
    if not s:match("^[/~%.]") then return nil end
    local p = expand_home(s)
    if p:sub(1, 1) ~= "/" then
        p = util.get_cwd() .. "/" .. p
    end
    if util.os.exists(p) or util.is_dir(p) then
        return "file://" .. p
    end
    return nil
end

function M.is_uri(s)
    if type(s) ~= "string" then return false end
    s = util.string.strip(s)
    if s == "" or s:find("%s") then return false end
    local scheme = s:match("^(%a[%w+.-]*):")
    if scheme then
        scheme = scheme:lower()
        if known_schemes[scheme] then return true end
        if s:match("^%a[%w+.-]*://") then return true end
        -- host:port 形式
        if s:match("^[%w%-%.]+:%d+") then return true end
        return false
    end
    if M.file_uri_for_path(s) then return true end
    -- 去掉路径/端口后的主机部分
    local host = s:match("^([^/:?#]+)")
    if not host then return false end
    if host == "localhost" then return true end
    if host:match("^%d+%.%d+%.%d+%.%d+$") then return true end
    if host:match("^%[[%x:]+%]$") then return true end
    -- 至少一个点，最后一段是字母 TLD
    if host:match("^[%w%-]+%.[%w%-%.]*%a%a+$") and not host:find("%.%.") then
        return true
    end
    return false
end

-- 把 is_uri 认可的字串规整成可导航的 URI（本地路径 → file://）
function M.normalize(s)
    local f = M.file_uri_for_path(s)
    if f then return f end
    return s
end

function M.split(s)
    local out = {}
    if type(s) ~= "string" then return out end
    local words = {}
    local function flush()
        if #words > 0 then
            out[#out + 1] = table.concat(words, " ")
            words = {}
        end
    end
    for tok in s:gmatch("%S+") do
        if M.is_uri(tok) then
            flush()
            out[#out + 1] = M.normalize(tok)
        else
            words[#words + 1] = tok
        end
    end
    flush()
    return out
end

-- 主机的全部父域：a.b.c → { "a.b.c", "b.c", "c" }
function M.domains_from_uri(uri)
    local host
    if type(uri) == "table" then
        host = uri.host
    elseif type(uri) == "string" then
        local t = M.parse(uri)
        host = t and t.host
        if not host then host = uri:match("^([%w%-%.]+)") end
    end
    if not host or host == "" then return {} end
    host = host:lower():gsub("^%.+", ""):gsub("%.+$", "")
    if host:match("^%d+%.%d+%.%d+%.%d+$") or host:sub(1, 1) == "[" then
        return { host }
    end
    local out = { host }
    local rest = host
    while true do
        local tail = rest:match("^[^%.]+%.(.+)$")
        if not tail then break end
        out[#out + 1] = tail
        rest = tail
    end
    return out
end

-- 去掉开头的 www.
function M.strip_www(host)
    if type(host) ~= "string" then return host end
    return (host:gsub("^www%.", ""))
end

function M.bare_domain(uri)
    local t = type(uri) == "table" and uri or M.parse(uri)
    if not t or not t.host then return nil end
    return M.strip_www(t.host)
end

return M
