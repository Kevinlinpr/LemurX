-- LemurX · luakit-compatible library · follow_selected
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "follow_selected" module API. No luakit code is used.
--
-- 在 normal 模式按回车跟随当前文字选区里的链接：
--   <Return> 当前标签   <Shift-Return> 新标签   <Control-Return> 后台标签   <Mod1-Return> 新窗口
-- 链接查找在渲染进程（follow_selected_wm.lua）完成。
-- IPC (follow_selected_wm)：→ query(view, action)   ← result(page_id, action, uri|nil)

local modes = require("modes")
local window = require("window")

local _M = {}

local wm = require_web_module("follow_selected_wm")

local actions = {
    navigate = function(w, uri) w:navigate(uri) end,
    new_tab = function(w, uri) w:new_tab(uri, { switch = true }) end,
    background_tab = function(w, uri) w:new_tab(uri, { switch = false }) end,
    new_window = function(w, uri) w:new_window(uri) end,
}
_M.actions = actions

local function window_for_view_id(id)
    for _, w in pairs(window.bywidget or {}) do
        local v = w.view
        if v and v.is_alive and v.id == id then return w end
    end
end

local function request(w, action)
    local view = w.view
    if not view then return end
    wm:emit_signal(view, "query", action)
end

wm:add_signal("result", function(_, page_id, action, uri)
    local w = window_for_view_id(page_id)
    if not w then return end
    if type(uri) ~= "string" or uri == "" then return end
    local fn = actions[action]
    if fn then fn(w, uri) end
end)

modes.add_binds("normal", {
    { "<Return>", "Follow the link inside the current text selection.",
        function (w) request(w, "navigate") end },
    { "<Shift-Return>", "Open the selected link in a new tab.",
        function (w) request(w, "new_tab") end },
    { "<Control-Return>", "Open the selected link in a background tab.",
        function (w) request(w, "background_tab") end },
    { "<Mod1-Return>", "Open the selected link in a new window.",
        function (w) request(w, "new_window") end },
})

return _M
