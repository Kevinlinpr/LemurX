-- LemurX · luakit-compatible library · noscript
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "noscript" module API. No luakit code is used.
--
-- 按域名开关 JavaScript（插件开关在 Android 上只是记录，没有实际效果）。
-- 状态存放在 luakit.data_dir/noscript.db（sqlite），内存里有一份缓存；
-- 在 navigation-request / load-status provisional 时把 view.enable_javascript 设成
-- 该域（或其父域）的记录值，没有记录则用 noscript.enable_scripts 设置的默认值。
-- 若宿主提供 lemurx.perm.set(origin, "javascript", allow)，也一并调用。
-- 绑定（normal）：,ts 切换脚本  ,tp 切换插件  ,tr 清除当前域记录
-- 设置：noscript.enable_scripts noscript.enable_plugins
-- 公开接口：noscript.lookup(domain) noscript.set_state(domain, {enable_scripts=,enable_plugins=})
--   noscript.toggle_scripts(w) noscript.toggle_plugins(w) noscript.reset(w) noscript.scripts_enabled_for(uri)
--   noscript.widget(w) noscript.domain_of(uri)
-- 模块信号：state-changed(domain, state)

local lousy = require("lousy")
local settings = require("settings")
local modes = require("modes")
local webview = require("webview")

local _M = {}
lousy.signal.setup(_M, true)

settings.register_settings({
    ["noscript.enable_scripts"] = {
        type = "boolean", default = true,
        desc = "Default JavaScript state for domains without an explicit noscript entry.",
    },
    ["noscript.enable_plugins"] = {
        type = "boolean", default = true,
        desc = "Default plugin state (recorded only; Android has no NPAPI plugins).",
    },
})

-- ---------------------------------------------------------------------------
-- 存储
-- ---------------------------------------------------------------------------
local cache = {}     -- domain -> { enable_scripts = bool|nil, enable_plugins = bool|nil }
local db

local function open_db()
    if db then return db end
    local ok, d = pcall(sqlite3, { filename = luakit.data_dir .. "/noscript.db" })
    if not ok then
        msg.warn("noscript: cannot open database: %s", tostring(d))
        return nil
    end
    db = d
    pcall(db.exec, db, [[
        CREATE TABLE IF NOT EXISTS by_domain (
            domain TEXT PRIMARY KEY,
            enable_scripts INTEGER,
            enable_plugins INTEGER
        );
    ]])
    local rows = nil
    pcall(function() rows = db:exec("SELECT domain, enable_scripts, enable_plugins FROM by_domain") end)
    for _, row in ipairs(type(rows) == "table" and rows or {}) do
        if type(row) == "table" and type(row.domain) == "string" then
            -- 列值：1 = 开，0 = 关，其它（NULL / -1）= 未设置
            local function tri(v)
                local n = tonumber(v)
                if n == 1 then return true elseif n == 0 then return false end
                return nil
            end
            cache[row.domain] = {
                enable_scripts = tri(row.enable_scripts),
                enable_plugins = tri(row.enable_plugins),
            }
        end
    end
    return db
end

-- 绑定参数里不能有 nil（会截断参数表），未设置用 -1 表示
local function to_int(v)
    if v == nil then return -1 end
    return v and 1 or 0
end

local function persist(domain, state)
    local d = open_db()
    if not d then return end
    if not state or (state.enable_scripts == nil and state.enable_plugins == nil) then
        pcall(d.exec, d, "DELETE FROM by_domain WHERE domain = ?", { domain })
    else
        pcall(d.exec, d, "INSERT OR REPLACE INTO by_domain (domain, enable_scripts, enable_plugins) VALUES (?, ?, ?)",
            { domain, to_int(state.enable_scripts), to_int(state.enable_plugins) })
    end
end

-- ---------------------------------------------------------------------------
-- 查询
-- ---------------------------------------------------------------------------
function _M.domain_of(uri)
    local host = tostring(uri or ""):match("^%a[%w+.-]*://([^/?#:@]+)")
    if not host then return nil end
    host = host:gsub("^[^@]*@", ""):lower()
    if host:sub(1, 4) == "www." then host = host:sub(5) end
    return host
end

-- 精确域 → 父域 逐级查找
function _M.lookup(domain)
    open_db()
    if not domain then return nil end
    local d = domain
    while d and d ~= "" do
        local st = cache[d]
        if st then return st, d end
        d = d:match("^[^%.]+%.(.+)$")
    end
    return nil
end

local function pick(st, key, setting)
    if st and st[key] ~= nil then return st[key] end
    return settings.get_setting(setting) ~= false
end

function _M.get_state(domain)
    local st = _M.lookup(domain)
    return {
        enable_scripts = pick(st, "enable_scripts", "noscript.enable_scripts"),
        enable_plugins = pick(st, "enable_plugins", "noscript.enable_plugins"),
        explicit = st ~= nil,
    }
end

