-- LemurX luakit 兼容运行时 · 内核入口
--
-- 这一层把 luakit（https://luakit.github.io）暴露给 rc.lua 的全部 C 级接口，
-- 在LemurX（Chromium/Android）上用 lemurx.* + __luakit 原生原语重新实现：
--   全局：luakit widget soup sqlite3 stylesheet timer regex utf8 xdg msg unique
--         download request ipc_channel require_web_module uris lfs
--   对象协议：add_signal / emit_signal / remove_signal / remove_signals、property::*、
--             type(obj) 返回类名（"widget"）
-- luakit 自己的纯 Lua 层（lib/、lousy/、config/rc.lua）原样放在 install_dir 下，
-- 由这里设好 package.path 后 require。
--
-- 加载时机：init.lua 之后、用户 filesDir/lua/*.lua 之前。只在本地特权主状态运行。
-- rc.lua 是否自动执行由 lemurx.storage "luakit_rc" 决定（"1" 开），
-- 因为 rc.lua 会用 window.lua 接管整个外壳（P3 控件树已落地，开了就是 luakit 的壳）。

if not __luakit then
    if lemurx and lemurx.log then lemurx.log("[luakit] kernel skipped: no native primitives in this state") end
    return
end
if rawget(_G, "luakit") and rawget(_G, "__luakit_kernel_loaded") then
    return
end

local env = __luakit.env() or {}
local install_dir = env.install_dir or ((lemurx.fs.root and lemurx.fs.root() or "") .. "/../luakit")
local kernel_dir = install_dir .. "/kernel"

-- 内核模块与 luakit lib 的搜索路径。config_dir 放最前：luakit 语义里用户配置目录里的
-- 同名模块会遮蔽安装目录（rc.lua 顶部那段 package.loaders 检查就是在提醒这件事）。
package.path = table.concat({
    (env.config_dir or (install_dir .. "/config")) .. "/?.lua",
    (env.config_dir or (install_dir .. "/config")) .. "/?/init.lua",
    install_dir .. "/lib/?.lua",
    install_dir .. "/lib/?/init.lua",
    kernel_dir .. "/?.lua",
    package.path or "",
}, ";")

local function kload(name)
    local chunk, err = loadfile(kernel_dir .. "/" .. name .. ".lua")
    if not chunk then
        error("[luakit] kernel module load failed: " .. tostring(err))
    end
    return chunk(env)
end

-- 顺序有依赖：compat51 先补 5.1 语法糖，object 给出对象协议，其余类建在其上。
local ok, err = xpcall(function()
    kload("lk_compat51")
    local object = kload("lk_object")
    _G.__lk = { env = env, object = object, install_dir = install_dir }
    kload("lk_msg")
    kload("lk_lfs")
    kload("lk_utf8")
    kload("lk_regex")
    kload("lk_timer")
    kload("lk_soup")
    kload("lk_sqlite3")
    kload("lk_xdg")
    kload("lk_luakit")
    kload("lk_unique")
    kload("lk_stylesheet")
    kload("lk_download")
    kload("lk_request")
    kload("lk_ipc")
    kload("lk_widget")
    kload("lk_dispatch")
    kload("lk_webview")
    kload("lk_widget_native")
    kload("lk_scheme")
    kload("lk_webipc")
end, debug.traceback)

if not ok then
    lemurx.log("[luakit] kernel init failed: " .. tostring(err))
    return
end

rawset(_G, "__luakit_kernel_loaded", true)
msg.info("luakit compat kernel %s ready (install_dir=%s)", luakit.version, install_dir)

-- 启动 URI 列表：桌面 luakit 来自命令行；这里从当前标签页拿一个，没有就空表。
if not rawget(_G, "uris") then
    local list = {}
    local okc, cur = pcall(lemurx.tabs.current)
    if okc and type(cur) == "table" and cur.url and cur.url ~= "" then
        list[1] = cur.url
    end
    rawset(_G, "uris", list)
end

-- 自动跑 rc.lua（开关）
local run_rc = lemurx.storage.get("luakit_rc", "0") == "1"
if run_rc then
    local rc = (env.config_dir or (install_dir .. "/config")) .. "/rc.lua"
    local okrc, rcerr = xpcall(function() dofile(rc) end, debug.traceback)
    if not okrc then
        msg.warn("rc.lua failed: %s", tostring(rcerr))
    end
end
