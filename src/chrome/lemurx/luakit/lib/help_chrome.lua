-- LemurX · luakit-compatible library · help_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "help_chrome" module API. No luakit code is used.
--
-- luakit://help/ 帮助入口：
--   luakit://help/               已加载模块一览（自写的 Markdown 说明，用 lib/markdown.lua 渲染）
--   luakit://help/keys           常用按键速查表 + 按模式列出全部绑定（复用 binds_chrome）
--   luakit://help/module/<名>    某个模块的说明
-- 命令 :help

local chrome = require("chrome")
local binds_chrome = require("binds_chrome")
require("markdown")

local M = {}

M.chrome_page = "luakit://help/"
M.stylesheet = [[
.doc h1, .doc h2, .doc h3 { margin: 14px 0 6px; }
.doc p { margin: 6px 0; }
.doc code { background: var(--lx-panel-2); padding: 1px 5px; border-radius: 5px; }
.mods { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; }
.mods a { display: block; padding: 12px 14px; border-radius: 12px; background: var(--lx-panel); border: 1px solid var(--lx-line); color: var(--lx-text); }
.mods a:hover { border-color: var(--lx-accent); text-decoration: none; }
.mods a .n { font-family: var(--lx-mono); color: var(--lx-accent-2); }
.mods a .s { display: block; color: var(--lx-muted); font-size: 13px; margin-top: 2px; }
.mods a.off { opacity: .45; }
.cheat { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 10px; }
.cheat .lx-card { margin: 0; }
.cheat .r { display: flex; justify-content: space-between; gap: 10px; padding: 5px 0; border-bottom: 1px solid var(--lx-line); }
.cheat .r:last-child { border-bottom: 0; }
.cheat .r span:last-child { color: var(--lx-muted); text-align: right; }
.nav { display: flex; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; }
]]

local esc = chrome.escape

