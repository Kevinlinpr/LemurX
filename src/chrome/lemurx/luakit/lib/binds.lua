-- LemurX · luakit-compatible library · binds
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "binds" module API. No luakit code is used.
--
-- 默认按键绑定与 ":" 命令集。绑定写法：
--   { "<Control-x>", "说明", function (w, m) … end }             键绑定，m.count 为数字前缀
--   { "gg",          "说明", function (w, b, m) … end }          缓冲区绑定，b 为已输入的串
--   { ":o[pen], :t", "说明", function (w, o) … end, { format = "{uri}" } }   命令，o.arg / o.bang
-- 全部通过 modes.add_binds / modes.add_cmds 注册。binds.menu_binds 是菜单类模式
--（undolist / tabhistory …）共用的上下移动绑定。

local lousy = require("lousy")
local modes = require("modes")
local window = require("window")
local webview = require("webview")
local util = lousy.util
local join = util.table.join

local _M = {}

local settings = package.loaded["settings"]
if not settings then
    local ok, mod = pcall(require, "settings")
    if ok and type(mod) == "table" then settings = mod end
end
local function get_setting(key, default)
    if settings and settings.get_setting then
        local ok, v = pcall(settings.get_setting, key)
        if ok and v ~= nil then return v end
    end
    return default
end

-- 旧式全局覆盖：binds.scroll_step / binds.zoom_step 若被 rc.lua 赋值则优先
_M.scroll_step = nil
_M.zoom_step = nil
local function step() return tonumber(_M.scroll_step) or tonumber(get_setting("window.scroll_step", 40)) or 40 end
local function zstep() return tonumber(_M.zoom_step) or tonumber(get_setting("window.zoom_step", 0.1)) or 0.1 end
local function count(m) return (type(m) == "table" and tonumber(m.count)) or 1 end

-- 缓冲序列绑定的回调在 luakit 里是 fn(w, buffer, opts)，而 lousy.bind 也可能
-- 统一成 fn(w, opts, args)。这里取第一个是表的参数当作 opts，两种都兼容。
local function bufmeta(b, m) if type(b) == "table" then return b end return m end

-- 兼容包装
function _M.add_binds(...)
    msg.warn("binds.add_binds() is deprecated; call modes.add_binds() instead")
    return modes.add_binds(...)
end
function _M.add_cmds(...)
    msg.warn("binds.add_cmds() is deprecated; call modes.add_cmds() instead")
    return modes.add_cmds(...)
end

-- ---------------------------------------------------------------------------
-- 小工具
-- ---------------------------------------------------------------------------
local function selection(kind)
    local ok, v = pcall(function() return luakit.selection[kind] end)
    if ok and type(v) == "string" and v ~= "" then return v end
    return nil
end

local function open_selection(w, kind, where)
    local text = selection(kind)
    if not text then
        w:warning(("nothing in the %s selection"):format(kind))
        return
    end
    if where == "tab" then w:new_tab(text)
    elseif where == "window" then w:new_window(text)
    else w:navigate(text) end
end

local function yank(w, text, what)
    text = tostring(text or "")
    pcall(function() luakit.selection.primary = text end)
    pcall(function() luakit.selection.clipboard = text end)
    w:notify(("yanked %s: %s"):format(what, text))
end

-- 调整 URI 里最后出现的数字
local function shift_uri_number(uri, delta)
    local head, num, tail = uri:match("^(.-)(%d+)([^%d]*)$")
    if not num then return nil end
    local n = tonumber(num) + delta
    if n < 0 then n = 0 end
    local s = tostring(n)
    if #s < #num then s = string.rep("0", #num - #s) .. s end
    return head .. s .. tail
end

window.methods.inc_uri = function(w, delta)
    local view = w.view
    if not view then return end
    local new = shift_uri_number(view.uri or "", delta or 1)
    if not new then
        w:warning("no number in the current address")
        return
    end
    w:navigate(new)
end

local function home_page() return get_setting("window.home_page", "about:blank") end

