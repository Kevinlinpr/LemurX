-- LemurX · luakit-compatible library · userscripts
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "userscripts" module API. No luakit code is used.
--
-- Greasemonkey 风格的用户脚本：从 luakit.data_dir/scripts/*.user.js 读取，解析
-- // ==UserScript== 元数据（@name @description @version @include @exclude @match @run-at @noframes），
-- 在 load-status 为 first-visual（≈document-start）或 finished（document-end / document-idle）时
-- 用 view:eval_js 注入。注入前包一层 GM_* 兼容函数（GM_getValue/GM_setValue 用页面 localStorage，
-- GM_addStyle、GM_xmlhttpRequest(fetch)、GM_openInTab、GM_log、GM_info、GM.*）。
-- 命令：:userscripts(:uscripts) 列表菜单  :userscripts-reload(:uscripts-reload)
--       :userscriptinstall(:usi, :usinstall) 把当前页（*.user.js）保存为脚本
-- 模式：uscriptlist（<space>/<Return> 启停，e 编辑，d 删除）
-- 设置：userscripts.enabled
-- 公开接口：userscripts.save(file, js) del(file) dir scripts load() parse_header(text)
--   matches(script, uri) wrap(script) run(view, status) toggle(name)

local lousy = require("lousy")
local settings = require("settings")
local modes = require("modes")
local webview = require("webview")

local _M = {}
lousy.signal.setup(_M, true)

settings.register_settings({
    ["userscripts.enabled"] = {
        type = "boolean", default = true,
        desc = "Run user scripts from the scripts directory.",
    },
})

_M.dir = luakit.data_dir .. "/scripts"
_M.scripts = {}        -- name -> script
local order = {}

-- ---------------------------------------------------------------------------
-- 启用状态持久化
-- ---------------------------------------------------------------------------
local enabled_cache = {}
local db

local function open_db()
    if db then return db end
    local ok, d = pcall(sqlite3, { filename = luakit.data_dir .. "/userscripts.db" })
    if not ok then return nil end
    db = d
    pcall(db.exec, db, "CREATE TABLE IF NOT EXISTS enabled (name TEXT PRIMARY KEY, enabled INTEGER);")
    local rows
    pcall(function() rows = db:exec("SELECT name, enabled FROM enabled") end)
    for _, row in ipairs(type(rows) == "table" and rows or {}) do
        if type(row) == "table" and type(row.name) == "string" then
            enabled_cache[row.name] = tonumber(row.enabled) == 1
        end
    end
    return db
end

local function persist_enabled(name, on)
    enabled_cache[name] = on
    local d = open_db()
    if d then
        pcall(d.exec, d, "INSERT OR REPLACE INTO enabled (name, enabled) VALUES (?, ?)", { name, on and 1 or 0 })
    end
end

-- ---------------------------------------------------------------------------
-- 元数据
-- ---------------------------------------------------------------------------
local MULTI = { include = true, exclude = true, match = true, grant = true, require = true, resource = true }

function _M.parse_header(text)
    local meta = { include = {}, exclude = {}, match = {}, grant = {}, require = {}, resource = {} }
    local block = text:match("//%s*==UserScript==%s*\n(.-)//%s*==/UserScript==")
    if not block then return meta, false end
    for line in block:gmatch("[^\n]+") do
        local key, value = line:match("^%s*//%s*@([%w%-:]+)%s*(.-)%s*$")
        if key then
            key = key:lower()
            if MULTI[key] then
                if value ~= "" then table.insert(meta[key], value) end
            elseif key == "run-at" then
                meta.run_at = value
            elseif meta[key] == nil then
                meta[key] = value
            end
        end
    end
    return meta, true
end

local function escape_pat(s)
    return (s:gsub("[%^%$%(%)%%%.%[%]%+%-%?]", "%%%0"))
end

-- @include/@exclude 的 glob 或 /regex/
local function compile_include(str)
    local body = str:match("^/(.*)/$")
    if body then
        local ok, r = pcall(function() return regex{ pattern = body } end)
        if ok then return { re = r } end
        return nil
    end
    local pat = escape_pat(str):gsub("%*", ".*")
    return { lua = "^" .. pat .. "$" }
end

