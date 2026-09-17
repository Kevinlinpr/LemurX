-- sqlite3 类（对应 luakit clib/sqlite3.c）
--   local db = sqlite3{ filename = path }
--   db:exec(sql [, bindings]) -> rows|nil     多语句；bindings {1=..., ":name"=...}
--   db:compile(sql) -> statement ；stmt:exec([bindings])
--   db:changes() ；db:close() ；db.filename 只读
-- 每个 sqlite3{} 一个独立连接（history/bookmarks/quickmarks/adblock 各开各的）。

local object = __lk.object
local N = __luakit

local sqlite3, statement

local function checkopen(obj)
    local p = object.priv(obj)
    if not p.id then error("sqlite3: database closed", 3) end
    return p
end

statement = object.class("sqlite3::statement", {
    methods = {
        exec = function(obj, bindings)
            local p = object.priv(obj)
            if not p.id then error("sqlite3: statement finalized", 2) end
            if bindings ~= nil and type(bindings) ~= "table" then
                error("sqlite3: bindings must be a table", 2)
            end
            return N.sqlite_stmt_exec(p.id, bindings)
        end,
    },
    gc = function(obj)
        local p = object.priv(obj)
        if p.id then
            N.sqlite_stmt_free(p.id)
            p.id = nil
        end
    end,
})

sqlite3 = object.class("sqlite3", {
    props = {
        filename = { get = function(obj) return object.priv(obj).filename end },
    },
    methods = {
        exec = function(obj, sql, bindings)
            local p = checkopen(obj)
            if type(sql) ~= "string" then error("sqlite3:exec expects sql string", 2) end
            if bindings ~= nil and type(bindings) ~= "table" then
                error("sqlite3: bindings must be a table", 2)
            end
            return N.sqlite_exec(p.id, sql, bindings)
        end,
        compile = function(obj, sql)
            local p = checkopen(obj)
            if type(sql) ~= "string" then error("sqlite3:compile expects sql string", 2) end
            local sid = N.sqlite_prepare(p.id, sql)
            local st = object.new(statement, { id = sid, db = obj })
            -- 语句表引用数据库，保证 db 不先于 stmt 被 gc
            p.statements[#p.statements + 1] = st
            return st
        end,
        changes = function(obj)
            local p = checkopen(obj)
            return N.sqlite_changes(p.id)
        end,
        close = function(obj)
            local p = object.priv(obj)
            if p.id then
                N.sqlite_close(p.id)
                p.id = nil
            end
        end,
    },
    new = function(props)
        if type(props) ~= "table" or type(props.filename) ~= "string" then
            error("sqlite3{} requires a 'filename' string", 3)
        end
        -- 相对路径落到 data_dir，避免落到进程 cwd（/）
        local filename = props.filename
        if filename ~= ":memory:" and filename:sub(1, 1) ~= "/" then
            filename = (__lk.env.data_dir or __lk.install_dir .. "/data") .. "/" .. filename
        end
        local id, err = N.sqlite_open(filename)
        if not id then
            error(("sqlite3: unable to open %q: %s"):format(filename, tostring(err)), 3)
        end
        return object.new(sqlite3, { id = id, filename = filename, statements = {} })
    end,
    gc = function(obj)
        local p = object.priv(obj)
        if p.id then
            N.sqlite_close(p.id)
            p.id = nil
        end
    end,
})

_G.sqlite3 = sqlite3
return sqlite3
