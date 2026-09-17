-- LemurX · luakit-compatible library · lousy.widget.tgname
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.tgname" module API. No luakit code is used.
--
-- 状态栏标签组名：显示 w.tabgroup_name（tabgroups 模块维护），为空时隐藏。

local util = require("lousy.util")
local common = require("lousy.widget.common")

local M = {}

local function group_name(w)
    if type(w) ~= "table" then return "" end
    local name = rawget(w, "tabgroup_name")
    if name == nil then
        local ok, v = pcall(function() return w.tabgroup_name end)
        if ok then name = v end
    end
    if type(name) == "function" then
        local ok, v = pcall(name, w)
        name = ok and v or nil
    end
    return name and tostring(name) or ""
end

local function new(w)
    local label = common.make_label("tgname")

    local function paint()
        if not label.is_alive then return end
        local name = group_name(w)
        if name == "" then
            label.text = ""
            label:hide()
        else
            label.text = util.escape(name)
            label:show()
        end
    end

    local nb = common.notebook(w)
    if nb then
        common.on_obj(nb, "switch-page", function() paint() end)
    end
    common.on_w(w, "init", paint)
    common.on_w(w, "tabgroup-changed", paint)
    common.attach_update(label, paint)
    paint()
    return label
end

return common.callable(M, new)
