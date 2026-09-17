-- luakit 全局模块（对应 luakit clib/luakit.c + common/clib/luakit.c）
--
-- 属性：config_dir data_dir cache_dir execpath confpath resource_path(rw) verbose nounique
--       dev_paths enable_spell_checking(rw) spell_checking_languages(rw) process_limit(rw)
--       options website_data webkit2 windows webkit_version webkit_user_agent_version
--       selection install_path install_paths version
-- 函数：quit spawn spawn_sync time uri_encode uri_decode idle_add idle_remove
--       register_scheme allow_certificate save_file exec wch_lower wch_upper
--       clear_favicon_database register_function(web 扩展，P4)
-- 信号：page-created / web-extension-created（P4 renderer 发）、download-start（P1）、
--       can-close、scheme-request::<name>（P2）

local object = __lk.object
local env = __lk.env
local N = __luakit
local unpack = table.unpack

local install_dir = __lk.install_dir
local config_dir = env.config_dir or (install_dir .. "/config")
local data_dir = env.data_dir or (install_dir .. "/data")
local cache_dir = env.cache_dir or (install_dir .. "/cache")

for _, d in ipairs({ config_dir, data_dir, cache_dir }) do
    pcall(N.lfs_mkdir, d)
end

local state = {
    resource_path = install_dir .. "/resources",
    process_limit = 0,
    spell = false,
    spell_langs = {},
    options = {},
    schemes = {},     -- name -> true（P2 接自定义 scheme 处理）
    allowed_certs = {}, -- host -> cert pem
}

