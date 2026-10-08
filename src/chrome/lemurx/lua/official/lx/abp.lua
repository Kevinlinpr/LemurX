-- lx.abp —— Adblock Plus / uBlock Origin 过滤列表引擎（纯 Lua，两个进程共用）。
--
-- 浏览器进程：compiler 把 EasyList 之类的列表文本编译成一张可 JSON 序列化的紧凑表；
-- 渲染进程：engine 拿这张表建索引，在 send-request 里对每个子资源请求做同步判定，
--           在 window-object-cleared 时给页面注入元素隐藏 CSS。
--
--   local abp = require("lx.abp")
--   local c = abp.compiler()
--   c:add(list_text, "easylist")          -- 可多次，hosts 文件格式也认
--   local compiled = c:finish()          -- 纯数据表，可 json 编码存盘 / 发给渲染进程
--
--   local e = abp.engine(compiled)
--   e:match(url, { type = "script", page = "www.site.com", initiator = "www.site.com" })
--       -> "block" | "allow" | nil, filter_index
--   e:page_flags("www.site.com") -> { document = bool, elemhide = bool, generichide = bool }
--   e:cosmetic_css("www.site.com") -> css 字符串（可能为空串）
--
-- 支持：|| | ^ * 通配、@@ 例外、$script/image/stylesheet/object/xmlhttprequest/subdocument/
--       document/media/font/websocket/ping/other、~type、third-party/3p/1p/first-party、
--       domain=a|~b、important、match-case、generichide/elemhide/document 例外、
--       ## 元素隐藏（含域名限定）、#@# 元素隐藏例外、hosts 文件（0.0.0.0 / 127.0.0.1）。
-- 不支持（跳过）：正则过滤器、$redirect/$csp/$removeparam/$header/$replace、
--       #?# / #$# / ##+js( 等程序化与脚本过滤器、##^ HTML 过滤器。
--
-- 索引策略沿用 uBlock：每条网络过滤器挑一个"完整 token"（两侧都是非字母数字或锚点），
-- 请求 URL 也切成 token，只测命中 token 的候选过滤器。纯域名过滤器（||host^ 无选项）
-- 单独放哈希集合，按后缀查，一次 O(域名段数)。

local util = require("lx.util")

local M = {}

-- ===== 标志位 =====
local F_EXCEPTION = 1
local F_HOST_ANCHOR = 2
local F_LEFT_ANCHOR = 4
local F_RIGHT_ANCHOR = 8
local F_THIRD_PARTY = 16
local F_FIRST_PARTY = 32
local F_IMPORTANT = 64
local F_MATCH_CASE = 128
local F_WILD = 256        -- 需要 Lua 模式匹配（含 * 或 ^）
local F_TRAIL_SEP = 512   -- 模式以 ^ 结尾（分隔符或地址结束）

M.flags = {
    EXCEPTION = F_EXCEPTION, HOST_ANCHOR = F_HOST_ANCHOR, LEFT_ANCHOR = F_LEFT_ANCHOR,
    RIGHT_ANCHOR = F_RIGHT_ANCHOR, THIRD_PARTY = F_THIRD_PARTY, FIRST_PARTY = F_FIRST_PARTY,
    IMPORTANT = F_IMPORTANT, MATCH_CASE = F_MATCH_CASE, WILD = F_WILD, TRAIL_SEP = F_TRAIL_SEP,
}

-- ===== 资源类型位 =====
local TYPES = {
    script = 1, image = 2, stylesheet = 4, object = 8, xmlhttprequest = 16,
    sub_frame = 32, document = 64, media = 128, font = 256, websocket = 512,
    ping = 1024, other = 2048,
}
M.types = TYPES
local TYPE_ALIASES = {
    css = "stylesheet", xhr = "xmlhttprequest", frame = "sub_frame", subdocument = "sub_frame",
    doc = "document", ["object-subrequest"] = "object", beacon = "ping", webrtc = "other",
    ["3p"] = "third-party", ["1p"] = "first-party", ["first-party"] = "first-party",
}
-- 未指定类型时匹配的集合：除 document 之外的一切
local TYPE_ALL_BUT_DOC = 0
for name, bit in pairs(TYPES) do
    if name ~= "document" then TYPE_ALL_BUT_DOC = TYPE_ALL_BUT_DOC | bit end
