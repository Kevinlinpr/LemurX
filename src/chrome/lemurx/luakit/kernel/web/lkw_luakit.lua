-- luakit 模块（渲染进程版，对应 luakit extension/clib/luakit.c + common/clib/luakit.c）
--
--   luakit.web_process_id   luakit.version   luakit.install_path* / resource_path
--   luakit.add_signal("page-created", fn(page))
--   luakit.register_function(uri_pattern, name, fn(page, resolve, reject, ...))
--   luakit.idle_add(fn) / idle_remove(fn)   luakit.time()   luakit.uri_encode / uri_decode
--   luakit.wch_lower / wch_upper

local N = __luakit_web
local object = __lk.object
local js = __lk.js
local env = __lk.env or {}
local unpack = table.unpack

local M = {}
object.setup_module_signals(M)

local pid = tonumber(env.pid) or 0
M.web_process_id = pid
M.version = env.version or "lemurx"
M.webkit_version = env.chromium_version or ""
M.install_path = env.install_dir
M.install_paths = env.install_paths or { install_dir = env.install_dir }
M.resource_path = (env.install_dir or "") .. "/resources"
M.config_dir = env.config_dir
M.data_dir = env.data_dir
M.cache_dir = env.cache_dir
M.dev_paths = false
M.verbose = env.verbose and true or false
M.nounique = true
M.windows = {}

function M.time() return os.clock() end

local function hex(c) return ("%%%02X"):format(c:byte()) end
function M.uri_encode(s)
    return (tostring(s):gsub("[^%w%-%._~]", hex))
end
function M.uri_decode(s)
    return (tostring(s):gsub("%%(%x%x)", function(h) return string.char(tonumber(h, 16)) end))
end
M.wch_lower = function(s) return utf8 and s:lower() or s:lower() end
M.wch_upper = function(s) return s:upper() end

-- idle：渲染进程没有 GLib 主循环；用 JS 微任务队列近似（任一存活页面即可，退化为直接调用）
local idle_queue = {}
function M.idle_add(fn)
    if type(fn) ~= "function" then error("luakit.idle_add expects a function", 2) end
    idle_queue[#idle_queue + 1] = fn
    __lk.schedule_idle()
end
function M.idle_remove(fn)
    for i = #idle_queue, 1, -1 do
        if idle_queue[i] == fn then table.remove(idle_queue, i) return true end
    end
    return false
end
function __lk.run_idle()
    local q = idle_queue
    idle_queue = {}
    for _, fn in ipairs(q) do
        local ok, keep = xpcall(fn, debug.traceback)
        if not ok then
            msg.warn("idle callback error: %s", tostring(keep))
        elseif keep == true then
            idle_queue[#idle_queue + 1] = fn
        end
    end
    if #idle_queue > 0 then __lk.schedule_idle() end
end
local idle_scheduled = false
function __lk.schedule_idle()
    if idle_scheduled then return end
    for rid in pairs(__lk.pages()) do
        if N.page_alive(rid) then
            local fn = js.function_handle(rid, function()
                idle_scheduled = false
                __lk.run_idle()
            end)
            local ok = pcall(function()
                local win = N.page_global(rid)
                N.js_call(win, "setTimeout", fn, 0)
                N.js_release(win)
            end)
            if ok then idle_scheduled = true return end
        end
    end
    -- 没有页面：直接跑
    __lk.run_idle()
end

-- register_function(pattern, name, fn)
local registered = {}    -- { pattern=, name=, fn= }
local by_name = {}       -- name -> list

function M.register_function(pattern, name, fn)
    if type(pattern) ~= "string" or pattern == "" then error("pattern cannot be empty", 2) end
    if type(name) ~= "string" or name == "" then error("function name cannot be empty", 2) end
    if type(fn) ~= "function" then error("luakit.register_function expects a function", 2) end
    local rec = { pattern = pattern, name = name, fn = fn }
    registered[#registered + 1] = rec
    by_name[name] = by_name[name] or {}
    table.insert(by_name[name], rec)
    -- 已经打开的页面立刻挂上
    for rid in pairs(__lk.pages()) do
        local uri = N.page_uri(rid)
        if uri and string.find(uri, pattern) then
            pcall(N.expose, rid, name)
        end
    end
end

__lk.on_window_cleared = function(p, rid, uri)
    local done = {}
    for _, rec in ipairs(registered) do
        if not done[rec.name] and uri and string.find(uri, rec.pattern) then
            done[rec.name] = true
            local ok, err = N.expose(rid, rec.name)
            if not ok then msg.warn("register_function(%s): %s", rec.name, tostring(err)) end
        end
    end
end

-- JS 调了 window[name](...)：__lk.dispatchers.fn(rid, name, resolver, ...)
function __lk.call_registered(rid, name, resolver, ...)
    local p = __lk.page_for(rid)
    local uri = N.page_uri(rid) or ""
    local list = by_name[name]
    if not list or not p then return end
    js.set_rid(resolver, rid)
    js.retain_gc(resolver)
    local function resolve(v)
        N.js_call(resolver, "resolve", js.unwrap(rid, v))
    end
    local function reject(v)
        N.js_call(resolver, "reject", js.unwrap(rid, v))
    end
    local args = table.pack(...)
    for i = 1, args.n do args[i] = __lk.wrap_value(args[i], rid) end
    for _, rec in ipairs(list) do
        if string.find(uri, rec.pattern) then
            local ok, err = xpcall(rec.fn, debug.traceback, p, resolve, reject, unpack(args, 1, args.n))
            if not ok then
                msg.warn("registered function %s error: %s", name, tostring(err))
                pcall(reject, tostring(err))
            end
            return
        end
    end
end

function __lk.emit_luakit_signal(name, ...)
    return M.emit_signal(name, ...)
end

-- luakit 用 luakit.emit_signal(...)；web 侧没有 luakit.quit 等 UI 方法
setmetatable(M, {
    __index = function(_, k)
        if k == "web_process_id" then return pid end
        return nil
    end,
})

_G.luakit = M
return M
