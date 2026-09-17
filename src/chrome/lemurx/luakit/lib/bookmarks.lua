-- LemurX · luakit-compatible library · bookmarks
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "bookmarks" module API. No luakit code is used.
--
-- 书签存储，luakit.data_dir/bookmarks.db（sqlite3）：
--   bookmarks(id INTEGER PRIMARY KEY, uri TEXT, title TEXT, desc TEXT, tags TEXT,
--             created INTEGER, modified INTEGER)
-- tags 以空格分隔存成一个字符串。
--   bookmarks.add(uri, { title=, desc=, tags= (string|{string}), created= }) -> id
--   bookmarks.get(id) -> row     bookmarks.remove(id)     bookmarks.update(id, fields)
--   bookmarks.tag(id, tags, replace)   bookmarks.untag(id, name)
--   bookmarks.find{ query=, tag=, limit=, offset= }  bookmarks.tags() -> {{name=, count=}}
--   bookmarks.db / db_path / init()
-- 信号：add(id, uri, opts) remove(id) update(id)

local lousy = require("lousy")

local M = {}
lousy.signal.setup(M, true)

M.db_path = luakit.data_dir .. "/bookmarks.db"
M.db = nil

function M.init()
    if M.db then return M.db end
    pcall(lfs.mkdir, luakit.data_dir)
    local ok, db = pcall(sqlite3, { filename = M.db_path })
    if not ok then
        msg.warn("bookmarks: cannot open %s: %s", M.db_path, tostring(db))
        return nil
    end
    db:exec([[
        CREATE TABLE IF NOT EXISTS bookmarks (
            id       INTEGER PRIMARY KEY,
            uri      TEXT NOT NULL,
            title    TEXT,
            desc     TEXT,
            tags     TEXT,
            created  INTEGER,
            modified INTEGER
        );
        CREATE INDEX IF NOT EXISTS bookmarks_uri ON bookmarks(uri);
    ]])
    M.db = db
    return db
end

local function need_db()
    local db = M.db or M.init()
    if not db then error("bookmarks: database unavailable", 3) end
    return db
end

