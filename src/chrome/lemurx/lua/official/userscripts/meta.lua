-- userscripts.meta —— 解析 // ==UserScript== 头
--
--   local meta = require("userscripts.meta").parse(src)
--   meta = { name, namespace, version, description, author, icon, homepage, support,
--            match = {}, include = {}, exclude = {}, grant = {}, require = {}, resource = { {name, url} },
--            connect = {}, run_at = "document-idle", noframes = bool, downloadURL, updateURL,
--            inject_into, names = { ["zh-CN"] = ... }, descriptions = {...}, raw = {...} }
--   返回 nil, err 表示没有头。

local M = {}

local LIST_KEYS = { match = true, include = true, exclude = true, grant = true, require = true, connect = true, ["exclude-match"] = true }

local function trim(s) return (s:gsub("^%s+", ""):gsub("%s+$", "")) end

function M.parse(src)
    if type(src) ~= "string" then return nil, "not a string" end
    local head = src:match("//%s*==UserScript==%s*\r?\n(.-)\r?\n%s*//%s*==/UserScript==")
    if not head then return nil, "no ==UserScript== header" end
    local meta = {
        match = {}, include = {}, exclude = {}, grant = {}, require = {}, resource = {}, connect = {},
        names = {}, descriptions = {}, raw = {}, run_at = "document-idle", noframes = false,
    }
    for line in (head .. "\n"):gmatch("([^\n]*)\n") do
        local key, value = line:match("^%s*//%s*@([%w%-_:]+)%s*(.-)%s*$")
        if key then
            value = value or ""
            meta.raw[#meta.raw + 1] = { key, value }
            local base, locale = key:match("^([%w%-_]+):(.+)$")
            base = base or key
            if base == "name" then
                if locale then meta.names[locale] = value else meta.name = value end
            elseif base == "description" then
                if locale then meta.descriptions[locale] = value else meta.description = value end
            elseif base == "exclude-match" then
                meta.exclude[#meta.exclude + 1] = value
            elseif LIST_KEYS[base] then
                if value ~= "" then table.insert(meta[base], value) end
            elseif base == "resource" then
                local rname, rurl = value:match("^(%S+)%s+(%S+)")
                if rname then meta.resource[#meta.resource + 1] = { name = rname, url = rurl } end
            elseif base == "run-at" then
                meta.run_at = value
            elseif base == "noframes" then
                meta.noframes = true
            elseif base == "namespace" then
                meta.namespace = value
            elseif base == "version" then
                meta.version = value
            elseif base == "author" then
                meta.author = value
            elseif base == "icon" or base == "iconURL" or base == "defaulticon" then
                meta.icon = meta.icon or value
            elseif base == "icon64" or base == "icon64URL" then
                meta.icon64 = value
            elseif base == "homepage" or base == "homepageURL" or base == "website" or base == "source" then
                meta.homepage = meta.homepage or value
            elseif base == "supportURL" then
                meta.support = value
            elseif base == "downloadURL" or base == "installURL" then
                meta.downloadURL = meta.downloadURL or value
            elseif base == "updateURL" then
                meta.updateURL = value
            elseif base == "inject-into" then
                meta.inject_into = value
            elseif base == "license" then
                meta.license = value
            elseif base == "unwrap" then
                meta.unwrap = true
            elseif base == "antifeature" then
                meta.antifeature = meta.antifeature or {}
                meta.antifeature[#meta.antifeature + 1] = value
            end
        end
    end
    -- 本地化名字优先 zh-CN / zh
    meta.display_name = meta.names["zh-CN"] or meta.names["zh"] or meta.names["zh-TW"] or meta.name or "未命名脚本"
    meta.display_description = meta.descriptions["zh-CN"] or meta.descriptions["zh"] or meta.description or ""
    -- grant 归一：没写 = none（TM 默认会推断，这里按“全部常用”给，宽松些不误伤）
    local grants = {}
    local has_none = false
    for _, g in ipairs(meta.grant) do
        g = trim(g)
        if g == "none" then has_none = true else grants[g] = true end
    end
    if #meta.grant == 0 then
        -- 没有 @grant：Tampermonkey 会按源码里出现的 GM_ 名字自动授权
        for name in src:gmatch("(GM[_%.][%w_]+)") do grants[name] = true end
        for _, extra in ipairs({ "unsafeWindow", "GM_info", "GM.info" }) do grants[extra] = true end
        if src:find("unsafeWindow", 1, true) then grants["unsafeWindow"] = true end
    end
    meta.grants = grants
    meta.grant_none = has_none and next(grants) == nil
    if meta.run_at ~= "document-start" and meta.run_at ~= "document-end" and meta.run_at ~= "document-idle"
        and meta.run_at ~= "document-body" and meta.run_at ~= "context-menu" then
        meta.run_at = "document-idle"
    end
    return meta
end

-- 生成稳定 id：namespace + name → 小写 slug + 短哈希
local function djb2(s)
    local h = 5381
    for i = 1, #s do h = (h * 33 + s:byte(i)) % 4294967296 end
    return ("%08x"):format(h)
end
function M.id_for(meta)
    local key = (meta.namespace or "") .. "\0" .. (meta.name or "")
    local slug = (meta.name or "script"):lower():gsub("[^%w]+", "-"):gsub("^%-+", ""):gsub("%-+$", ""):sub(1, 32)
    if slug == "" then slug = "script" end
    return slug .. "-" .. djb2(key):sub(1, 6)
end

-- 版本比较：1.2.10 > 1.2.9；带字母的段按字符串比
function M.compare_version(a, b)
    a, b = tostring(a or "0"), tostring(b or "0")
    local pa, pb = {}, {}
    for s in a:gmatch("[^%.%-+_]+") do pa[#pa + 1] = s end
    for s in b:gmatch("[^%.%-+_]+") do pb[#pb + 1] = s end
    for i = 1, math.max(#pa, #pb) do
        local x, y = pa[i] or "0", pb[i] or "0"
        local nx, ny = tonumber(x), tonumber(y)
        if nx and ny then
            if nx ~= ny then return nx < ny and -1 or 1 end
        else
            if x ~= y then return x < y and -1 or 1 end
        end
    end
    return 0
end

-- 只取头部（更新检查用 @updateURL 拿到的 .meta.js）
function M.header_only(src)
    return src:match("(//%s*==UserScript==.-//%s*==/UserScript==)")
end

return M
