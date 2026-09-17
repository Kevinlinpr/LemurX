-- LemurX · luakit-compatible library · lousy.widget.progress
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.progress" module API. No luakit code is used.
--
-- 状态栏加载进度：加载中显示 "(NN%)"，加载完成后隐藏。

local common = require("lousy.widget.common")

local M = {}

local function new(w)
    local label = common.make_label("progress")
    local state

    local function paint(view)
        if not label.is_alive then return end
        view = view or (state and state.current_view()) or common.current_view(w)
        if not view or not view.is_loading then
            label.text = ""
            label:hide()
            return
        end
        local p = tonumber(view.progress) or 0
        if p > 1 then p = p / 100 end
        label.text = string.format("(%d%%)", math.floor(p * 100 + 0.5))
        label:show()
    end

    state = common.follow_view(w, {
        ["property::progress"] = function(v) paint(v) end,
        ["load-status"] = function(v) paint(v) end,
        switch = function(v) paint(v) end,
    }, function() return label.is_alive end)

    common.on_w(w, "init", function() paint() end)
    common.attach_update(label, function() paint() end)
    paint()
    return label
end

return common.callable(M, new)
