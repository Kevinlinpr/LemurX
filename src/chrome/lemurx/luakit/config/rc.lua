-- LemurX · luakit-compatible library · rc.lua
--
-- Default user configuration for the luakit-compatible shell.  Copy this file
-- to luakit.config_dir .. "/rc.lua" and edit; a user copy shadows this one.
-- Only runs when the shell is switched on:
--     lemurx.storage.set("luakit_rc", "1")

-- Modules from the user's config_dir must not shadow the bundled library by
-- accident; warn when that happens (same convention as luakit).
do
    local path = package.path
    for _, dir in ipairs({ luakit.config_dir }) do
        for _, m in ipairs({ "lousy", "window", "webview", "modes", "binds" }) do
            local f = io.open(dir .. "/" .. m .. ".lua", "r")
            if f then
                f:close()
                msg.warn("%s/%s.lua shadows the bundled module", dir, m)
            end
        end
    end
    package.path = path
end

-- Logging: "verbose" makes msg.verbose() visible.
-- msg.set_level("verbose")

-- Common library
local lousy = require("lousy")

-- Theme (config_dir/theme.lua, falling back to the bundled one)
lousy.theme.init(lousy.util.find_config("theme.lua"))
assert(lousy.theme.get(), "failed to load theme")

-- Window / webview / modes / default bindings
local window = require("window")
local webview = require("webview")
local modes = require("modes")
require("binds")

-- Settings and the luakit://settings page
local settings = require("settings")
require("settings_chrome")

-- Home page, search engines and other simple settings
settings.window.home_page = "luakit://newtab/"
settings.window.new_tab_page = "luakit://newtab/"
settings.window.search_engines.default = "https://duckduckgo.com/?q=%s"
settings.window.search_engines.ddg = "https://duckduckgo.com/?q=%s"
settings.window.search_engines.google = "https://www.google.com/search?q=%s"
settings.window.search_engines.wikipedia = "https://en.wikipedia.org/wiki/Special:Search?search=%s"

-- Standard module set (comment out what you do not want)
require("webinspector")
require("styles")
require("proxy")
require("adblock")
require("adblock_chrome")
require("quickmarks")
require("undoclose")
require("tabhistory")
require("userscripts")
require("bookmarks")
require("bookmarks_chrome")
require("downloads")
require("downloads_chrome")
require("viewpdf")
require("history")
require("history_chrome")
require("help_chrome")
require("binds_chrome")
require("log_chrome")
require("newtab_chrome")
require("introspector_chrome")
require("taborder")
require("session")
require("view_source")
require("follow")
require("follow_selected")
require("go_input")
require("go_next_prev")
require("go_up")
require("cmdhist")
require("search")
require("completion")
require("open_editor")
require("select")
require("tab_favicons")
require("tabmenu")
require("clear_data")
require("error_page")
require("hide_scrollbars")
require("image_css")
require("noscript")
require("formfiller")
require("referer_control")
require("domain_props")

-- Status-bar widgets (left to right / right to left)
window.add_signal("build", function (w)
    local widgets, l, r = require("lousy.widget"), w.sbar.l.layout, w.sbar.r.layout
    l:pack(widgets.uri)
    l:pack(widgets.hist)
    l:pack(widgets.progress)
    r:pack(widgets.buf)
    r:pack(widgets.ssl)
    r:pack(widgets.tabi)
    r:pack(widgets.scroll)
    r:pack(widgets.zoom)
end)

-- Unique instance: a second launch hands its URIs to the running one.
if not luakit.nounique then
    require("unique_instance")
end

-- Restore the previous session, otherwise open the requested / home page.
-- `uris` is the argument list the runtime was started with (may be empty).
if not require("session").restore() then
    window.new(uris)
end
