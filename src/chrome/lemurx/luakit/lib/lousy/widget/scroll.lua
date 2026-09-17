-- LemurX · luakit-compatible library · lousy.widget.scroll
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.scroll" module API. No luakit code is used.
--
-- 状态栏滚动位置：All（页面不可滚动）/ Top / Bot / NN%。

local common = require("lousy.widget.common")

local M = {}

function M.describe(scroll)
    if type(scroll) ~= "table" then return "All" end
    local y = tonumber(scroll.y) or 0
    local ymax = tonumber(scroll.ymax) or 0
    if ymax <= 0 then return "All" end
    if y <= 0 then return "Top" end
    if y >= ymax then return "Bot" end
    return string.format("%d%%", math.floor(y / ymax * 100 + 0.5))
end

local function new(w)
    local label = common.make_label("scroll")
    local state

    local function paint(view)
        if not label.is_alive then return end
        view = view or (state and state.current_view()) or common.current_view(w)
        if not view then
            label.text = ""
            return
        end
        local ok, s = pcall(function() return view.scroll end)
        local snapshot = nil
        if ok and type(s) == "table" then
            local oky, y = pcall(function() return s.y end)
            local okm, ymax = pcall(function() return s.ymax end)
            snapshot = { y = oky and y or 0, ymax = okm and ymax or 0 }
        end
        label.text = M.describe(snapshot)
    end

    state = common.follow_view(w, {
        ["scroll"] = function(v) paint(v) end,
        ["property::scroll"] = function(v) paint(v) end,
        ["expose"] = function(v) paint(v) end,
        ["load-status"] = function(v) paint(v) end,
        switch = function(v) paint(v) end,
    }, function() return label.is_alive end)

    common.on_w(w, "init", function() paint() end)
    common.attach_update(label, function() paint() end)
    paint()
    return label
end

return common.callable(M, new)
