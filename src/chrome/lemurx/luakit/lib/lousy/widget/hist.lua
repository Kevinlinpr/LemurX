-- LemurX · luakit-compatible library · lousy.widget.hist
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.hist" module API. No luakit code is used.
--
-- 状态栏历史指示：能后退显示 "+"，能前进显示 "-"，两者都不能时隐藏。
-- 形如 "[+-]" / "[+]" / "[-]"。

local common = require("lousy.widget.common")

local M = {}

local function new(w)
    local label = common.make_label("hist")
    local state

    local function paint(view)
        if not label.is_alive then return end
        view = view or (state and state.current_view()) or common.current_view(w)
        local back, fwd = false, false
        if view then
            local ok1, b = pcall(view.can_go_back, view)
            local ok2, f = pcall(view.can_go_forward, view)
            back = ok1 and b == true
            fwd = ok2 and f == true
        end
        if not back and not fwd then
            label.text = ""
            label:hide()
            return
        end
        label.text = "[" .. (back and "+" or "") .. (fwd and "-" or "") .. "]"
        label:show()
    end

    state = common.follow_view(w, {
        ["load-status"] = function(v) paint(v) end,
        ["property::uri"] = function(v) paint(v) end,
        switch = function(v) paint(v) end,
    }, function() return label.is_alive end)

    common.on_w(w, "init", function() paint() end)
    common.attach_update(label, function() paint() end)
    paint()
    return label
end

return common.callable(M, new)
