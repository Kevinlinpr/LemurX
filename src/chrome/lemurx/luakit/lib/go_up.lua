-- LemurX · luakit-compatible library · go_up
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "go_up" module API. No luakit code is used.
--
-- 沿 URI 层级向上：先去掉 fragment，再去掉 query，再去掉一段路径，最后去掉最具体的子域。
--   gu  上一级（可带计数：3gu）   gU  直接到站点根
-- 公开接口：go_up.go_up(uri, n) go_up.go_upmost(uri) go_up.split(uri) go_up.join(parts)

local modes = require("modes")

local _M = {}

-- 手写拆分，不依赖 soup（保证 query/fragment 处理一致）
function _M.split(uri)
    uri = tostring(uri or "")
    local rest = uri
    local fragment = nil
    local hash = rest:find("#", 1, true)
    if hash then
        fragment = rest:sub(hash + 1)
        rest = rest:sub(1, hash - 1)
    end
    local query = nil
    local qm = rest:find("?", 1, true)
    if qm then
        query = rest:sub(qm + 1)
        rest = rest:sub(1, qm - 1)
    end
    local scheme, authority, path = rest:match("^(%a[%w+.-]*)://([^/]*)(.*)$")
    if not scheme then
        return { scheme = nil, authority = "", host = "", path = rest, query = query, fragment = fragment }
    end
    local userinfo, hostport = authority:match("^(.*)@(.*)$")
    hostport = hostport or authority
    local host, port = hostport:match("^(.*):(%d+)$")
    host = host or hostport
    return { scheme = scheme, authority = authority, userinfo = userinfo, host = host, port = port,
             path = path or "", query = query, fragment = fragment }
end

function _M.join(p)
    if not p.scheme then
        local s = p.path or ""
        if p.query then s = s .. "?" .. p.query end
        if p.fragment then s = s .. "#" .. p.fragment end
        return s
    end
    local out = p.scheme .. "://"
    if p.userinfo then out = out .. p.userinfo .. "@" end
    out = out .. (p.host or "")
    if p.port then out = out .. ":" .. p.port end
    local path = p.path or ""
    if path == "" then path = "/" end
    out = out .. path
    if p.query and p.query ~= "" then out = out .. "?" .. p.query end
    if p.fragment and p.fragment ~= "" then out = out .. "#" .. p.fragment end
    return out
end

local function is_ip(host)
    return host:match("^%d+%.%d+%.%d+%.%d+$") ~= nil or host:find(":", 1, true) ~= nil
end

local function up_once(p)
    if p.fragment then
        p.fragment = nil
        return true
    end
    if p.query then
        p.query = nil
        return true
    end
    local path = p.path or ""
    if path ~= "" and path ~= "/" then
        -- 去掉尾部斜杠后再删一段
        local trimmed = path:gsub("/+$", "")
        local parent = trimmed:match("^(.*/)[^/]*$") or "/"
        p.path = parent
        return true
    end
    local host = p.host or ""
    if host ~= "" and not is_ip(host) then
        local labels = {}
        for l in host:gmatch("[^%.]+") do labels[#labels + 1] = l end
        if #labels > 2 then
            table.remove(labels, 1)
            p.host = table.concat(labels, ".")
            p.path = "/"
            return true
        end
    end
    return false
end

function _M.go_up(uri, n)
    n = tonumber(n) or 1
    local p = _M.split(uri)
    for _ = 1, n do
        if not up_once(p) then break end
    end
    return _M.join(p)
end

function _M.go_upmost(uri)
    local p = _M.split(uri)
    p.fragment, p.query = nil, nil
    p.path = "/"
    return _M.join(p)
end

modes.add_binds("normal", {
    { "^gu$", "Go up one level in the URI (count = levels).", function (w, m)
        local uri = w.view and w.view.uri
        if not uri then return end
        local target = _M.go_up(uri, (m and m.count) or 1)
        if target ~= uri then w:navigate(target) end
    end, { count = 1 } },
    { "^gU$", "Go to the root of the current site.", function (w)
        local uri = w.view and w.view.uri
        if not uri then return end
        local target = _M.go_upmost(uri)
        if target ~= uri then w:navigate(target) end
    end },
})

return _M