local function parse_value(s)
    if s == "true" then return true end
    if s == "false" then return false end
    local n = tonumber(s)
    if n then return n end
    return s
end

local function save_session_if_possible(w)
    if type(w.save_session) == "function" then
        local ok, err = pcall(w.save_session, w)
        if not ok then msg.warn("session save failed: %s", tostring(err)) end
        return ok
    end
    local session = package.loaded["session"]
    if session and session.save then return pcall(session.save) end
    return false
end

local function current_uri(w)
    local view = w.view
    return view and view.uri or ""
end

-- ---------------------------------------------------------------------------
-- 菜单类模式共用绑定
-- ---------------------------------------------------------------------------
local function menu_move(dir)
    return function(w)
        if not w.menu then return end
        if dir > 0 then w.menu:move_down() else w.menu:move_up() end
    end
end

_M.menu_binds = {
    { "j",           "Select the next row.",          menu_move(1) },
    { "k",           "Select the previous row.",      menu_move(-1) },
    { "<Down>",      "Select the next row.",          menu_move(1) },
    { "<Up>",        "Select the previous row.",      menu_move(-1) },
    { "<KP_Down>",   "Select the next row.",          menu_move(1) },
    { "<KP_Up>",     "Select the previous row.",      menu_move(-1) },
    { "<Tab>",       "Select the next row.",          menu_move(1) },
    { "<Shift-Tab>", "Select the previous row.",      menu_move(-1) },
    { "<Control-j>", "Select the next row.",          menu_move(1) },
    { "<Control-k>", "Select the previous row.",      menu_move(-1) },
    { "<Escape>",    "Leave the menu.",               function(w) w:set_mode() end },
    { "<Control-[>", "Leave the menu.",               function(w) w:set_mode() end },
}

-- ---------------------------------------------------------------------------
-- all：任何模式下都生效
-- ---------------------------------------------------------------------------
modes.add_binds("all", {
    { "<Escape>",    "Return to normal mode.", function(w) w:set_mode() end },
    { "<Control-[>", "Return to normal mode.", function(w) w:set_mode() end },
})

-- ---------------------------------------------------------------------------
-- normal
-- ---------------------------------------------------------------------------
local function scroll_by(axis, sign)
    return function(w, m) w:scroll({ [axis .. "rel"] = sign * step() * count(m) }) end
end
local function scroll_page(sign, fraction)
    return function(w, m) w:scroll({ ypagerel = sign * fraction * count(m) }) end
end

