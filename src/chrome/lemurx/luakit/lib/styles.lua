-- LemurX · luakit-compatible library · styles
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "styles" module API. No luakit code is used.
--
-- 用户样式表：读取 luakit.data_dir/styles/*.css，解析 Mozilla 风格的
--   @-moz-document domain(a.com), url-prefix("https://b.org/x"), url("..."), regexp("...") { css }
-- 头部，把每个块编译成内核 stylesheet{} 对象，在页面导航时按 URI 决定
-- view.stylesheets[ss] 的开关。块外的 CSS 对所有页面生效。
-- 命令：:styles-reload(:sr) :styles-list :styles-new
-- 模式：styles-list（<space>/<Return> 切换，e 编辑）
-- 设置：styles.enabled
-- 公开接口：styles.load_file(path) detect_files() watch_styles(guard, path) new_style(w)
--   toggle_sheet(title) parse(css) block_matches(block, uri) apply(view) sheets dir
-- 每张样式表的启用状态保存在 luakit.data_dir/styles.db。

local lousy = require("lousy")
local settings = require("settings")
local modes = require("modes")
local webview = require("webview")
local window = require("window")

local _M = {}
lousy.signal.setup(_M, true)

settings.register_settings({
    ["styles.enabled"] = {
        type = "boolean", default = true,
        desc = "Apply user stylesheets from the styles directory.",
    },
})

_M.dir = luakit.data_dir .. "/styles"

local sheets = {}       -- title -> sheet
local titles = {}       -- 有序标题
_M.sheets = sheets

-- ---------------------------------------------------------------------------
-- 启用状态持久化
-- ---------------------------------------------------------------------------
local enabled_cache = {}
local db

local function open_db()
    if db then return db end
    local ok, d = pcall(sqlite3, { filename = luakit.data_dir .. "/styles.db" })
    if not ok then return nil end
    db = d
    pcall(db.exec, db, "CREATE TABLE IF NOT EXISTS enabled (title TEXT PRIMARY KEY, enabled INTEGER);")
    local rows
    pcall(function() rows = db:exec("SELECT title, enabled FROM enabled") end)
    for _, row in ipairs(type(rows) == "table" and rows or {}) do
        if type(row) == "table" and type(row.title) == "string" then
            enabled_cache[row.title] = tonumber(row.enabled) == 1
        end
    end
    return db
end

local function persist_enabled(title, on)
    enabled_cache[title] = on
    local d = open_db()
    if d then
        pcall(d.exec, d, "INSERT OR REPLACE INTO enabled (title, enabled) VALUES (?, ?)", { title, on and 1 or 0 })
    end
end

-- ---------------------------------------------------------------------------
-- 解析
-- ---------------------------------------------------------------------------
local function strip_comments(css)
    return (css:gsub("/%*.-%*/", ""))
end

local function unquote(s)
    s = s:gsub("^%s+", ""):gsub("%s+$", "")
    local q = s:sub(1, 1)
    if (q == '"' or q == "'") and s:sub(-1) == q then
        s = s:sub(2, -2)
        -- CSS 字符串转义：\\ → \，\" → "，\' → '
        s = s:gsub("\\(.)", "%1")
    end
    return s
end

