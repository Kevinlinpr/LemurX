-- userscripts · 渲染进程半边
--
-- 浏览器进程（official/userscripts.lua）把启用的用户脚本打包推过来，这里在每个新文档：
--   1. 按 @match/@include/@exclude 选出要跑的脚本（/regex/ 形式的交给页面 JS 判定）
--   2. 注入 GM 运行时（userscripts/gm.lua），再逐个脚本 eval（各自独立，一个语法错误不影响别的）
--   3. 三个桥函数把 GM_setValue / GM_xmlhttpRequest / 其他调用转回浏览器进程
--
-- 通道 "lx.userscripts"：
--   ← "scripts"(bundle_json)                 全量脚本包（启动 / 安装 / 开关后）
--   ← "value"(script_id, key, value, del)    别的页面改了值 → 同步缓存 + 触发页面里的 ValueChangeListener
--   ← "xhr_result"(req_id, result_json)
--   ← "call_result"(req_id, result_json)
--   ← "run_menu"(page_id, script_id, cmd_id)
--   ← "enable"(bool)
--   → "hello"(pid)
--   → "set"(script_id, key, value, del)
--   → "xhr"(pid, req_id, details_json)
--   → "call"(pid, page_id, req_id, args_json)
--   → "menu"(page_id, script_id, name, cmds)
--   → "ran"(page_id, [script ids])           本页跑了哪些脚本（角标 / 菜单用）
--
-- 限制：只在主框架跑（LemurX 的 page 对象 = 主框架）；@noframes 因此天然成立。

local W = require("lx.web")
local match = require("userscripts.match")
local GM_JS = require("userscripts.gm")
local json = W.json
local util = W.util

local M = {}
local ch = W.channel("lx.userscripts")

local enabled = true
local scripts = {}        -- 有序：{ id, info, matcher, js (缓存的注入代码) }
local by_id = {}
local values = {}         -- id -> {k = v}
local pending = {}        -- req_id -> { resolve, reject }
local req_seq = 0

-- 支持的 GM 名字（顺序即函数参数顺序）
local GM_NAMES = {
    "GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues", "GM_getValues", "GM_setValues", "GM_deleteValues",
    "GM_addValueChangeListener", "GM_removeValueChangeListener",
    "GM_addStyle", "GM_addElement", "GM_getResourceText", "GM_getResourceURL",
    "GM_log", "GM_openInTab", "GM_setClipboard", "GM_notification",
    "GM_registerMenuCommand", "GM_unregisterMenuCommand", "GM_getTab", "GM_saveTab", "GM_getTabs",
    "GM_download", "GM_cookie", "GM_webRequest", "GM_xmlhttpRequest",
    "cloneInto", "exportFunction", "createObjectIn",
}
local ALWAYS = { "unsafeWindow", "GM_info", "GM" }

