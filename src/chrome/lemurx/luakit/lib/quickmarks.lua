-- LemurX · luakit-compatible library · quickmarks
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "quickmarks" module API. No luakit code is used.
--
-- 快捷书签：一个字母/数字 token 对应一个或多个网址，存在 luakit.data_dir/quickmarks
-- （每行 "token<TAB>uri uri ..."）。
--   quickmarks.get(token[, load_file])  set(token, uris[, load_file, save_file])
--   del(token[, load_file, save_file])  delall([save_file])  get_tokens()  load([path])  save([path])
-- 按键（normal）：M<t> 记下当前页；go<t> / gn<t> / gw<t> 在当前标签 / 新标签 / 新窗口打开
-- 命令：:qmark <t> <uri...>  :qmarkedit/:qme <t>  :delqmarks [t...]  :qmarks（luakit://quickmarks/）

local modes = require("modes")

local M = {}

M.file = luakit.data_dir .. "/quickmarks"
M.chrome_page = "luakit://quickmarks/"

local marks = nil -- token -> { uri, ... }

local function valid_token(t)
    return type(t) == "string" and t:match("^[%w]$") ~= nil
end

local function to_list(uris)
    if type(uris) == "string" then
        local out = {}
        for u in uris:gmatch("%S+") do out[#out + 1] = u end
        return out
    elseif type(uris) == "table" then
        local out = {}
        for _, u in ipairs(uris) do if type(u) == "string" and u ~= "" then out[#out + 1] = u end end
        return out
    end
    return {}
end

function M.load(path)
    path = path or M.file
    marks = {}
    local f = io.open(path, "r")
    if not f then return marks end
    for line in f:lines() do
        local token, rest = line:match("^(%w)[\t ]+(.-)%s*$")
        if token and rest and rest ~= "" then marks[token] = to_list(rest) end
    end
    f:close()
    return marks
end

function M.save(path)
    path = path or M.file
    if not marks then M.load() end
    pcall(lfs.mkdir, luakit.data_dir)
    local f, err = io.open(path, "w")
    if not f then
        msg.warn("quickmarks: cannot write %s: %s", path, tostring(err))
        return false
    end
    for _, token in ipairs(M.get_tokens()) do
        f:write(token, "\t", table.concat(marks[token], " "), "\n")
    end
    f:close()
    return true
end

local function ensure(load_file)
    if load_file or not marks then M.load() end
end

function M.get(token, load_file)
    ensure(load_file)
    local list = marks[token]
    if not list then return nil end
    return { table.unpack(list) }
end

function M.get_tokens()
    ensure(false)
    local out = {}
    for t in pairs(marks) do out[#out + 1] = t end
    table.sort(out)
    return out
end

function M.set(token, uris, load_file, save_file)
    if not valid_token(token) then error("quickmarks: token must be a single letter or digit", 2) end
    ensure(load_file)
    local list = to_list(uris)
    if #list == 0 then error("quickmarks: at least one uri required", 2) end
    marks[token] = list
    if save_file ~= false then M.save() end
end

function M.del(token, load_file, save_file)
    ensure(load_file)
    marks[token] = nil
    if save_file ~= false then M.save() end
end

function M.delall(save_file)
    marks = {}
    if save_file ~= false then M.save() end
end

-- 打开 token 对应的地址：mode = "current" | "tab" | "window"
local function open(w, token, mode)
    local uris = M.get(token, true)
    if not uris then
        w:error(("No quickmark '%s'"):format(token))
        return
    end
    for i, uri in ipairs(uris) do
        if mode == "window" then
            w:new_window(uri)
        elseif mode == "tab" or i > 1 then
            w:new_tab(uri)
        else
            w:navigate(uri)
        end
    end
end

-- 缓冲区模式绑定的回调既可能收到 (w, buffer, opts) 也可能收到 (w, opts)，两种都接
local function buffer_of(a, b)
    if type(a) == "string" then return a end
    if type(a) == "table" and type(a.buffer) == "string" then return a.buffer end
    if type(b) == "table" and type(b.buffer) == "string" then return b.buffer end
    return ""
end
M.buffer_of = buffer_of

modes.add_binds("normal", {
    { "^g[onw]%w$", "Open quickmark: go<t> here, gn<t> in a new tab, gw<t> in a new window.",
        function(w, a, b)
            local buf = buffer_of(a, b)
            local kind, token = buf:match("^g([onw])(%w)$")
            if not kind then return false end
            open(w, token, ({ o = "current", n = "tab", w = "window" })[kind])
        end },
    { "^M%w$", "Quickmark the current page under the typed letter or digit.",
        function(w, a, b)
            local buf = buffer_of(a, b)
            local token = buf:match("^M(%w)$")
            local uri = w.view and w.view.uri
            if not token or not uri then return false end
            M.set(token, uri, true, true)
            w:notify(("Quickmark %s → %s"):format(token, uri))
        end },
})

modes.add_cmds({
    { ":qmark, :qma", "Set a quickmark: :qmark <token> <uri> [uri ...]",
        function(w, o)
            local arg = (o and o.arg) or ""
            local token, rest = arg:match("^%s*(%w)%s+(.+)$")
            if not token then
                w:error("Usage: :qmark <token> <uri> [uri ...]")
                return
            end
            M.set(token, rest, true, true)
            w:notify(("Quickmark %s set"):format(token))
        end },
    { ":qmarkedit, :qme", "Edit a quickmark in the command line.",
        function(w, o)
            local token = ((o and o.arg) or ""):match("^%s*(%w)")
            local uris = token and M.get(token, true)
            if not uris then
                w:error("Usage: :qmarkedit <token>")
                return
            end
            w:enter_cmd((":qmark %s %s"):format(token, table.concat(uris, " ")))
        end },
    { ":delqmarks, :delqm", "Delete quickmarks: :delqmarks <token> [token ...] (all when omitted).",
        function(w, o)
            local arg = (o and o.arg) or ""
            local any = false
            for token in arg:gmatch("%w") do
                M.del(token, false, false)
                any = true
            end
            if any then M.save() else M.delall(true) end
            w:notify(any and "Quickmarks deleted" or "All quickmarks deleted")
        end },
    { ":qmarks", "List all quickmarks.", function(w) w:new_tab(M.chrome_page) end },
})

-- luakit://quickmarks/ 列表页（可选，有 chrome 模块时才注册）
do
    local ok, chrome = pcall(require, "chrome")
    if ok and chrome and chrome.add then
        chrome.add("quickmarks", function()
            local rows = {}
            for _, token in ipairs(M.get_tokens()) do
                local links = {}
                for _, uri in ipairs(marks[token]) do
                    links[#links + 1] = ("<a href=\"%s\">%s</a>"):format(chrome.escape(uri), chrome.escape(uri))
                end
                rows[#rows + 1] = ("<div class=\"lx-row\"><kbd>%s</kbd><div class=\"lx-grow\">%s</div>"
                    .. "<button class=\"lx-small lx-danger\" onclick=\"qmDel('%s', this)\">&times;</button></div>")
                    :format(chrome.escape(token), table.concat(links, "<br>"), chrome.escape(token))
            end
            local body = "<div class=\"lx-card\">"
                .. (#rows > 0 and table.concat(rows) or "<div class=\"lx-empty\">No quickmarks. Press <kbd>M</kbd> then a letter on any page.</div>")
                .. "</div><p class=\"lx-muted\">Open with <kbd>go</kbd><i>x</i>, <kbd>gn</kbd><i>x</i> (new tab) or <kbd>gw</kbd><i>x</i> (new window).</p>"
            return chrome.render({
                title = "Quickmarks", heading = "Quickmarks", body = body,
                script = "function qmDel(t, b){ if (typeof quickmarks_del !== 'function') return; quickmarks_del(t).then(function(){ var r=b.closest('.lx-row'); if(r) r.remove(); }); }",
            })
        end, nil, {
            quickmarks_del = function(_, token) M.del(token, true, true) return true end,
            quickmarks_get = function(_, token) return M.get(token, true) end,
        })
    end
end

return M
