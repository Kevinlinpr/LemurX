-- LemurX · luakit-compatible library · adblock
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "adblock" module API. No luakit code is used.
--
-- 基于 Adblock Plus 过滤列表的请求拦截器（浏览器进程侧）。
--   * 从 luakit.data_dir/adblock/*.txt 读取过滤列表，解析 ABP 语法：
--     普通拦截规则、@@ 例外规则、|| 域名锚、| 首尾锚、^ 分隔符、* 通配、
--     $ 选项（资源类型、third-party、domain=、match-case）。
--     元素隐藏（##）与 /regex/ 规则不支持，计入 ignored。
--   * 规则编译为 Lua pattern，并按"记号"（token）分桶做快速匹配；
--     编译结果通过 ipc_channel("adblock_wm") 同步到渲染进程的 adblock_wm.lua，
--     由其在 send-request 阶段拦截子资源；本模块自己只在 navigation-request
--     阶段处理 $document 类型的顶层导航拦截。
--
-- 公开接口：adblock.load / list_set_enabled / whitelist_domain_access /
--   subscriptions / rules / enabled / compile_rule / build_index / make_context / match
-- 命令：:adblock-reload(:abr) :adblock-enable(:abe) :adblock-disable(:abd)
--       :adblock-list-enable(:able) :adblock-list-disable(:abld)
-- 设置：adblock.enabled
-- 模块信号：rules-updated、list-loaded(sub)、navigation-blocked(view, uri, rule)、enabled-changed(bool)
-- IPC (adblock_wm)：→ enable(bool) / update_rules({block=,allow=}) / whitelist({domains})
--                   ← ready(pid)

local lousy = require("lousy")
local settings = require("settings")
local modes = require("modes")
local webview = require("webview")
local window = require("window")

local _M = {}
lousy.signal.setup(_M, true)

local wm = require_web_module("adblock_wm")

local SENTINEL = "\1"
local SEP_CLASS = "[^%w%-%.%%_]"

-- ---------------------------------------------------------------------------
-- 设置
-- ---------------------------------------------------------------------------
settings.register_settings({
    ["adblock.enabled"] = {
        type = "boolean",
        default = true,
        desc = "Whether request filtering with the loaded Adblock Plus lists is active.",
    },
})

-- ---------------------------------------------------------------------------
-- URI 工具
-- ---------------------------------------------------------------------------

-- 拆 URI：返回 scheme, 去掉 scheme:// 与 user@ 之后的剩余部分, host
local function split_uri(uri)
    local scheme, rest = uri:match("^(%a[%w+.-]*)://(.*)$")
    if not scheme then return nil, uri, nil end
    local after_at = rest:match("^[^/?#]*@(.*)$")
    if after_at then rest = after_at end
    local host = rest:match("^([^/?#:]*)")
    return scheme, rest, host
end
_M.split_uri = split_uri

local KNOWN_SLD = {
    co = true, com = true, net = true, org = true, gov = true, edu = true, ac = true,
    ["or"] = true, ne = true, go = true, mil = true,
}

-- 近似"可注册域"：a.b.c → b.c；a.b.co.uk → b.co.uk
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
_M.base_domain = base_domain

-- host 是否属于 domain（相等或以 .domain 结尾）
local function host_in_domain(host, domain)
    if not host or not domain then return false end
    if host == domain then return true end
    return host:sub(-(#domain + 1)) == "." .. domain
end

local function host_in_list(host, list)
    for _, d in ipairs(list) do
        if host_in_domain(host, d) then return true end
    end
    return false
end

-- 由请求头推断资源类型（Chromium 会带 Sec-Fetch-Dest）
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
    return nil
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
_M.infer_type = infer_type

-- 匹配上下文：把一次请求需要的所有派生量算好，供多条规则复用
-- opts.type 可强制类型（导航拦截时传 "document"）
function _M.make_context(uri, page_uri, headers, opts)
    opts = opts or {}
    local _, rest, host = split_uri(uri)
    local page_host = nil
    if page_uri then
        local _, _, ph = split_uri(page_uri)
        page_host = ph
    end
    local lower = uri:lower()
    local tokens = {}
    for tok in lower:gmatch("%w+") do tokens[#tokens + 1] = tok end
    local third
    if page_host and host then
        third = base_domain(page_host) ~= base_domain(host)
    else
        third = false
    end
    return {
        uri = uri,
        u = uri .. SENTINEL,
        ul = lower .. SENTINEL,
        s = "." .. (rest or uri) .. SENTINEL,
        sl = "." .. (rest or uri):lower() .. SENTINEL,
        host = host or "",
        page_host = page_host or host or "",
        tokens = tokens,
        type = opts.type or infer_type(uri, headers),
        third = third,
    }
end

-- ---------------------------------------------------------------------------
-- 规则编译
-- ---------------------------------------------------------------------------
local TYPE_OPTIONS = {
    script = true, image = true, stylesheet = true, object = true, xmlhttprequest = true,
    subdocument = true, document = true, font = true, media = true, websocket = true,
    ping = true, other = true, ["object-subrequest"] = true, background = true,
}
local TYPE_ALIAS = { ["object-subrequest"] = "object", background = "image" }
local UNSUPPORTED_OPTIONS = {
    popup = true, elemhide = true, generichide = true, genericblock = true, csp = true,
    rewrite = true, redirect = true, ["redirect-rule"] = true, ["removeparam"] = true,
    important = false, -- important 只影响优先级，忽略但不丢规则
}

local MAGIC = { ["("] = true, [")"] = true, ["."] = true, ["%"] = true, ["+"] = true, ["-"] = true,
                ["?"] = true, ["["] = true, ["]"] = true, ["$"] = true, ["^"] = true }

-- ABP 地址模式 → Lua pattern。返回 pattern, host_anchored
local function pattern_to_lua(pat, case_sensitive)
    local host_anchored, start_anchored, end_anchored = false, false, false
    if pat:sub(1, 2) == "||" then
        host_anchored = true
        pat = pat:sub(3)
    elseif pat:sub(1, 1) == "|" then
        start_anchored = true
        pat = pat:sub(2)
    end
    if pat:sub(-1) == "|" then
        end_anchored = true
        pat = pat:sub(1, -2)
    end
    pat = pat:gsub("%*+", "*")
    if host_anchored and pat:sub(1, 1) == "*" then
        -- "||*..." 没有可用的域名锚，退化为普通规则
        host_anchored = false
    end
    if not case_sensitive then pat = pat:lower() end
    local out = {}
    if host_anchored then
        out[#out + 1] = "^[^/?#]-%."
    elseif start_anchored then
        out[#out + 1] = "^"
    end
    for i = 1, #pat do
        local c = pat:sub(i, i)
        if c == "*" then
            out[#out + 1] = ".-"
        elseif c == "^" then
            out[#out + 1] = SEP_CLASS
        elseif MAGIC[c] then
            out[#out + 1] = "%" .. c
        else
            out[#out + 1] = c
        end
    end
    if end_anchored then out[#out + 1] = SENTINEL end
    return table.concat(out), host_anchored, start_anchored, end_anchored
end

-- 从模式里挑出两侧都有边界的记号（用于索引）
local function bounded_tokens(pat)
    local start_anchor = false
    if pat:sub(1, 2) == "||" then
        start_anchor = true
        pat = pat:sub(3)
    elseif pat:sub(1, 1) == "|" then
        start_anchor = true
        pat = pat:sub(2)
    end
    local end_anchor = false
    if pat:sub(-1) == "|" then
        end_anchor = true
        pat = pat:sub(1, -2)
    end
    pat = pat:lower()
    local toks = {}
    local pos = 1
    while true do
        local s, e = pat:find("%w+", pos)
        if not s then break end
        local before = s > 1 and pat:sub(s - 1, s - 1) or nil
        local after = e < #pat and pat:sub(e + 1, e + 1) or nil
        local ok_before = (before == nil and start_anchor) or (before ~= nil and before ~= "*")
        local ok_after = (after == nil and end_anchor) or (after ~= nil and after ~= "*")
        if ok_before and ok_after then toks[#toks + 1] = pat:sub(s, e) end
        pos = e + 1
    end
    return toks
end

-- 编译一行过滤规则。返回 rule, is_exception ；不支持 / 无效返回 nil, reason
function _M.compile_rule(line)
    if type(line) ~= "string" then return nil, "not a string" end
    line = line:gsub("^%s+", ""):gsub("%s+$", "")
    if line == "" then return nil, "empty" end
    local first = line:sub(1, 1)
    if first == "!" or first == "[" then return nil, "comment" end
    if line:find("##", 1, true) or line:find("#@#", 1, true) or line:find("#?#", 1, true)
        or line:find("#$#", 1, true) then
        return nil, "element hiding"
    end
    local exception = false
    if line:sub(1, 2) == "@@" then
        exception = true
        line = line:sub(3)
    end
    if line:sub(1, 1) == "/" and line:sub(-1) == "/" and #line > 2 then
        return nil, "regex rule"
    end
    local pattern, options = line, nil
    local dollar = line:match("^.*()%$")
    if dollar and dollar > 1 then
        local opt_text = line:sub(dollar + 1)
        if opt_text:match("^[%w,~=|%.%-_%*:]*$") then
            pattern = line:sub(1, dollar - 1)
            options = opt_text
        end
    end
    if pattern == "" then return nil, "empty pattern" end

    local rule = { s = (exception and "@@" or "") .. line }
    if options then
        for opt in options:gmatch("[^,]+") do
            local neg = false
            if opt:sub(1, 1) == "~" then
                neg = true
                opt = opt:sub(2)
            end
            local key, val = opt:match("^([^=]+)=(.*)$")
            key = (key or opt):lower()
            if key == "domain" then
                for d in (val or ""):gmatch("[^|]+") do
                    if d:sub(1, 1) == "~" then
                        rule.nd = rule.nd or {}
                        rule.nd[#rule.nd + 1] = d:sub(2):lower()
                    else
                        rule.d = rule.d or {}
                        rule.d[#rule.d + 1] = d:lower()
                    end
                end
            elseif key == "third-party" or key == "3p" then
                rule.tp = not neg
            elseif key == "first-party" or key == "1p" then
                rule.tp = neg
            elseif key == "match-case" then
                rule.cs = true
            elseif TYPE_OPTIONS[key] then
                key = TYPE_ALIAS[key] or key
                if neg then
                    rule.nt = rule.nt or {}
                    rule.nt[key] = true
                else
                    rule.t = rule.t or {}
                    rule.t[key] = true
                end
            elseif UNSUPPORTED_OPTIONS[key] then
                return nil, "unsupported option " .. key
            elseif UNSUPPORTED_OPTIONS[key] == nil then
                return nil, "unknown option " .. key
            end
        end
    end
    local lua_pat, host_anchored = pattern_to_lua(pattern, rule.cs)
    rule.p = lua_pat
    if host_anchored then rule.h = true end
    rule.toks = bounded_tokens(pattern)
    return rule, exception
end

-- ---------------------------------------------------------------------------
-- 索引与匹配
-- ---------------------------------------------------------------------------

-- 把规则数组建成 { by_tok = {tok = {rule}}, generic = {rule} }
function _M.build_index(rules)
    local idx = { by_tok = {}, generic = {}, count = 0 }
    for _, r in ipairs(rules) do
        local best, best_size = nil, math.huge
        for _, tok in ipairs(r.toks or {}) do
            local bucket = idx.by_tok[tok]
            local size = bucket and #bucket or 0
            if size < best_size or (size == best_size and best and #tok > #best) then
                best, best_size = tok, size
            end
        end
        if best then
            r.k = best
            idx.by_tok[best] = idx.by_tok[best] or {}
            table.insert(idx.by_tok[best], r)
        else
            idx.generic[#idx.generic + 1] = r
        end
        idx.count = idx.count + 1
    end
    return idx
end

local function rule_applies(r, ctx)
    if r.t then
        if not r.t[ctx.type] then return false end
    elseif ctx.type == "document" then
        -- 没写类型的规则不拦顶层文档
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
_M.rule_applies = rule_applies

-- 在索引中找第一条命中的规则
function _M.match(idx, ctx)
    if not idx then return nil end
    local seen = {}
    for _, tok in ipairs(ctx.tokens) do
        local bucket = idx.by_tok[tok]
        if bucket and not seen[tok] then
            seen[tok] = true
            for _, r in ipairs(bucket) do
                if rule_applies(r, ctx) then return r end
            end
        end
    end
    for _, r in ipairs(idx.generic) do
        if rule_applies(r, ctx) then return r end
    end
    return nil
end

-- 完整裁决：返回 "block", rule / "allow", rule / nil
function _M.decide(uri, page_uri, headers, opts)
    local ctx = _M.make_context(uri, page_uri, headers, opts)
    local block = _M.match(_M.block_index, ctx)
    if not block then return nil end
    local allow = _M.match(_M.allow_index, ctx)
    if allow then return "allow", allow end
    return "block", block
end

-- ---------------------------------------------------------------------------
-- 订阅（过滤列表文件）
-- ---------------------------------------------------------------------------
local subscriptions = {}     -- title -> sub
local order = {}             -- 按加载顺序的 title 列表
local merged = { block = {}, allow = {} }
local session_whitelist = {}
local list_state = nil       -- title -> enabled（持久化）

local function adblock_dir()
    return luakit.data_dir .. "/adblock"
end
_M.dir = adblock_dir()

local function state_path() return adblock_dir() .. "/lists.state" end

local function read_file(path)
    if lousy.load then
        local ok, s = pcall(lousy.load, path)
        if ok and type(s) == "string" then return s end
    end
    local f = io.open(path, "rb")
    if not f then return nil end
    local s = f:read("a")
    f:close()
    return s
end

local function load_state()
    if list_state then return list_state end
    list_state = {}
    local s = read_file(state_path())
    if s then
        for line in s:gmatch("[^\n]+") do
            local title, flag = line:match("^(.-)\t([01])$")
            if title then list_state[title] = (flag == "1") end
        end
    end
    return list_state
end

local function save_state()
    local out = {}
    for title, on in pairs(list_state or {}) do
        out[#out + 1] = title .. "\t" .. (on and "1" or "0")
    end
    table.sort(out)
    local f = io.open(state_path(), "wb")
    if not f then return false end
    f:write(table.concat(out, "\n"), "\n")
    f:close()
    return true
end

local function parse_list(text, sub)
    sub.block, sub.allow = {}, {}
    sub.black, sub.white, sub.ignored = 0, 0, 0
    for line in text:gmatch("[^\r\n]+") do
        local meta_key, meta_val = line:match("^!%s*([%w ]+):%s*(.-)%s*$")
        if meta_key then
            local k = meta_key:lower()
            if k == "title" and meta_val ~= "" then sub.title_hint = meta_val end
            if (k == "homepage" or k == "redirect") and meta_val ~= "" then sub.uri = sub.uri or meta_val end
        else
            local rule, exc = _M.compile_rule(line)
            if rule then
                if exc then
                    sub.allow[#sub.allow + 1] = rule
                    sub.white = sub.white + 1
                else
                    sub.block[#sub.block + 1] = rule
                    sub.black = sub.black + 1
                end
            elseif exc ~= "comment" and exc ~= "empty" then
                sub.ignored = sub.ignored + 1
            end
        end
    end
end

local function rebuild_merged()
    merged = { block = {}, allow = {} }
    for _, title in ipairs(order) do
        local sub = subscriptions[title]
        if sub and sub.enabled then
            for _, r in ipairs(sub.block) do merged.block[#merged.block + 1] = r end
            for _, r in ipairs(sub.allow) do merged.allow[#merged.allow + 1] = r end
        end
    end
    _M.block_index = _M.build_index(merged.block)
    _M.allow_index = _M.build_index(merged.allow)
end

-- 发给渲染进程的紧凑规则表（去掉 toks，只留 k）
local function wire_rules(list)
    local out = {}
    for i, r in ipairs(list) do
        out[i] = { p = r.p, h = r.h, cs = r.cs, t = r.t, nt = r.nt, tp = r.tp, d = r.d, nd = r.nd, k = r.k, s = r.s }
    end
    return out
end

local function sync(view)
    local payload = { block = wire_rules(merged.block), allow = wire_rules(merged.allow) }
    if view then
        wm:emit_signal(view, "update_rules", payload)
        wm:emit_signal(view, "enable", _M.enabled)
        wm:emit_signal(view, "whitelist", session_whitelist)
    else
        wm:emit_signal("update_rules", payload)
        wm:emit_signal("enable", _M.enabled)
        wm:emit_signal("whitelist", session_whitelist)
    end
end

local function load_file(path, reload)
    local name = path:match("([^/]+)$") or path
    local title = name:gsub("%.txt$", "")
    local existing = subscriptions[title]
    if existing and not reload then return existing end
    local text = read_file(path)
    if not text then
        msg.warn("adblock: cannot read %s", path)
        return nil
    end
    local sub = existing or { title = title, path = path }
    sub.path = path
    parse_list(text, sub)
    local state = load_state()
    if sub.enabled == nil then
        if state[title] ~= nil then sub.enabled = state[title] else sub.enabled = true end
    end
    if not existing then
        subscriptions[title] = sub
        order[#order + 1] = title
    end
    msg.info("adblock: loaded %s (%d block, %d allow, %d ignored)", title, sub.black, sub.white, sub.ignored)
    _M.emit_signal("list-loaded", sub)
    return sub
end

-- adblock.load(reload, single_list, no_sync)
function _M.load(reload, single_list, no_sync)
    local dir = adblock_dir()
    pcall(lfs.mkdir, dir)
    if single_list then
        local path = single_list
        if not path:find("/", 1, true) then path = dir .. "/" .. path end
        load_file(path, reload)
    else
        local ok, iter, state = pcall(lfs.dir, dir)
        if ok and iter then
            local names = {}
            for name in iter, state do
                if name:match("%.txt$") then names[#names + 1] = name end
            end
            table.sort(names)
            for _, name in ipairs(names) do load_file(dir .. "/" .. name, reload) end
        end
    end
    rebuild_merged()
    if not no_sync then sync() end
    _M.emit_signal("rules-updated")
end

-- adblock.list_set_enabled(a, enabled)：a 为序号或标题
function _M.list_set_enabled(a, enabled)
    local title = a
    if type(a) == "number" then title = order[a] end
    local sub = title and subscriptions[title]
    if not sub then return false, "no such list: " .. tostring(a) end
    sub.enabled = enabled and true or false
    load_state()[sub.title] = sub.enabled
    save_state()
    rebuild_merged()
    sync()
    _M.emit_signal("rules-updated")
    return true
end

-- 本次会话放行某个域（导航与子资源都不再拦）
function _M.whitelist_domain_access(domain)
    if type(domain) ~= "string" or domain == "" then return end
    domain = domain:lower()
    for _, d in ipairs(session_whitelist) do
        if d == domain then return end
    end
    session_whitelist[#session_whitelist + 1] = domain
    wm:emit_signal("whitelist", session_whitelist)
end

function _M.is_whitelisted(host)
    return host_in_list(host or "", session_whitelist)
end

-- 有序的订阅列表（chrome 页面用）
function _M.list_subscriptions()
    local out = {}
    for i, title in ipairs(order) do
        local s = subscriptions[title]
        out[#out + 1] = { index = i, title = s.title, path = s.path, uri = s.uri, enabled = s.enabled,
                          black = s.black, white = s.white, ignored = s.ignored }
    end
    return out
end

-- ---------------------------------------------------------------------------
-- 模块属性：enabled / subscriptions / rules
-- ---------------------------------------------------------------------------
local function set_enabled(on)
    on = on and true or false
    settings.set_setting("adblock.enabled", on)
    wm:emit_signal("enable", on)
    _M.emit_signal("enabled-changed", on)
end

setmetatable(_M, {
    __index = function(_, k)
        if k == "enabled" then return settings.get_setting("adblock.enabled") ~= false end
        if k == "subscriptions" then return subscriptions end
        if k == "rules" then return merged end
        if k == "whitelist" then return session_whitelist end
        return nil
    end,
    __newindex = function(t, k, v)
        if k == "enabled" then
            set_enabled(v)
        elseif k == "subscriptions" or k == "rules" then
            error("adblock." .. k .. " is read-only", 2)
        else
            rawset(t, k, v)
        end
    end,
})

-- ---------------------------------------------------------------------------
-- webview 钩子：顶层导航的 $document 拦截 + 新渲染进程同步
-- ---------------------------------------------------------------------------
local function on_navigation(view, uri, reason)
    if not _M.enabled then return end
    if type(uri) ~= "string" or not uri:match("^https?://") then return end
    local _, _, host = split_uri(uri)
    if _M.is_whitelisted(host) then return end
    local verdict, rule = _M.decide(uri, uri, nil, { type = "document" })
    if verdict == "block" then
        msg.info("adblock: navigation to %s blocked by %s", uri, rule.s)
        _M.emit_signal("navigation-blocked", view, uri, rule)
        local w = webview.window and webview.window(view)
        if w and w.warning then
            pcall(w.warning, w, "adblock: navigation blocked (" .. rule.s .. ")")
        end
        return false
    end
end

webview.add_signal("init", function(view)
    view:add_signal("navigation-request", on_navigation)
    view:add_signal("web-extension-loaded", function(v) sync(v) end)
end)

wm:add_signal("ready", function(_, pid)
    msg.verbose("adblock: renderer %s ready, syncing rules", tostring(pid))
    sync()
end)

settings.add_signal("setting-changed", function(_, ev)
    if ev and ev.key == "adblock.enabled" then
        wm:emit_signal("enable", ev.value and true or false)
        _M.emit_signal("enabled-changed", ev.value and true or false)
    end
end)

-- ---------------------------------------------------------------------------
-- 命令
-- ---------------------------------------------------------------------------
local function cmd_arg(o)
    if type(o) == "string" then return o end
    if type(o) == "table" then return o.arg end
    return nil
end

local function list_toggle_cmd(w, o, enabled)
    local arg = cmd_arg(o)
    arg = arg and arg:gsub("^%s+", ""):gsub("%s+$", "") or ""
    if arg == "" then
        w:error("usage: :adblock-list-" .. (enabled and "enable" or "disable") .. " <number|title>")
        return
    end
    local key = tonumber(arg) or arg
    local ok, err = _M.list_set_enabled(key, enabled)
    if ok then
        w:notify(("adblock: list %s %s"):format(tostring(arg), enabled and "enabled" or "disabled"))
    else
        w:error("adblock: " .. tostring(err))
    end
end

modes.add_cmds({
    { ":adblock-reload, :abr", "Re-read every filter list from the adblock directory.",
        function (w)
            _M.load(true)
            w:notify(("adblock: %d list(s) reloaded"):format(#order))
        end },
    { ":adblock-list-enable, :able", "Turn a filter list on (by number or title).",
        function (w, o) list_toggle_cmd(w, o, true) end },
    { ":adblock-list-disable, :abld", "Turn a filter list off (by number or title).",
        function (w, o) list_toggle_cmd(w, o, false) end },
    { ":adblock-enable, :abe", "Activate request filtering.",
        function (w) set_enabled(true) w:notify("adblock: enabled") end },
    { ":adblock-disable, :abd", "Deactivate request filtering.",
        function (w) set_enabled(false) w:notify("adblock: disabled") end },
})

-- 初次加载
_M.load(false)

return _M