local function parse_conditions(text)
    local conds = {}
    for kind, arg in text:gmatch("([%w%-]+)%s*(%b())") do
        kind = kind:lower()
        arg = unquote(arg:sub(2, -2))
        if kind == "domain" or kind == "url" or kind == "url-prefix" or kind == "regexp" then
            local c = { kind = kind, arg = arg }
            if kind == "regexp" then
                local ok, r = pcall(function() return regex{ pattern = "^(?:" .. arg .. ")$" } end)
                if ok then c.re = r else msg.warn("styles: bad regexp(%s): %s", arg, tostring(r)) end
            end
            conds[#conds + 1] = c
        end
    end
    return conds
end

-- 找到与 pos 处 "{" 配对的 "}"
local function matching_brace(css, open_pos)
    local depth = 0
    for i = open_pos, #css do
        local c = css:sub(i, i)
        if c == "{" then depth = depth + 1
        elseif c == "}" then
            depth = depth - 1
            if depth == 0 then return i end
        end
    end
    return nil
end

-- 返回 { global = css, blocks = { { conds = {...}, css = "..." } } }
function _M.parse(css)
    css = strip_comments(css or "")
    local out = { global = "", blocks = {} }
    local pos = 1
    local global_parts = {}
    while true do
        local s, e = css:find("@%-moz%-document", pos)
        if not s then
            global_parts[#global_parts + 1] = css:sub(pos)
            break
        end
        global_parts[#global_parts + 1] = css:sub(pos, s - 1)
        local brace = css:find("{", e + 1, true)
        if not brace then break end
        local close = matching_brace(css, brace)
        if not close then break end
        local conds = parse_conditions(css:sub(e + 1, brace - 1))
        out.blocks[#out.blocks + 1] = { conds = conds, css = css:sub(brace + 1, close - 1) }
        pos = close + 1
    end
    out.global = table.concat(global_parts):gsub("^%s+", ""):gsub("%s+$", "")
    return out
end

local function host_of(uri)
    return (tostring(uri or ""):match("^%a[%w+.-]*://([^/?#:@]+)") or ""):lower()
end

function _M.block_matches(block, uri)
    uri = tostring(uri or "")
    if #block.conds == 0 then return true end
    local host = host_of(uri)
    for _, c in ipairs(block.conds) do
        if c.kind == "domain" then
            local d = c.arg:lower()
            if host == d or host:sub(-(#d + 1)) == "." .. d then return true end
        elseif c.kind == "url" then
            if uri == c.arg then return true end
        elseif c.kind == "url-prefix" then
            if uri:sub(1, #c.arg) == c.arg then return true end
        elseif c.kind == "regexp" and c.re then
            local ok, m = pcall(c.re.match, c.re, uri)
            if ok and m then return true end
        end
    end
    return false
end

-- ---------------------------------------------------------------------------
-- 加载
-- ---------------------------------------------------------------------------
local function read_file(path)
    local f = io.open(path, "rb")
    if not f then return nil end
    local s = f:read("a")
    f:close()
    return s
end

local function all_views()
    local out = {}
    for _, w in pairs(window.bywidget or {}) do
        local ok, n = pcall(function() return w.tabs:count() end)
        if ok and n then
            for i = 1, n do
                local v = w.tabs:atindex(i)
                if v then out[#out + 1] = v end
            end
        end
    end
    return out
end

local function drop_sheet(sheet)
    for _, v in ipairs(all_views()) do
        pcall(function()
            if sheet.global_ss then v.stylesheets[sheet.global_ss] = false end
            for _, b in ipairs(sheet.blocks) do v.stylesheets[b.ss] = false end
        end)
    end
end

function _M.load_file(path)
    local text = read_file(path)
    if not text then
        msg.warn("styles: cannot read %s", path)
        return nil
    end
    local title = (path:match("([^/]+)$") or path):gsub("%.css$", "")
    local parsed = _M.parse(text)
    local old = sheets[title]
    if old then drop_sheet(old) end
    local sheet = { title = title, path = path, blocks = {} }
    if parsed.global ~= "" then sheet.global_ss = stylesheet{ source = parsed.global } end
    for _, b in ipairs(parsed.blocks) do
        sheet.blocks[#sheet.blocks + 1] = { conds = b.conds, ss = stylesheet{ source = b.css } }
    end
    open_db()
    if enabled_cache[title] ~= nil then sheet.enabled = enabled_cache[title] else sheet.enabled = true end
    sheets[title] = sheet
    if not old then titles[#titles + 1] = title end
    _M.emit_signal("sheet-loaded", sheet)
    return sheet
end

function _M.detect_files()
    pcall(lfs.mkdir, _M.dir)
    local ok, iter, st = pcall(lfs.dir, _M.dir)
    if not ok or not iter then return end
    local names = {}
    for name in iter, st do
        if name:match("%.css$") then names[#names + 1] = name end
    end
    table.sort(names)
    for _, name in ipairs(names) do _M.load_file(_M.dir .. "/" .. name) end
    for _, v in ipairs(all_views()) do _M.apply(v) end
end

-- 轮询文件修改时间（Android 没有 inotify 给 Lua 用）
function _M.watch_styles(guard, path)
    if type(guard) ~= "table" then error("watch_styles: guard must be a table", 2) end
    guard[1] = true
    local last = lfs.attributes(path, "modification")
    local t = timer{ interval = 1000 }
    t:add_signal("timeout", function(tm)
        if not guard[1] then
            tm:stop()
            return
        end
        local m = lfs.attributes(path, "modification")
        if m ~= last then
            last = m
            _M.load_file(path)
            for _, v in ipairs(all_views()) do _M.apply(v) end
        end
    end)
    t:start()
    return t
end

-- ---------------------------------------------------------------------------
-- 应用
-- ---------------------------------------------------------------------------
function _M.apply(view, uri)
    uri = uri or view.uri
    local on = settings.get_setting("styles.enabled") ~= false
    for _, title in ipairs(titles) do
        local sheet = sheets[title]
        local active = on and sheet.enabled
        pcall(function()
            if sheet.global_ss then view.stylesheets[sheet.global_ss] = active end
            for _, b in ipairs(sheet.blocks) do
                view.stylesheets[b.ss] = active and _M.block_matches(b, uri) or false
            end
        end)
    end
end

-- 样式表对某 URI 的状态："off" / "on" / "active"
function _M.state_for(sheet, uri)
    if not sheet.enabled then return "off" end
    if sheet.global_ss then return "active" end
    for _, b in ipairs(sheet.blocks) do
        if _M.block_matches(b, uri) then return "active" end
    end
    return "on"
end

function _M.toggle_sheet(title)
    local sheet = sheets[title]
    if not sheet then return nil end
    sheet.enabled = not sheet.enabled
    persist_enabled(title, sheet.enabled)
    for _, v in ipairs(all_views()) do _M.apply(v) end
    _M.emit_signal("sheet-toggled", sheet)
    return sheet.enabled
end

function _M.new_style(w)
    local uri = w.view and w.view.uri or ""
    local host = host_of(uri)
    if host == "" then
        w:warning("styles: current page has no domain")
        return
    end
    pcall(lfs.mkdir, _M.dir)
    local path = _M.dir .. "/" .. host .. ".css"
    if not lfs.attributes(path) then
        local f = io.open(path, "wb")
        if f then
            f:write(("@-moz-document domain(%q) {\n\n}\n"):format(host))
            f:close()
        end
    end
    _M.load_file(path)
    _M.apply(w.view)
    local ok, editor = pcall(require, "editor")
    if ok and editor and editor.edit then
        editor.edit(path, 2, function() _M.load_file(path) for _, v in ipairs(all_views()) do _M.apply(v) end end)
    else
        w:notify("styles: created " .. path)
    end
end

webview.add_signal("init", function(view)
    view:add_signal("navigation-request", function(v, uri) _M.apply(v, uri) end)
    view:add_signal("load-status", function(v, status, uri)
        if status == "provisional" or status == "committed" then _M.apply(v, uri) end
    end)
end)

settings.add_signal("setting-changed", function(a, b)
    local ev = type(a) == "table" and a or b -- C 组 settings 是模块信号：handler(ev)；也兼容 (obj, ev)
    if ev and ev.key == "styles.enabled" then
        for _, v in ipairs(all_views()) do _M.apply(v) end
    end
end)

-- ---------------------------------------------------------------------------
-- 菜单模式
-- ---------------------------------------------------------------------------
local function build_menu(w)
    local uri = w.view and w.view.uri or ""
    local rows = { { "Stylesheet", "State", title = true } }
    for _, title in ipairs(titles) do
        local sheet = sheets[title]
        rows[#rows + 1] = { title, _M.state_for(sheet, uri), sheet = sheet }
    end
    if #rows == 1 then rows[2] = { "(no .css files in " .. _M.dir .. ")", "", selectable = false } end
    w.menu:build(rows)
end

modes.new_mode("styles-list", "Enable, disable or edit user stylesheets.", {
    enter = function(w)
        build_menu(w)
        w.menu:show()
        w:set_prompt("Stylesheets — <space>: toggle, e: edit")
    end,
    leave = function(w) w.menu:hide() end,
})

modes.add_binds("styles-list", {
    { "<space>", "Toggle the selected stylesheet.", function (w)
        local row = w.menu:get()
        if row and row.sheet then
            _M.toggle_sheet(row.sheet.title)
            build_menu(w)
        end
    end },
    { "<Return>", "Toggle the selected stylesheet.", function (w)
        local row = w.menu:get()
        if row and row.sheet then
            _M.toggle_sheet(row.sheet.title)
            build_menu(w)
        end
    end },
    { "e", "Edit the selected stylesheet.", function (w)
        local row = w.menu:get()
        if row and row.sheet then
            local path = row.sheet.path
            w:set_mode()
            local ok, editor = pcall(require, "editor")
            if ok and editor and editor.edit then
                editor.edit(path, 1, function() _M.load_file(path) for _, v in ipairs(all_views()) do _M.apply(v) end end)
            else
                w:notify("styles: edit " .. path)
            end
        end
    end },
    { "<Tab>", "Select the next stylesheet.", function (w) w.menu:move_down() end },
    { "<Shift-Tab>", "Select the previous stylesheet.", function (w) w.menu:move_up() end },
})

modes.add_cmds({
    { ":styles-reload, :sr", "Re-scan the styles directory and reload every stylesheet.", function (w)
        _M.detect_files()
        w:notify(("styles: %d stylesheet(s) loaded"):format(#titles))
    end },
    { ":styles-list", "List user stylesheets and toggle them.", function (w) w:set_mode("styles-list") end },
    { ":styles-new", "Create a stylesheet for the current domain.", function (w) _M.new_style(w) end },
})

_M.detect_files()

return _M
