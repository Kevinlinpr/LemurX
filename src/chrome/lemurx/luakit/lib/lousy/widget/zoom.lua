-- LemurX · luakit-compatible library · lousy.widget.zoom
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.zoom" module API. No luakit code is used.
--
-- 状态栏缩放指示：缩放不是默认值时显示 "[NNN%]"，否则隐藏。
-- 默认值取 settings 里的 webview.zoom_level（百分数），没有 settings 模块时按 100%。

local common = require("lousy.widget.common")

local M = {}

local function default_zoom()
    local s = package.loaded["settings"]
    if type(s) == "table" and type(s.get_setting) == "function" then
        local ok, v = pcall(s.get_setting, "webview.zoom_level")
        if ok and type(v) == "number" and v > 0 then
            if v > 10 then return v / 100 end
            return v
        end
    end
    return 1.0
end
M.default_zoom = default_zoom

local function new(w)
    local label = common.make_label("zoom")
    local state

    local function paint(view)
        if not label.is_alive then return end
        view = view or (state and state.current_view()) or common.current_view(w)
        local zl = view and tonumber(view.zoom_level) or nil
        if not zl or math.abs(zl - default_zoom()) < 0.005 then
            label.text = ""
            label:hide()
            return
        end
        label.text = string.format("[%d%%]", math.floor(zl * 100 + 0.5))
        label:show()
    end

    state = common.follow_view(w, {
        ["property::zoom_level"] = function(v) paint(v) end,
        ["load-status"] = function(v) paint(v) end,
        switch = function(v) paint(v) end,
    }, function() return label.is_alive end)

    common.on_w(w, "init", function() paint() end)
    common.attach_update(label, function() paint() end)
    paint()
    return label
end

return common.callable(M, new)
