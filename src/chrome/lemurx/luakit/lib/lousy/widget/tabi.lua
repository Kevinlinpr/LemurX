-- LemurX · luakit-compatible library · lousy.widget.tabi
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.tabi" module API. No luakit code is used.
--
-- 状态栏标签页计数："[当前/总数]"。

local common = require("lousy.widget.common")

local M = {}

local function new(w)
    local label = common.make_label("tabi")

    local function paint()
        if not label.is_alive then return end
        local nb = common.notebook(w)
        if not nb then
            label.text = ""
            return
        end
        local okc, cur = pcall(nb.current, nb)
        local okn, n = pcall(nb.count, nb)
        cur = okc and tonumber(cur) or 0
        n = okn and tonumber(n) or 0
        if n <= 0 then
            label.text = ""
            return
        end
        label.text = string.format("[%d/%d]", cur, n)
    end

    local nb = common.notebook(w)
    if nb then
        for _, sig in ipairs({ "switch-page", "page-added", "page-removed", "page-reordered" }) do
            common.on_obj(nb, sig, function()
                if label.is_alive then paint() end
            end)
        end
    end
    common.on_w(w, "init", paint)
    common.on_w(w, "tab-count-changed", paint)
    common.attach_update(label, paint)
    paint()
    return label
end

return common.callable(M, new)
