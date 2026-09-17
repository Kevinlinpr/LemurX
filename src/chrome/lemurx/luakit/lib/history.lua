-- LemurX · luakit-compatible library · history
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "history" module API. No luakit code is used.
--
-- 浏览历史，落在 luakit.data_dir/history.db（sqlite3）：
--   history(id INTEGER PRIMARY KEY, uri TEXT, title TEXT, visits INTEGER, last_visit INTEGER)
-- 页面 load-status 到 "committed" 时记一条（跳过 luakit:// about: data: view-source: 以及
-- 隐私标签页 / history.frozen 里的 view），标题在 property::title 时补上。
--   history.add(uri, title, update_visits)   history.remove(id_or_uri)
--   history.search{ query=, limit=, offset= } history.count()  history.clear()
--   history.db  （其他模块可直接 SELECT）        history.frozen[view] = true 暂停记录
--   history.add_signal("add", fn(uri, title) -> false 跳过)
-- 设置：history.enabled（boolean, true）

local lousy = require("lousy")
local settings = require("settings")

local M = {}
lousy.signal.setup(M, true)

settings.register_settings({
    ["history.enabled"] = {
        type = "boolean", default = true,
        desc = "Record visited pages into the history database.",
    },
})

M.db_path = luakit.data_dir .. "/history.db"
M.db = nil
M.frozen = setmetatable({}, { __mode = "k" })

local SKIP_SCHEMES = { luakit = true, about = true, data = true, ["view-source"] = true, blob = true, javascript = true }

local function scheme_of(uri)
    return (uri or ""):match("^(%a[%w+.-]*):")
end

function M.init()
    if M.db then return M.db end
    pcall(lfs.mkdir, luakit.data_dir)
    local ok, db = pcall(sqlite3, { filename = M.db_path })
    if not ok then
        msg.warn("history: cannot open %s: %s", M.db_path, tostring(db))
        return nil
    end
    db:exec([[
        CREATE TABLE IF NOT EXISTS history (
            id         INTEGER PRIMARY KEY,
            uri        TEXT NOT NULL,
            title      TEXT,
            visits     INTEGER NOT NULL DEFAULT 0,
            last_visit INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS history_uri ON history(uri);
        CREATE INDEX IF NOT EXISTS history_last_visit ON history(last_visit);
    ]])
    M.db = db
    return db
end

local function need_db()
    return M.db or M.init()
end

-- 记录一次访问；返回行 id
function M.add(uri, title, update_visits)
    if type(uri) ~= "string" or uri == "" then return nil end
    if update_visits == nil then update_visits = true end
    if M.emit_signal("add", uri, title) == false then return nil end
    local db = need_db()
    if not db then return nil end
    local now = os.time()
    local rows = db:exec("SELECT id, title FROM history WHERE uri = :u LIMIT 1", { [":u"] = uri })
    local row = rows and rows[1]
    if row then
        local new_title = (title and title ~= "") and title or row.title
        if update_visits then
            db:exec("UPDATE history SET title = :t, visits = visits + 1, last_visit = :v WHERE id = :id",
                { [":t"] = new_title, [":v"] = now, [":id"] = row.id })
        else
            db:exec("UPDATE history SET title = :t WHERE id = :id", { [":t"] = new_title, [":id"] = row.id })
        end
        return row.id
    end
    db:exec("INSERT INTO history (uri, title, visits, last_visit) VALUES (:u, :t, :n, :v)",
        { [":u"] = uri, [":t"] = title or "", [":n"] = update_visits and 1 or 0, [":v"] = now })
    local r = db:exec("SELECT last_insert_rowid() AS id")
    return r and r[1] and r[1].id or nil
end

-- 删除：按 id（number）或 uri（string）
function M.remove(what)
    local db = need_db()
    if not db then return end
    if type(what) == "number" then
        db:exec("DELETE FROM history WHERE id = :id", { [":id"] = what })
    elseif type(what) == "string" then
        db:exec("DELETE FROM history WHERE uri = :u", { [":u"] = what })
    end
    M.emit_signal("remove", what)
end

function M.clear()
    local db = need_db()
    if not db then return end
    db:exec("DELETE FROM history")
    M.emit_signal("clear")
end

function M.count()
    local db = need_db()
    if not db then return 0 end
    local r = db:exec("SELECT COUNT(*) AS n FROM history")
    return r and r[1] and tonumber(r[1].n) or 0
end

-- 查询：opts { query = "词 词", limit = 100, offset = 0, order = "last_visit"|"visits" }
function M.search(opts)
    opts = opts or {}
    local db = need_db()
    if not db then return {} end
    local where, binds = {}, {}
    local n = 0
    for word in (opts.query or ""):gmatch("%S+") do
        n = n + 1
        local k = ":w" .. n
        where[#where + 1] = ("(uri LIKE %s ESCAPE '\\' OR title LIKE %s ESCAPE '\\')"):format(k, k)
        binds[k] = "%" .. word:gsub("[%%_\\]", "\\%0") .. "%"
    end
    local sql = "SELECT id, uri, title, visits, last_visit FROM history"
    if #where > 0 then sql = sql .. " WHERE " .. table.concat(where, " AND ") end
    local order = opts.order == "visits" and "visits DESC, last_visit DESC" or "last_visit DESC"
    sql = sql .. " ORDER BY " .. order
    sql = sql .. (" LIMIT %d OFFSET %d"):format(math.floor(tonumber(opts.limit) or 100), math.floor(tonumber(opts.offset) or 0))
    return db:exec(sql, next(binds) and binds or nil) or {}
end

-- ====================================================================
-- 自动记录
-- ====================================================================
local function should_record(view, uri)
    if not settings.get_setting("history.enabled") then return false end
    if M.frozen[view] then return false end
    local private = false
    pcall(function() private = view.private end)
    if private then return false end
    local scheme = scheme_of(uri)
    if not scheme or SKIP_SCHEMES[scheme] then return false end
    return true
end

local last_uri = setmetatable({}, { __mode = "k" })

local hooked = setmetatable({}, { __mode = "k" })
local function hook(view)
    if hooked[view] then return end
    hooked[view] = true
    view:add_signal("load-status", function(v, status, uri)
        if status ~= "committed" then return end
        uri = uri or v.uri
        if not should_record(v, uri) then last_uri[v] = nil return end
        last_uri[v] = uri
        local ok, err = pcall(M.add, uri, v.title, true)
        if not ok then msg.warn("history: add failed: %s", tostring(err)) end
    end)
    view:add_signal("property::title", function(v)
        local uri = last_uri[v]
        if not uri or uri ~= v.uri then return end
        local title = v.title
        if type(title) ~= "string" or title == "" then return end
        pcall(M.add, uri, title, false)
    end)
end

M.hook_view = hook

do
    local ok, webview = pcall(require, "webview")
    if ok and type(webview) == "table" and webview.add_signal then
        webview.add_signal("init", function(view) hook(view) end)
    end
    widget.add_signal("create", function(w) if w.type == "webview" then hook(w) end end)
    if __lk and __lk.webviews then
        for _, v in pairs(__lk.webviews) do if v.is_alive then hook(v) end end
    end
end

return M
