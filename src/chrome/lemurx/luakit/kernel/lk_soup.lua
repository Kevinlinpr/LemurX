-- soup 模块（对应 luakit clib/soup.c + common/clib/soup.h）
--   soup.parse_uri(str) -> { scheme, user, password, host, port, path, query, fragment } | nil
--   soup.uri_tostring(tbl) -> str
--   soup.proxy_uri        "default" | "no_proxy" | "http://host:port" …   （读写）
--   soup.accept_policy    "always" | "never" | "no_third_party"           （读写）
--   soup.cookies_storage  cookie 落盘路径（Chromium 自管 cookie 库，这里只记值）
-- 解析走 GURL，没写 scheme 默认补 http://。

local object = __lk.object
local N = __luakit

local state = {
    proxy_uri = "default",
    accept_policy = "no_third_party",
    cookies_storage = nil,
}

local function uri_tostring(t)
    if type(t) ~= "table" then error("soup.uri_tostring expects a table", 2) end
    local scheme = (t.scheme and t.scheme ~= "") and t.scheme or "http"
    local host = t.host
    if scheme == "file" and (not host) then host = "" end
    local out = scheme .. ":"
    if host ~= nil then
        out = out .. "//"
        if t.user and t.user ~= "" then
            out = out .. t.user .. "@"
        end
        out = out .. host
        local port = tonumber(t.port)
        if port and port > 0 then
            out = out .. ":" .. tostring(math.floor(port))
        end
    end
    local path = t.path or ""
    if host ~= nil and path ~= "" and path:sub(1, 1) ~= "/" then
        path = "/" .. path
    end
    out = out .. path
    if t.query and t.query ~= "" then out = out .. "?" .. t.query end
    if t.fragment and t.fragment ~= "" then out = out .. "#" .. t.fragment end
    return out
end

local function set_proxy(v)
    v = v == nil and "default" or v
    if type(v) ~= "string" then error("soup.proxy_uri must be a string", 3) end
    state.proxy_uri = v
    -- 代理生效点：P1 接 lemurx 网络层（ProxyConfigService）；目前记录并广播
    if lemurx.net and lemurx.net.setProxy then
        pcall(lemurx.net.setProxy, v)
    else
        msg.verbose("soup.proxy_uri = %s (network proxy hook not wired yet)", v)
    end
end

local function set_policy(v)
    if v ~= "always" and v ~= "never" and v ~= "no_third_party" then
        error("accept_policy must be one of 'always', 'never', 'no_third_party'", 3)
    end
    state.accept_policy = v
    if lemurx.cookie and lemurx.cookie.setAcceptPolicy then
        pcall(lemurx.cookie.setAcceptPolicy, v)
    else
        -- 用 Chromium 的 block_third_party_cookies 偏好近似
        local block3p = (v == "no_third_party")
        pcall(lemurx.prefs.set, "profile.block_third_party_cookies", block3p)
        if v == "never" then
            pcall(lemurx.prefs.set, "profile.default_content_setting_values.cookies", 2)
        else
            pcall(lemurx.prefs.set, "profile.default_content_setting_values.cookies", 1)
        end
    end
end

local soup = object.module("soup", {
    funcs = {
        parse_uri = function(s)
            if type(s) ~= "string" then error("soup.parse_uri expects a string", 2) end
            return N.uri_parse(s)
        end,
        uri_tostring = uri_tostring,
    },
    props = {
        proxy_uri = { get = function() return state.proxy_uri end, set = set_proxy },
        accept_policy = { get = function() return state.accept_policy end, set = set_policy },
        cookies_storage = {
            get = function() return state.cookies_storage end,
            set = function(v)
                if type(v) ~= "string" or v == "" then error("cookies_storage cannot be empty", 3) end
                state.cookies_storage = v
                pcall(__luakit.lfs_touch, v)
            end,
        },
    },
})

_G.soup = soup
return soup
