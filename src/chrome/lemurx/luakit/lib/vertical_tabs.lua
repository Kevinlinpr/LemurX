-- LemurX · luakit-compatible library · vertical_tabs
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "vertical_tabs" module API. No luakit code is used.
--
-- 把标签栏竖着放到页面左侧：每个窗口 init 时拆掉横向 w.tablist，用
-- lousy.widget.tablist(w, { orientation = "vertical" }) 重新建一个塞进 w.paned 的左槽（pack1），
-- 宽度取 settings vertical_tabs.width（位置属性 w.paned.position）。
-- 设置：vertical_tabs.width（像素，默认 200）
-- 公开接口：vertical_tabs.apply(w) vertical_tabs.revert(w)

local lousy = require("lousy")
local settings = require("settings")
local window = require("window")

local _M = {}

settings.register_settings({
    ["vertical_tabs.width"] = {
        type = "number", default = 200, min = 60,
        desc = "Width in pixels of the vertical tab list.",
    },
})

local applied = setmetatable({}, { __mode = "k" }) -- w -> { old = tablist|nil }

local function wire_clicks(w, tl)
    if not tl.add_signal then return end
    tl:add_signal("tab-clicked", function(_, idx, mods, but)
        if but == 2 then
            local view = w.tabs:atindex(idx)
            if view then w:close_tab(view) end
        else
            w.tabs:switch(idx)
        end
        return true
    end)
end

local function set_width(w)
    local width = tonumber(settings.get_setting("vertical_tabs.width")) or 200
    pcall(function() w.paned.position = width end)
    local tl = w.tablist
    if tl and tl.widget then
        pcall(function() tl.widget.min_size = { w = width } end)
    end
end

function _M.apply(w)
    if applied[w] then set_width(w) return true end
    if not (w.paned and lousy.widget and lousy.widget.tablist) then return false end
    local old = w.tablist
    if old and old.widget then
        pcall(function() w.layout:remove(old.widget) end)
        pcall(function() old:destroy() end)
    end
    local ok, tl = pcall(lousy.widget.tablist, w, { orientation = "vertical", notebook = w.tabs })
    if not ok or type(tl) ~= "table" or not tl.widget then
        msg.warn("vertical_tabs: cannot create vertical tablist: %s", tostring(tl))
        return false
    end
    w.tablist = tl
    wire_clicks(w, tl)
    -- 先把页面区域摘下来再按 左(标签栏)/右(页面) 顺序放回，宿主无论按槽位还是按顺序排都正确
    local okp, err = pcall(function()
        pcall(function() w.paned:remove(w.tabs) end)
        w.paned:pack1(tl.widget, { resize = false, shrink = false })
        w.paned:pack2(w.tabs, { resize = true, shrink = true })
    end)
    if not okp then msg.warn("vertical_tabs: pack failed: %s", tostring(err)) end
    applied[w] = { old = old }
    set_width(w)
    if tl.update then pcall(tl.update, tl) end
    return true
end

function _M.revert(w)
    if not applied[w] then return end
    local tl = w.tablist
    if tl and tl.widget then
        pcall(function() w.paned:remove(tl.widget) end)
        pcall(function() tl:destroy() end)
    end
    local ok, htl = pcall(lousy.widget.tablist, w, { orientation = "horizontal", notebook = w.tabs })
    if ok and type(htl) == "table" and htl.widget then
        w.tablist = htl
        wire_clicks(w, htl)
        pcall(function() w.layout:pack(htl.widget) end)
        pcall(function() w.layout:reorder(htl.widget, 0) end)
    end
    applied[w] = nil
end

window.add_signal("init", function(w) _M.apply(w) end)
for _, w in pairs(window.bywidget or {}) do _M.apply(w) end

settings.add_signal("setting-changed", function(a, b)
    local ev = type(a) == "table" and a or b -- C 组 settings 是模块信号：handler(ev)；也兼容 (obj, ev)
    local key = type(ev) == "table" and ev.key or ev
    if key == "vertical_tabs.width" then
        for w in pairs(applied) do set_width(w) end
    end
end)

return _M