modes.add_binds("normal", {
    -- 模式切换
    { "i", "Enter insert mode (keys go to the page).", function(w) w:set_mode("insert") end },
    { ":", "Start typing a command.",                  function(w) w:set_mode("command") end },
    { "<Control-z>", "Pass every key to the page until Escape.", function(w) w:set_mode("passthrough") end },

    -- 滚动
    { "j",          "Scroll down one step.",  scroll_by("y", 1) },
    { "k",          "Scroll up one step.",    scroll_by("y", -1) },
    { "h",          "Scroll left one step.",  scroll_by("x", -1) },
    { "l",          "Scroll right one step.", scroll_by("x", 1) },
    { "<Down>",     "Scroll down one step.",  scroll_by("y", 1) },
    { "<Up>",       "Scroll up one step.",    scroll_by("y", -1) },
    { "<Left>",     "Scroll left one step.",  scroll_by("x", -1) },
    { "<Right>",    "Scroll right one step.", scroll_by("x", 1) },
    { "<KP_Down>",  "Scroll down one step.",  scroll_by("y", 1) },
    { "<KP_Up>",    "Scroll up one step.",    scroll_by("y", -1) },
    { "<KP_Left>",  "Scroll left one step.",  scroll_by("x", -1) },
    { "<KP_Right>", "Scroll right one step.", scroll_by("x", 1) },
    { "^",          "Scroll to the far left.",  function(w) w:scroll({ x = 0 }) end },
    { "0",          "Scroll to the far left.",  function(w) w:scroll({ x = 0 }) end },
    { "$",          "Scroll to the far right.", function(w) w:scroll({ x = -1 }) end },
    { "<Control-e>", "Scroll down one step.",  scroll_by("y", 1) },
    { "<Control-y>", "Scroll up one step.",    scroll_by("y", -1) },
    { "<Control-d>", "Scroll down half a page.", scroll_page(1, 0.5) },
    { "<Control-u>", "Scroll up half a page.",   scroll_page(-1, 0.5) },
    { "<Control-f>", "Scroll down a page.",      scroll_page(1, 1) },
    { "<Control-b>", "Scroll up a page.",        scroll_page(-1, 1) },
    { "<space>",     "Scroll down a page.",      scroll_page(1, 1) },
    { "<Shift-space>", "Scroll up a page.",      scroll_page(-1, 1) },
    { "<BackSpace>", "Scroll up a page.",        scroll_page(-1, 1) },
    { "<Page_Down>", "Scroll down a page.",      scroll_page(1, 1) },
    { "<Page_Up>",   "Scroll up a page.",        scroll_page(-1, 1) },
    { "<KP_Next>",   "Scroll down a page.",      scroll_page(1, 1) },
    { "<KP_Page_Up>", "Scroll up a page.",       scroll_page(-1, 1) },
    { "<Home>",      "Scroll to the top.",       function(w) w:scroll({ y = 0 }) end },
    { "<End>",       "Scroll to the bottom.",    function(w) w:scroll({ y = -1 }) end },
    { "<KP_Home>",   "Scroll to the top.",       function(w) w:scroll({ y = 0 }) end },
    { "<KP_End>",    "Scroll to the bottom.",    function(w) w:scroll({ y = -1 }) end },
    { "gg", "Scroll to the top, or to N percent with a count.", function(w, b, m)
        m = bufmeta(b, m)
        if m and m.count then w:scroll({ ypct = m.count }) else w:scroll({ y = 0 }) end
    end },
    { "G", "Scroll to the bottom, or to N percent with a count.", function(w, m)
        if m and m.count then w:scroll({ ypct = m.count }) else w:scroll({ y = -1 }) end
    end },
    { "%", "Scroll to N percent of the page.", function(w, m) w:scroll({ ypct = count(m) == 1 and 100 or count(m) }) end },

    -- 缩放
    { "+",  "Zoom in.",         function(w, m) w:zoom_in(zstep() * count(m)) end },
    { "-",  "Zoom out.",        function(w, m) w:zoom_out(zstep() * count(m)) end },
    { "=",  "Reset zoom.",      function(w) w:zoom_set() end },
    { "zi", "Zoom in.",         function(w, b, m) w:zoom_in(zstep() * count(bufmeta(b, m))) end },
    { "zo", "Zoom out.",        function(w, b, m) w:zoom_out(zstep() * count(bufmeta(b, m))) end },
    { "zz", "Reset zoom.",      function(w) w:zoom_set() end },

    { "<F11>", "Toggle fullscreen.", function(w) w.win.fullscreen = not w.win.fullscreen end },

    -- 剪贴板
    { "pp", "Open the primary selection here.",          function(w) open_selection(w, "primary", "current") end },
    { "pt", "Open the primary selection in a new tab.",  function(w) open_selection(w, "primary", "tab") end },
    { "pw", "Open the primary selection in a new window.", function(w) open_selection(w, "primary", "window") end },
    { "PP", "Open the clipboard here.",                   function(w) open_selection(w, "clipboard", "current") end },
    { "PT", "Open the clipboard in a new tab.",           function(w) open_selection(w, "clipboard", "tab") end },
    { "PW", "Open the clipboard in a new window.",        function(w) open_selection(w, "clipboard", "window") end },
    { "y",  "Copy the current address.",                 function(w) yank(w, current_uri(w), "address") end },
    { "Y",  "Copy the selected text to the clipboard.",  function(w)
        local text = selection("primary")
        if text then yank(w, text, "selection") else w:warning("nothing selected") end
    end },

    -- 地址里的数字
    { "<Control-a>", "Increase the last number in the address.", function(w, m) w:inc_uri(count(m)) end },
    { "<Control-x>", "Decrease the last number in the address.", function(w, m) w:inc_uri(-count(m)) end },

    -- 打开
    { "o", "Open an address here.",            function(w) w:enter_cmd(":open ") end },
    { "t", "Open an address in a new tab.",    function(w) w:enter_cmd(":tabopen ") end },
    { "w", "Open an address in a new window.", function(w) w:enter_cmd(":winopen ") end },
    { "O", "Edit the current address.",                    function(w) w:enter_cmd(":open " .. current_uri(w)) end },
    { "T", "Edit the current address for a new tab.",      function(w) w:enter_cmd(":tabopen " .. current_uri(w)) end },
    { "W", "Edit the current address for a new window.",   function(w) w:enter_cmd(":winopen " .. current_uri(w)) end },

    -- 历史
    { "H",           "Go back.",    function(w, m) w:back(count(m)) end },
    { "L",           "Go forward.", function(w, m) w:forward(count(m)) end },
    { "<Back>",      "Go back.",    function(w, m) w:back(count(m)) end },
    { "<Forward>",   "Go forward.", function(w, m) w:forward(count(m)) end },
    { "<Control-o>", "Go back.",    function(w, m) w:back(count(m)) end },
    { "<Control-i>", "Go forward.", function(w, m) w:forward(count(m)) end },

    -- 标签
    { "<Control-Page_Up>",   "Previous tab.", function(w, m) w:prev_tab(count(m)) end },
    { "<Control-Page_Down>", "Next tab.",     function(w, m) w:next_tab(count(m)) end },
    { "<Control-Tab>",       "Next tab.",     function(w, m) w:next_tab(count(m)) end },
    { "<Shift-Control-Tab>", "Previous tab.", function(w, m) w:prev_tab(count(m)) end },
    { "J",  "Next tab.",      function(w, m) w:next_tab(count(m)) end },
    { "K",  "Previous tab.",  function(w, m) w:prev_tab(count(m)) end },
    { "gt", "Next tab, or tab N with a count.", function(w, b, m)
        m = bufmeta(b, m)
        if m and m.count then w:goto_tab(m.count) else w:next_tab() end
    end },
    { "gT", "Previous tab.",  function(w, b, m) w:prev_tab(count(bufmeta(b, m))) end },
    { "g0", "First tab.",     function(w) w:goto_tab(1) end },
    { "g$", "Last tab.",      function(w) w:goto_tab(-1) end },
    { "<Control-t>", "Open a new tab.",   function(w) w:new_tab(home_page()) end },
    { "<Control-w>", "Close this tab.",   function(w) w:close_tab() end },
    { "d",  "Close this tab.",            function(w) w:close_tab() end },
    { "<",  "Move this tab left.",        function(w, m) w:move_tab(-count(m)) end },
    { ">",  "Move this tab right.",       function(w, m) w:move_tab(count(m)) end },
    { "gh", "Open the home page here.",        function(w) w:navigate(home_page()) end },
    { "gH", "Open the home page in a new tab.", function(w) w:new_tab(home_page()) end },
    { "gy", "Duplicate this tab.", function(w)
        local view = w.view
        if not view then return end
        local ok, state = pcall(function() return view.session_state end)
        if ok and type(state) == "string" and state ~= "" then
            w:new_tab({ session_state = state, uri = view.uri })
        else
            w:new_tab(view.uri)
        end
    end },

    -- 加载
    { "r", "Reload.",                     function(w) w:reload() end },
    { "R", "Reload, skipping the cache.", function(w) w:reload(true) end },
    { "<Control-c>", "Stop loading.",     function(w) w:stop() end },
    { "<Control-R>", "Restart the browser (not possible on Android).", function(w)
        w:warning("restarting is not supported on this platform")
    end },

    -- 窗口
    { "ZZ", "Save the session and close this window.", function(w)
        save_session_if_possible(w)
        w:close_win()
    end },
    { "ZQ", "Close this window without saving the session.", function(w) w:close_win(true) end },

    -- 帮助 / 开发
    { "<F1>",  "Open the help page.",      function(w) w:new_tab("luakit://help/") end },
    { "<F12>", "Toggle the web inspector.", function(w) w:toggle_inspector() end },
})

