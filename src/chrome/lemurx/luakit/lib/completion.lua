-- LemurX · luakit-compatible library · completion
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "completion" module API. No luakit code is used.
--
-- ":" 命令补全。在 command 模式按 <Tab> 进入 completion 模式：
--   * 还在敲命令名 → 列出匹配的命令（modes.get_cmds()）
--   * 命令的 format 含 {uri} → 从 history（package.loaded.history.db）和
--     bookmarks（package.loaded.bookmarks.db）里按关键字查候选
--   * format 含 {setting} → 列出 settings.get_settings() 里的键
-- <Tab>/<Shift-Tab>/<Down>/<Up> 换选中行并回填输入栏，<Return> 执行，<Escape> 回到 command 模式。
-- settings.completion.max_items 控制每类候选的条数。

local lousy = require("lousy")
local modes = require("modes")
local window = require("window")
local util = lousy.util
local escape = util.escape

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
if settings and settings.register_settings then
    pcall(settings.register_settings, {
        ["completion.max_items"] = {
            type = "number", default = 25, min = 1,
            desc = "How many rows each completion source may contribute.",
        },
    })
end

local function max_items() return math.floor(tonumber(get_setting("completion.max_items", 25)) or 25) end

-- ---------------------------------------------------------------------------
-- 候选来源
-- ---------------------------------------------------------------------------
local function like_pattern(s)
    return "%" .. s:gsub("[%%_]", function(c) return "\\" .. c end) .. "%"
end

local function db_rows(mod_name, table_name, term, limit)
    local mod = package.loaded[mod_name]
    if not (mod and mod.db and type(mod.db.exec) == "function") then return {} end
    local sql = ("SELECT uri, title FROM %s WHERE uri LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' LIMIT ?"):format(table_name)
    local ok, rows = pcall(mod.db.exec, mod.db, sql, { like_pattern(term), like_pattern(term), limit })
    if not ok or type(rows) ~= "table" then return {} end
    return rows
end

