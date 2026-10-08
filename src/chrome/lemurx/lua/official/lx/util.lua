-- lx.util —— 纯 Lua 工具集，浏览器进程与渲染进程都能 require。
--
--   url.parse / url.build / url.encode / url.decode / url.query / url.strip_fragment
--   host_of(url) origin_of(url) base_domain(host) same_site(a, b) is_ip(host)
--   host_matches(host, pattern)  glob_match(str, pattern)  trim  split  starts_with  ends_with
--   escape_html  escape_lua_pattern  now_ms  today

local M = {}

-- ===== 字符串 =====
function M.trim(s)
    return (tostring(s or ""):gsub("^%s+", ""):gsub("%s+$", ""))
end

function M.split(s, sep, plain)
    local out = {}
    if s == nil or s == "" then return out end
    sep = sep or ","
    local i = 1
    while true do
        local a, b = string.find(s, sep, i, plain ~= false)
        if not a then
            out[#out + 1] = string.sub(s, i)
            break
        end
        out[#out + 1] = string.sub(s, i, a - 1)
        i = b + 1
    end
    return out
end

function M.starts_with(s, prefix)
    return string.sub(s, 1, #prefix) == prefix
end

function M.ends_with(s, suffix)
    return suffix == "" or string.sub(s, -#suffix) == suffix
end

function M.escape_lua_pattern(s)
    return (s:gsub("[%^%$%(%)%%%.%[%]%*%+%-%?]", "%%%0"))
end

local html_escapes = { ["&"] = "&amp;", ["<"] = "&lt;", [">"] = "&gt;", ['"'] = "&quot;", ["'"] = "&#39;" }
function M.escape_html(s)
    return (tostring(s == nil and "" or s):gsub("[&<>\"']", html_escapes))
end

-- glob：* 任意串，? 任意单字符；其余按字面
function M.glob_match(str, pattern)
    if pattern == "*" then return true end
    local lp = "^" .. M.escape_lua_pattern(pattern):gsub("%%%*", ".*"):gsub("%%%?", ".") .. "$"
    return string.find(str, lp) ~= nil
end

function M.now_ms()
    return math.floor(os.time() * 1000)
end

function M.today()
    return os.date("%Y-%m-%d")
end

-- ===== URL =====
local url = {}
M.url = url

local function hexchar(c) return string.format("%%%02X", string.byte(c)) end

function url.encode(s)
    return (tostring(s):gsub("[^%w%-%._~]", hexchar))
end

function url.decode(s)
    s = tostring(s):gsub("+", " ")
    return (s:gsub("%%(%x%x)", function(h) return string.char(tonumber(h, 16)) end))
end

-- 解析 URL：{scheme, host, port, path, query(原串), fragment, userinfo}
-- 解析失败（没有 scheme）返回 nil
function url.parse(u)
    if type(u) ~= "string" then return nil end
    local out = {}
    local rest = u
    local frag_at = rest:find("#", 1, true)
    if frag_at then
        out.fragment = rest:sub(frag_at + 1)
        rest = rest:sub(1, frag_at - 1)
    end
    local scheme = rest:match("^([%a][%w+.-]*):")
    if not scheme then return nil end
    out.scheme = scheme:lower()
    rest = rest:sub(#scheme + 2)
    if rest:sub(1, 2) == "//" then
        rest = rest:sub(3)
        local slash = rest:find("[/?]") or (#rest + 1)
        local authority = rest:sub(1, slash - 1)
        rest = rest:sub(slash)
        local at = authority:find("@", 1, true)
        if at then
            out.userinfo = authority:sub(1, at - 1)
            authority = authority:sub(at + 1)
        end
        local host, port = authority:match("^(%[[^%]]+%])(:?%d*)$")
        if not host then host, port = authority:match("^([^:]*)(:?%d*)$") end
        out.host = (host or authority):lower()
        if port and port ~= "" then out.port = tonumber(port:sub(2)) end
    end
    local q = rest:find("?", 1, true)
    if q then
        out.query = rest:sub(q + 1)
        rest = rest:sub(1, q - 1)
    end
    out.path = rest ~= "" and rest or (out.host and "/" or "")
    return out
end

-- 拼回 URL（url.parse 的逆）
function url.build(p)
    local out = { p.scheme or "https", ":" }
    if p.host then
        out[#out + 1] = "//"
        if p.userinfo then out[#out + 1] = p.userinfo .. "@" end
        out[#out + 1] = p.host
        if p.port then out[#out + 1] = ":" .. tostring(p.port) end
    end
    out[#out + 1] = p.path or "/"
    local q = p.query
    if type(q) == "table" then q = url.build_query(q) end  -- 也接受 {k=v} 表
    if q and q ~= "" then out[#out + 1] = "?" .. q end
    if p.fragment then out[#out + 1] = "#" .. p.fragment end
    return table.concat(out)
end

-- 查询串 → 有序键值对列表 {{k, v}, ...}（保序，允许重复键）
function url.query_pairs(q)
    local out = {}
    if not q or q == "" then return out end
    for pair in q:gmatch("[^&]+") do
        local k, v = pair:match("^([^=]*)=?(.*)$")
        out[#out + 1] = { k, v }
    end
    return out
end

-- 查询串 → 表（重复键取最后一个，值已 decode）
function url.query(q)
    local out = {}
    for _, kv in ipairs(url.query_pairs(q)) do
        out[url.decode(kv[1])] = url.decode(kv[2])
    end
    return out
end

function url.build_query(t)
    local keys = {}
    for k in pairs(t) do keys[#keys + 1] = tostring(k) end
    table.sort(keys)
    local out = {}
    for _, k in ipairs(keys) do
        local v = t[k]
        if v ~= nil and v ~= false then
            out[#out + 1] = url.encode(k) .. "=" .. url.encode(v == true and "1" or tostring(v))
        end
    end
    return table.concat(out, "&")
end

function url.strip_fragment(u)
    local at = u:find("#", 1, true)
    return at and u:sub(1, at - 1) or u
end

function M.host_of(u)
    if type(u) ~= "string" then return "" end
    local h = u:match("^%a[%w+.-]*://([^/?#]+)")
    if not h then return "" end
    h = h:gsub("^[^@]*@", ""):gsub(":%d+$", "")
    return h:lower()
end

function M.origin_of(u)
    local p = url.parse(u)
    if not p or not p.host then return "" end
    return p.scheme .. "://" .. p.host .. (p.port and (":" .. p.port) or "")
end

function M.is_ip(host)
    if not host then return false end
    if host:match("^%d+%.%d+%.%d+%.%d+$") then return true end
    if host:sub(1, 1) == "[" or host:find(":", 1, true) then return true end
    return false
end

-- ===== 公共后缀（简表）=====
-- 完整 PSL 有上万条；这里收录最常见的两段式后缀，够用来判断"同站"和 eTLD+1。
local two_level = {}
for suffix in ([[
co.uk org.uk me.uk ltd.uk plc.uk net.uk sch.uk ac.uk gov.uk nhs.uk
com.cn net.cn org.cn gov.cn edu.cn ac.cn mil.cn
com.hk net.hk org.hk edu.hk gov.hk idv.hk
com.tw net.tw org.tw edu.tw gov.tw idv.tw
co.jp ne.jp or.jp ac.jp go.jp gr.jp ad.jp ed.jp lg.jp
co.kr ne.kr or.kr re.kr pe.kr go.kr ac.kr
com.au net.au org.au edu.au gov.au id.au asn.au
co.nz net.nz org.nz govt.nz ac.nz school.nz geek.nz
com.br net.br org.br gov.br edu.br
com.mx org.mx gob.mx edu.mx net.mx
com.ar net.ar org.ar gob.ar edu.ar
co.za org.za net.za gov.za web.za ac.za
co.in net.in org.in firm.in gen.in ind.in ac.in edu.in gov.in res.in
com.sg net.sg org.sg edu.sg gov.sg per.sg
com.my net.my org.my edu.my gov.my
co.id or.id ac.id go.id web.id my.id
com.ph net.ph org.ph
com.vn net.vn org.vn edu.vn gov.vn
co.th in.th ac.th go.th or.th
com.tr net.tr org.tr gen.tr edu.tr gov.tr bel.tr web.tr
com.ru net.ru org.ru msk.ru spb.ru
com.ua net.ua org.ua kiev.ua
com.pl net.pl org.pl edu.pl
co.il org.il net.il ac.il gov.il muni.il
com.eg
com.sa net.sa org.sa
com.pk net.pk org.pk edu.pk
com.bd net.bd org.bd
com.ng
com.co net.co org.co edu.co gov.co
com.pe net.pe org.pe edu.pe gob.pe
com.ve net.ve org.ve
com.uy edu.uy
com.ec
co.ke or.ke ac.ke
co.tz
com.gh
com.ng
com.es org.es nom.es
com.pt edu.pt
co.at or.at ac.at gv.at
com.gr net.gr org.gr edu.gr
com.cy
co.it
com.ee
co.hu
com.ro
co.rs
com.mk
com.ba
com.al
com.ge
com.kz
com.uz
com.np
com.lk
com.mm
com.kh
com.la
com.mo
github.io gitlab.io herokuapp.com netlify.app vercel.app pages.dev web.app firebaseapp.com
appspot.com blogspot.com wordpress.com tumblr.com azurewebsites.net cloudfront.net
amazonaws.com s3.amazonaws.com cloudapp.net workers.dev repl.co glitch.me
myshopify.com github.dev
]]):gmatch("%S+") do
    two_level[suffix] = true
end

-- eTLD+1：example.co.uk → example.co.uk；a.b.example.com → example.com；IP/单段原样返回
function M.base_domain(host)
    if not host or host == "" then return host or "" end
    host = host:lower():gsub("%.$", "")
    if M.is_ip(host) then return host end
    local labels = M.split(host, ".", true)
    local n = #labels
    if n <= 2 then return host end
    local last2 = labels[n - 1] .. "." .. labels[n]
    if two_level[last2] then
        if n >= 3 then return labels[n - 2] .. "." .. last2 end
        return host
    end
    return last2
end

function M.same_site(host_a, host_b)
    if host_a == host_b then return true end
    if host_a == "" or host_b == "" then return false end
    return M.base_domain(host_a) == M.base_domain(host_b)
end

-- host 是否命中 pattern：pattern 可为精确域名（连子域一起匹配）、"*.example.com"、
-- 或含 * 的 glob；"~" 开头由调用方处理（取反）
function M.host_matches(host, pattern)
    if not host or not pattern or pattern == "" then return false end
    host = host:lower()
    pattern = pattern:lower()
    if pattern == "*" or pattern == "<all_urls>" then return true end
    if pattern:sub(1, 2) == "*." then
        local base = pattern:sub(3)
        return host == base or M.ends_with(host, "." .. base)
    end
    if pattern:find("*", 1, true) then
        return M.glob_match(host, pattern)
    end
    return host == pattern or M.ends_with(host, "." .. pattern)
end

-- 从 URL 里取"路径 + 查询"，方便做站内规则
function M.path_of(u)
    return u:match("^%a[%w+.-]*://[^/?#]+([^#]*)") or "/"
end

-- 深拷贝（只处理表、无环）
function M.deep_copy(v)
    if type(v) ~= "table" then return v end
    local out = {}
    for k, x in pairs(v) do out[k] = M.deep_copy(x) end
    return out
end

-- 合并默认值：返回 defaults 与 overrides 的浅合并新表
function M.merge(defaults, overrides)
    local out = {}
    for k, v in pairs(defaults or {}) do out[k] = v end
    for k, v in pairs(overrides or {}) do out[k] = v end
    return out
end

-- 表长度（哈希表也算）
function M.count(t)
    local n = 0
    for _ in pairs(t or {}) do n = n + 1 end
    return n
end

-- 人类可读字节数
function M.human_bytes(n)
    n = tonumber(n) or 0
    if n < 1024 then return string.format("%d B", n) end
    if n < 1024 * 1024 then return string.format("%.1f KB", n / 1024) end
    if n < 1024 * 1024 * 1024 then return string.format("%.1f MB", n / 1024 / 1024) end
    return string.format("%.2f GB", n / 1024 / 1024 / 1024)
end

-- 人类可读时长（秒）
function M.human_duration(sec)
    sec = math.floor(tonumber(sec) or 0)
    if sec < 60 then return sec .. "秒" end
    if sec < 3600 then return string.format("%d分%02d秒", sec // 60, sec % 60) end
    return string.format("%d小时%02d分", sec // 3600, (sec % 3600) // 60)
end

-- base64（纯 Lua，小数据够用；大数据优先用原生 fs/http 的 base64 选项）
local b64chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
function M.base64_encode(data)
    return ((data:gsub(".", function(x)
        local r, b = "", x:byte()
        for i = 8, 1, -1 do r = r .. (b % 2 ^ i - b % 2 ^ (i - 1) > 0 and "1" or "0") end
        return r
    end) .. "0000"):gsub("%d%d%d?%d?%d?%d?", function(x)
        if #x < 6 then return "" end
        local c = 0
        for i = 1, 6 do c = c + (x:sub(i, i) == "1" and 2 ^ (6 - i) or 0) end
        return b64chars:sub(c + 1, c + 1)
    end) .. ({ "", "==", "=" })[#data % 3 + 1])
end

function M.base64_decode(data)
    data = data:gsub("[^" .. b64chars .. "=]", "")
    return (data:gsub(".", function(x)
        if x == "=" then return "" end
        local r, f = "", (b64chars:find(x, 1, true) - 1)
        for i = 6, 1, -1 do r = r .. (f % 2 ^ i - f % 2 ^ (i - 1) > 0 and "1" or "0") end
        return r
    end):gsub("%d%d%d?%d?%d?%d?%d?%d?", function(x)
        if #x ~= 8 then return "" end
        local c = 0
        for i = 1, 8 do c = c + (x:sub(i, i) == "1" and 2 ^ (8 - i) or 0) end
        return string.char(c)
    end))
end

return M
