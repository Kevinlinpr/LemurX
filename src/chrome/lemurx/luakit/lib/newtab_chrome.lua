-- LemurX · luakit-compatible library · newtab_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "newtab_chrome" module API. No luakit code is used.
--
-- luakit://newtab/ 新标签页。优先级：
--   1. newtab_chrome.new_tab_file（默认 luakit.data_dir/newtab.html）存在则直接用其内容；
--   2. 否则 newtab_chrome.new_tab_src 非空则用它；
--   3. 否则内建页面：一个 "X" 标志 + 设置 newtab_chrome.quicklinks 里的快捷链接。
-- 设置：newtab_chrome.quicklinks（array:string，每项 "标题|https://..." 或纯网址）

local chrome = require("chrome")
local settings = require("settings")

local M = {}

M.new_tab_file = luakit.data_dir .. "/newtab.html"
M.new_tab_src = nil

settings.register_settings({
    ["newtab_chrome.quicklinks"] = {
        type = "array:string",
        default = {},
        desc = "Shortcuts shown on the new tab page, each as \"Label|https://url\" or a bare URL.",
    },
})

local esc = chrome.escape

local style = [[
body { min-height: 100vh; display: flex; flex-direction: column; padding: 0; }
main.lx-wrap { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.xmark {
  width: 120px; height: 120px; border-radius: 34px; display: grid; place-items: center;
  font-size: 72px; font-weight: 900; color: #fff; letter-spacing: -4px;
  background: linear-gradient(135deg, #ff7a1a 0%, #ff3d81 100%);
  box-shadow: 0 20px 60px rgba(255, 90, 60, .35);
}
.brand { margin-top: 18px; color: var(--lx-muted); letter-spacing: 3px; font-size: 12px; text-transform: uppercase; }
.links { margin-top: 36px; display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 10px; width: 100%; max-width: 560px; }
.links a {
  display: block; padding: 14px 12px; border-radius: 14px; text-align: center; color: var(--lx-text);
  background: var(--lx-panel); border: 1px solid var(--lx-line); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.links a:hover { border-color: var(--lx-accent); text-decoration: none; }
.hint { margin-top: 40px; color: var(--lx-muted); font-size: 13px; }
]]

local function read_file(path)
    local f = io.open(path, "r")
    if not f then return nil end
    local s = f:read("a")
    f:close()
    return s
end

local function quicklinks_html()
    local links = settings.get_setting("newtab_chrome.quicklinks") or {}
    if #links == 0 then return "" end
    local out = { "<div class=\"links\">" }
    for _, item in ipairs(links) do
        local label, uri = item:match("^(.-)|(.+)$")
        if not uri then
            uri = item
            label = uri:match("^%a[%w+.-]*://([^/]+)") or uri
        end
        out[#out + 1] = ("<a href=\"%s\">%s</a>"):format(esc(uri), esc(label))
    end
    out[#out + 1] = "</div>"
    return table.concat(out)
end

local function builtin()
    return chrome.render({
        title = "New tab", header = false, style = style,
        body = "<div class=\"xmark\">X</div><div class=\"brand\">LemurX</div>"
            .. quicklinks_html()
            .. "<div class=\"hint\">Press <kbd>o</kbd> to open a page, <kbd>:</kbd> for commands, <kbd>F1</kbd> for help.</div>",
    })
end

chrome.add("newtab", function()
    local file = M.new_tab_file
    if type(file) == "string" then
        local html = read_file(file)
        if html then return html end
    end
    if type(M.new_tab_src) == "string" and M.new_tab_src ~= "" then return M.new_tab_src end
    return builtin()
end)

return M
