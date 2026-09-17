-- LemurX · luakit-compatible library · lousy.widget.uri
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.uri" module API. No luakit code is used.
--
-- 状态栏 URI 标签：显示当前页地址；鼠标悬停链接时改显 "Link: <uri>"；
-- 页面加载完成后用 loaded 配色。

local util = require("lousy.util")
local common = require("lousy.widget.common")

local M = {}

local function new(w)
    local theme = common.theme()
    local label = common.make_label("uri")
    local hovered = nil
    local state

    local function paint(view)
        if not label.is_alive then return end
        view = view or (state and state.current_view()) or common.current_view(w)
        if hovered then
            label.text = util.escape("Link: " .. hovered)
            label.fg = theme.uri_sbar_fg
            return
        end
        local uri = view and view.uri
        if uri == nil or uri == "" then uri = "about:blank" end
        label.text = util.escape(uri)
        if view and view.is_loading then
            label.fg = theme.uri_sbar_fg
        else
            label.fg = theme.uri_sbar_loaded_fg or theme.uri_sbar_fg
        end
    end

    state = common.follow_view(w, {
        ["property::uri"] = function(v) paint(v) end,
        ["load-status"] = function(v) paint(v) end,
        ["link-hover"] = function(v, link)
            hovered = link
            paint(v)
        end,
        ["link-unhover"] = function(v)
            hovered = nil
            paint(v)
        end,
        switch = function(v)
            hovered = nil
            paint(v)
        end,
    }, function() return label.is_alive end)

    common.on_w(w, "init", function() paint() end)
    common.attach_update(label, function() paint() end)
    paint()
    return label
end

return common.callable(M, new)
