-- LemurX · luakit-compatible library · tabgroups
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "tabgroups" module API. No luakit code is used.
--
-- 标签分组。每个 webview 带一个 view.tabgroup 名字，窗口有 w.tabgroup_name（当前组）与
-- w.tabgroups（有序组名 + 每组最近使用的标签）。切换组 = 跳到该组最近的标签；
-- lousy.widget.tgname 读 w.tabgroup_name 显示。分组关系按 URI 持久化到
-- luakit.data_dir/tabgroups.lua，会话恢复时按 URI 找回分组。
--   x  组菜单（<Return> 切换，n 新建，r 重命名，d 删除）   X  当前组的标签菜单
--   :tabgroup-new <name>  :tabgroup-switch <name>  :tabgroup-move <name>
--   :tabgroup-rename <name>  :tabgroup-close [name]
-- 设置：tabgroups.switch_to_new_tab tabgroups.default_name
-- 公开接口：create_tabgroup(w, name) switch_tabgroup(w, name) move_tab_to_tabgroup(w, view, name)
--   open_new_tab_in_tabgroup(w, group, uri, opts) delete_tabgroup(w, name) rename_tabgroup(w, old, new)
--   current_tabgroup([obj,] w) groups(w) tabs_in(w, name)
-- 窗口信号：tabgroup-changed(w, name)

local lousy = require("lousy")
local settings = require("settings")
local modes = require("modes")
local window = require("window")

local _M = {}
lousy.signal.setup(_M, true)

settings.register_settings({
    ["tabgroups.switch_to_new_tab"] = {
        type = "boolean", default = true,
        desc = "Switch to a tab right after moving/opening it in another tab group.",
    },
    ["tabgroups.default_name"] = {
        type = "string", default = "main",
        desc = "Name of the tab group new windows start with.",
    },
})

_M.file = luakit.data_dir .. "/tabgroups.lua"

-- ---------------------------------------------------------------------------
-- 持久化（uri -> group）
-- ---------------------------------------------------------------------------
local remembered = nil

local function load_remembered()
    if remembered then return remembered end
    remembered = {}
    local f = io.open(_M.file, "rb")
    if not f then return remembered end
    local text = f:read("a")
    f:close()
    local chunk = load(text, "=tabgroups.lua", "t", {})
    if chunk then
        local ok, t = pcall(chunk)
        if ok and type(t) == "table" then remembered = t end
    end
    return remembered
end

