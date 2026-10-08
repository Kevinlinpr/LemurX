-- userscripts.match —— @match / @include / @exclude 匹配（浏览器端和渲染端共用）
--
--   local match = require("userscripts.match")
--   local m = match.compile({ match = {...}, include = {...}, exclude = {...} })
--   m:test(url) -> true | false | "regex:<js-regex-source>"（需要 JS 才能判定的 include）
--
-- 规则（照 Tampermonkey / Greasemonkey 文档）：
--   @match   <scheme>://<host><path>   scheme 为 * 时匹配 http/https；host 可 * 或 *.example.com；path 通配 *
--   @include 通配 * 的 glob，或 /regex/ ；支持 .tld 魔法后缀
--   @exclude 同 @include，优先级最高
--   一个都没写：匹配所有页面

local M = {}

local function escape(s)
    return (s:gsub("[%^%$%(%)%%%.%[%]%+%-%?%*]", "%%%0"))
end

-- glob -> Lua 模式（* 任意；.tld -> 任意顶级域）
local function glob_to_pattern(glob)
    local p = escape(glob)
    p = p:gsub("%%%*", ".-")
    p = p:gsub("%%%.tld", "%%.[%%w%%.]+")      -- 转义后的 "\.tld"
    -- 末尾 ".-" 改成 ".*"（贪婪到结尾，避免匹配不到）
    if p:sub(-2) == ".-" then p = p:sub(1, -3) .. ".*" end
    return "^" .. p .. "$"
end

-- @match 转 Lua 模式；非法返回 nil
local function match_to_pattern(spec)
    if spec == "<all_urls>" then return "^%a[%w+.-]*://.*$" end
    local scheme, host, path = spec:match("^(%*?[%w+.-]*)://([^/]*)(/.*)$")
    if not scheme then return nil end
    local sp
    if scheme == "*" then
        sp = "https?"
    else
        sp = escape(scheme)
    end
    local pathp = glob_to_pattern(path):sub(2)   -- 去掉开头 ^，保留结尾 $
    if host == "*" or host == "" then
        return "^" .. sp .. "://[^/]*" .. pathp
    elseif host:sub(1, 2) == "*." then
        -- 规范：*.example.com 匹配 example.com 本身及所有子域 —— Lua 模式没有可选组，拆成两条
        local base = escape(host:sub(3))
        return {
            "^" .. sp .. "://" .. base .. pathp,
            "^" .. sp .. "://[%w%-%.]-%." .. base .. pathp,
        }
    end
    return "^" .. sp .. "://" .. escape(host) .. pathp
end

local Matcher = {}
Matcher.__index = Matcher

function M.compile(meta)
    local self = setmetatable({ inc = {}, inc_m = {}, exc = {}, regex_inc = {}, regex_exc = {}, all = false }, Matcher)
    local n = 0
    for _, m in ipairs(meta.match or {}) do
        local p = match_to_pattern(m)
        if type(p) == "table" then
            for _, q in ipairs(p) do self.inc_m[#self.inc_m + 1] = q end
            n = n + 1
        elseif p then
            self.inc_m[#self.inc_m + 1] = p
            n = n + 1
        end
    end
    for _, g in ipairs(meta.include or {}) do
        n = n + 1
        local re = g:match("^/(.*)/[gimsuy]*$")
        if re then
            self.regex_inc[#self.regex_inc + 1] = re
        else
            self.inc[#self.inc + 1] = glob_to_pattern(g)
        end
    end
    for _, g in ipairs(meta.exclude or {}) do
        local re = g:match("^/(.*)/[gimsuy]*$")
        if re then
            self.regex_exc[#self.regex_exc + 1] = re
        else
            self.exc[#self.exc + 1] = glob_to_pattern(g)
        end
    end
    if n == 0 then self.all = true end
    return self
end

local function any(patterns, url)
    for i = 1, #patterns do
        if url:find(patterns[i]) then return true end
    end
    return false
end

-- @match 不看端口和 #fragment：去掉再比
local function strip_for_match(url)
    url = url:gsub("#.*$", "")
    return (url:gsub("^(%a[%w+.-]*://[^/:]+):%d+", "%1"))
end

-- 返回：true / false / { regex_inc = {...}, regex_exc = {...} }（需要 JS 判定的部分）
function Matcher:test(url)
    if any(self.exc, url) then return false end
    local included = self.all or any(self.inc, url) or (#self.inc_m > 0 and any(self.inc_m, strip_for_match(url)))
    local need_js = (#self.regex_inc > 0 and not included) or #self.regex_exc > 0
    if need_js then
        return { included = included, regex_inc = self.regex_inc, regex_exc = self.regex_exc }
    end
    return included
end

-- 把 JS 侧判定表达式拼出来：返回 true/false
function M.js_test(pending)
    local parts = {}
    local function lit(s) return "new RegExp(" .. string.format("%q", s):gsub("\\\n", "\\n") .. ")" end
    local exc = {}
    for _, r in ipairs(pending.regex_exc) do exc[#exc + 1] = lit(r) .. ".test(location.href)" end
    local inc = {}
    if pending.included then inc[#inc + 1] = "true" end
    for _, r in ipairs(pending.regex_inc) do inc[#inc + 1] = lit(r) .. ".test(location.href)" end
    if #inc == 0 then inc[1] = "false" end
    local expr = "(" .. table.concat(inc, "||") .. ")"
    if #exc > 0 then expr = expr .. "&&!(" .. table.concat(exc, "||") .. ")" end
    return "(function(){try{return " .. expr .. "}catch(e){return false}})()"
end

M.glob_to_pattern = glob_to_pattern
M.match_to_pattern = match_to_pattern
return M
