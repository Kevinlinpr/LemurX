-- Lua 5.1 / LuaJIT 兼容层。luakit 的 lib/ 按 5.1 写；LemurX跑 5.4。
-- 只补 lib/ 里实际用到的：unpack loadstring setfenv/getfenv table.getn table.maxn
-- package.loaders math.pow string.gfind。

if not rawget(_G, "unpack") then
    _G.unpack = table.unpack
end

if not rawget(_G, "loadstring") then
    -- 5.4 的 load 接受字符串；chunkname 语义一致
    _G.loadstring = function(s, name)
        return load(s, name)
    end
end

if not table.getn then
    table.getn = function(t) return #t end
end

if not table.maxn then
    table.maxn = function(t)
        local n = 0
        for k in pairs(t) do
            if type(k) == "number" and k > n then n = k end
        end
        return n
    end
end

if not package.loaders then
    package.loaders = package.searchers
end

if not math.pow then
    math.pow = function(a, b) return a ^ b end
end

if not string.gfind then
    string.gfind = string.gmatch
end

-- setfenv / getfenv：5.2+ 没有函数环境，只有 _ENV upvalue。
-- 对 Lua 函数：找它的 _ENV upvalue 换成新表；level 参数表示调用栈层级
-- （setfenv(1, t) 改当前函数，markdown.lua 就这么用）。
local function find_env_upvalue(f)
    local i = 1
    while true do
        local name = debug.getupvalue(f, i)
        if not name then return nil end
        if name == "_ENV" then return i end
        i = i + 1
    end
end

if not rawget(_G, "setfenv") then
    _G.setfenv = function(f, env)
        if type(f) == "number" then
            -- level：1 = 调 setfenv 的函数
            local info = debug.getinfo(f + 1, "f")
            if not info or not info.func then
                error("setfenv: invalid level", 2)
            end
            f = info.func
        end
        if type(f) ~= "function" then
            error("setfenv: function expected", 2)
        end
        local idx = find_env_upvalue(f)
        if idx then
            debug.upvaluejoin(f, idx, function() return env end, 1)
        end
        -- 没有 _ENV upvalue 的函数不访问全局，改环境没有意义，静默
        return f
    end
end

if not rawget(_G, "getfenv") then
    _G.getfenv = function(f)
        f = f or 1
        if type(f) == "number" then
            local info = debug.getinfo(f + 1, "f")
            if not info or not info.func then return _G end
            f = info.func
        end
        if type(f) ~= "function" then return _G end
        local idx = find_env_upvalue(f)
        if not idx then return _G end
        local _, value = debug.getupvalue(f, idx)
        return value
    end
end

-- luakit 的 luaH_fixups：string.wlen、os.abspath
if not string.wlen then
    string.wlen = function(s)
        return utf8.len(s) or #s
    end
end

if not os.abspath then
    os.abspath = function(path)
        if type(path) ~= "string" or path == "" then return path end
        if path:sub(1, 1) == "/" then return path end
        if path:sub(1, 2) == "~/" then
            local home = os.getenv("HOME") or ""
            return home .. path:sub(2)
        end
        local cwd = ""
        if __luakit and __luakit.lfs_currentdir then
            cwd = __luakit.lfs_currentdir() or ""
        end
        return cwd .. "/" .. path
    end
end