local function param_names(info)
    local names = {}
    local grants = info.grants or {}
    if not info.grant_none then
        for _, n in ipairs(GM_NAMES) do
            if grants[n] or grants["GM." .. n:gsub("^GM_", "")] or (n == "GM_xmlhttpRequest" and grants["GM.xmlHttpRequest"]) then
                names[#names + 1] = n
            end
        end
    end
    for _, n in ipairs(ALWAYS) do names[#names + 1] = n end
    return names
end

-- 组装一个脚本的注入代码（只做一次）
local function build_js(rec)
    local info = rec.info
    local names = param_names(info)
    local info_pub = {
        id = info.id, name = info.name, namespace = info.namespace, version = info.version, description = info.description,
        author = info.author, icon = info.icon, icon64 = info.icon64, homepage = info.homepage, support = info.support,
        match = info.match, include = info.include, exclude = info.exclude, grant = info.grant, resources = info.resource_list,
        require = info.require, run_at = info.run_at, noframes = info.noframes, header = info.header,
        downloadURL = info.downloadURL, updateURL = info.updateURL, handler_version = "1.0",
    }
    local parts = {
        "window.__lxus.run(", json.encode(info_pub), ",", "__LXUS_VALUES__", ",", json.encode(rec.resources or {}), ",",
        json.encode(names), ",function(", table.concat(names, ","), "){\n",
    }
    if rec.requires and #rec.requires > 0 then
        for _, r in ipairs(rec.requires) do
            parts[#parts + 1] = r
            parts[#parts + 1] = "\n;\n"
        end
    end
    parts[#parts + 1] = rec.code or ""
    parts[#parts + 1] = "\n});\n//# sourceURL=userscript:"
    parts[#parts + 1] = (info.id or "script") .. ".user.js"
    return table.concat(parts)
end

local function inject_script(page, rec)
    if not rec.js then rec.js = build_js(rec) end
    local vals = json.encode(values[rec.info.id] or {})
    -- 值表每次现拼（可能变了）；用 gsub 的函数形式避免替换串里的 % 被当成捕获
    local js = rec.js:gsub("__LXUS_VALUES__", function() return vals end, 1)
    local _, err = W.eval(page, js, "userscript:" .. rec.info.id)
    if err then
        W.log("userscripts: %s failed: %s", rec.info.id, tostring(err))
        ch:emit_signal("log", rec.info.id, "error", tostring(err))
        return false
    end
    return true
end

local function should_run(page, rec, uri)
    local r = rec.matcher:test(uri)
    if r == true then return true end
    if r == false or r == nil then return false end
    -- 需要 JS 正则判定
    local v = W.eval(page, match.js_test(r))
    return v == true
end

W.on_window_cleared(function(page, uri)
    if not enabled or #scripts == 0 then return end
    if type(uri) ~= "string" or not (uri:find("^https?://") or uri:find("^file://") or uri:find("^lemurx://")) then return end
    local ran = {}
    local bridged = false
    for _, rec in ipairs(scripts) do
        if should_run(page, rec, uri) then
            if not bridged then
                local _, err = W.eval(page, GM_JS, "lx-userscripts-gm")
                if err then W.log("userscripts: gm runtime: %s", tostring(err)) return end
                bridged = true
            end
            if inject_script(page, rec) then ran[#ran + 1] = rec.info.id end
        end
    end
    if #ran > 0 then
        local st = W.state(page, "userscripts")
        st.ran = ran
        local ok, id = pcall(function() return page.id end)
        if ok then ch:emit_signal("ran", id, ran) end
    end
end, 50)

-- ===== 桥函数 =====
W.expose("__lx_us_set", function(page, arg)
    local t = json.decode(tostring(arg))
    if type(t) ~= "table" or not t.id or not t.key then return "0" end
    values[t.id] = values[t.id] or {}
    if t.del then values[t.id][t.key] = nil else values[t.id][t.key] = t.value end
    ch:emit_signal("set", t.id, t.key, t.value, t.del and true or false)
    return "1"
end)

local function next_req(resolve, reject)
    req_seq = req_seq + 1
    local id = req_seq
    pending[id] = { resolve = resolve, reject = reject, at = os.time() }
    return id
end

W.expose_async("__lx_us_xhr", function(page, resolve, reject, arg)
    local id = next_req(resolve, reject)
    ch:emit_signal("xhr", W.pid, id, tostring(arg))
end)

W.expose_async("__lx_us_call", function(page, resolve, reject, arg)
    local t = json.decode(tostring(arg)) or {}
    local ok, page_id = pcall(function() return page.id end)
    if t.fn == "menu" then
        -- 记在页面状态里，并转给浏览器进程（三点菜单显示）
        local st = W.state(page, "userscripts")
        st.menus = st.menus or {}
        st.menus[t.script] = { name = t.name, cmds = t.cmds }
        ch:emit_signal("menu", ok and page_id or -1, t.script, t.name, t.cmds)
        return resolve("1")
    end
    local id = next_req(resolve, reject)
    ch:emit_signal("call", W.pid, ok and page_id or -1, id, tostring(arg))
end)

-- ===== 来自浏览器进程 =====
local function finish(req_id, result, is_err)
    local p = pending[req_id]
    if not p then return end
    pending[req_id] = nil
    if is_err then p.reject(result) else p.resolve(result) end
end
ch:add_signal("xhr_result", function(_, _page, req_id, result_json) finish(tonumber(req_id), result_json) end)
ch:add_signal("call_result", function(_, _page, req_id, result_json, is_err) finish(tonumber(req_id), result_json, is_err) end)

ch:add_signal("scripts", function(_, _page, bundle_json)
    local bundle = type(bundle_json) == "string" and json.decode(bundle_json) or bundle_json
    if type(bundle) ~= "table" then W.log("userscripts: bad bundle") return end
    scripts, by_id = {}, {}
    for _, s in ipairs(bundle.scripts or {}) do
        local info = s.info
        local rec = {
            info = info, code = s.code, requires = s.requires, resources = s.resources,
            matcher = match.compile({ match = info.match, include = info.include, exclude = info.exclude }),
        }
        scripts[#scripts + 1] = rec
        by_id[info.id] = rec
    end
    if type(bundle.values) == "table" then values = bundle.values end
    W.log("userscripts: %d scripts loaded", #scripts)
end)

local function page_by_id(page_id)
    for _, p in pairs(__lk.pages()) do
        local ok, id = pcall(function() return p.id end)
        if ok and id == page_id then return p end
    end
end

ch:add_signal("value", function(_, _page, script_id, key, value, del)
    values[script_id] = values[script_id] or {}
    if del then values[script_id][key] = nil else values[script_id][key] = value end
    local js = ("window.__lxus&&window.__lxus.onValue(%s,%s,%s,%s)"):format(
        json.encode(script_id), json.encode(key), json.encode(value), del and "true" or "false")
    for _, p in pairs(__lk.pages()) do pcall(W.eval, p, js) end
end)

ch:add_signal("run_menu", function(_, _page, page_id, script_id, cmd_id)
    local p = page_by_id(tonumber(page_id))
    if not p then return end
    W.eval(p, ("window.__lxus&&window.__lxus.runMenu(%s,%s)"):format(json.encode(script_id), json.encode(cmd_id)))
end)

ch:add_signal("enable", function(_, _page, on) enabled = on ~= false end)

-- 超时的桥请求清理（浏览器进程没回）
W.on_document_loaded(function()
    local now = os.time()
    for id, p in pairs(pending) do
        if now - p.at > 180 then pending[id] = nil pcall(p.reject, "timeout") end
    end
end)

ch:emit_signal("hello", W.pid)

M.scripts = function() return scripts end
return M