-- @match：<scheme>://<host><path>
local function compile_match(str)
    local scheme, host, path = str:match("^([%w%*]+)://([^/]*)(/.*)$")
    if not scheme then return nil end
    local sch = scheme == "*" and "https?" or escape_pat(scheme)
    local pth = escape_pat(path):gsub("%*", ".*")
    local out = {}
    if host == "*" then
        out[#out + 1] = "^" .. sch .. "://[^/]+" .. pth .. "$"
    elseif host:sub(1, 2) == "*." then
        local base = escape_pat(host:sub(3))
        out[#out + 1] = "^" .. sch .. "://" .. base .. pth .. "$"
        out[#out + 1] = "^" .. sch .. "://[^/]+%." .. base .. pth .. "$"
    else
        out[#out + 1] = "^" .. sch .. "://" .. escape_pat(host) .. pth .. "$"
    end
    return { alts = out }
end

local function test_pattern(p, uri)
    if not p then return false end
    if p.lua then return uri:find(p.lua) ~= nil end
    if p.re then
        local ok, m = pcall(p.re.match, p.re, uri)
        return ok and m and true or false
    end
    if p.alts then
        for _, a in ipairs(p.alts) do
            if uri:find(a) then return true end
        end
    end
    return false
end

function _M.matches(script, uri)
    uri = tostring(uri or "")
    for _, p in ipairs(script.excludes) do
        if test_pattern(p, uri) then return false end
    end
    if #script.includes == 0 and #script.matches == 0 then return true end
    for _, p in ipairs(script.includes) do
        if test_pattern(p, uri) then return true end
    end
    for _, p in ipairs(script.matches) do
        if test_pattern(p, uri) then return true end
    end
    return false
end

-- ---------------------------------------------------------------------------
-- 注入包装
-- ---------------------------------------------------------------------------
local function js_string(s)
    return '"' .. tostring(s):gsub("[%c\"\\]", function(c)
        return string.format("\\u%04x", c:byte())
    end) .. '"'
end

_M.gm_shim = [[
var unsafeWindow = window;
function __lk_key(k) { return "__lkgm:" + __lk_name + ":" + k; }
function GM_getValue(k, d) { try { var v = localStorage.getItem(__lk_key(k)); return v === null ? d : JSON.parse(v); } catch (e) { return d; } }
function GM_setValue(k, v) { try { localStorage.setItem(__lk_key(k), JSON.stringify(v)); } catch (e) {} }
function GM_deleteValue(k) { try { localStorage.removeItem(__lk_key(k)); } catch (e) {} }
function GM_listValues() { var out = [], p = __lk_key(""); try { for (var i = 0; i < localStorage.length; i++) { var key = localStorage.key(i); if (key.indexOf(p) === 0) out.push(key.slice(p.length)); } } catch (e) {} return out; }
function GM_addStyle(css) { var s = document.createElement("style"); s.textContent = css; (document.head || document.documentElement).appendChild(s); return s; }
function GM_log() { try { console.log.apply(console, arguments); } catch (e) {} }
function GM_openInTab(u) { return window.open(u, "_blank"); }
function GM_registerMenuCommand() {}
function GM_getResourceText() { return ""; }
function GM_getResourceURL() { return ""; }
function GM_setClipboard(t) { try { navigator.clipboard.writeText(String(t)); } catch (e) {} }
function GM_xmlhttpRequest(o) {
  o = o || {};
  var ctrl = new AbortController();
  fetch(o.url, { method: o.method || "GET", headers: o.headers || {}, body: o.data, signal: ctrl.signal, credentials: o.anonymous ? "omit" : "include" })
    .then(function (r) { return r.text().then(function (t) {
      var res = { status: r.status, statusText: r.statusText, responseText: t, response: t, finalUrl: r.url, responseHeaders: "" };
      r.headers.forEach(function (v, k) { res.responseHeaders += k + ": " + v + "\r\n"; });
      if (o.responseType === "json") { try { res.response = JSON.parse(t); } catch (e) {} }
      if (o.onload) o.onload(res);
    }); })
    .catch(function (e) { if (o.onerror) o.onerror({ error: String(e) }); });
  return { abort: function () { ctrl.abort(); } };
}
var GM_info = { script: { name: __lk_name, version: __lk_version, description: __lk_desc, namespace: __lk_ns }, scriptHandler: "LemurX luakit", version: "1" };
var GM = {
  info: GM_info,
  getValue: function (k, d) { return Promise.resolve(GM_getValue(k, d)); },
  setValue: function (k, v) { GM_setValue(k, v); return Promise.resolve(); },
  deleteValue: function (k) { GM_deleteValue(k); return Promise.resolve(); },
  listValues: function () { return Promise.resolve(GM_listValues()); },
  addStyle: GM_addStyle, openInTab: GM_openInTab, xmlHttpRequest: GM_xmlhttpRequest,
  registerMenuCommand: GM_registerMenuCommand, setClipboard: GM_setClipboard, log: GM_log
};
]]

function _M.wrap(script)
    return table.concat({
        "(function () {\n",
        "var __lk_name = ", js_string(script.name), ";\n",
        "var __lk_version = ", js_string(script.meta.version or ""), ";\n",
        "var __lk_desc = ", js_string(script.meta.description or ""), ";\n",
        "var __lk_ns = ", js_string(script.meta.namespace or ""), ";\n",
        _M.gm_shim,
        "\n", script.source, "\n})();\n",
    })
end

-- ---------------------------------------------------------------------------
-- 加载 / 保存
-- ---------------------------------------------------------------------------
local function read_file(path)
    local f = io.open(path, "rb")
    if not f then return nil end
    local s = f:read("a")
    f:close()
    return s
end

local function load_script(path)
    local source = read_file(path)
    if not source then return nil end
    local meta, has_header = _M.parse_header(source)
    local file = path:match("([^/]+)$") or path
    local name = meta.name or file:gsub("%.user%.js$", "")
    local script = {
        name = name, file = file, path = path, meta = meta, source = source, has_header = has_header,
        includes = {}, excludes = {}, matches = {},
        run_at = meta.run_at or "document-end",
        noframes = meta.noframes ~= nil,
    }
    for _, s in ipairs(meta.include) do script.includes[#script.includes + 1] = compile_include(s) end
    for _, s in ipairs(meta.exclude) do script.excludes[#script.excludes + 1] = compile_include(s) end
    for _, s in ipairs(meta.match) do script.matches[#script.matches + 1] = compile_match(s) end
    script.js = _M.wrap(script)
    open_db()
    if enabled_cache[name] ~= nil then script.enabled = enabled_cache[name] else script.enabled = true end
    return script
end

function _M.load()
    _M.scripts = {}
    order = {}
    pcall(lfs.mkdir, _M.dir)
    local ok, iter, st = pcall(lfs.dir, _M.dir)
    if not ok or not iter then return _M.scripts end
    local names = {}
    for name in iter, st do
        if name:match("%.user%.js$") then names[#names + 1] = name end
    end
    table.sort(names)
    for _, file in ipairs(names) do
        local s = load_script(_M.dir .. "/" .. file)
        if s then
            _M.scripts[s.name] = s
            order[#order + 1] = s.name
        end
    end
    _M.emit_signal("loaded")
    return _M.scripts
end

function _M.save(file, js)
    if type(file) ~= "string" or type(js) ~= "string" then error("userscripts.save(file, js)", 2) end
    if not file:find("/", 1, true) then file = _M.dir .. "/" .. file end
    if not file:match("%.user%.js$") then file = file .. ".user.js" end
    pcall(lfs.mkdir, _M.dir)
    local f, err = io.open(file, "wb")
    if not f then return nil, err end
    f:write(js)
    f:close()
    _M.load()
    return file
end

function _M.del(file)
    if type(file) ~= "string" then error("userscripts.del(file)", 2) end
    if not file:find("/", 1, true) then file = _M.dir .. "/" .. file end
    local ok, err = os.remove(file)
    _M.load()
    return ok, err
end

function _M.toggle(name)
    local s = _M.scripts[name]
    if not s then return nil end
    s.enabled = not s.enabled
    persist_enabled(name, s.enabled)
    return s.enabled
end

function _M.list()
    local out = {}
    for _, name in ipairs(order) do out[#out + 1] = _M.scripts[name] end
    return out
end

-- ---------------------------------------------------------------------------
-- 注入
-- ---------------------------------------------------------------------------
local STAGE = {
    ["first-visual"] = { ["document-start"] = true },
    ["finished"] = { ["document-end"] = true, ["document-idle"] = true, ["document-body"] = true },
}

function _M.run(view, status, uri)
    if settings.get_setting("userscripts.enabled") == false then return 0 end
    local stage = STAGE[status]
    if not stage then return 0 end
    uri = uri or view.uri
    if type(uri) ~= "string" or not uri:match("^https?://") and not uri:match("^file://") then return 0 end
    local n = 0
    for _, name in ipairs(order) do
        local s = _M.scripts[name]
        local run_at = s.run_at
        if not STAGE["first-visual"][run_at] and not STAGE["finished"][run_at] then run_at = "document-end" end
        if s.enabled and stage[run_at] and _M.matches(s, uri) then
            n = n + 1
            pcall(view.eval_js, view, s.js, { source = s.file })
        end
    end
    return n
end

webview.add_signal("init", function(view)
    view:add_signal("load-status", function(v, status, uri)
        if status == "first-visual" or status == "finished" then _M.run(v, status, uri) end
    end)
end)

-- ---------------------------------------------------------------------------
-- 菜单 / 命令
-- ---------------------------------------------------------------------------
local function build_menu(w)
    local uri = w.view and w.view.uri or ""
    local rows = { { "Script", "State", "Runs here?", title = true } }
    for _, s in ipairs(_M.list()) do
        rows[#rows + 1] = { s.name, s.enabled and "on" or "off", _M.matches(s, uri) and "yes" or "no", script = s }
    end
    if #rows == 1 then rows[2] = { "(no *.user.js in " .. _M.dir .. ")", "", "", selectable = false } end
    w.menu:build(rows)
end

modes.new_mode("uscriptlist", "Enable, disable, edit or delete user scripts.", {
    enter = function(w)
        build_menu(w)
        w.menu:show()
        w:set_prompt("User scripts — <space>: toggle, e: edit, d: delete")
    end,
    leave = function(w) w.menu:hide() end,
})

local function toggle_selected(w)
    local row = w.menu:get()
    if row and row.script then
        _M.toggle(row.script.name)
        build_menu(w)
    end
end

modes.add_binds("uscriptlist", {
    { "<space>", "Toggle the selected script.", toggle_selected },
    { "<Return>", "Toggle the selected script.", toggle_selected },
    { "e", "Edit the selected script.", function (w)
        local row = w.menu:get()
        if row and row.script then
            w:set_mode()
            local ok, editor = pcall(require, "editor")
            if ok and editor and editor.edit then
                editor.edit(row.script.path, 1, function() _M.load() end)
            else
                w:notify("userscripts: edit " .. row.script.path)
            end
        end
    end },
    { "d", "Delete the selected script.", function (w)
        local row = w.menu:get()
        if row and row.script then
            _M.del(row.script.path)
            build_menu(w)
        end
    end },
    { "<Tab>", "Select the next script.", function (w) w.menu:move_down() end },
    { "<Shift-Tab>", "Select the previous script.", function (w) w.menu:move_up() end },
})

modes.add_cmds({
    { ":userscripts, :uscripts", "List user scripts.", function (w) w:set_mode("uscriptlist") end },
    { ":userscripts-reload, :uscripts-reload", "Re-read the scripts directory.", function (w)
        _M.load()
        w:notify(("userscripts: %d script(s) loaded"):format(#order))
    end },
    { ":userscriptinstall, :usi, :usinstall", "Install the *.user.js shown in the current tab.", function (w)
        local view = w.view
        local uri = view and view.uri or ""
        if not uri:match("%.user%.js") then
            w:error("userscripts: the current page is not a *.user.js file")
            return
        end
        local src = view.source
        if type(src) ~= "string" or src == "" then
            w:error("userscripts: cannot read page source")
            return
        end
        -- Chromium 把纯文本包在 <pre> 里显示
        local body = src:match("<pre[^>]*>(.-)</pre>")
        if body then
            src = body:gsub("&lt;", "<"):gsub("&gt;", ">"):gsub("&amp;", "&"):gsub("&quot;", '"')
        end
        local file = uri:match("([^/?#]+%.user%.js)") or "installed.user.js"
        local path, err = _M.save(file, src)
        if path then w:notify("userscripts: installed " .. path) else w:error("userscripts: " .. tostring(err)) end
    end },
})

_M.load()

return _M
