-- LemurX · luakit-compatible library · referer_control
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "referer_control" module API. No luakit code is used.
--
-- referer_control_wm 的浏览器进程侧小垫片（luakit 只有 wm；这个模块是 LemurX 扩展）：
-- 提供 settings 键 referer_control.policy，并把策略 / 例外域 / 规则推送到渲染进程。
-- 设置：referer_control.policy（enum: same-origin | strip | origin | none）
-- 公开接口：referer_control.policy（读写）referer_control.exceptions（表，set_exceptions 推送）
--   referer_control.set_exceptions(list) referer_control.set_rules(list)
-- IPC (referer_control_wm)：→ policy(name) exceptions({domains}) rules({{host=,policy=}})

local settings = require("settings")

local _M = {}

local wm = require_web_module("referer_control_wm")

settings.register_settings({
    ["referer_control.policy"] = {
        type = "enum",
        default = "same-origin",
        options = {
            ["same-origin"] = { desc = "Send the Referer only to the same registrable domain." },
            strip = { desc = "Never send a Referer." },
            origin = { desc = "Cross-site requests get only the origin as Referer." },
            none = { desc = "Leave the Referer untouched." },
        },
        desc = "How the Referer header is handled for sub-resource requests.",
    },
})

local exceptions = {}
local rules = {}

local function push(view)
    local policy = settings.get_setting("referer_control.policy") or "same-origin"
    if view then
        wm:emit_signal(view, "policy", policy)
        wm:emit_signal(view, "exceptions", exceptions)
        wm:emit_signal(view, "rules", rules)
    else
        wm:emit_signal("policy", policy)
        wm:emit_signal("exceptions", exceptions)
        wm:emit_signal("rules", rules)
    end
end

function _M.set_exceptions(list)
    exceptions = {}
    for _, d in ipairs(list or {}) do exceptions[#exceptions + 1] = tostring(d):lower() end
    push()
end

function _M.set_rules(list)
    rules = {}
    for _, r in ipairs(list or {}) do
        if type(r) == "table" and r.host then rules[#rules + 1] = { host = tostring(r.host):lower(), policy = r.policy } end
    end
    push()
end

settings.add_signal("setting-changed", function(a, b)
    local ev = type(a) == "table" and a or b -- C 组 settings 是模块信号：handler(ev)；也兼容 (obj, ev)
    if ev and ev.key == "referer_control.policy" then push() end
end)

luakit.add_signal("web-extension-created", function(view) push(view) end)

setmetatable(_M, {
    __index = function(_, k)
        if k == "policy" then return settings.get_setting("referer_control.policy") end
        if k == "exceptions" then return exceptions end
        if k == "rules" then return rules end
    end,
    __newindex = function(t, k, v)
        if k == "policy" then
            settings.set_setting("referer_control.policy", v)
            push()
        elseif k == "exceptions" then
            _M.set_exceptions(v)
        elseif k == "rules" then
            _M.set_rules(v)
        else
            rawset(t, k, v)
        end
    end,
})

push()

return _M