end

-- 这些选项在这里没有语义：整条过滤器丢弃（uBlock 对未知选项同样处理）
local UNSUPPORTED_OPTIONS = {
    redirect = true, ["redirect-rule"] = true, csp = true, removeparam = true, header = true,
    replace = true, urltransform = true, permissions = true, uritransform = true, rewrite = true,
    ["inline-script"] = true, ["inline-font"] = true, empty = true, mp4 = true, popunder = true,
    ["cname"] = true, ["strict1p"] = true, ["strict3p"] = true, ["denyallow"] = true,
    ["to"] = true, ["from"] = true, ["method"] = true, ["ipaddress"] = true, ["reason"] = true,
}
-- 认识但忽略（不影响匹配）
local IGNORED_OPTIONS = {
    popup = true, badfilter = true, genericblock = true, ["_"] = true, all = true,
    ["object-subrequest"] = false,
}

-- 常见但区分度差的 token：有别的 token 就不选它们
local WEAK_TOKENS = {
    http = true, https = true, www = true, com = true, net = true, org = true, html = true,
    js = true, css = true, png = true, gif = true, jpg = true, jpeg = true, php = true,
    cn = true, co = true, static = true, img = true, images = true, cdn = true, api = true,
}

-- ===== 小工具 =====
local function lower(s) return string.lower(s) end

