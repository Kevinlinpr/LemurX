-- LemurX · luakit-compatible library · lousy.util
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.util" module API. No luakit code is used.
--
-- 通用工具集：表 / 字符串 / 路径 / 文件查找 / 转义。
-- 文件系统只通过全局 `lfs`（内核 lk_lfs）与标准 `io` 访问；找不到 `luakit` 全局时
-- （例如单元测试里）所有 find_* 会退化为只搜当前目录。

local M = {}

local rawtype = rawget(_G, "__lk") and __lk.object and __lk.object.rawtype or type
local unpack = table.unpack

local function lk()
    return rawget(_G, "luakit")
end

local function fs()
    return rawget(_G, "lfs") or package.loaded["lfs"]
end

-- =====================================================================
-- 表工具  M.table.*
-- =====================================================================
local T = {}
M.table = T

-- 在表的值里找 item，返回对应的键；找不到返回 nil
function T.hasitem(t, item)
    for k, v in pairs(t) do
        if v == item then return k end
    end
    return nil
end

-- 布尔版 hasitem
function T.contains(t, item)
    return T.hasitem(t, item) ~= nil
end

-- 合并任意多张表：数组部分依次追加，命名键后者覆盖前者
function T.join(...)
    local out = {}
    for i = 1, select("#", ...) do
        local src = select(i, ...)
        if rawtype(src) == "table" then
            for k, v in pairs(src) do
                if math.type(k) == "integer" then
                    out[#out + 1] = v
                else
                    out[k] = v
                end
            end
        end
    end
    return out
end

-- 所有键，排好序（数字优先，其次按字符串表示）
function T.keys(t)
    local out = {}
    for k in pairs(t) do out[#out + 1] = k end
    table.sort(out, function(a, b)
        local ta, tb = rawtype(a), rawtype(b)
        if ta == tb then
            if ta == "number" or ta == "string" then return a < b end
            return tostring(a) < tostring(b)
        end
        if ta == "number" then return true end
        if tb == "number" then return false end
        return ta < tb
    end)
    return out
end

-- 所有值，作为数组返回（顺序按 keys() 排序，保证确定性）
function T.values(t)
    local out = {}
    for _, k in ipairs(T.keys(t)) do out[#out + 1] = t[k] end
    return out
end

-- 反转数组部分
function T.reverse(t)
    local out, n = {}, #t
    for i = n, 1, -1 do out[n - i + 1] = t[i] end
    return out
end

-- 浅拷贝（不带元表）
function T.clone(t)
    local out = {}
    for k, v in pairs(t) do out[k] = v end
    return out
end

-- 浅拷贝并沿用元表
function T.copy(t)
    return setmetatable(T.clone(t), getmetatable(t))
end

-- 深拷贝（处理环）
function T.deep_clone(t, seen)
    if rawtype(t) ~= "table" then return t end
    seen = seen or {}
    if seen[t] then return seen[t] end
    local out = {}
    seen[t] = out
    for k, v in pairs(t) do
        out[T.deep_clone(k, seen)] = T.deep_clone(v, seen)
    end
    return setmetatable(out, getmetatable(t))
end

-- 两张表键值完全一致（浅比较）
function T.isclone(a, b)
    if a == b then return true end
    if rawtype(a) ~= "table" or rawtype(b) ~= "table" then return false end
    local n = 0
    for k, v in pairs(a) do
        if b[k] ~= v then return false end
        n = n + 1
    end
    for _ in pairs(b) do n = n - 1 end
    return n == 0
end

-- 只保留连续整数下标部分
function T.toarray(t)
    local out = {}
    for i, v in ipairs(t) do out[i] = v end
    return out
end

-- 按谓词 pred(index, value) 过滤数组，结果重新紧排
function T.filter_array(t, pred)
    local out = {}
    for i, v in ipairs(t) do
        if pred(i, v) then out[#out + 1] = v end
    end
    return out
end

-- 差集：t 中不在 other 里出现（按值）的元素
function T.difference(t, other)
    local exclude = {}
    for _, v in pairs(other or {}) do exclude[v] = true end
    local out = {}
    for k, v in pairs(t) do
        if not exclude[v] then
            if math.type(k) == "integer" then out[#out + 1] = v else out[k] = v end
        end
    end
    return out
end

-- 数组里第一个满足 pred 的元素
function T.find(t, pred)
    for i, v in ipairs(t) do
        if pred(v, i) then return v, i end
    end
    return nil
end

-- =====================================================================
-- 字符串工具  M.string.*
-- =====================================================================
local S = {}
M.string = S

-- 按 Lua 模式切分；pattern 缺省为空白。ret 可传入已有表用于追加
function S.split(s, pattern, ret)
    ret = ret or {}
    pattern = pattern or "%s+"
    if s == "" then
        ret[#ret + 1] = ""
        return ret
    end
    local pos = 1
    while true do
        local a, b = s:find(pattern, pos)
        if not a or b < a then
            -- 空匹配或没找到：把剩余部分全部收下
            ret[#ret + 1] = s:sub(pos)
            break
        end
        ret[#ret + 1] = s:sub(pos, a - 1)
        pos = b + 1
        if pos > #s then
            ret[#ret + 1] = ""
            break
        end
    end
    return ret
end

-- 去掉首尾匹配 pattern（默认空白）的部分
function S.strip(s, pattern)
    pattern = pattern or "%s"
    local out = s:gsub("^" .. pattern .. "+", "")
    out = out:gsub(pattern .. "+$", "")
    return out
end
S.trim = S.strip

-- 去除多行文本的公共缩进。first=true 时以第一行的缩进为准
function S.dedent(text, first)
    local lines = S.split(text, "\n")
    local indent
    if first then
        indent = lines[1] and lines[1]:match("^(%s*)") or ""
    else
        for _, line in ipairs(lines) do
            if line:match("%S") then
                local ws = line:match("^(%s*)")
                if not indent or #ws < #indent then indent = ws end
            end
        end
        indent = indent or ""
    end
    if indent == "" then return text end
    for i, line in ipairs(lines) do
        if line:sub(1, #indent) == indent then
            lines[i] = line:sub(#indent + 1)
        else
            lines[i] = line:gsub("^%s+", "")
        end
    end
    return table.concat(lines, "\n")
end

-- 光标语义：偏移 o 是 1..#s+1 之间的字节位置（光标在第 o 个字节之前）。
-- prev_glyph 返回光标前一个字形的起始偏移和字形本身；next_glyph 返回光标后
-- 一个字形结束后的偏移和字形本身。越界返回 nil。
function S.prev_glyph(s, o)
    o = o or (#s + 1)
    if o <= 1 or o > #s + 1 then return nil end
    local start = o - 1
    -- 往前跨过 UTF-8 续字节 (10xxxxxx)
    while start > 1 and (s:byte(start) & 0xC0) == 0x80 do
        start = start - 1
    end
    return start, s:sub(start, o - 1)
end

function S.next_glyph(s, o)
    o = o or 1
    if o < 1 or o > #s then return nil end
    local stop = o
    while stop < #s and (s:byte(stop + 1) & 0xC0) == 0x80 do
        stop = stop + 1
    end
    return stop + 1, s:sub(o, stop)
end

-- 是否以 prefix 开头 / suffix 结尾（纯文本，不是模式）
function S.startswith(s, prefix)
    return s:sub(1, #prefix) == prefix
end

function S.endswith(s, suffix)
    return suffix == "" or s:sub(-#suffix) == suffix
end

-- =====================================================================
-- os 工具  M.os.*
-- =====================================================================
local O = {}
M.os = O

-- 文件存在且可读
function O.exists(path)
    if rawtype(path) ~= "string" or path == "" then return false end
    local fh = io.open(path, "rb")
    if not fh then return false end
    fh:close()
    return true
end

-- =====================================================================
-- 转义
-- =====================================================================
local html_escape_map = {
    ["&"] = "&amp;", ["<"] = "&lt;", [">"] = "&gt;", ['"'] = "&quot;", ["'"] = "&#39;",
}
local html_unescape_map = {
    amp = "&", lt = "<", gt = ">", quot = '"', apos = "'", nbsp = "\194\160",
}

-- HTML/XML 转义
function M.escape(text)
    if text == nil then return "" end
    return (tostring(text):gsub("[&<>\"']", html_escape_map))
end

-- 反转义：命名实体 + 十进制/十六进制数字实体
function M.unescape(text)
    if text == nil then return "" end
    return (tostring(text):gsub("&(#?[%w]+);", function(ent)
        if ent:sub(1, 1) == "#" then
            local code
            if ent:sub(2, 2):lower() == "x" then
                code = tonumber(ent:sub(3), 16)
            else
                code = tonumber(ent:sub(2))
            end
            if code and code >= 0 and code < 0x110000 then
                return utf8.char(code)
            end
            return "&" .. ent .. ";"
        end
        return html_unescape_map[ent] or ("&" .. ent .. ";")
    end))
end

-- SQL 字面量：单引号翻倍并加引号；nil → NULL
function M.sql_escape(s)
    if s == nil then return "NULL" end
    if rawtype(s) == "number" then return tostring(s) end
    if rawtype(s) == "boolean" then return s and "1" or "0" end
    return "'" .. tostring(s):gsub("'", "''") .. "'"
end

-- Lua 模式转义：给魔法字符加 %
function M.lua_escape(s)
    return (tostring(s):gsub("[%^%$%(%)%%%.%[%]%*%+%-%?]", "%%%0"))
end

-- 与区域设置无关的数字转字符串。sigs：浮点数保留的有效位数（默认 2 位小数）
function M.ntos(num, sigs)
    if rawtype(num) ~= "number" then return tostring(num) end
    local s
    if math.type(num) == "integer" or num == math.floor(num) and math.abs(num) < 2 ^ 53 then
        s = string.format("%d", math.floor(num))
    elseif sigs then
        s = string.format("%." .. tostring(sigs) .. "g", num)
    else
        s = string.format("%.2f", num)
    end
    return (s:gsub(",", "."))
end

-- luakit.uri_encode / uri_decode 的透传（没有内核时给纯 Lua 兜底）
function M.uri_encode(s, allowed)
    local L = lk()
    if L and L.uri_encode then return L.uri_encode(s, allowed) end
    return (tostring(s):gsub("[^%w%-_%.~]", function(c)
        return string.format("%%%02X", c:byte())
    end))
end

function M.uri_decode(s)
    local L = lk()
    if L and L.uri_decode then return L.uri_decode(s) end
    return (tostring(s):gsub("%%(%x%x)", function(h) return string.char(tonumber(h, 16)) end))
end

-- =====================================================================
-- 路径
-- =====================================================================
function M.join(...)
    local parts = {}
    for i = 1, select("#", ...) do
        local p = select(i, ...)
        if p ~= nil and p ~= "" then parts[#parts + 1] = tostring(p) end
    end
    local out = table.concat(parts, "/")
    -- 折叠重复斜杠（保留 scheme:// 里的双斜杠）
    out = out:gsub("([^:])//+", "%1/")
    return out
end

function M.basename(path)
    path = tostring(path):gsub("/+$", "")
    return path:match("([^/]*)$") or path
end

function M.dirname(path)
    path = tostring(path):gsub("/+$", "")
    local dir = path:match("^(.*)/[^/]*$")
    if dir == nil then return "." end
    if dir == "" then return "/" end
    return dir
end

function M.is_absolute(path)
    return rawtype(path) == "string" and path:sub(1, 1) == "/"
end

function M.get_cwd()
    local f = fs()
    if f and f.currentdir then
        local ok, d = pcall(f.currentdir)
        if ok and rawtype(d) == "string" then return d end
    end
    return "."
end

local function attr_mode(path)
    local f = fs()
    if not f or not f.attributes then
        -- 没有 lfs：用 io 判断文件；目录判不了
        return O.exists(path) and "file" or nil
    end
    local ok, a = pcall(f.attributes, path)
    if not ok or rawtype(a) ~= "table" then return nil end
    return a.mode
end

function M.is_dir(path)
    return attr_mode(path) == "directory"
end

function M.is_file(path)
    local m = attr_mode(path)
    return m == "file" or m == "link"
end

-- 逐级建目录；成功（或已存在）返回 true
function M.mkpath(path)
    if rawtype(path) ~= "string" or path == "" then return false end
    if M.is_dir(path) then return true end
    local f = fs()
    local acc = path:sub(1, 1) == "/" and "" or nil
    for seg in path:gmatch("[^/]+") do
        acc = acc and (acc .. "/" .. seg) or seg
        if not M.is_dir(acc) then
            if f and f.mkdir then
                pcall(f.mkdir, acc)
            else
                os.execute("mkdir -p '" .. acc:gsub("'", "'\\''") .. "'")
            end
        end
    end
    return M.is_dir(path)
end

-- luakit 语义：返回 0 表示成功
function M.mkdir(path)
    return M.mkpath(path) and 0 or 1
end

-- 递归删除。参数是 widget 时：销毁整棵子树并返回全部被销毁的 widget 列表；
-- 参数是路径时：删除整个目录树。
function M.recursive_remove(target)
    if type(target) == "widget" then
        local out = {}
        local function walk(wi)
            if not wi or not wi.is_alive then return end
            local kids = {}
            local ok, children = pcall(function() return wi.children end)
            if ok and rawtype(children) == "table" then
                for _, c in ipairs(children) do kids[#kids + 1] = c end
            end
            local okc, child = pcall(function() return wi.child end)
            if okc and child and not T.hasitem(kids, child) then kids[#kids + 1] = child end
            for _, c in ipairs(kids) do walk(c) end
            out[#out + 1] = wi
            if wi.type ~= "webview" then pcall(wi.destroy, wi) end
        end
        walk(target)
        return out
    end
    if rawtype(target) ~= "string" then return false end
    local f = fs()
    if M.is_dir(target) then
        if f and f.dir then
            local ok, iter = pcall(f.dir, target)
            if ok then
                for name in iter do
                    if name ~= "." and name ~= ".." then
                        M.recursive_remove(target .. "/" .. name)
                    end
                end
            end
        end
        if f and f.rmdir then pcall(f.rmdir, target) end
    else
        os.remove(target)
    end
    return not M.is_dir(target) and not O.exists(target)
end

-- =====================================================================
-- 文件查找
-- =====================================================================
local function search_dirs(kind)
    local L = lk()
    local dirs = {}
    local function add(d)
        if rawtype(d) == "string" and d ~= "" and not T.hasitem(dirs, d) then dirs[#dirs + 1] = d end
    end
    local dev = L and L.dev_paths
    if dev then
        local cwd = M.get_cwd()
        add(cwd)
        if rawtype(dev) == "table" then for _, d in ipairs(dev) do add(d) end end
        if kind == "config" then add(cwd .. "/config") end
        if kind == "resource" then add(cwd .. "/resources") end
        if kind == "install" or kind == "data" then add(cwd .. "/lib") end
    end
    if not L then return dirs end
    local install = L.install_path
    if kind == "config" then
        add(L.config_dir)
        if install then add(install .. "/config") end
    elseif kind == "data" then
        add(L.data_dir)
        add(install)
        if install then add(install .. "/lib") end
    elseif kind == "cache" then
        add(L.cache_dir)
    elseif kind == "resource" then
        local rp = L.resource_path
        if rawtype(rp) == "string" then
            for d in rp:gmatch("[^;]+") do add(d) end
        end
        if install then add(install .. "/resources") end
    elseif kind == "install" then
        add(install)
        if install then
            add(install .. "/lib")
            add(install .. "/config")
        end
        local ip = L.install_paths
        if rawtype(ip) == "table" then for _, d in pairs(ip) do add(d) end end
    end
    return dirs
end

-- 在给定目录列表中查找相对路径；绝对路径直接检查
function M.find_path(name, dirs, silent)
    if rawtype(name) ~= "string" or name == "" then
        if silent then return nil end
        error("find_path: invalid file name " .. tostring(name), 3)
    end
    if M.is_absolute(name) then
        if O.exists(name) or M.is_dir(name) then return name end
    else
        for _, d in ipairs(dirs) do
            local p = d .. "/" .. name
            if O.exists(p) or M.is_dir(p) then return p end
        end
    end
    if silent then return nil end
    error(("unable to locate %q (searched: %s)"):format(name, table.concat(dirs, ", ")), 3)
end

function M.find_config(name, silent)   return M.find_path(name, search_dirs("config"), silent) end
function M.find_data(name, silent)     return M.find_path(name, search_dirs("data"), silent) end
function M.find_cache(name, silent)    return M.find_path(name, search_dirs("cache"), silent) end
function M.find_resource(name, silent) return M.find_path(name, search_dirs("resource"), silent) end
function M.find_install(name, silent)  return M.find_path(name, search_dirs("install"), silent) end

-- =====================================================================
-- 杂项
-- =====================================================================
function M.time()
    local L = lk()
    if L and L.time then return L.time() end
    return os.time()
end

-- 执行一段 Lua 源码并返回其返回值
function M.eval(src, env)
    local chunk, err = load(src, "=eval", "t", env or _G)
    if not chunk then error(err, 2) end
    return chunk()
end

-- 只编译不执行；返回函数或 (nil, err)
function M.checkfile(path)
    local chunk, err = loadfile(path)
    if not chunk then return nil, err end
    return chunk
end

-- 废弃提示：同一调用点只警告一次
local deprecation_seen = {}
function M.deprecate(name, replacement, level)
    local info = debug.getinfo((level or 2) + 1, "Sl")
    local site = info and (tostring(info.short_src) .. ":" .. tostring(info.currentline)) or "?"
    local key = site .. "|" .. tostring(name)
    if deprecation_seen[key] then return end
    deprecation_seen[key] = true
    local text = ("%s is deprecated"):format(tostring(name))
    if replacement then text = text .. ("; use %s instead"):format(tostring(replacement)) end
    text = text .. " (" .. site .. ")"
    local m = rawget(_G, "msg")
    if m and m.warn then m.warn("%s", text) else io.stderr:write(text, "\n") end
end

-- 旧接口：直接交给 soup
function M.parse_uri(s)
    M.deprecate("lousy.util.parse_uri", "lousy.uri.parse")
    local sp = rawget(_G, "soup")
    if sp and sp.parse_uri then return sp.parse_uri(s) end
    return nil
end

-- /etc/hosts 里的主机名（Android 上通常不可读，返回空表）
local etc_hosts_cache
function M.get_etc_hosts(force)
    if etc_hosts_cache and not force then return etc_hosts_cache end
    local out = {}
    local fh = io.open("/etc/hosts", "r")
    if fh then
        for line in fh:lines() do
            line = line:gsub("#.*$", "")
            local first = true
            for word in line:gmatch("%S+") do
                if not first then out[#out + 1] = word end
                first = false
            end
        end
        fh:close()
    end
    etc_hosts_cache = out
    return out
end

-- 读取整个文件
function M.read_file(path)
    local fh, err = io.open(path, "rb")
    if not fh then return nil, err end
    local data = fh:read("a")
    fh:close()
    return data
end

-- 原子写文件（先写临时文件再改名）
function M.write_file(path, data)
    local tmp = path .. ".tmp." .. tostring(os.time())
    local fh, err = io.open(tmp, "wb")
    if not fh then return nil, err end
    fh:write(data)
    fh:close()
    local ok, rerr = os.rename(tmp, path)
    if not ok then
        os.remove(tmp)
        return nil, rerr
    end
    return true
end

M.unpack = unpack

return M
