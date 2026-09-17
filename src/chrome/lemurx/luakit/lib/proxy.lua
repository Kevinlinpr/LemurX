-- LemurX · luakit-compatible library · proxy
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "proxy" module API. No luakit code is used.
--
-- 代理菜单：维护一组命名代理，切换时写 soup.proxy_uri。列表存在 luakit.data_dir/proxymenu，
-- 每行 "name<TAB>address"，当前生效的一行以 "*" 开头。菜单里另有两个固定项：
-- (system) → "default"，(none) → "no_proxy"。
--   :proxy                 打开菜单        :proxy <name> <address>  新增/修改
-- 模式 proxymenu：<Return> 启用  d 删除  e 编辑  a 新增
-- 公开接口：proxy.get_names() get(name) get_active() load(fd) save(fd) set(name, address, save) del(name)
--   set_active(name) proxy.widget(w)（状态栏指示，可选）

local lousy = require("lousy")
local modes = require("modes")

local _M = {}
lousy.signal.setup(_M, true)

_M.file = luakit.data_dir .. "/proxymenu"

local entries = {}       -- name -> address
local names = {}         -- 有序
local active = nil       -- name | nil

local BUILTIN = {
    { name = "(system)", address = "default" },
    { name = "(none)", address = "no_proxy" },
}

local function index_of(name)
    for i, n in ipairs(names) do
        if n == name then return i end
    end
end

function _M.get_names()
    local out = {}
    for i, n in ipairs(names) do out[i] = n end
    return out
end

function _M.get(name)
    return entries[name]
end

function _M.get_active()
    if not active then return { name = nil, address = soup.proxy_uri } end
    return { name = active, address = entries[active] or (active == "(system)" and "default") or (active == "(none)" and "no_proxy") }
end

function _M.load(fd_name)
    local path = fd_name or _M.file
    entries, names, active = {}, {}, nil
    local f = io.open(path, "rb")
    if not f then return false end
    for line in f:lines() do
        local star, name, address = line:match("^(%*?)([^\t]+)\t(.+)$")
        if name then
            name = name:gsub("^%s+", ""):gsub("%s+$", "")
            address = address:gsub("^%s+", ""):gsub("%s+$", "")
            if not entries[name] then names[#names + 1] = name end
            entries[name] = address
            if star == "*" then active = name end
        end
    end
    f:close()
    if active then pcall(function() soup.proxy_uri = entries[active] end) end
    return true
end

function _M.save(fd_name)
    local path = fd_name or _M.file
    pcall(lfs.mkdir, luakit.data_dir)
    local f, err = io.open(path, "wb")
    if not f then return false, err end
    for _, n in ipairs(names) do
        f:write((active == n) and "*" or "", n, "\t", entries[n], "\n")
    end
    f:close()
    return true
end

function _M.set(name, address, save_file)
    if type(name) ~= "string" or name == "" then error("proxy.set: name required", 2) end
    if type(address) ~= "string" or address == "" then error("proxy.set: address required", 2) end
    if not entries[name] then names[#names + 1] = name end
    entries[name] = address
    if active == name then pcall(function() soup.proxy_uri = address end) end
    if save_file ~= false then _M.save() end
    _M.emit_signal("changed")
end

function _M.del(name)
    if not entries[name] then return false end
    entries[name] = nil
    local i = index_of(name)
    if i then table.remove(names, i) end
    if active == name then _M.set_active(nil) end
    _M.save()
    _M.emit_signal("changed")
    return true
end

function _M.set_active(name)
    local address
    if name == nil or name == "(system)" then
        address = "default"
        active = name
    elseif name == "(none)" then
        address = "no_proxy"
        active = name
    else
        address = entries[name]
        if not address then return false end
        active = name
    end
    local ok, err = pcall(function() soup.proxy_uri = address end)
    if not ok then
        msg.warn("proxy: cannot apply %s: %s", tostring(address), tostring(err))
        return false
    end
    _M.save()
    _M.emit_signal("changed")
    return true
end

-- ---------------------------------------------------------------------------
-- 菜单
-- ---------------------------------------------------------------------------
local function build_menu(w)
    local rows = { { "Proxy", "Address", title = true } }
    local theme = (pcall(lousy.theme.get) and lousy.theme.get()) or {}
    for _, b in ipairs(BUILTIN) do
        local is_active = (active == b.name) or (active == nil and b.address == "default")
        rows[#rows + 1] = { (is_active and "● " or "  ") .. b.name, b.address, proxy = b.name, builtin = true,
                            fg = is_active and theme.proxy_active_menu_fg or theme.proxy_inactive_menu_fg }
    end
    for _, n in ipairs(names) do
        local is_active = active == n
        rows[#rows + 1] = { (is_active and "● " or "  ") .. n, entries[n], proxy = n,
                            fg = is_active and theme.proxy_active_menu_fg or theme.proxy_inactive_menu_fg }
    end
    w.menu:build(rows)
end

modes.new_mode("proxymenu", "Choose the active proxy.", {
    enter = function(w)
        build_menu(w)
        w.menu:show()
        w:set_prompt("Proxy — <Return>: use, a: add, e: edit, d: delete")
    end,
    leave = function(w) w.menu:hide() end,
})

modes.add_binds("proxymenu", {
    { "<Return>", "Use the selected proxy.", function (w)
        local row = w.menu:get()
        if row and row.proxy then
            if _M.set_active(row.proxy) then
                w:set_mode()
                w:notify("proxy: now using " .. row.proxy .. " (" .. tostring(_M.get_active().address) .. ")")
            else
                w:error("proxy: cannot activate " .. row.proxy)
            end
        end
    end },
    { "d", "Delete the selected proxy entry.", function (w)
        local row = w.menu:get()
        if row and row.proxy and not row.builtin then
            _M.del(row.proxy)
            build_menu(w)
        end
    end },
    { "e", "Edit the selected proxy entry.", function (w)
        local row = w.menu:get()
        if row and row.proxy and not row.builtin then
            w:enter_cmd((":proxy %s %s"):format(row.proxy, entries[row.proxy] or ""))
        end
    end },
    { "a", "Add a proxy entry.", function (w) w:enter_cmd(":proxy ") end },
    { "<Tab>", "Select the next entry.", function (w) w.menu:move_down() end },
    { "<Shift-Tab>", "Select the previous entry.", function (w) w.menu:move_up() end },
})

modes.add_cmds({
    { ":proxy", "Open the proxy menu, or add a proxy: :proxy <name> <address>.", function (w, o)
        local arg = type(o) == "table" and o.arg or (type(o) == "string" and o) or ""
        arg = arg:gsub("^%s+", ""):gsub("%s+$", "")
        if arg == "" then
            w:set_mode("proxymenu")
            return
        end
        local name, address = arg:match("^(%S+)%s+(%S+)$")
        if not name then
            w:error("usage: :proxy <name> <address>  (e.g. :proxy home socks5://127.0.0.1:1080)")
            return
        end
        _M.set(name, address)
        w:notify(("proxy: saved %s = %s"):format(name, address))
    end },
})

-- 状态栏指示
function _M.widget(w)
    local label = widget{ type = "label" }
    local function update()
        local a = _M.get_active()
        if a.name and a.name ~= "(system)" then
            label.text = "[" .. a.name .. "]"
            label:show()
        else
            label.text = ""
            label:hide()
        end
    end
    _M.add_signal("changed", update)
    pcall(update)
    return label
end

_M.load()

return _M