-- 标签规范化：接受 "a b, c" 或 {"a","b"}；去重、排序、返回数组
function M.parse_tags(tags)
    local set, out = {}, {}
    local function put(t)
        t = tostring(t):match("^%s*(.-)%s*$")
        if t ~= "" and not set[t] then set[t] = true; out[#out + 1] = t end
    end
    if type(tags) == "table" then
        for _, t in ipairs(tags) do put(t) end
    elseif type(tags) == "string" then
        for t in tags:gmatch("[^%s,]+") do put(t) end
    end
    table.sort(out)
    return out
end

local function tags_string(tags)
    return table.concat(M.parse_tags(tags), " ")
end

local function row_out(row)
    if not row then return nil end
    row.tags = row.tags or ""
    row.tag_list = M.parse_tags(row.tags)
    return row
end

function M.get(id)
    local db = need_db()
    local rows = db:exec("SELECT * FROM bookmarks WHERE id = :id", { [":id"] = tonumber(id) })
    return row_out(rows and rows[1])
end

function M.get_by_uri(uri)
    local db = need_db()
    local rows = db:exec("SELECT * FROM bookmarks WHERE uri = :u ORDER BY id LIMIT 1", { [":u"] = uri })
    return row_out(rows and rows[1])
end

function M.add(uri, opts)
    if type(uri) ~= "string" or uri == "" then error("bookmarks.add: uri required", 2) end
    opts = opts or {}
    local db = need_db()
    local now = os.time()
    db:exec("INSERT INTO bookmarks (uri, title, desc, tags, created, modified) VALUES (:u, :t, :d, :g, :c, :m)", {
        [":u"] = uri,
        [":t"] = opts.title or "",
        [":d"] = opts.desc or "",
        [":g"] = tags_string(opts.tags),
        [":c"] = opts.created or now,
        [":m"] = now,
    })
    local r = db:exec("SELECT last_insert_rowid() AS id")
    local id = r and r[1] and r[1].id
    M.emit_signal("add", id, uri, opts)
    return id
end

function M.remove(id)
    local db = need_db()
    db:exec("DELETE FROM bookmarks WHERE id = :id", { [":id"] = tonumber(id) })
    M.emit_signal("remove", tonumber(id))
end

-- 更新若干字段：{ uri=, title=, desc=, tags= }
function M.update(id, fields)
    local db = need_db()
    id = tonumber(id)
    local row = M.get(id)
    if not row then return false end
    fields = fields or {}
    db:exec("UPDATE bookmarks SET uri = :u, title = :t, desc = :d, tags = :g, modified = :m WHERE id = :id", {
        [":u"] = fields.uri or row.uri,
        [":t"] = fields.title ~= nil and fields.title or row.title,
        [":d"] = fields.desc ~= nil and fields.desc or row.desc,
        [":g"] = fields.tags ~= nil and tags_string(fields.tags) or row.tags,
        [":m"] = os.time(),
        [":id"] = id,
    })
    M.emit_signal("update", id)
    return true
end

function M.tag(id, new_tags, replace)
    local row = M.get(id)
    if not row then return false end
    local list = replace and {} or row.tag_list
    for _, t in ipairs(M.parse_tags(new_tags)) do list[#list + 1] = t end
    return M.update(id, { tags = list })
end

function M.untag(id, name)
    local row = M.get(id)
    if not row then return false end
    local kept = {}
    for _, t in ipairs(row.tag_list) do if t ~= name then kept[#kept + 1] = t end end
    return M.update(id, { tags = kept })
end

-- 查询：{ query = "词", tag = "标签", limit=, offset=, order = "created"|"title"|"modified" }
function M.find(opts)
    opts = opts or {}
    local db = need_db()
    local where, binds, n = {}, {}, 0
    for word in (opts.query or ""):gmatch("%S+") do
        n = n + 1
        local k = ":w" .. n
        where[#where + 1] = ("(uri LIKE %s ESCAPE '\\' OR title LIKE %s ESCAPE '\\' OR desc LIKE %s ESCAPE '\\' OR tags LIKE %s ESCAPE '\\')"):format(k, k, k, k)
        binds[k] = "%" .. word:gsub("[%%_\\]", "\\%0") .. "%"
    end
    if opts.tag and opts.tag ~= "" then
        where[#where + 1] = "(' ' || tags || ' ') LIKE :tag ESCAPE '\\'"
        binds[":tag"] = "% " .. opts.tag:gsub("[%%_\\]", "\\%0") .. " %"
    end
    local sql = "SELECT * FROM bookmarks"
    if #where > 0 then sql = sql .. " WHERE " .. table.concat(where, " AND ") end
    local order = ({ title = "title COLLATE NOCASE ASC", modified = "modified DESC" })[opts.order] or "created DESC"
    sql = sql .. " ORDER BY " .. order
    if opts.limit then
        sql = sql .. (" LIMIT %d OFFSET %d"):format(math.floor(tonumber(opts.limit) or 100), math.floor(tonumber(opts.offset) or 0))
    end
    local rows = db:exec(sql, next(binds) and binds or nil) or {}
    for _, r in ipairs(rows) do row_out(r) end
    return rows
end

function M.count()
    local db = need_db()
    local r = db:exec("SELECT COUNT(*) AS n FROM bookmarks")
    return r and r[1] and tonumber(r[1].n) or 0
end

-- 所有标签及使用次数
function M.tags()
    local db = need_db()
    local rows = db:exec("SELECT tags FROM bookmarks WHERE tags IS NOT NULL AND tags != ''") or {}
    local counts = {}
    for _, r in ipairs(rows) do
        for _, t in ipairs(M.parse_tags(r.tags)) do counts[t] = (counts[t] or 0) + 1 end
    end
    local out = {}
    for name, count in pairs(counts) do out[#out + 1] = { name = name, count = count } end
    table.sort(out, function(a, b) return a.name < b.name end)
    return out
end

return M