-- ---------------------------------------------------------------------------
-- insert / passthrough
-- ---------------------------------------------------------------------------
modes.add_binds("insert", {
    { "<Control-z>", "Pass every key to the page until Escape.", function(w) w:set_mode("passthrough") end },
})

modes.add_binds("passthrough", {
    { "<Escape>", "Return to normal mode.", function(w) w:set_mode() end },
})

-- ---------------------------------------------------------------------------
-- 命令
-- ---------------------------------------------------------------------------
local function arg_or_nil(o)
    local a = o and o.arg
    if type(a) ~= "string" then return nil end
    a = a:gsub("^%s+", ""):gsub("%s+$", "")
    if a == "" then return nil end
    return a
end

modes.add_cmds({
    { ":o[pen]", "Open an address or search in this tab.", function(w, o)
        w:navigate(arg_or_nil(o) or home_page())
    end, { format = "{uri}" } },
    { ":t[abopen]", "Open an address or search in a new tab.", function(w, o)
        w:new_tab(arg_or_nil(o) or home_page())
    end, { format = "{uri}" } },
    { ":priv-t[abopen]", "Open an address in a new private tab.", function(w, o)
        w:new_tab(arg_or_nil(o) or home_page(), { private = true })
    end, { format = "{uri}" } },
    { ":w[inopen]", "Open an address in a new window.", function(w, o)
        w:new_window(arg_or_nil(o) or home_page())
    end, { format = "{uri}" } },

    { ":c[lose]", "Close this tab.", function(w) w:close_tab() end },
    { ":q[uit]", "Close this window.", function(w, o) w:close_win(o.bang) end },
    { ":wq[all]", "Save the session and close every window.", function(w)
        save_session_if_possible(w)
        local wins = {}
        for _, ww in pairs(window.bywidget) do wins[#wins + 1] = ww end
        for _, ww in ipairs(wins) do ww:close_win(true) end
    end },
    { ":write", "Save the session.", function(w)
        if save_session_if_possible(w) then w:notify("session saved") else w:warning("session module not loaded") end
    end },
    { ":restart", "Restart the browser (not possible on Android).", function(w)
        w:warning("restarting is not supported on this platform")
    end },

    { ":back", "Go back N pages.", function(w, o) w:back(tonumber(arg_or_nil(o)) or 1) end },
    { ":f[orward]", "Go forward N pages.", function(w, o) w:forward(tonumber(arg_or_nil(o)) or 1) end },
    { ":reload", "Reload this tab (! skips the cache).", function(w, o) w:reload(o.bang) end },
    { ":stop", "Stop loading.", function(w) w:stop() end },
    { ":inc[rease]", "Increase the last number in the address by N.", function(w, o)
        w:inc_uri(tonumber(arg_or_nil(o)) or 1)
    end },
    { ":print", "Print the page.", function(w)
        local view = w.view
        if view then view:eval_js("window.print()", { source = "print" }) end
    end },
    { ":noh[lsearch]", "Clear search highlights.", function(w)
        if type(w.clear_search) == "function" then w:clear_search()
        elseif w.view then w.view:clear_search() end
    end },

    { ":javascript, :js", "Run JavaScript in this tab.", function(w, o)
        local code = arg_or_nil(o)
        local view = w.view
        if not (code and view) then return end
        view:eval_js(code, { source = "js command", callback = function(ret, err)
            if err then w:error(tostring(err)) elseif ret ~= nil then w:notify(tostring(ret)) end
        end })
    end },
    { ":lua", "Run Lua in the browser process (no argument: interactive prompt).", function(w, o)
        local code = arg_or_nil(o)
        if code then modes.eval_lua(w, code) else w:set_mode("lua") end
    end },

    { ":tab", "Run a command in a fresh tab.", function(w, o)
        local cmd = arg_or_nil(o)
        if not cmd then return end
        w:new_tab(get_setting("window.new_tab_page", "about:blank"))
        w:run_cmd(cmd)
    end, { format = "{command}" } },
    { ":tabd[o]", "Run a command in every tab.", function(w, o)
        local cmd = arg_or_nil(o)
        if not cmd then return end
        local cur = w.tabs:current()
        for i = 1, w.tabs:count() do
            w.tabs:switch(i)
            w:run_cmd(cmd)
        end
        if cur >= 1 then w.tabs:switch(cur) end
    end, { format = "{command}" } },
    { ":tabdu[plicate]", "Duplicate this tab.", function(w)
        local view = w.view
        if not view then return end
        local ok, state = pcall(function() return view.session_state end)
        if ok and type(state) == "string" and state ~= "" then
            w:new_tab({ session_state = state, uri = view.uri })
        else
            w:new_tab(view.uri)
        end
    end },
    { ":tabfir[st]", "Go to the first tab.", function(w) w:goto_tab(1) end },
    { ":tabl[ast]", "Go to the last tab.", function(w) w:goto_tab(-1) end },
    { ":tabn[ext]", "Go to the next tab.", function(w, o) w:next_tab(tonumber(arg_or_nil(o)) or 1) end },
    { ":tabp[revious]", "Go to the previous tab.", function(w, o) w:prev_tab(tonumber(arg_or_nil(o)) or 1) end },
    { ":tabde[tach]", "Move this tab into a new window.", function(w)
        local view = w.view
        if not view then return end
        if w.tabs:count() == 1 then
            w:warning("this is the only tab")
            return
        end
        w:detach_tab(view)
        window.new({ view })
    end },

    { ":dump", "Write the page source to a file.", function(w, o)
        local view = w.view
        if not view then return end
        local path = arg_or_nil(o) or (luakit.data_dir .. "/dump-" .. os.date("%Y%m%d-%H%M%S") .. ".html")
        local ok, err = view:save(path)
        if ok then w:notify("saved to " .. path) else w:error("dump failed: " .. tostring(err)) end
    end },
    { ":save", "Save the page to a file.", function(w, o)
        local view = w.view
        if not view then return end
        local path = arg_or_nil(o) or (luakit.data_dir .. "/page-" .. os.date("%Y%m%d-%H%M%S") .. ".html")
        local ok, err = view:save(path)
        if ok then w:notify("saved to " .. path) else w:error("save failed: " .. tostring(err)) end
    end },

    { ":set", "Change a setting: :set name value", function(w, o)
        local a = arg_or_nil(o)
        local name, value = a and a:match("^(%S+)%s*(.*)$")
        if not name then w:error("usage: :set <setting> <value>") return end
        if not (settings and settings.set_setting) then w:error("settings module not loaded") return end
        if value == "" then
            w:notify(("%s = %s"):format(name, tostring(get_setting(name, nil))))
            return
        end
        local ok, err = pcall(settings.set_setting, name, parse_value(value))
        if ok then w:notify(("%s = %s"):format(name, value)) else w:error(tostring(err)) end
    end, { format = "{setting}" } },
    { ":seton", "Change a setting for one domain: :seton domain name value", function(w, o)
        local a = arg_or_nil(o)
        local domain, name, value = a and a:match("^(%S+)%s+(%S+)%s*(.*)$")
        if not name then w:error("usage: :seton <domain> <setting> <value>") return end
        if not (settings and settings.set_setting) then w:error("settings module not loaded") return end
        local ok, err = pcall(settings.set_setting, name, parse_value(value), domain)
        if ok then w:notify(("%s[%s] = %s"):format(name, domain, value)) else w:error(tostring(err)) end
    end, { format = "{domain} {setting}" } },

    { ":help", "Open the help page.", function(w) w:new_tab("luakit://help/") end },
    { ":inspect", "Toggle the web inspector.", function(w) w:toggle_inspector() end },
    { ":viewsource, :vs", "Toggle the page source view.", function(w) w:toggle_source() end },
})

return _M
