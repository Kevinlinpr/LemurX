-- LemurX luakit 兼容运行时 · 渲染进程（web extension）内核入口
--
-- 对应 luakit 的 extension/：每个 web 进程一个 Lua 状态，暴露
--   page / dom_document / dom_element / ipc_channel / luakit / msg / soup
-- 给 lib/*_wm.lua 这些 web 模块。原生原语在隐藏全局表 __luakit_web
-- （chrome/renderer/lemurx/luakit_web_extension.cc），DOM 走主世界 V8 句柄。
--
-- 源码由浏览器进程经 LuakitWebHost.ResolveModule 下发；require 搜索顺序：
--   kernel/web/  kernel/  <config>/  lib/

if not __luakit_web then
    error("lkw_init: __luakit_web primitives missing")
end

local N = __luakit_web
local env = N.json_decode(N.env() or "{}") or {}

-- 与 UI 进程共用的两块：5.1 兼容层、对象协议
require("lk_compat51")
local object = require("lk_object")

_G.__lk = { env = env, object = object, web = true }

require("lkw_msg")
require("lkw_lfs")
require("lkw_soup")
require("lkw_js")
require("lkw_dom")
require("lkw_page")
require("lkw_ipc")
require("lkw_luakit")
require("lkw_dispatch")

msg.verbose("luakit web extension kernel ready")
return true
