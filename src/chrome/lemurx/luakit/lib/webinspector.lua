-- LemurX · luakit-compatible library · webinspector
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "webinspector" module API. No luakit code is used.
--
-- 开发者工具：:inspect(:in) 与 <F12> 切换当前标签的检查器（内核 view:show_inspector /
-- close_inspector，Android 上走 CDP）。
-- 设置：webinspector.enabled（false 时命令给出提示）
-- 公开接口：webinspector.toggle(w) webinspector.show(w) webinspector.hide(w)

local settings = require("settings")
local modes = require("modes")

local _M = {}

settings.register_settings({
    ["webinspector.enabled"] = {
        type = "boolean", default = true,
        desc = "Allow opening the web inspector (developer tools).",
    },
})

local function allowed(w)
    if settings.get_setting("webinspector.enabled") == false then
        w:warning("webinspector is disabled (settings webinspector.enabled)")
        return false
    end
    return w.view ~= nil
end

function _M.show(w)
    if not allowed(w) then return end
    local ok, err = pcall(w.view.show_inspector, w.view)
    if not ok then w:error("inspector: " .. tostring(err)) end
end

function _M.hide(w)
    if not w.view then return end
    pcall(w.view.close_inspector, w.view)
end

function _M.toggle(w)
    if not w.view then return end
    if w.view.inspector then _M.hide(w) else _M.show(w) end
end

modes.add_cmds({
    { ":inspect, :in", "Toggle the web inspector for the current tab.", function (w) _M.toggle(w) end },
})

modes.add_binds("normal", {
    { "<F12>", "Toggle the web inspector.", function (w) _M.toggle(w) end },
})

return _M
