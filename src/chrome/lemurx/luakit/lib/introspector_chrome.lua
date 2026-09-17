-- LemurX · luakit-compatible library · introspector_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "introspector_chrome" module API. No luakit code is used.
--
-- luakit://introspector/ ：历史遗留的页面名，内容与 luakit://binds/ 相同（复用 binds_chrome）。
-- 命令 :introspector

local chrome = require("chrome")
local binds_chrome = require("binds_chrome")

local M = {}

M.chrome_page = "luakit://introspector/"

chrome.add("introspector", function()
    return binds_chrome.render({ title = "Introspector" })
end)

do
    local ok, modes = pcall(require, "modes")
    if ok and modes then
        modes.add_cmds({
            { ":introspector", "Show all key bindings (alias of :binds).", function(w) w:new_tab(M.chrome_page) end },
        })
    end
end

return M