function _M.set_state(domain, state)
    open_db()
    if type(domain) ~= "string" or domain == "" then return end
    if state == nil then
        cache[domain] = nil
    else
        local cur = cache[domain] or {}
        if state.enable_scripts ~= nil then cur.enable_scripts = state.enable_scripts and true or false end
        if state.enable_plugins ~= nil then cur.enable_plugins = state.enable_plugins and true or false end
        cache[domain] = cur
    end
    persist(domain, cache[domain])
    _M.emit_signal("state-changed", domain, cache[domain])
end

function _M.scripts_enabled_for(uri)
    return _M.get_state(_M.domain_of(uri)).enable_scripts
end

-- ---------------------------------------------------------------------------
-- 应用到 webview
-- ---------------------------------------------------------------------------
local function apply(view, uri)
    local domain = _M.domain_of(uri or view.uri)
    if not domain then return end
    local st = _M.get_state(domain)
    local ok, cur = pcall(function() return view.enable_javascript end)
    if not ok or cur ~= st.enable_scripts then
        pcall(function() view.enable_javascript = st.enable_scripts end)
    end
    if lemurx and lemurx.perm and lemurx.perm.set then
        local scheme = tostring(uri or view.uri or ""):match("^(%a[%w+.-]*)://") or "https"
        pcall(lemurx.perm.set, scheme .. "://" .. domain, "javascript", st.enable_scripts and "allow" or "block")
    end
    view.noscript_state = st
end
_M.apply = apply

webview.add_signal("init", function(view)
    view:add_signal("navigation-request", function(v, uri)
        apply(v, uri)
    end)
    view:add_signal("load-status", function(v, status, uri)
        if status == "provisional" then apply(v, uri) end
    end)
end)

-- ---------------------------------------------------------------------------
-- 切换
-- ---------------------------------------------------------------------------
local function current_domain(w)
    local view = w.view
    local domain = view and _M.domain_of(view.uri)
    if not domain then
        w:warning("noscript: no domain for the current page")
    end
    return domain
end

function _M.toggle_scripts(w)
    local domain = current_domain(w)
    if not domain then return end
    local st = _M.get_state(domain)
    _M.set_state(domain, { enable_scripts = not st.enable_scripts })
    w:notify(("JavaScript %s for %s (reload to apply)"):format(st.enable_scripts and "disabled" or "enabled", domain))
    if w.view then apply(w.view) end
end

function _M.toggle_plugins(w)
    local domain = current_domain(w)
    if not domain then return end
    local st = _M.get_state(domain)
    _M.set_state(domain, { enable_plugins = not st.enable_plugins })
    w:notify(("Plugins %s for %s (no effect on Android)"):format(st.enable_plugins and "disabled" or "enabled", domain))
end

function _M.reset(w)
    local domain = current_domain(w)
    if not domain then return end
    _M.set_state(domain, nil)
    w:notify("noscript: cleared rules for " .. domain)
    if w.view then apply(w.view) end
end

modes.add_binds("normal", {
    { ",ts", "Toggle JavaScript for the current domain.", function (w) _M.toggle_scripts(w) end },
    { ",tp", "Toggle plugins for the current domain.", function (w) _M.toggle_plugins(w) end },
    { ",tr", "Forget the noscript rules for the current domain.", function (w) _M.reset(w) end },
})

-- ---------------------------------------------------------------------------
-- 状态栏指示（lousy.widget 风格工厂）
-- ---------------------------------------------------------------------------
function _M.widget(w)
    local label = widget{ type = "label" }
    label.font = (pcall(lousy.theme.get) and lousy.theme.get().sbar_font) or nil
    local function update()
        local view = w.view
        local domain = view and _M.domain_of(view.uri)
        local st = _M.get_state(domain)
        label.text = st.enable_scripts and "JS" or "js̶"
        label.tooltip = st.enable_scripts and "JavaScript enabled" or "JavaScript disabled"
        pcall(function()
            local theme = lousy.theme.get()
            label.fg = st.enable_scripts and (theme.trust_fg or theme.sbar_fg) or (theme.notrust_fg or theme.sbar_fg)
        end)
    end
    _M.add_signal("state-changed", update)
    if w.add_signal then
        w:add_signal("switched-page", update)
    end
    if w.tabs and w.tabs.add_signal then
        w.tabs:add_signal("switch-page", update)
    end
    webview.add_signal("init", function(view)
        view:add_signal("property::uri", function() if w.view == view then update() end end)
    end)
    pcall(update)
    return label
end

-- ---------------------------------------------------------------------------
-- 模块属性：enable_scripts / enable_plugins → settings
-- ---------------------------------------------------------------------------
setmetatable(_M, {
    __index = function(_, k)
        if k == "enable_scripts" then return settings.get_setting("noscript.enable_scripts") ~= false end
        if k == "enable_plugins" then return settings.get_setting("noscript.enable_plugins") ~= false end
        return nil
    end,
    __newindex = function(t, k, v)
        if k == "enable_scripts" or k == "enable_plugins" then
            settings.set_setting("noscript." .. k, v and true or false)
        else
            rawset(t, k, v)
        end
    end,
})

return _M