-- 模块说明（Markdown，自写）。key = 模块名
M.modules = {
    window = { summary = "Browser window shell", doc = [[
The **window** module builds each browser window: the tab strip, the web view
notebook, the status bar and the input bar. Other modules extend it by adding
functions to `window.methods` before the first window is created.

Useful entry points: `window.new(uris)`, `window.current()`, `w:new_tab(uri)`,
`w:close_tab()`, `w:notify(text)`.]] },
    webview = { summary = "Per-tab web view behaviour", doc = [[
Every tab is a `webview` widget. The **webview** module wires default behaviour
(status-bar link hover, title updates, new-window decisions) and lets modules
hook every new tab with `webview.add_signal("init", function (view) ... end)`.]] },
    modes = { summary = "Modal key handling", doc = [[
LemurX is modal, like vi. **modes** defines `normal`, `insert`, `command`,
`passthrough` and friends; `modes.add_binds(mode, binds)` and `modes.add_cmds(cmds)`
register new keys and `:commands`.]] },
    binds = { summary = "Default key bindings", doc = [[
Default bindings for the core modes. See [all bindings](luakit://binds/) for the
live table including anything added by other modules.]] },
    settings = { summary = "Central settings registry", doc = [[
Modules register typed settings (`settings.register_settings{ ["group.name"] = {...} }`).
Read or write them as `settings.group.name`; domain overrides live under
`settings.on["example.com"].group.name`. Values persist in `settings.db`.
Browse and edit them at [luakit://settings/](luakit://settings/).]] },
    chrome = { summary = "luakit:// page router", doc = [[
`chrome.add(name, function (view, meta) return html end, on_first_visual, exports)`
serves `luakit://name/`. Functions in `exports` become `window.<name>()` in the
page's JavaScript and return Promises.]] },
    history = { summary = "Browsing history (history.db)", doc = [[
Visited pages are stored in `history.db`. Search them at
[luakit://history/](luakit://history/) or with `:history <words>`.
Set `settings.history.enabled = false` to stop recording.]] },
    bookmarks = { summary = "Bookmarks with tags (bookmarks.db)", doc = [[
`:bookmark [uri] [tags]` saves a page; <kbd>B</kbd> opens
[luakit://bookmarks/](luakit://bookmarks/) where you can filter by tag, edit and delete.]] },
    quickmarks = { summary = "One-key site shortcuts", doc = [[
Press <kbd>M</kbd> followed by a letter or digit to remember the current page.
Open it later with <kbd>go</kbd><i>x</i>, in a new tab with <kbd>gn</kbd><i>x</i>
or in a new window with <kbd>gw</kbd><i>x</i>. `:qmarks` lists them.]] },
    downloads = { summary = "Download manager", doc = [[
Downloads land in `settings.downloads.default_dir`. Watch progress at
[luakit://downloads/](luakit://downloads/) (`:downloads`, <kbd>gd</kbd>).
Hook `downloads.add_signal("open-file", fn(file, mime))` to open finished files.]] },
    error_page = { summary = "Friendly error pages", doc = [[
Failed loads, untrusted certificates and crashed tabs are replaced with an error
page offering *Try again*, *Go back* and, for certificate problems, *Ignore certificate*.]] },
    adblock = { summary = "Request blocking", doc = [[
Filter lists in the data directory block matching requests. `:adblock-list`,
`:adblock-enable`, `:adblock-disable`.]] },
    follow = { summary = "Keyboard link hints", doc = [[
Press <kbd>f</kbd> to label every link with a hint, then type the hint to follow it.
<kbd>F</kbd> opens in a new tab.]] },
    search = { summary = "Find in page", doc = [[
<kbd>/</kbd> searches forward, <kbd>?</kbd> backward; <kbd>n</kbd> and <kbd>N</kbd>
jump between matches.]] },
    session = { summary = "Save and restore tabs", doc = [[
Sessions are saved on exit and restored on start; `:session` writes one now.]] },
    log_chrome = { summary = "Runtime log viewer", doc = [[
[luakit://log/](luakit://log/) shows recent log lines with a level filter.]] },
    newtab_chrome = { summary = "New tab page", doc = [[
[luakit://newtab/](luakit://newtab/) is shown for empty tabs. Drop a `newtab.html`
into the data directory to replace it, or fill `settings.newtab_chrome.quicklinks`.]] },
    clear_data = { summary = "Clear cookies, cache and site data", doc = [[
`:clear-data` opens [luakit://clear-data/](luakit://clear-data/).]] },
    view_source = { summary = "View page source", doc = [[
`:view-source` (or <kbd>Ctrl-Shift-U</kbd>) opens `view-source:` for the current page in a new tab.]] },
    tabmenu = { summary = "Tab switcher menu", doc = [[
`:tabmenu` lists open tabs in a menu; <kbd>Return</kbd> switches, <kbd>Delete</kbd> closes.]] },
}

-- 速查表（自写）
M.cheatsheet = {
    { "Navigate", {
        { "o / t / w", "open URL here / new tab / new window" },
        { "O / T / W", "same, prefilled with the current URL" },
        { "H / L", "back / forward" },
        { "r / R", "reload / reload bypassing cache" },
        { "gH", "home page" },
        { "y", "copy current URL" },
        { "p / P", "open clipboard URL here / in a new tab" },
    } },
    { "Tabs", {
        { "gt / gT", "next / previous tab" },
        { "d", "close tab" },
        { "u", "reopen closed tab" },
        { "g0 / g$", "first / last tab" },
        { ":tabmenu", "pick a tab from a list" },
    } },
    { "Page", {
        { "j / k / h / l", "scroll" },
        { "gg / G", "top / bottom" },
        { "Ctrl-d / Ctrl-u", "half page down / up" },
        { "+ / - / =", "zoom in / out / reset" },
        { "f / F", "follow link here / in new tab" },
        { "/ ?  n N", "search, next / previous match" },
    } },
    { "Modes", {
        { "i", "insert mode" },
        { ":", "command mode" },
        { "Ctrl-z", "pass all keys to the page" },
        { "Escape", "back to normal mode" },
    } },
    { "Data", {
        { "a / A", "bookmark page (with tags / immediately)" },
        { "B", "bookmarks page" },
        { "M<x>  go<x>", "set / open quickmark" },
        { "gd", "downloads page" },
        { ":history", "browsing history" },
    } },
}

local function nav(active)
    local items = { { "", "Modules" }, { "keys", "Keys" } }
    local out = {}
    for _, it in ipairs(items) do
        out[#out + 1] = ("<a class=\"lx-btn%s\" href=\"luakit://help/%s\">%s</a>"):format(
            active == it[1] and " lx-primary" or "", it[1], it[2])
    end
    out[#out + 1] = "<a class=\"lx-btn\" href=\"luakit://settings/\">Settings</a>"
    out[#out + 1] = "<a class=\"lx-btn\" href=\"luakit://log/\">Log</a>"
    return "<div class=\"nav\">" .. table.concat(out) .. "</div>"
end

local function loaded(name)
    return package.loaded[name] ~= nil
end

local function index_html()
    local names = {}
    for name in pairs(M.modules) do names[#names + 1] = name end
    table.sort(names)
    local cards = {}
    for _, name in ipairs(names) do
        local info = M.modules[name]
        cards[#cards + 1] = ("<a class=\"%s\" href=\"luakit://help/module/%s\"><span class=\"n\">%s</span><span class=\"s\">%s</span></a>")
            :format(loaded(name) and "" or "off", esc(name), esc(name), esc(info.summary or ""))
    end
    -- 其它已加载但没有说明的 lib 模块
    local extra = {}
    for name in pairs(package.loaded) do
        if type(name) == "string" and not M.modules[name] and not name:find("%.")
            and lfs.attributes(luakit.install_path .. "/lib/" .. name .. ".lua") then
            extra[#extra + 1] = name
        end
    end
    table.sort(extra)
    local intro = markdown([[
LemurX is a keyboard-driven browser shell built on a luakit-compatible Lua runtime.
Everything you see is a Lua module; the cards below describe the ones that ship by
default. Dimmed cards are modules that are not loaded in this profile.

Press <kbd>F1</kbd> anytime to return here, or <kbd>:</kbd> and type `help`.]])
    local body = nav("") .. "<div class=\"doc\">" .. intro .. "</div><div class=\"mods\">" .. table.concat(cards) .. "</div>"
    if #extra > 0 then
        local tags = {}
        for _, n in ipairs(extra) do tags[#tags + 1] = "<span class=\"lx-tag lx-on\">" .. esc(n) .. "</span>" end
        body = body .. "<div class=\"lx-card\" style=\"margin-top:14px\"><h2>Also loaded</h2>" .. table.concat(tags, " ") .. "</div>"
    end
    return chrome.render({ title = "Help", heading = "Help", style = M.stylesheet, body = body })
end

local function keys_html()
    local cards = {}
    for _, group in ipairs(M.cheatsheet) do
        local rows = {}
        for _, r in ipairs(group[2]) do
            rows[#rows + 1] = ("<div class=\"r\"><kbd>%s</kbd><span>%s</span></div>"):format(esc(r[1]), esc(r[2]))
        end
        cards[#cards + 1] = "<div class=\"lx-card\"><h2>" .. esc(group[1]) .. "</h2>" .. table.concat(rows) .. "</div>"
    end
    local sections = {}
    for _, mode in ipairs(binds_chrome.collect()) do sections[#sections + 1] = binds_chrome.mode_html(mode) end
    local body = nav("keys") .. "<div class=\"cheat\">" .. table.concat(cards) .. "</div>"
        .. "<h2 style=\"margin:24px 0 10px;font-size:16px\">Every binding, by mode</h2>" .. table.concat(sections)
    return chrome.render({ title = "Keys", heading = "Keys", style = M.stylesheet .. binds_chrome.stylesheet, body = body })
end

local function module_html(name)
    local info = M.modules[name]
    local body
    if not info then
        body = nav("") .. ("<div class=\"lx-card\"><p>No description for <code>%s</code>.</p></div>"):format(esc(name))
    else
        body = nav("") .. "<div class=\"lx-card doc\"><h2>" .. esc(name)
            .. (loaded(name) and " <span class=\"lx-tag lx-on\">loaded</span>" or " <span class=\"lx-tag\">not loaded</span>")
            .. "</h2>" .. markdown(info.doc or "") .. "</div>"
    end
    return chrome.render({ title = name, heading = "Help · " .. name, style = M.stylesheet, body = body })
end

chrome.add("help", function(_, meta)
    local path = meta.path or ""
    if path == "" or path == "index" then return index_html() end
    if path == "keys" or path == "binds" then return keys_html() end
    local mod = path:match("^module/([%w_]+)/?$")
    if mod then return module_html(mod) end
    return false
end)

do
    local ok, modes = pcall(require, "modes")
    if ok and modes then
        modes.add_cmds({
            { ":help", "Open the help pages.", function(w) w:new_tab(M.chrome_page) end },
        })
    end
end

return M