local function command_rows(prefix, limit)
    local rows = {}
    local seen = {}
    for _, c in ipairs(modes.get_cmds("command")) do
        local shown = nil
        for _, n in ipairs(c.names) do
            if n:sub(1, #prefix) == prefix and not seen[n] then
                shown = shown or n
                seen[n] = true
            end
        end
        if shown then
            rows[#rows + 1] = { ":" .. shown, c.desc or "", complete = ":" .. shown .. " " }
            if #rows >= limit then break end
        end
    end
    table.sort(rows, function(a, b) return a[1] < b[1] end)
    return rows
end

local function setting_rows(cmd_prefix, prefix, limit)
    if not (settings and settings.get_settings) then return {} end
    local ok, all = pcall(settings.get_settings)
    if not ok or type(all) ~= "table" then return {} end
    local names = {}
    for name, meta in pairs(all) do
        if type(name) == "string" and name:sub(1, #prefix) == prefix then
            names[#names + 1] = { name = name, meta = meta }
        end
    end
    table.sort(names, function(a, b) return a.name < b.name end)
    local rows = {}
    for i, e in ipairs(names) do
        if i > limit then break end
        local desc = type(e.meta) == "table" and (e.meta.desc or "") or ""
        rows[#rows + 1] = { e.name, desc, complete = cmd_prefix .. e.name .. " " }
    end
    return rows
end

local function uri_rows(cmd_prefix, term, limit)
    local out = {}
    local hist = db_rows("history", "history", term, limit)
    if #hist > 0 then
        out[#out + 1] = { "History", "", title = true, selectable = false }
        for _, r in ipairs(hist) do
            out[#out + 1] = { r.uri or "", r.title or "", complete = cmd_prefix .. (r.uri or "") }
        end
    end
    local bm = db_rows("bookmarks", "bookmarks", term, limit)
    if #bm > 0 then
        out[#out + 1] = { "Bookmarks", "", title = true, selectable = false }
        for _, r in ipairs(bm) do
            out[#out + 1] = { r.uri or "", r.title or "", complete = cmd_prefix .. (r.uri or "") }
        end
    end
    return out
end

local function find_command(name)
    for _, c in ipairs(modes.get_cmds("command")) do
        for _, n in ipairs(c.names) do
            if n == name then return c end
        end
    end
    return nil
end

--- 根据输入栏文本生成候选行
function _M.candidates(text)
    text = tostring(text or "")
    local body = text:gsub("^:", "")
    local limit = max_items()
    local cmd, sep, arg = body:match("^(%S*)(%s*)(.*)$")
    if sep == "" then
        local rows = command_rows(cmd, limit)
        if #rows > 0 then table.insert(rows, 1, { "Commands", "", title = true, selectable = false }) end
        return rows
    end
    local c = find_command(cmd)
    local fmt = c and c.opts and c.opts.format or nil
    local prefix = ":" .. cmd .. " "
    if fmt and fmt:find("{uri}", 1, true) then
        return uri_rows(prefix, arg, limit)
    elseif fmt and fmt:find("{setting}", 1, true) then
        local dom = fmt:find("{domain}", 1, true) and arg:match("^(%S+)%s+") or nil
        if dom then
            return setting_rows(prefix .. dom .. " ", arg:match("^%S+%s+(.*)$") or "", limit)
        elseif not fmt:find("{domain}", 1, true) then
            return setting_rows(prefix, arg, limit)
        end
    end
    return {}
end

-- ---------------------------------------------------------------------------
-- 模式
-- ---------------------------------------------------------------------------
local function st(w)
    w.completion_state = w.completion_state or {}
    return w.completion_state
end

function _M.update_completions(w, text)
    local s = st(w)
    text = text or w.ibar.input.text or ""
    s.typed = text
    local rows = _M.candidates(text)
    s.rows = rows
    if not w.menu then return end
    if #rows == 0 then
        w.menu:hide()
        return
    end
    w.menu:build(rows)
    w.menu:show()
end

local function apply_selection(w)
    local s = st(w)
    if not w.menu then return end
    local row = w.menu:get()
    if not row or row.title then return end
    local text = row.complete or row[1]
    s.updating = true
    w:set_input(text)
    s.updating = false
end

function _M.exit_completion(w)
    local s = st(w)
    local text = w.ibar.input.text or ""
    w:set_mode("command")
    w:set_input(text ~= "" and text or ":")
    s.rows = nil
end

modes.new_mode("completion", "Pick a completion for the command being typed.", {
    has_input = true,
    enter = function(w, text)
        local s = st(w)
        text = text or s.typed or ":"
        if text == "" then text = ":" end
        s.updating = true
        w:set_ibar_theme("completion")
        w:set_input(text)
        s.updating = false
        _M.update_completions(w, text)
        if w.menu and s.rows and #s.rows > 0 then
            -- 直接选中第一条可选行并回填
            local row = w.menu:get()
            if row and row.title then w.menu:move_down() end
            apply_selection(w)
        end
    end,
    changed = function(w, text)
        local s = st(w)
        if s.updating then return end
        if text:sub(1, 1) ~= ":" then
            w:set_mode()
            return
        end
        _M.update_completions(w, text)
    end,
    activate = function(w, text)
        w:set_mode()
        w:run_cmd(text)
    end,
    leave = function(w)
        w:hide_menu()
    end,
})

local function cycle(dir)
    return function(w)
        if not w.menu then return end
        if dir > 0 then w.menu:move_down() else w.menu:move_up() end
        apply_selection(w)
    end
end

modes.add_binds("completion", {
    { "<Tab>",       "Next completion.",            cycle(1) },
    { "<Down>",      "Next completion.",            cycle(1) },
    { "<Control-j>", "Next completion.",            cycle(1) },
    { "<Shift-Tab>", "Previous completion.",        cycle(-1) },
    { "<Up>",        "Previous completion.",        cycle(-1) },
    { "<Control-k>", "Previous completion.",        cycle(-1) },
    { "<Escape>",    "Back to the command line.",   function(w) _M.exit_completion(w) end },
    { "<Control-[>", "Back to the command line.",   function(w) _M.exit_completion(w) end },
})

modes.add_binds("command", {
    { "<Tab>", "Show completions for the command being typed.", function(w)
        w:set_mode("completion", w.ibar.input.text or ":")
    end },
})

return _M