local function to_lua_pattern(pat, flags)
    -- 把 ABP 通配模式转成 Lua 模式（不含左右锚点，由调用方按需加）
    local out = {}
    local n = #pat
    for i = 1, n do
        local c = pat:sub(i, i)
        if c == "*" then
            out[#out + 1] = ".-"
        elseif c == "^" then
            if i == n then
                -- 结尾分隔符：由 F_TRAIL_SEP 处理，这里不输出
            else
                out[#out + 1] = "[^%w_%-%.%%]"
            end
        elseif c:match("[%^%$%(%)%%%.%[%]%+%-%?]") then
            out[#out + 1] = "%" .. c
        else
            out[#out + 1] = c
        end
    end
    return table.concat(out)
end

-- 选 token：两侧都被非 [%w%%] 字符或锚点包围的最长 token
local function pick_token(pat, flags)
    local best, best_len, best_weak = nil, 0, true
    local n = #pat
    local host_anchor = (flags & F_HOST_ANCHOR) ~= 0
    local left_anchor = (flags & F_LEFT_ANCHOR) ~= 0
    local right_anchor = (flags & F_RIGHT_ANCHOR) ~= 0
    local i = 1
    while i <= n do
        local s, e = pat:find("[%w%%]+", i)
        if not s then break end
        local tok = pat:sub(s, e)
        local before = s > 1 and pat:sub(s - 1, s - 1) or ""
        local after = e < n and pat:sub(e + 1, e + 1) or ""
        local ok_before = (s == 1 and (host_anchor or left_anchor)) or (before ~= "" and before ~= "*")
        local ok_after = (e == n and right_anchor) or (after ~= "" and after ~= "*")
        if ok_before and ok_after and not tok:find("%%", 1, true) then
            local weak = WEAK_TOKENS[tok] or #tok < 2
            if best == nil or (best_weak and not weak) or (weak == best_weak and #tok > best_len) then
                best, best_len, best_weak = tok, #tok, weak
            end
        end
        i = e + 1
    end
    return best
end

-- ===== 编译器 =====
local Compiler = {}
Compiler.__index = Compiler

function M.compiler()
    local c = setmetatable({}, Compiler)
    c.hosts = {}          -- host -> true（纯域名拦截）
    c.ehosts = {}         -- host -> true（纯域名例外，@@||host^）
    c.fp, c.ff, c.ft, c.fd = {}, {}, {}, {}
    c.idx = {}            -- token -> {i,...}
    c.generic = {}        -- 无 token 的过滤器
    c.cg = {}             -- 通用元素隐藏选择器（去重）
    c.cg_seen = {}
    c.cd = {}             -- domain -> {selectors}
    c.cx = {}             -- domain -> {selectors}（例外）
    c.doc = {}            -- $document 例外 host 列表
    c.eh = {}             -- $elemhide 例外
    c.gh = {}             -- $generichide 例外
    c.stats = { lines = 0, network = 0, hosts = 0, cosmetic = 0, skipped = 0, lists = {} }
    return c
end

local function add_host_set(set, host)
    host = lower(host)
    if host == "" or host:find("[^%w%.%-_]") then return false end
    set[host] = true
    return true
end

local function parse_options(optstr)
    -- 返回 flags_add, typemask, domains, err
    local flags, types, neg_types, domains, site = 0, 0, 0, nil, nil
    local has_type = false
    for raw in optstr:gmatch("[^,]+") do
        local opt = raw
        local neg = false
        if opt:sub(1, 1) == "~" then
            neg = true
            opt = opt:sub(2)
        end
        local name, value = opt:match("^([%w_%-]+)=(.*)$")
        name = name or opt
        name = lower(name)
        name = TYPE_ALIASES[name] or name
        if name == "elemhide" or name == "generichide" or name == "document" or name == "specifichide" then
            -- 站点级例外：由调用方单独处理（第 5 个返回值给出名字）
            site = name
        elseif TYPES[name] then
            has_type = true
            if neg then neg_types = neg_types | TYPES[name] else types = types | TYPES[name] end
        elseif name == "third-party" then
            flags = flags | (neg and F_FIRST_PARTY or F_THIRD_PARTY)
        elseif name == "first-party" then
            flags = flags | (neg and F_THIRD_PARTY or F_FIRST_PARTY)
        elseif name == "important" then
            flags = flags | F_IMPORTANT
        elseif name == "match-case" then
            flags = flags | F_MATCH_CASE
        elseif name == "domain" then
            if not value or value == "" then return nil, nil, nil, "empty domain" end
            domains = lower(value)
        elseif UNSUPPORTED_OPTIONS[name] then
            return nil, nil, nil, "unsupported option " .. name
        elseif IGNORED_OPTIONS[name] then
            -- 忽略
        else
            return nil, nil, nil, "unknown option " .. name
        end
    end
    if has_type then
        if types == 0 then
            -- 只有取反类型：全部减去
            types = TYPE_ALL_BUT_DOC & ~neg_types
        else
            types = types & ~neg_types
        end
    else
        types = 0  -- 0 = 全部（不含 document）
    end
    return flags, types, domains, nil, site
end

-- 是否是"纯域名"模式：||host^ 或 ||host
local function pure_host(pat)
    local host = pat:match("^([%w%.%-_]+)%^?$")
    if host and host:find("%.") then return host end
    return nil
end

function Compiler:_add_cosmetic(domains, selector, exception)
    selector = util.trim(selector)
    if selector == "" then return false end
    -- 程序化 / 脚本 / 样式注入 语法：跳过
    if selector:find("^%+js%(") or selector:find("^%^") then return false end
    if selector:find(":has%-text%(", 1) or selector:find(":matches%-css") or selector:find(":xpath%(")
        or selector:find(":upward%(") or selector:find(":remove%(") or selector:find(":style%(")
        or selector:find(":matches%-path%(") or selector:find(":min%-text%-length")
        or selector:find(":watch%-attr") or selector:find(":matches%-attr") or selector:find(":others%(")
        or selector:find(":nth%-ancestor") or selector:find(":remove%-attr") or selector:find(":remove%-class")
        or selector:find(":contains%(") or selector:find(":%-abp%-") or selector:find(":if%(")
        or selector:find(":if%-not%(") or selector:find(":matches%-media") or selector:find(":shadow%(") then
        return false
    end
    -- 明显不是选择器的
    if selector:find("[{}]") then return false end

    self.stats.cosmetic = self.stats.cosmetic + 1
    if domains == "" then
        if exception then
            -- 全局例外：标记为 false，finish() 时从通用表剔除，之后再出现也不收
            self.cg_seen[selector] = false
            return true
        end
        if self.cg_seen[selector] == nil then
            self.cg_seen[selector] = true
            self.cg[#self.cg + 1] = selector
        end
        return true
    end
    for d in domains:gmatch("[^,]+") do
        d = lower(util.trim(d))
        if d ~= "" then
            local neg = d:sub(1, 1) == "~"
            if neg then d = d:sub(2) end
            local target
            if (neg and not exception) or (exception and not neg) then
                -- ~domain##sel（在 domain 上不隐藏）与 domain#@#sel 都是"该域名例外"
                target = self.cx
                if neg and not exception then
                    -- ~a.com##sel：通用隐藏 + a.com 例外
                    if self.cg_seen[selector] == nil then
                        self.cg_seen[selector] = true
                        self.cg[#self.cg + 1] = selector
                    end
                end
            else
                target = self.cd
            end
            local list = target[d]
            if not list then list = {} target[d] = list end
            list[#list + 1] = selector
        end
    end
    return true
end

-- 单行解析。返回 true 表示产生了规则
function Compiler:add_line(line)
    self.stats.lines = self.stats.lines + 1
    line = util.trim(line)
    if line == "" or line:sub(1, 1) == "!" or line:sub(1, 1) == "[" then return false end
    if line:sub(1, 1) == "#" and not line:find("#[@?$%%]?#", 1) then return false end  -- hosts 注释

    -- hosts 文件格式
    do
        local ip, host = line:match("^(%d+%.%d+%.%d+%.%d+)%s+([%w%.%-_]+)")
        if ip and (ip == "0.0.0.0" or ip == "127.0.0.1") then
            if host ~= "localhost" and host ~= "localhost.localdomain" and host ~= "broadcasthost"
                and host ~= "local" and host ~= "0.0.0.0" then
                if add_host_set(self.hosts, host) then
                    self.stats.hosts = self.stats.hosts + 1
                    return true
                end
            end
            return false
        end
    end

    -- 元素隐藏
    do
        local sep_at = line:find("#[@?$%%]?[@$]?#", 1)
        if sep_at then
            local sep = line:match("^#[@?$%%]?[@$]?#", sep_at)
            local domains = line:sub(1, sep_at - 1)
            local selector = line:sub(sep_at + #sep)
            if sep == "##" then
                return self:_add_cosmetic(domains, selector, false)
            elseif sep == "#@#" then
                return self:_add_cosmetic(domains, selector, true)
            else
                self.stats.skipped = self.stats.skipped + 1
                return false  -- #?# #$# #%# 等
            end
        end
    end

    -- 网络过滤器
    local text = line
    local flags = 0
    if text:sub(1, 2) == "@@" then
        flags = flags | F_EXCEPTION
        text = text:sub(3)
    end
    -- 选项：最后一个 $ 之后
    local pattern, options = text, nil
    local dollar = text:match(".*()%$")
    if dollar and dollar > 1 then
        local cand = text:sub(dollar + 1)
        if cand:match("^[%w~_,=|%.%-%*:/%%]*$") then
            pattern, options = text:sub(1, dollar - 1), cand
        end
    end
    -- 正则过滤器：/.../ 不支持
    if #pattern > 1 and pattern:sub(1, 1) == "/" and pattern:sub(-1) == "/" then
        self.stats.skipped = self.stats.skipped + 1
        return false
    end
    local types, domains, site_opt = 0, nil, nil
    if options then
        local fadd, t, d, err, site = parse_options(options)
        if not fadd then
            self.stats.skipped = self.stats.skipped + 1
            return false
        end
        flags = flags | fadd
        types, domains, site_opt = t, d, site
    end

    -- 锚点
    if pattern:sub(1, 2) == "||" then
        flags = flags | F_HOST_ANCHOR
        pattern = pattern:sub(3)
    elseif pattern:sub(1, 1) == "|" then
        flags = flags | F_LEFT_ANCHOR
        pattern = pattern:sub(2)
    end
    if pattern:sub(-1) == "|" then
        flags = flags | F_RIGHT_ANCHOR
        pattern = pattern:sub(1, -2)
    end
    -- 去掉多余的通配
    pattern = pattern:gsub("%*%*+", "*")
    if (flags & F_LEFT_ANCHOR) == 0 and (flags & F_HOST_ANCHOR) == 0 then
        pattern = pattern:gsub("^%*+", "")
    end
    if (flags & F_RIGHT_ANCHOR) == 0 then
        pattern = pattern:gsub("%*+$", "")
    end
    if (flags & F_MATCH_CASE) == 0 then pattern = lower(pattern) end

    -- 站点级例外（$document / $elemhide / $generichide）
    if site_opt and (flags & F_EXCEPTION) ~= 0 then
        local host = (flags & F_HOST_ANCHOR) ~= 0 and pure_host(pattern) or nil
        if host then
            local list = site_opt == "document" and self.doc or (site_opt == "elemhide" and self.eh or self.gh)
            list[#list + 1] = host
            self.stats.network = self.stats.network + 1
            return true
        end
        -- 不是纯域名的站点级例外：当普通例外处理（丢掉 site 语义）
        if site_opt == "document" then types = TYPES.document end
    elseif site_opt then
        -- 非例外过滤器上的 $document：只匹配 document 类型
        if site_opt == "document" then types = TYPES.document else return false end
    end

    if pattern == "" and (flags & F_HOST_ANCHOR) == 0 and not domains and types == 0 then
        self.stats.skipped = self.stats.skipped + 1
        return false
    end

    -- 纯域名快速路径（没有任何选项）
    if (flags & F_HOST_ANCHOR) ~= 0 and not options then
        local host = pure_host(pattern)
        if host then
            local set = (flags & F_EXCEPTION) ~= 0 and self.ehosts or self.hosts
            if add_host_set(set, host) then
                self.stats.hosts = self.stats.hosts + 1
                return true
            end
        end
    end

    if pattern:find("[%*%^]") then
        flags = flags | F_WILD
        if pattern:sub(-1) == "^" then flags = flags | F_TRAIL_SEP end
    end

    local i = #self.fp + 1
    self.fp[i] = pattern
    self.ff[i] = flags
    self.ft[i] = types
    self.fd[i] = domains or ""
    local tok = pick_token(lower(pattern), flags)
    if tok then
        local bucket = self.idx[tok]
        if not bucket then bucket = {} self.idx[tok] = bucket end
        bucket[#bucket + 1] = i
    else
        self.generic[#self.generic + 1] = i
    end
    self.stats.network = self.stats.network + 1
    return true
end

function Compiler:add(text, name)
    local n = 0
    for line in (text .. "\n"):gmatch("([^\r\n]*)\r?\n") do
        if self:add_line(line) then n = n + 1 end
    end
    self.stats.lists[#self.stats.lists + 1] = { name = name or "?", rules = n }
    return n
end

function Compiler:finish()
    local cg = {}
    for _, sel in ipairs(self.cg) do
        if self.cg_seen[sel] then cg[#cg + 1] = sel end
    end
    local hosts, ehosts = {}, {}
    for h in pairs(self.hosts) do hosts[#hosts + 1] = h end
    for h in pairs(self.ehosts) do ehosts[#ehosts + 1] = h end
    table.sort(hosts)
    table.sort(ehosts)
    return {
        v = 2,
        hosts = hosts, ehosts = ehosts,
        fp = self.fp, ff = self.ff, ft = self.ft, fd = self.fd,
        idx = self.idx, generic = self.generic,
        cg = cg, cd = self.cd, cx = self.cx,
        doc = self.doc, eh = self.eh, gh = self.gh,
        stats = {
            lines = self.stats.lines, network = self.stats.network, hosts = #hosts,
            cosmetic = self.stats.cosmetic, skipped = self.stats.skipped,
            generic_cosmetic = #cg, lists = self.stats.lists,
        },
    }
end

-- ===== 引擎 =====
local Engine = {}
Engine.__index = Engine

function M.engine(compiled)
    local e = setmetatable({}, Engine)
    e.c = compiled
    e.hosts = {}
    for _, h in ipairs(compiled.hosts or {}) do e.hosts[h] = true end
    e.ehosts = {}
    for _, h in ipairs(compiled.ehosts or {}) do e.ehosts[h] = true end
    e.fp, e.ff, e.ft, e.fd = compiled.fp or {}, compiled.ff or {}, compiled.ft or {}, compiled.fd or {}
    e.idx = compiled.idx or {}
    e.generic = compiled.generic or {}
    e.lua_pat = {}      -- i -> 编好的 Lua 模式（惰性）
    e.dom_cache = {}    -- i -> {inc={}, exc={}}
    e.doc_set, e.eh_set, e.gh_set = {}, {}, {}
    for _, h in ipairs(compiled.doc or {}) do e.doc_set[h] = true end
    for _, h in ipairs(compiled.eh or {}) do e.eh_set[h] = true end
    for _, h in ipairs(compiled.gh or {}) do e.gh_set[h] = true end
    e.css_cache = {}
    e.generic_css = nil
    return e
end

-- host 的所有后缀（含自身）：a.b.c.com → a.b.c.com, b.c.com, c.com, com
local function each_suffix(host, fn)
    local h = host
    while h and h ~= "" do
        if fn(h) then return true end
        local dot = h:find(".", 1, true)
        if not dot then break end
        h = h:sub(dot + 1)
    end
    return false
end

local function host_in_set(set, host)
    if not host or host == "" then return false end
    return each_suffix(host, function(h) return set[h] == true end)
end

function Engine:_domains(i)
    local d = self.dom_cache[i]
    if d then return d end
    d = { inc = {}, exc = {}, has_inc = false }
    for part in (self.fd[i] or ""):gmatch("[^|]+") do
        if part:sub(1, 1) == "~" then
            d.exc[#d.exc + 1] = part:sub(2)
        else
            d.inc[#d.inc + 1] = part
            d.has_inc = true
        end
    end
    self.dom_cache[i] = d
    return d
end

local function domain_matches(list, host)
    for _, d in ipairs(list) do
        if host == d or (#host > #d and host:sub(-#d - 1) == "." .. d) then return true end
    end
    return false
end

function Engine:_pattern_matches(i, url, lurl, host_start, host_end)
    local flags = self.ff[i]
    local pat = self.fp[i]
    local hay = (flags & F_MATCH_CASE) ~= 0 and url or lurl
    if (flags & F_WILD) == 0 then
        -- 纯文本
        if (flags & F_HOST_ANCHOR) ~= 0 then
            local init = host_start
            while true do
                local s, e = hay:find(pat, init, true)
                if not s then return false end
                if s > host_end + 1 then return false end
                if s == host_start or hay:sub(s - 1, s - 1) == "." then
                    if (flags & F_RIGHT_ANCHOR) == 0 or e == #hay then return true end
                end
                init = s + 1
            end
        elseif (flags & F_LEFT_ANCHOR) ~= 0 then
            if hay:sub(1, #pat) ~= pat then return false end
            return (flags & F_RIGHT_ANCHOR) == 0 or #hay == #pat
        elseif (flags & F_RIGHT_ANCHOR) ~= 0 then
            return #hay >= #pat and hay:sub(-#pat) == pat
        else
            return hay:find(pat, 1, true) ~= nil
        end
    end
    -- 通配：Lua 模式（首次用到时编好几种变体，之后只做 find）
    local lp = self.lua_pat[i]
    if not lp then
        local body = to_lua_pattern(pat, flags)
        local tail = (flags & F_RIGHT_ANCHOR) ~= 0 and "$" or ""
        local anchored = (flags & (F_HOST_ANCHOR | F_LEFT_ANCHOR)) ~= 0
        local prefix = anchored and "^" or ""
        lp = {
            plain = prefix .. body .. tail,
            sep = prefix .. body .. "[^%w_%-%.%%]" .. tail,
            eos = prefix .. body .. "$",
            trail = (flags & F_TRAIL_SEP) ~= 0,
        }
        self.lua_pat[i] = lp
    end
    local find = string.find
    local function try(init)
        if lp.trail then
            if find(hay, lp.sep, init) then return true end
            return find(hay, lp.eos, init) ~= nil
        end
        return find(hay, lp.plain, init) ~= nil
    end
    if (flags & F_HOST_ANCHOR) ~= 0 then
        -- 从 host 开头及每个 "." 之后尝试
        if try(host_start) then return true end
        local pos = host_start
        while true do
            local dot = find(hay, ".", pos, true)
            if not dot or dot > host_end then return false end
            if try(dot + 1) then return true end
            pos = dot + 1
        end
    else
        return try(1)
    end
end

-- 匹配一条候选：返回 true 表示该过滤器命中
function Engine:_candidate_hits(i, url, lurl, host_start, host_end, typebit, third_party, doc_host)
    local flags = self.ff[i]
    local types = self.ft[i]
    if types == 0 then
        if typebit == TYPES.document then return false end
    elseif (types & typebit) == 0 then
        return false
    end
    if (flags & F_THIRD_PARTY) ~= 0 and not third_party then return false end
    if (flags & F_FIRST_PARTY) ~= 0 and third_party then return false end
    local fd = self.fd[i]
    if fd ~= "" then
        local d = self:_domains(i)
        if d.has_inc and not domain_matches(d.inc, doc_host) then return false end
        if #d.exc > 0 and domain_matches(d.exc, doc_host) then return false end
    end
    return self:_pattern_matches(i, url, lurl, host_start, host_end)
end

-- ctx = { type = "script"|..., page = host, initiator = host, third_party = bool|nil }
-- 返回 verdict ("block" | "allow" | nil), filter_index_or_hostname
function Engine:match(url, ctx)
    ctx = ctx or {}
    local lurl = lower(url)
    local scheme_end = lurl:find("://", 1, true)
    if not scheme_end then return nil end
    local host_start = scheme_end + 3
    local host_end = (lurl:find("[/?#]", host_start) or (#lurl + 1)) - 1
    local host = lurl:sub(host_start, host_end):gsub("^[^@]*@", ""):gsub(":%d+$", "")
    local tname = ctx.type or "other"
    local typebit = TYPES[tname] or TYPES[TYPE_ALIASES[tname] or ""] or TYPES.other
    local doc_host = ctx.initiator or ctx.page or ""
    local third_party = ctx.third_party
    if third_party == nil then
        third_party = doc_host ~= "" and not util.same_site(host, doc_host)
    end

    -- 1) 纯域名例外 / 拦截（例外不能提前返回：$important 仍要压过它）
    local allowed = host_in_set(self.ehosts, host)
    local blocked, blocked_by = false, nil
    if not allowed and typebit ~= TYPES.document and host_in_set(self.hosts, host) then
        blocked, blocked_by = true, host
    end

    -- 2) token 候选
    local function scan(bucket)
        for k = 1, #bucket do
            local i = bucket[k]
            if self:_candidate_hits(i, url, lurl, host_start, host_end, typebit, third_party, doc_host) then
                local flags = self.ff[i]
                if (flags & F_EXCEPTION) ~= 0 then
                    allowed = true
                else
                    if (flags & F_IMPORTANT) ~= 0 then return i end
                    if not blocked then blocked, blocked_by = true, i end
                end
            end
        end
        return nil
    end
    local idx = self.idx
    local pos = 1
    local seen = {}
    while true do
        local s, e = lurl:find("[%w%%]+", pos)
        if not s then break end
        local tok = lurl:sub(s, e)
        if not seen[tok] then
            seen[tok] = true
            local bucket = idx[tok]
            if bucket then
                local important = scan(bucket)
                if important then return "block", important end
            end
        end
        pos = e + 1
    end
    if #self.generic > 0 then
        local important = scan(self.generic)
        if important then return "block", important end
    end
    if allowed then return "allow", nil end
    if blocked then return "block", blocked_by end
    return nil
end

-- 站点级开关：{document=, elemhide=, generichide=}
function Engine:page_flags(host)
    host = lower(host or "")
    return {
        document = host_in_set(self.doc_set, host),
        elemhide = host_in_set(self.eh_set, host),
        generichide = host_in_set(self.gh_set, host),
    }
end

local function css_from_selectors(list, exclude)
    local out = {}
    local chunk = {}
    local n = 0
    for _, sel in ipairs(list) do
        if not (exclude and exclude[sel]) then
            n = n + 1
            chunk[#chunk + 1] = sel
            if #chunk >= 400 then
                -- :is() 是宽容选择器列表：一条无效不影响其他
                out[#out + 1] = ":is(" .. table.concat(chunk, ",") .. "){display:none!important}"
                chunk = {}
            end
        end
    end
    if #chunk > 0 then
        out[#out + 1] = ":is(" .. table.concat(chunk, ",") .. "){display:none!important}"
    end
    return table.concat(out, "\n"), n
end

-- 页面用的元素隐藏 CSS。opts.generic=false 时不含通用规则。
function Engine:cosmetic_css(host, opts)
    opts = opts or {}
    host = lower(host or "")
    local flags = self:page_flags(host)
    if flags.document or flags.elemhide then return "" end
    local want_generic = opts.generic ~= false and not flags.generichide
    local key = host .. (want_generic and "|g" or "|s")
    local cached = self.css_cache[key]
    if cached then return cached end

    local c = self.c
    -- 该站的例外选择器
    local exclude = nil
    local specific = {}
    local seen = {}
    each_suffix(host, function(h)
        local ex = c.cx and c.cx[h]
        if ex then
            exclude = exclude or {}
            for _, s in ipairs(ex) do exclude[s] = true end
        end
        local list = c.cd and c.cd[h]
        if list then
            for _, s in ipairs(list) do
                if not seen[s] then seen[s] = true specific[#specific + 1] = s end
            end
        end
        return false
    end)
    local parts = {}
    if want_generic then
        if exclude then
            parts[#parts + 1] = (css_from_selectors(c.cg or {}, exclude))
        else
            if not self.generic_css then
                self.generic_css = (css_from_selectors(c.cg or {}))
            end
            parts[#parts + 1] = self.generic_css
        end
    end
    if #specific > 0 then
        parts[#parts + 1] = (css_from_selectors(specific, exclude))
    end
    local css = table.concat(parts, "\n")
    -- 只缓存有站点特定内容的（通用部分单独缓存了）
    if #specific > 0 or exclude then self.css_cache[key] = css end
    return css
end

function Engine:stats()
    return self.c.stats or {}
end

-- 把 match() 返回的第二个值变成可读的过滤器文本（调试 / 拦截日志用）
function Engine:describe(by)
    if type(by) == "string" then return "||" .. by .. "^" end
    if type(by) ~= "number" then return nil end
    local pat, flags = self.fp[by], self.ff[by] or 0
    if not pat then return nil end
    local s = pat
    if (flags & F_HOST_ANCHOR) ~= 0 then s = "||" .. s
    elseif (flags & F_LEFT_ANCHOR) ~= 0 then s = "|" .. s end
    if (flags & F_RIGHT_ANCHOR) ~= 0 then s = s .. "|" end
    if (flags & F_EXCEPTION) ~= 0 then s = "@@" .. s end
    local opts = {}
    if (flags & F_THIRD_PARTY) ~= 0 then opts[#opts + 1] = "third-party" end
    if (flags & F_FIRST_PARTY) ~= 0 then opts[#opts + 1] = "~third-party" end
    if (flags & F_IMPORTANT) ~= 0 then opts[#opts + 1] = "important" end
    local d = self.fd[by]
    if d and d ~= "" then opts[#opts + 1] = "domain=" .. d end
    if #opts > 0 then s = s .. "$" .. table.concat(opts, ",") end
    return s
end

-- 把 Chromium RequestDestination / 老 ResourceType 名字归一到 ABP 类型
function M.normalize_type(t)
    if not t then return "other" end
    t = lower(t)
    if TYPES[t] then return t end
    local map = {
        empty = "xmlhttprequest", xhr = "xmlhttprequest", fetch = "xmlhttprequest", json = "xmlhttprequest",
        iframe = "sub_frame", frame = "sub_frame", fencedframe = "sub_frame", subdocument = "sub_frame",
        style = "stylesheet", css = "stylesheet",
        audio = "media", video = "media", track = "media",
        worker = "script", sharedworker = "script", serviceworker = "script", audioworklet = "script",
        paintworklet = "script", module = "script",
        embed = "object", object = "object",
        beacon = "ping", report = "ping",
        manifest = "other", webbundle = "other", dictionary = "other", speculationrules = "other",
        websocket = "websocket", main_frame = "document",
    }
    return map[t] or "other"
end

return M
