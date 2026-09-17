-- LemurX · luakit-compatible library · lousy.widget.ssl
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.ssl" module API. No luakit code is used.
--
-- 状态栏 TLS 指示：https 页面显示 "(trust)" 或 "(notrust)"，其它页面隐藏。
-- 颜色取 trust_fg / notrust_fg。

local common = require("lousy.widget.common")

local M = {}

M.trusted_text = "(trust)"
M.untrusted_text = "(notrust)"

local function new(w)
    local theme = common.theme()
    local label = common.make_label("ssl")
    local state

    local function paint(view)
        if not label.is_alive then return end
        view = view or (state and state.current_view()) or common.current_view(w)
        local uri = view and view.uri or ""
        if not uri:match("^https://") then
            label.text = ""
            label:hide()
            return
        end
        local ok, trusted = pcall(view.ssl_trusted, view)
        if ok and trusted == true then
            label.text = M.trusted_text
            label.fg = theme.trust_fg
        else
            label.text = M.untrusted_text
            label.fg = theme.notrust_fg
        end
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
