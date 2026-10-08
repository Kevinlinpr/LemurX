-- UI 进程 ⇄ 渲染进程 Lua 的传输层（对应 luakit 的 ipc_endpoint / web_module 接线）
--
-- 原生半边：chrome/browser/ui/android/lemurx/lemurx_luakit_web_host.cc（__luakit.web_*）
-- 渲染进程半边：chrome/renderer/lemurx/luakit_web_extension.cc + kernel/web/*.lua
--
-- 接的东西：
--   __lk.ipc_transport.send(channel, signame, args, target)  ← lk_ipc.lua
--   __lk.on_require_web_module(name)                          ← lk_ipc.lua
--   __lk.dispatchers.webipc / weblog / webeval / webext        ← C++ 回投
--   luakit "web-extension-created"(view) + view "web-extension-loaded"

local N = __luakit
local object = __lk.object
local unpack = table.unpack

local function tab_of_target(target)
    if target == nil then return -1 end
    if type(target) == "number" then return target end
    if type(target) == "widget" and target.type == "webview" then
        return target.id
    end
    if type(target) == "table" and target.id then return target.id end
    return -1
end

-- JSON 数组不能有洞：nil → json null
local function pack_args(args)
    local arr = {}
    local n = args.n or #args
    for i = 1, n do
        local v = args[i]
        if v == nil then
            v = N.json_decode("null")
        elseif type(v) == "widget" then
            v = v.id
        end
        arr[i] = v
    end
    return N.json_encode(arr)
end

__lk.ipc_transport = {
    send = function(channel, signame, args, target)
        local packed = { n = #args, unpack(args) }
        N.web_emit(channel, tab_of_target(target), signame, pack_args(packed))
    end,
}

-- LemurX 扩展：定向发给某个渲染进程（args 是普通数组）
__lk.ipc_send_pid = function(channel, pid, signame, args)
    local packed = { n = #args, unpack(args) }
    N.web_emit_pid(channel, pid, signame, pack_args(packed))
end
__lk.web_process_hooks = __lk.web_process_hooks or {}

__lk.on_require_web_module = function(name)
    N.web_require(name)
end

-- 内核加载前 require_web_module 已登记过的模块补发
for _, name in ipairs(__lk.web_modules or {}) do
    N.web_require(name)
end
if __lk.ipc_flush_pending then __lk.ipc_flush_pending() end

-- ===== 回投 =====
local D = __lk.dispatchers

-- 渲染进程 ipc_channel:emit_signal → 本地通道处理器 (ch, ...)
D.webipc = function(pid, json)
    local ev = N.json_decode(json)
    if not ev then return end
    local args = N.json_decode(ev.args or "[]") or {}
    __lk.ipc_deliver(ev.channel, ev.signame, args, pid)
end

-- 渲染进程 msg.*：level 0 fatal 1 warn 2 info 3 verbose 4 debug
local level_names = { [0] = "fatal", [1] = "warn", [2] = "info", [3] = "verbose", [4] = "debug" }
D.weblog = function(level, json, pid)
    local ev = N.json_decode(json)
    if not ev then return end
    local name = level_names[tonumber(level) or 2] or "info"
    if name == "fatal" then name = "error" end
    local fn = msg[name] or msg.info
    fn("[web %s] %s: %s", tostring(pid), tostring(ev.group or "?"), tostring(ev.msg or ""))
end

-- webview:eval_js 经渲染进程执行的回包
local eval_callbacks = {}
local next_eval = 1
D.webeval = function(cb_id, json)
    local cb = eval_callbacks[cb_id]
    eval_callbacks[cb_id] = nil
    if not cb then return end
    local ev = N.json_decode(json) or {}
    local result = nil
    if ev.result then result = N.json_decode(ev.result) end
    local ok, err = xpcall(cb, debug.traceback, result, ev.error)
    if not ok then msg.warn("web eval callback error: %s", tostring(err)) end
end

-- 在渲染进程 Lua 状态里跑 JS（与 webview:eval_js 的 CDP 路径互补：这条走主世界 V8，
-- 和 luakit 的 web extension 同一个上下文，能看到 register_function 暴露的东西）
function __lk.web_eval(view, script, source, cb)
    local id = next_eval
    next_eval = next_eval + 1
    eval_callbacks[id] = cb or function() end
    N.web_eval(tab_of_target(view), script, source or "(lua)", id)
end

function __lk.web_scroll(view, x, y)
    N.web_scroll(tab_of_target(view), math.floor(tonumber(x) or 0), math.floor(tonumber(y) or 0))
end

-- 渲染进程生命周期
D.webext = function(pid, json)
    local ev = N.json_decode(json)
    if not ev then return end
    if ev.ev == "created" then
        msg.verbose("web extension ready in process %s", tostring(pid))
        -- LemurX 扩展：渲染进程 Lua 状态就位（web 模块已 require）。官方脚本在这里把
        -- 规则表等大块数据推给这个进程（__lk.ipc_send_pid），不用广播。
        for _, hook in ipairs(__lk.web_process_hooks) do
            local ok, err = pcall(hook, pid)
            if not ok then msg.warn("web process hook error: %s", tostring(err)) end
        end
    elseif ev.ev == "page" then
        local view = __lk.webview_for_tab and __lk.webview_for_tab(ev.tab, false)
        if view and object.is_alive(view) then
            -- luakit webview.c webview_connect_to_endpoint 的两次通知
            luakit.emit_signal("web-extension-created", view)
            object.emit_signal(view, "web-extension-loaded")
        else
            luakit.emit_signal("web-extension-created")
        end
    elseif ev.ev == "destroyed" then
        msg.verbose("web extension process %s gone", tostring(pid))
    end
end

-- luakit.web_processes()：当前有 Lua 状态的渲染进程 id 列表（LemurX扩展）
if rawget(_G, "luakit") then
    rawset(luakit, "web_processes", function() return N.web_processes() end)
end

return true