local function save_remembered()
    local lines = { "return {" }
    local keys = {}
    for uri in pairs(remembered or {}) do keys[#keys + 1] = uri end
    table.sort(keys)
    for _, uri in ipairs(keys) do
        lines[#lines + 1] = ("  [%q] = %q,"):format(uri, remembered[uri])
    end
    lines[#lines + 1] = "}"
    pcall(lfs.mkdir, luakit.data_dir)
    local f = io.open(_M.file, "wb")
    if not f then return end
    f:write(table.concat(lines, "\n"), "\n")
    f:close()
end

local function remember(view)
    local uri = view.uri
    if type(uri) ~= "string" or uri == "" or uri == "about:blank" then return end
    load_remembered()
    if remembered[uri] ~= view.tabgroup then
        remembered[uri] = view.tabgroup
        save_remembered()
    end
end

-- ---------------------------------------------------------------------------
-- 数据结构
-- ---------------------------------------------------------------------------
local function ensure(w)
    if rawget(w, "tabgroups") then return w.tabgroups end
    local default = settings.get_setting("tabgroups.default_name") or "main"
    w.tabgroups = { order = { default }, last = {} }
    w.tabgroup_name = default
    return w.tabgroups
end

local function all_tabs(w)
    local out = {}
    local ok, n = pcall(function() return w.tabs:count() end)
    if not ok or not n then return out end
    for i = 1, n do
        local v = w.tabs:atindex(i)
        if v then out[#out + 1] = v end
    end
    return out
end

function _M.groups(w)
    local tg = ensure(w)
    local out = {}
    for i, n in ipairs(tg.order) do out[i] = n end
    return out
end

function _M.tabs_in(w, name)
    name = name or w.tabgroup_name
    local out = {}
    for _, v in ipairs(all_tabs(w)) do
        if v.tabgroup == name then out[#out + 1] = v end
    end
    return out
end

function _M.current_tabgroup(a, b)
    local w = b or a
    ensure(w)
    return w.tabgroup_name
end

function _M.create_tabgroup(w, name)
    if type(name) ~= "string" or name == "" then error("tabgroups: group name required", 2) end
    local tg = ensure(w)
    for _, n in ipairs(tg.order) do
        if n == name then return { name = name, existing = true } end
    end
    tg.order[#tg.order + 1] = name
    _M.emit_signal("group-created", w, name)
    return { name = name }
end

local function set_current(w, name)
    local tg = ensure(w)
    if w.tabgroup_name ~= name then
        w.tabgroup_name = name
        if w.emit_signal then pcall(w.emit_signal, w, "tabgroup-changed", name) end
        _M.emit_signal("switched", w, name)
    end
    local v = w.view
    if v and v.tabgroup == name then tg.last[name] = v end
end

function _M.switch_tabgroup(w, name)
    local tg = ensure(w)
    _M.create_tabgroup(w, name)
    local target = tg.last[name]
    if not (target and target.is_alive and target.tabgroup == name) then
        target = _M.tabs_in(w, name)[1]
    end
    if target then
        local idx = w.tabs:indexof(target)
        if idx and idx > 0 then w.tabs:switch(idx) end
    else
        -- 空组：开一个新标签放进去
        local uri = settings.get_setting("window.new_tab_page") or "about:blank"
        _M.open_new_tab_in_tabgroup(w, name, uri, { switch = true })
    end
    set_current(w, name)
end

function _M.move_tab_to_tabgroup(w, view, name)
    _M.create_tabgroup(w, name)
    view.tabgroup = name
    remember(view)
    ensure(w).last[name] = view
    if settings.get_setting("tabgroups.switch_to_new_tab") ~= false then
        local idx = w.tabs:indexof(view)
        if idx and idx > 0 then w.tabs:switch(idx) end
        set_current(w, name)
    end
    _M.emit_signal("tab-moved", w, view, name)
end

function _M.open_new_tab_in_tabgroup(w, group, uri, opts)
    local name = type(group) == "table" and group.name or group
    _M.create_tabgroup(w, name)
    opts = opts or {}
    if opts.switch == nil then opts.switch = settings.get_setting("tabgroups.switch_to_new_tab") ~= false end
    w.tabgroup_pending = name
    local view = w:new_tab(uri, opts)
    w.tabgroup_pending = nil
    if view then
        view.tabgroup = name
        remember(view)
        ensure(w).last[name] = view
        if opts.switch then set_current(w, name) end
    end
    return view
end

function _M.rename_tabgroup(w, old, new)
    local tg = ensure(w)
    if type(new) ~= "string" or new == "" then return false end
    for i, n in ipairs(tg.order) do
        if n == old then
            tg.order[i] = new
            tg.last[new], tg.last[old] = tg.last[old], nil
            for _, v in ipairs(all_tabs(w)) do
                if v.tabgroup == old then
                    v.tabgroup = new
                    remember(v)
                end
            end
            if w.tabgroup_name == old then
                w.tabgroup_name = new
                if w.emit_signal then pcall(w.emit_signal, w, "tabgroup-changed", new) end
            end
            return true
        end
    end
    return false
end

function _M.delete_tabgroup(w, name)
    local tg = ensure(w)
    if #tg.order <= 1 then return nil end
    local idx
    for i, n in ipairs(tg.order) do
        if n == name then idx = i break end
    end
    if not idx then return nil end
    for _, v in ipairs(_M.tabs_in(w, name)) do
        w:close_tab(v)
    end
    table.remove(tg.order, idx)
    tg.last[name] = nil
    if w.tabgroup_name == name then
        _M.switch_tabgroup(w, tg.order[math.max(1, idx - 1)])
    end
    _M.emit_signal("group-deleted", w, name)
    return true
end

-- ---------------------------------------------------------------------------
-- 窗口接线
-- ---------------------------------------------------------------------------
local function attach_view(w, view)
    if not view.tabgroup then
        local pending = rawget(w, "tabgroup_pending")
        local uri = view.uri
        local known = uri and load_remembered()[uri]
        view.tabgroup = pending or known or w.tabgroup_name
        if known and known ~= w.tabgroup_name then _M.create_tabgroup(w, known) end
    end
    if not view.tabgroup_watch then
        view.tabgroup_watch = true
        view:add_signal("property::uri", function(v)
            if v.tabgroup then remember(v) end
        end)
    end
end

window.add_signal("init", function(w)
    ensure(w)
    for _, v in ipairs(all_tabs(w)) do attach_view(w, v) end
    if w.add_signal then
        w:add_signal("new-tab", function(_, view) attach_view(w, view) end)
        w:add_signal("attach-tab", function(_, view) attach_view(w, view) end)
    end
    if w.tabs and w.tabs.add_signal then
        w.tabs:add_signal("switch-page", function(_, view)
            if view and view.is_alive then
                attach_view(w, view)
                set_current(w, view.tabgroup)
            end
        end)
    end
end)

-- ---------------------------------------------------------------------------
-- 菜单与命令
-- ---------------------------------------------------------------------------
local function build_group_menu(w)
    local rows = { { "Tab group", "Tabs", title = true } }
    for _, name in ipairs(_M.groups(w)) do
        local mark = (name == w.tabgroup_name) and "● " or "  "
        rows[#rows + 1] = { mark .. name, tostring(#_M.tabs_in(w, name)), group = name }
    end
    w.menu:build(rows)
end

modes.new_mode("tabgroup-menu", "Switch, create, rename or delete tab groups.", {
    enter = function(w)
        build_group_menu(w)
        w.menu:show()
        w:set_prompt("Tab groups — <Return>: switch, n: new, r: rename, d: delete")
    end,
    leave = function(w) w.menu:hide() end,
})

modes.add_binds("tabgroup-menu", {
    { "<Return>", "Switch to the selected group.", function (w)
        local row = w.menu:get()
        w:set_mode()
        if row and row.group then _M.switch_tabgroup(w, row.group) end
    end },
    { "n", "Create a new tab group.", function (w) w:enter_cmd(":tabgroup-new ") end },
    { "r", "Rename the selected group.", function (w)
        local row = w.menu:get()
        if row and row.group then
            _M.switch_tabgroup(w, row.group)
            w:enter_cmd(":tabgroup-rename " .. row.group)
        end
    end },
    { "d", "Delete the selected group and close its tabs.", function (w)
        local row = w.menu:get()
        if row and row.group then
            if _M.delete_tabgroup(w, row.group) == nil then
                w:warning("tabgroups: cannot delete the only group")
            end
            build_group_menu(w)
        end
    end },
    { "<Tab>", "Select the next group.", function (w) w.menu:move_down() end },
    { "<Shift-Tab>", "Select the previous group.", function (w) w.menu:move_up() end },
})

modes.new_mode("tabgroup-tabs-menu", "Pick a tab from the current group.", {
    enter = function(w)
        local rows = { { "Tab", "URI", title = true } }
        for _, v in ipairs(_M.tabs_in(w, w.tabgroup_name)) do
            rows[#rows + 1] = { v.title ~= "" and v.title or v.uri, v.uri, view = v }
        end
        w.menu:build(rows)
        w.menu:show()
        w:set_prompt(("Tabs in %s"):format(w.tabgroup_name))
    end,
    leave = function(w) w.menu:hide() end,
})

modes.add_binds("tabgroup-tabs-menu", {
    { "<Return>", "Go to the selected tab.", function (w)
        local row = w.menu:get()
        w:set_mode()
        if row and row.view and row.view.is_alive then
            local idx = w.tabs:indexof(row.view)
            if idx and idx > 0 then w.tabs:switch(idx) end
        end
    end },
    { "<Tab>", "Select the next tab.", function (w) w.menu:move_down() end },
    { "<Shift-Tab>", "Select the previous tab.", function (w) w.menu:move_up() end },
})

modes.add_binds("normal", {
    { "x", "Open the tab group menu.", function (w) w:set_mode("tabgroup-menu") end },
    { "X", "List the tabs of the current tab group.", function (w) w:set_mode("tabgroup-tabs-menu") end },
})

local function arg_of(o)
    local a = type(o) == "table" and o.arg or (type(o) == "string" and o) or ""
    return (a:gsub("^%s+", ""):gsub("%s+$", ""))
end

modes.add_cmds({
    { ":tabgroup-new", "Create a tab group and move the current tab into it.", function (w, o)
        local name = arg_of(o)
        if name == "" then w:error("usage: :tabgroup-new <name>") return end
        _M.create_tabgroup(w, name)
        if w.view then _M.move_tab_to_tabgroup(w, w.view, name) else _M.switch_tabgroup(w, name) end
        w:notify("tabgroups: now in " .. name)
    end },
    { ":tabgroup-switch", "Switch to a tab group.", function (w, o)
        local name = arg_of(o)
        if name == "" then w:set_mode("tabgroup-menu") return end
        _M.switch_tabgroup(w, name)
    end },
    { ":tabgroup-move", "Move the current tab to a tab group.", function (w, o)
        local name = arg_of(o)
        if name == "" then w:error("usage: :tabgroup-move <name>") return end
        if w.view then _M.move_tab_to_tabgroup(w, w.view, name) end
    end },
    { ":tabgroup-rename", "Rename the current tab group.", function (w, o)
        local name = arg_of(o)
        if name == "" then w:error("usage: :tabgroup-rename <name>") return end
        if _M.rename_tabgroup(w, w.tabgroup_name, name) then w:notify("tabgroups: renamed to " .. name) end
    end },
    { ":tabgroup-close", "Close a tab group (default: current) and all its tabs.", function (w, o)
        local name = arg_of(o)
        if name == "" then name = w.tabgroup_name end
        if _M.delete_tabgroup(w, name) == nil then
            w:warning("tabgroups: cannot close the only group")
        end
    end },
})

return _M
