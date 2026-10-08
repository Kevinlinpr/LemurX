-- wappalyzer/detect · 指纹匹配引擎（纯 Lua，浏览器进程和渲染进程都能跑）
--
-- detect(signals) -> { {name, cat, cat_name, version, confidence, via = {...}}, ... }
-- signals：
--   url      页面 URL
--   html     文档 HTML（前 200KB 即可）
--   scripts  { src, ... }
--   meta     { name(小写) = content }
--   js       { "全局变量路径" = 值（字符串）或 true }   渲染进程用 js_probes() 拿要探测的路径
--   dom      { "选择器" = true }                       渲染进程用 dom_probes() 拿要查的选择器
--   cookies  { name, ... }
--   headers  { name(小写) = value }
--   attrs    { "ng-version" = "17.1.0" }               渲染进程顺手取的属性值
local T = require("wappalyzer.techs")
local M = {}

local function lower(s) return type(s) == "string" and s:lower() or "" end
local function find(hay, pat)
    if type(hay) ~= "string" or hay == "" then return false end
    local ok, r = pcall(string.find, hay, pat)
    return ok and r ~= nil
end
local function ifind(hay, pat) return find(lower(hay), lower(pat)) end

-- 渲染进程需要探测的全局变量路径 / 选择器（去重）
function M.js_probes()
    local seen, out = {}, {}
    for _, t in ipairs(T.TECHS) do
        for _, p in ipairs(t.js or {}) do if not seen[p] then seen[p] = true out[#out + 1] = p end end
        if t.version and t.version.js and not seen[t.version.js] then seen[t.version.js] = true out[#out + 1] = t.version.js end
    end
    return out
end
function M.dom_probes()
    local seen, out = {}, {}
    for _, t in ipairs(T.TECHS) do
        for _, s in ipairs(t.dom or {}) do if not seen[s] then seen[s] = true out[#out + 1] = s end end
    end
    return out
end
function M.attr_probes() return { "ng-version" } end

local function version_of(t, sig, hits)
    local v = t.version
    if not v then return nil end
    if v.js and sig.js and type(sig.js[v.js]) == "string" then
        local s = sig.js[v.js]:match("^v?([%d]+[%d%.%-%w]*)")
        if s then return s end
    end
    if v.attr and sig.attrs and type(sig.attrs[v.attr]) == "string" then return sig.attrs[v.attr] end
    if v.meta and sig.meta and sig.meta[v.meta] and v.re then
        local s = sig.meta[v.meta]:match(v.re)
        if s then return s end
    end
    if v.header and sig.headers and sig.headers[v.header] and v.re then
        local s = sig.headers[v.header]:match(v.re)
        if s then return s end
    end
    return nil
end

function M.detect(sig)
    sig = sig or {}
    local html = type(sig.html) == "string" and sig.html:sub(1, 200 * 1024) or ""
    local html_l = html:lower()
    local found = {}   -- name -> result
    local order = {}

    local function add(t, via, conf)
        local r = found[t.name]
        if not r then
            r = { name = t.name, cat = t.cat, cat_name = T.CATS[t.cat] or t.cat, confidence = 0, via = {} }
            found[t.name] = r
            order[#order + 1] = r
        end
        r.confidence = math.min(100, r.confidence + conf)
        r.via[#r.via + 1] = via
    end

    for _, t in ipairs(T.TECHS) do
        local hit = false
        for _, p in ipairs(t.js or {}) do
            if sig.js and sig.js[p] ~= nil and sig.js[p] ~= false then add(t, "js:" .. p, 60) hit = true break end
        end
        for _, pat in ipairs(t.scripts or {}) do
            for _, src in ipairs(sig.scripts or {}) do
                if ifind(src, pat) then add(t, "script:" .. src:sub(1, 80), 50) hit = true break end
            end
            if hit then break end
        end
        for _, pat in ipairs(t.html or {}) do
            if find(html_l, pat:lower()) then add(t, "html:" .. pat, 40) hit = true break end
        end
        for name, pat in pairs(t.meta or {}) do
            local v = sig.meta and sig.meta[name]
            if v and ifind(v, pat) then add(t, "meta:" .. name, 70) hit = true break end
        end
        for name, pat in pairs(t.headers or {}) do
            local v = sig.headers and sig.headers[name]
            if v and ifind(v, pat) then add(t, "header:" .. name, 70) hit = true break end
        end
        for _, pat in ipairs(t.cookies or {}) do
            for _, c in ipairs(sig.cookies or {}) do
                if find(c, pat) then add(t, "cookie:" .. c, 40) hit = true break end
            end
            if hit then break end
        end
        for _, sel in ipairs(t.dom or {}) do
            if sig.dom and sig.dom[sel] then add(t, "dom:" .. sel, 50) hit = true break end
        end
        for _, pat in ipairs(t.url or {}) do
            if find(sig.url or "", pat) then add(t, "url:" .. pat, 30) hit = true break end
        end
    end

    -- implies：递归展开
    local changed = true
    local guard = 0
    while changed and guard < 5 do
        changed = false
        guard = guard + 1
        for _, t in ipairs(T.TECHS) do
            if found[t.name] then
                for _, dep in ipairs(t.implies or {}) do
                    if not found[dep] then
                        for _, d in ipairs(T.TECHS) do
                            if d.name == dep then add(d, "implied-by:" .. t.name, 30) changed = true break end
                        end
                    end
                end
            end
        end
    end

    for _, r in ipairs(order) do
        for _, t in ipairs(T.TECHS) do
            if t.name == r.name then r.version = version_of(t, sig) break end
        end
    end
    table.sort(order, function(a, b)
        if a.confidence ~= b.confidence then return a.confidence > b.confidence end
        return a.name < b.name
    end)
    return order
end

-- 按分类分组：{ {cat, cat_name, items={...}}, ... }
function M.group(results)
    local by, order = {}, {}
    for _, r in ipairs(results) do
        local g = by[r.cat]
        if not g then g = { cat = r.cat, cat_name = r.cat_name, items = {} } by[r.cat] = g order[#order + 1] = g end
        g.items[#g.items + 1] = r
    end
    local CAT_ORDER = { "cms", "ecommerce", "blog", "forum", "wiki", "lms", "ssg", "framework", "js", "ui", "server", "lang", "hosting", "cdn", "analytics", "tag", "ads", "marketing", "ab", "chat", "captcha", "payment", "map", "video", "search", "security", "font", "pwa", "misc" }
    local rank = {}
    for i, c in ipairs(CAT_ORDER) do rank[c] = i end
    table.sort(order, function(a, b) return (rank[a.cat] or 99) < (rank[b.cat] or 99) end)
    return order
end

return M
