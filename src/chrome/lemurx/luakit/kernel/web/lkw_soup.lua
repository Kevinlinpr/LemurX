-- soup（渲染进程版，对应 luakit extension/clib/soup.c）：parse_uri / uri_tostring

local N = __luakit_web

local M = {}

function M.parse_uri(s)
    if type(s) ~= "string" then error("soup.parse_uri expects a string", 2) end
    return N.uri_parse(s)
end

function M.uri_tostring(t)
    if type(t) ~= "table" then error("soup.uri_tostring expects a table", 2) end
    local out = (t.scheme or "http") .. "://"
    if t.user then
        out = out .. t.user
        if t.password then out = out .. ":" .. t.password end
        out = out .. "@"
    end
    out = out .. (t.host or "")
    if t.port then out = out .. ":" .. tostring(t.port) end
    out = out .. (t.path or "/")
    if t.query and t.query ~= "" then out = out .. "?" .. t.query end
    if t.fragment and t.fragment ~= "" then out = out .. "#" .. t.fragment end
    return out
end

_G.soup = M
return M
