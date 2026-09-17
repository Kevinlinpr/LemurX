-- LemurX · luakit-compatible library · adblock_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "adblock_wm" module API. No luakit code is used.
--
-- adblock 的渲染进程半边：接收浏览器进程编译好的规则表，在每个 page 的
-- send-request 信号里裁决子资源请求；命中拦截规则且无例外规则时返回 false。
-- 第三方判定以 page.uri 的主域为第一方。本文件不依赖任何浏览器侧模块。
--
-- IPC (adblock_wm)：
--   ← enable(bool)                       开关
--   ← update_rules({ block = {rule}, allow = {rule} })
--        rule = { p = lua_pattern, h = host_anchored, cs = match_case,
--                 t = {type=true}, nt = {type=true}, tp = third_party|nil,
--                 d = {domains}, nd = {domains}, k = index_token|nil, s = source }
--   ← whitelist({ domains })             本会话放行的域
--   → ready(web_process_id)              模块加载完成，请求同步

local ui = ipc_channel("adblock_wm")

local SENTINEL = "\1"

local state = {
    enabled = true,
    block = { by_tok = {}, generic = {} },
    allow = { by_tok = {}, generic = {} },
    whitelist = {},
    nrules = 0,
}

-- ---------------------------------------------------------------------------
-- URI 工具（与 adblock.lua 同语义，独立实现以保持 web 模块自足）
-- ---------------------------------------------------------------------------
local function split_uri(uri)
    local scheme, rest = uri:match("^(%a[%w+.-]*)://(.*)$")
    if not scheme then return nil, uri, nil end
    local after_at = rest:match("^[^/?#]*@(.*)$")
    if after_at then rest = after_at end
    local host = rest:match("^([^/?#:]*)")
    return scheme, rest, host
end

local KNOWN_SLD = {
    co = true, com = true, net = true, org = true, gov = true, edu = true, ac = true,
    ["or"] = true, ne = true, go = true, mil = true,
}

