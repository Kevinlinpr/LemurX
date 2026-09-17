-- LemurX · luakit-compatible library · tab_favicons
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "tab_favicons" module API. No luakit code is used.
--
-- 在标签条的每个 tab 控件前放一个 16px 图标：优先用页面 favicon
-- （image:set_favicon_for_uri），拿不到时按页面类型用 resources/icons/ 里的占位图
-- （luakit:// → tab-icon-chrome，隐私标签 → tab-icon-private，普通页 → tab-icon-page）。
-- 实现方式：包一层 lousy.widget.tab 工厂（group A），拿到它返回的 tab 控件后往里塞 image；
-- 若 tab 控件不是盒子（比如只是一个 label），则什么也不做并在日志里说明。
--   tab_favicons.attach(tab_widget, view)   供 tab 实现主动调用
--   设置 tab_favicons.enabled（boolean, true）

local settings = require("settings")

local M = {}

settings.register_settings({
    ["tab_favicons.enabled"] = {
        type = "boolean", default = true,
        desc = "Show page icons in the tab strip.",
    },
})

M.size = 16
M.icons = {
    page = "tab-icon-page.png",
    chrome = "tab-icon-chrome.png",
    private = "tab-icon-private.png",
    error = "tab-icon-error.png",
    crash = "tab-icon-crash.png",
    security = "tab-icon-security-error.png",
}

local attached = setmetatable({}, { __mode = "k" }) -- view -> image

local function icon_path(name)
    return luakit.resource_path .. "/icons/" .. (M.icons[name] or M.icons.page)
end

function M.kind_for(view, status)
    if status == "crashed" then return "crash" end
    if status == "failed" then return "error" end
    local private, uri = false, ""
    pcall(function() private = view.private; uri = view.uri or "" end)
    if uri:match("^luakit://") then return "chrome" end
    if private then return "private" end
    return "page"
end

function M.update(view, status)
    local img = attached[view]
    if not img or not img.is_alive then return end
    local kind = M.kind_for(view, status)
    local ok, got = false, false
    if kind == "page" then
        ok, got = pcall(img.set_favicon_for_uri, img, view.uri)
    end
    if not (ok and got) then
        pcall(img.filename, img, icon_path(kind), M.size)
    end
end

-- 把图标控件塞进 tab 控件；返回 image 或 nil
function M.attach(tab, view)
    if not settings.get_setting("tab_favicons.enabled") then return nil end
    if attached[view] then return attached[view] end
    local box = tab
    if type(tab) == "table" and type(tab.widget) == "widget" then box = tab.widget end
    if type(box) ~= "widget" then return nil end
    if box.type == "eventbox" then box = box.child end
    if type(box) ~= "widget" or (box.type ~= "hbox" and box.type ~= "vbox") then
        msg.verbose("tab_favicons: tab widget is a %s, cannot add an icon", tostring(box and box.type))
        return nil
    end
    local img = widget({ type = "image" })
    img.margin_right = 4
    local ok = pcall(box.pack, box, img, { expand = false, fill = false, start = true })
    if not ok then return nil end
    -- 放到最前面
    pcall(box.reorder, box, img, 0)
    attached[view] = img
    view:add_signal("favicon", function(v) M.update(v) end)
    view:add_signal("load-status", function(v, status)
        if status == "committed" or status == "finished" or status == "failed" then M.update(v, status) end
    end)
    view:add_signal("crashed", function(v) M.update(v, "crashed") end)
    view:add_signal("destroy", function(v) attached[v] = nil end)
    M.update(view)
    return img
end

local function find_view(...)
    for i = 1, select("#", ...) do
        local a = select(i, ...)
        if type(a) == "widget" and a.type == "webview" then return a end
        if type(a) == "table" and type(a.view) == "widget" then return a.view end
    end
    return nil
end

local function wrap(factory)
    return function(...)
        local tab = factory(...)
        local view = find_view(...)
        if tab and view then pcall(M.attach, tab, view) end
        return tab
    end
end

-- 包住 group A 的 tab 工厂
do
    local ok, lousy = pcall(require, "lousy")
    local wrapped = false
    if ok and type(lousy) == "table" and type(lousy.widget) == "table" then
        local orig = rawget(lousy.widget, "tab") or lousy.widget.tab
        if type(orig) == "function" then
            lousy.widget.tab = wrap(orig)
            wrapped = true
        elseif type(orig) == "table" and type(orig.new) == "function" then
            orig.new = wrap(orig.new)
            wrapped = true
        end
    end
    local ok2, mod = pcall(require, "lousy.widget.tab")
    if ok2 and type(mod) == "function" and not wrapped then
        package.loaded["lousy.widget.tab"] = wrap(mod)
        wrapped = true
    end
    if not wrapped then
        msg.verbose("tab_favicons: no lousy.widget.tab factory to decorate; call tab_favicons.attach(tab, view) manually")
    end
end

return M