-- ===== 命令行式拆词（g_shell_parse_argv 的够用子集）=====
local function shell_split(cmd)
    local argv, cur, i, n = {}, nil, 1, #cmd
    local quote = nil
    while i <= n do
        local c = cmd:sub(i, i)
        if quote then
            if c == quote then
                quote = nil
            elseif c == "\\" and quote == '"' and i < n then
                i = i + 1
                cur = (cur or "") .. cmd:sub(i, i)
            else
                cur = (cur or "") .. c
            end
        elseif c == "'" or c == '"' then
            quote = c
            cur = cur or ""
        elseif c == "\\" and i < n then
            i = i + 1
            cur = (cur or "") .. cmd:sub(i, i)
        elseif c:match("%s") then
            if cur then
                argv[#argv + 1] = cur
                cur = nil
            end
        else
            cur = (cur or "") .. c
        end
        i = i + 1
    end
    if quote then
        return nil, "unterminated quote"
    end
    if cur then argv[#argv + 1] = cur end
    return argv
end

-- ===== spawn 回调登记 =====
local spawn_cbs = {}
local next_spawn_id = 1

-- ===== idle =====
local idle_fns = {}
local idle_scheduled = false
local IDLE_POST_ID = 1

local function pump_idle()
    idle_scheduled = false
    if #idle_fns == 0 then return end
    local copy = { unpack(idle_fns) }
    for _, fn in ipairs(copy) do
        local ok, keep = xpcall(fn, debug.traceback)
        if not ok then
            msg.warn("idle callback error: %s", tostring(keep))
            keep = false
        end
        if not keep then
            for i, f in ipairs(idle_fns) do
                if f == fn then table.remove(idle_fns, i) break end
            end
        end
    end
    if #idle_fns > 0 and not idle_scheduled then
        idle_scheduled = true
        -- 处理器要求继续时别把 Lua 线程转死：下一帧再来
        lemurx.timer.after(16, function() N.post(IDLE_POST_ID) end)
    end
end

local function idle_add(fn)
    if type(fn) ~= "function" then error("luakit.idle_add expects a function", 2) end
    idle_fns[#idle_fns + 1] = fn
    if not idle_scheduled then
        idle_scheduled = true
        N.post(IDLE_POST_ID)
    end
end

local function idle_remove(fn)
    for i, f in ipairs(idle_fns) do
        if f == fn then
            table.remove(idle_fns, i)
            return true
        end
    end
    return false
end

-- ===== selection（剪贴板）=====
local selection = setmetatable({}, {
    __index = function(_, k)
        if k == "primary" or k == "clipboard" or k == "secondary" then
            local ok, v = pcall(lemurx.clipboard.get)
            return ok and v or nil
        end
        return nil
    end,
    __newindex = function(_, k, v)
        if k == "primary" or k == "clipboard" or k == "secondary" then
            pcall(lemurx.clipboard.set, v == nil and "" or tostring(v))
            return
        end
        error("luakit.selection: unknown selection " .. tostring(k), 2)
    end,
})

-- ===== website_data =====
local type_map = {
    memory_cache = "cache", disk_cache = "cache", offline_application_cache = "cache",
    session_storage = "site_data", local_storage = "site_data", websql_databases = "site_data",
    indexeddb_databases = "site_data", plugin_data = "site_data",
    cookies = "cookies", device_id_hash_salt = "site_data", hsts_cache = "cache",
    all = "all",
}

local function map_types(types)
    if type(types) ~= "table" then error("website data types must be a table", 3) end
    local out, seen = {}, {}
    for _, t in ipairs(types) do
        if type(t) ~= "string" then error("website data types must be strings", 3) end
        local m = type_map[t]
        if not m then error("unknown website data type " .. t, 3) end
        if m == "all" then
            return { "cache", "cookies", "site_data", "history" }
        end
        if not seen[m] then
            seen[m] = true
            out[#out + 1] = m
        end
    end
    if #out == 0 then error("no website data types specified", 3) end
    return out
end

-- GTimeSpan（微秒）→ lemurx 时间桶
local function map_timespan(us)
    us = tonumber(us) or 0
    if us <= 0 then return "all" end
    local s = us / 1e6
    if s <= 15 * 60 then return "15m" end
    if s <= 3600 then return "hour" end
    if s <= 86400 then return "day" end
    if s <= 7 * 86400 then return "week" end
    return "all"
end

local website_data = {
    clear = function(types, timespan)
        local mapped = map_types(types)
        local ok, err = pcall(lemurx.data.clear, mapped, map_timespan(timespan))
        if not ok then return nil, tostring(err) end
        return true
    end,
    fetch = function(types)
        map_types(types)
        -- Chromium 没有 WebKitWebsiteDataManager.fetch 那种按域枚举；返回空表
        msg.verbose("luakit.website_data.fetch: per-domain enumeration not available on Chromium")
        return {}
    end,
    remove = function(types, domain)
        map_types(types)
        if type(domain) ~= "string" then error("domain must be a string", 2) end
        if lemurx.data.clearOrigin then
            local ok = pcall(lemurx.data.clearOrigin, domain)
            return ok
        end
        msg.verbose("luakit.website_data.remove(%s): per-domain removal not wired", domain)
        return true
    end,
}

-- ===== install_paths =====
local install_paths = {
    install_dir = install_dir,
    config_dir = config_dir,
    data_dir = data_dir,
    cache_dir = cache_dir,
    doc_dir = install_dir .. "/doc",
    man_dir = install_dir .. "/man",
    pixmap_dir = install_dir .. "/resources",
    app_dir = install_dir,
}

-- ===== 键名大小写（gdk_keyval_convert_case 的 ASCII/UTF-8 近似）=====
local keyname_upper = {
    minus = "underscore", equal = "plus", bracketleft = "braceleft", bracketright = "braceright",
    semicolon = "colon", apostrophe = "quotedbl", grave = "asciitilde", backslash = "bar",
    comma = "less", period = "greater", slash = "question",
    ["1"] = "exclam", ["2"] = "at", ["3"] = "numbersign", ["4"] = "dollar", ["5"] = "percent",
    ["6"] = "asciicircum", ["7"] = "ampersand", ["8"] = "asterisk", ["9"] = "parenleft", ["0"] = "parenright",
}
local keyname_lower = {}
for k, v in pairs(keyname_upper) do keyname_lower[v] = k end

local function wch_case(key, upper)
    if type(key) ~= "string" then error("expected key name string", 3) end
    if utf8.len(key) == 1 then
        return upper and key:upper() or key:lower()
    end
    local tbl = upper and keyname_upper or keyname_lower
    return tbl[key] or key
end

-- ===== 模块 =====
local luakit = object.module("luakit", {
    funcs = {
        quit = function()
            local M = rawget(_G, "luakit")
            if M and M.__has_signal("can-close") then
                local ret = M.emit_signal("can-close")
                if ret == false then return end
            end
            msg.info("luakit.quit(): exiting process")
            os.exit(0, true)
        end,

        spawn = function(cmd, cb)
            if type(cmd) ~= "string" then error("luakit.spawn expects a command string", 2) end
            local argv, err = shell_split(cmd)
            if not argv or #argv == 0 then
                error("luakit.spawn: " .. tostring(err or "empty command"), 2)
            end
            local id = 0
            if cb ~= nil then
                if type(cb) ~= "function" then error("luakit.spawn callback must be a function", 2) end
                id = next_spawn_id
                next_spawn_id = next_spawn_id + 1
                spawn_cbs[id] = cb
            end
            local pid, perr = N.spawn(argv, id)
            if not pid then
                spawn_cbs[id] = nil
                error("luakit.spawn: " .. tostring(perr), 2)
            end
            return pid
        end,

        spawn_sync = function(cmd)
            if type(cmd) ~= "string" then error("luakit.spawn_sync expects a command string", 2) end
            local argv, err = shell_split(cmd)
            if not argv or #argv == 0 then
                error("luakit.spawn_sync: " .. tostring(err or "empty command"), 2)
            end
            return N.spawn_sync(argv)
        end,

        exec = function(cmd)
            -- luakit.exec 用同一命令替换当前进程（重启自己）；Android 上做不到 execl，
            -- 退化为 spawn + 退出
            msg.warn("luakit.exec(%q): exec() not available on Android, spawning then quitting", tostring(cmd))
            if type(cmd) == "string" and cmd ~= "" then
                pcall(_G.luakit.spawn, cmd)
            end
            _G.luakit.quit()
        end,

        time = N.time,
        uri_encode = function(s, allowed)
            if type(s) ~= "string" then error("luakit.uri_encode expects a string", 2) end
            return N.uri_encode(s, allowed)
        end,
        uri_decode = function(s, illegal)
            if type(s) ~= "string" then error("luakit.uri_decode expects a string", 2) end
            return N.uri_decode(s, illegal)
        end,
        idle_add = idle_add,
        idle_remove = idle_remove,

        register_scheme = function(name)
            if type(name) ~= "string" or not name:match("^[%a][%w+%-.]*$") then
                error("luakit.register_scheme: invalid scheme name " .. tostring(name), 2)
            end
            if name == "http" or name == "https" then
                error("luakit.register_scheme: cannot override http/https", 2)
            end
            if state.schemes[name] then return end
            state.schemes[name] = true
            if __lk.on_register_scheme then
                __lk.on_register_scheme(name)
            end
        end,

        allow_certificate = function(host, cert)
            if type(host) ~= "string" then error("host must be a string", 2) end
            if type(cert) ~= "string" then error("certificate must be a PEM string", 2) end
            state.allowed_certs[host] = cert
            if __lk.on_allow_certificate then
                __lk.on_allow_certificate(host, cert)
            end
        end,

        save_file = function(title, window, default_folder, default_name)
            -- GTK 文件对话框；Android 上没有同步对话框，按 luakit 默认行为返回默认路径
            default_folder = default_folder or (xdg.download_dir or data_dir)
            default_name = default_name or "download"
            local path = default_folder .. "/" .. default_name
            msg.verbose("luakit.save_file(%q) -> %s (no native chooser)", tostring(title), path)
            return path
        end,

        wch_lower = function(key) return wch_case(key, false) end,
        wch_upper = function(key) return wch_case(key, true) end,

        clear_favicon_database = function()
            pcall(lemurx.data.clear, { "cache" }, "all")
        end,

        -- web 扩展进程侧 API 注册（chrome_wm 用）；P4 renderer 内核实现，先记账
        register_function = function(pattern, name, fn)
            __lk.registered_functions = __lk.registered_functions or {}
            table.insert(__lk.registered_functions, { pattern = pattern, name = name, fn = fn })
            if __lk.on_register_function then
                __lk.on_register_function(pattern, name, fn)
            end
        end,
    },
    props = {
        config_dir = { get = function() return config_dir end },
        data_dir = { get = function() return data_dir end },
        cache_dir = { get = function() return cache_dir end },
        execpath = { get = function() return "/system/bin/app_process" end },
        confpath = { get = function() return config_dir .. "/rc.lua" end },
        resource_path = {
            get = function() return state.resource_path end,
            set = function(v)
                if type(v) ~= "string" then error("resource_path must be a string", 3) end
                state.resource_path = v
            end,
        },
        verbose = { get = function() return lemurx.storage.get("luakit_verbose", "0") == "1" or env.verbose == true end },
        nounique = { get = function() return true end },
        dev_paths = { get = function() return false end },
        webkit2 = { get = function() return true end },
        enable_spell_checking = {
            get = function() return state.spell end,
            set = function(v)
                state.spell = v and true or false
                pcall(lemurx.prefs.set, "browser.enable_spellchecking", state.spell)
            end,
        },
        spell_checking_languages = {
            get = function() return { unpack(state.spell_langs) } end,
            set = function(v)
                if type(v) ~= "table" then error("spell_checking_languages must be a table", 3) end
                state.spell_langs = { unpack(v) }
                pcall(lemurx.prefs.set, "spellcheck.dictionaries", state.spell_langs)
            end,
        },
        process_limit = {
            get = function() return state.process_limit end,
            set = function(v)
                if type(v) ~= "number" then error("process_limit must be a number", 3) end
                state.process_limit = v
                -- Chromium 进程模型由 site isolation 决定，这里只记值
            end,
        },
        options = { get = function() return state.options end },
        website_data = { get = function() return website_data end },
        windows = {
            get = function()
                if __lk.list_windows then return __lk.list_windows() end
                return {}
            end,
        },
        webkit_version = {
            -- lib 里用 "^(%d+)%.(%d+)" 做能力判断（>=2.18 等）；给一个足够新的 WebKitGTK 号，
            -- 真实引擎版本在 luakit.chromium_version
            get = function() return "2.99.0" end,
        },
        webkit_user_agent_version = { get = function() return "605.1.15" end },
        chromium_version = { get = function() return env.chromium_version or "" end },
        selection = { get = function() return selection end },
        install_path = { get = function() return install_dir end },
        install_paths = { get = function() return install_paths end },
        version = { get = function() return "2.4.0-lemurx" .. (env.version_name and ("+" .. env.version_name) or "") end },
        web_process_id = { get = function() return 0 end },
    },
})

-- 原生回投：spawn 退出
__lk.on_spawn_exit = function(id, reason, status)
    local cb = spawn_cbs[id]
    if not cb then return end
    spawn_cbs[id] = nil
    local ok, err = xpcall(cb, debug.traceback, reason, status)
    if not ok then msg.warn("spawn callback error: %s", tostring(err)) end
end

__lk.on_post = function(id)
    if id == IDLE_POST_ID then pump_idle() end
end

__lk.registered_schemes = state.schemes
__lk.allowed_certs = state.allowed_certs

_G.luakit = luakit
package.loaded["luakit"] = luakit
return luakit