local function base_domain(host)
    if not host or host == "" then return "" end
    if host:match("^[%d%.]+$") or host:find(":", 1, true) then return host end
    local labels = {}
    for l in host:gmatch("[^%.]+") do labels[#labels + 1] = l end
    local n = #labels
    if n <= 2 then return host end
    if KNOWN_SLD[labels[n - 1]] and #labels[n] == 2 then
        return labels[n - 2] .. "." .. labels[n - 1] .. "." .. labels[n]
    end
    return labels[n - 1] .. "." .. labels[n]
end

local function host_in_domain(host, domain)
    if host == domain then return true end
    return host:sub(-(#domain + 1)) == "." .. domain
end

local function host_in_list(host, list)
    for _, d in ipairs(list) do
        if host_in_domain(host, d) then return true end
    end
    return false
end

local DEST_TYPE = {
    script = "script", image = "image", style = "stylesheet", font = "font",
    iframe = "subdocument", frame = "subdocument", document = "document",
    empty = "xmlhttprequest", video = "media", audio = "media", track = "media",
    object = "object", embed = "object", websocket = "websocket", manifest = "other",
    worker = "script", sharedworker = "script", serviceworker = "script", xslt = "other",
}
local EXT_TYPE = {
    js = "script", mjs = "script", css = "stylesheet",
    png = "image", jpg = "image", jpeg = "image", gif = "image", webp = "image", svg = "image",
    ico = "image", bmp = "image", avif = "image",
    woff = "font", woff2 = "font", ttf = "font", otf = "font", eot = "font",
    mp4 = "media", webm = "media", mp3 = "media", ogg = "media", m4a = "media", m3u8 = "media",
    swf = "object", html = "subdocument", htm = "subdocument", json = "xmlhttprequest",
}

local function header(headers, name)
    if type(headers) ~= "table" then return nil end
    local v = headers[name]
    if v ~= nil then return v end
    local lname = name:lower()
    for k, x in pairs(headers) do
        if type(k) == "string" and k:lower() == lname then return x end
    end
end

local function infer_type(uri, headers)
    local dest = header(headers, "Sec-Fetch-Dest")
    if dest and DEST_TYPE[dest] then return DEST_TYPE[dest] end
    local accept = header(headers, "Accept")
    if type(accept) == "string" then
        if accept:find("text/css", 1, true) then return "stylesheet" end
        if accept:find("^image/") then return "image" end
        if accept:find("^text/html") then return "subdocument" end
        if accept:find("^video/") or accept:find("^audio/") then return "media" end
    end
    local path = uri:match("^%a[%w+.-]*://[^/?#]*([^?#]*)") or ""
    local ext = path:match("%.(%w+)$")
    if ext and EXT_TYPE[ext:lower()] then return EXT_TYPE[ext:lower()] end
    return "other"
end

local function make_context(uri, page_uri, headers, forced_type)
    local _, rest, host = split_uri(uri)
    local page_host
    if page_uri then
        local _, _, ph = split_uri(page_uri)
        page_host = ph
    end
    local lower = uri:lower()
    local tokens = {}
    for tok in lower:gmatch("%w+") do tokens[#tokens + 1] = tok end
    local third = false
    if page_host and host then third = base_domain(page_host) ~= base_domain(host) end
    return {
        u = uri .. SENTINEL,
        ul = lower .. SENTINEL,
        s = "." .. (rest or uri) .. SENTINEL,
        sl = "." .. (rest or uri):lower() .. SENTINEL,
        host = host or "",
        page_host = page_host or host or "",
        tokens = tokens,
        type = forced_type or infer_type(uri, headers),
        third = third,
    }
end

-- ---------------------------------------------------------------------------
-- 匹配
-- ---------------------------------------------------------------------------
local function build_index(rules)
    local idx = { by_tok = {}, generic = {} }
    for _, r in ipairs(rules or {}) do
        if type(r) == "table" and type(r.p) == "string" then
            if r.k then
                idx.by_tok[r.k] = idx.by_tok[r.k] or {}
                table.insert(idx.by_tok[r.k], r)
            else
                idx.generic[#idx.generic + 1] = r
            end
        end
    end
    return idx
end

local function rule_applies(r, ctx)
    if r.t then
        if not r.t[ctx.type] then return false end
    elseif ctx.type == "document" then
        return false
    end
    if r.nt and r.nt[ctx.type] then return false end
    if r.tp ~= nil and r.tp ~= ctx.third then return false end
    if r.d and not host_in_list(ctx.page_host, r.d) then return false end
    if r.nd and host_in_list(ctx.page_host, r.nd) then return false end
    local target
    if r.h then
        target = r.cs and ctx.s or ctx.sl
    else
        target = r.cs and ctx.u or ctx.ul
    end
    return target:find(r.p) ~= nil
end

local function match(idx, ctx)
    local seen = {}
    for _, tok in ipairs(ctx.tokens) do
        if not seen[tok] then
            seen[tok] = true
            local bucket = idx.by_tok[tok]
            if bucket then
                for _, r in ipairs(bucket) do
                    if rule_applies(r, ctx) then return r end
                end
            end
        end
    end
    for _, r in ipairs(idx.generic) do
        if rule_applies(r, ctx) then return r end
    end
    return nil
end

-- 页面级放行缓存：@@||site^$document 类例外让整页不再拦截
local page_allow_cache = {}   -- page_uri -> bool

local function page_is_exempt(page_uri)
    if not page_uri then return false end
    local cached = page_allow_cache[page_uri]
    if cached ~= nil then return cached end
    local _, _, host = split_uri(page_uri)
    local exempt = false
    if host and host_in_list(host, state.whitelist) then
        exempt = true
    else
        local ctx = make_context(page_uri, page_uri, nil, "document")
        exempt = match(state.allow, ctx) ~= nil
    end
    page_allow_cache[page_uri] = exempt
    return exempt
end

-- 供其它 web 模块 / 测试直接调用
local M = {}

function M.decide(uri, page_uri, headers)
    if not state.enabled then return nil end
    if type(uri) ~= "string" or not uri:match("^https?://") then return nil end
    if page_is_exempt(page_uri) then return nil end
    local ctx = make_context(uri, page_uri, headers)
    if host_in_list(ctx.host, state.whitelist) then return nil end
    local block = match(state.block, ctx)
    if not block then return nil end
    local allow = match(state.allow, ctx)
    if allow then return "allow", allow end
    return "block", block
end

function M.stats()
    return { enabled = state.enabled, rules = state.nrules, whitelist = #state.whitelist }
end

local function on_send_request(p, uri, headers)
    local verdict, rule = M.decide(uri, p.uri, headers)
    if verdict == "block" then
        msg.debug("adblock_wm: blocked %s (%s)", uri, rule.s or rule.p)
        return false
    end
end

luakit.add_signal("page-created", function(p)
    p:add_signal("send-request", on_send_request)
end)

-- ---------------------------------------------------------------------------
-- IPC
-- ---------------------------------------------------------------------------
ui:add_signal("enable", function(_, _, on)
    state.enabled = on and true or false
end)

ui:add_signal("update_rules", function(_, _, rules)
    rules = rules or {}
    state.block = build_index(rules.block)
    state.allow = build_index(rules.allow)
    state.nrules = #(rules.block or {}) + #(rules.allow or {})
    page_allow_cache = {}
    msg.verbose("adblock_wm: %d rules installed", state.nrules)
end)

ui:add_signal("whitelist", function(_, _, domains)
    state.whitelist = {}
    for _, d in ipairs(domains or {}) do
        if type(d) == "string" then state.whitelist[#state.whitelist + 1] = d:lower() end
    end
    page_allow_cache = {}
end)

ui:emit_signal("ready", luakit.web_process_id)

M.state = state
return M
