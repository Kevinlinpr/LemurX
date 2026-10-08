-- @name 用户脚本
-- @description Tampermonkey 兼容的用户脚本管理器：安装 .user.js、GM_* API、@require/@resource、自动更新、脚本命令
-- @version 1.0.0
-- @icon 🐒
-- @category 开发与效率
-- @replaces Tampermonkey · Violentmonkey · Greasemonkey
-- @page lemurx://userscripts/
--
-- 结构：
--   浏览器进程（本文件）：安装 / 存储 / @require @resource 下载 / 更新 / 值持久化 / GM_xmlhttpRequest 代发 / 管理页
--   渲染进程（official/userscripts/web.lua）：按 URL 匹配、注入 GM 运行时（userscripts/gm.lua）和脚本
--   共用：userscripts/meta.lua（头解析）userscripts/match.lua（@match/@include）
--
-- 与 Tampermonkey 的差别：脚本跑在页面主世界（Violentmonkey 的 @inject-into page）；只跑主框架；
-- GM_cookie / GM_webRequest 不支持；GM_xmlhttpRequest 由浏览器进程直连，天然无 CORS。

local lx = require("lx")
local meta_mod = require("userscripts.meta")
local util, json = lx.util, lx.json
local esc = lx.html.escape

local ID = "userscripts"
local CHANNEL = "lx.userscripts"
local data = lx.data(ID)

local S
local index = nil              -- 有序数组：{id, enabled, installed, updated, source_url, meta, size, last_error}
local index_by_id = {}
local values_cache = {}        -- id -> table
local bundle_json = nil
local tab_menus = {}           -- tab id -> { script_id -> {name, cmds} }
local tab_ran = {}             -- tab id -> {ids}
local logs = {}                -- id -> { {time, level, text} ... }
local busy = {}                -- id -> true（安装/更新中）

-- ===== 存储 =====
local function load_index()
    index = data:read_json("index.json") or {}
    index_by_id = {}
    for _, r in ipairs(index) do index_by_id[r.id] = r end
end
local function save_index()
    data:write_json("index.json", index)
end
local function src_path(id) return "src/" .. id .. ".user.js" end
local function read_source(id) return data:read(src_path(id)) end
local function values_of(id)
    local v = values_cache[id]
    if v then return v end
    v = data:read_json("values/" .. id .. ".json") or {}
    values_cache[id] = v
    return v
end
local save_timers = {}
local function save_values_later(id)
    if save_timers[id] then return end
    save_timers[id] = lx.after(800, function()
        save_timers[id] = nil
        data:write_json("values/" .. id .. ".json", values_of(id))
    end)
end

local function add_log(id, level, text)
    local l = logs[id]
    if not l then l = {} logs[id] = l end
    l[#l + 1] = { time = util.now_ms(), level = level, text = tostring(text):sub(1, 2000) }
    if #l > 50 then table.remove(l, 1) end
end

-- ===== 打包推送 =====
local function slim_meta(m)
    local res_list = {}
    for _, r in ipairs(m.resource or {}) do res_list[#res_list + 1] = { name = r.name, url = r.url } end
    return {
        id = m.id, name = m.display_name or m.name, namespace = m.namespace, version = m.version,
        description = m.display_description or m.description, author = m.author, icon = m.icon, icon64 = m.icon64,
        homepage = m.homepage, support = m.support, match = m.match, include = m.include, exclude = m.exclude,
        grant = m.grant, grants = m.grants, grant_none = m.grant_none, require = m.require, resource_list = res_list,
        run_at = m.run_at, noframes = m.noframes, header = m.header, downloadURL = m.downloadURL, updateURL = m.updateURL,
    }
end

local function build_bundle()
    local out = { scripts = {}, values = {} }
    for _, r in ipairs(index) do
        if r.enabled ~= false then
            local code = read_source(r.id)
            if code then
                local requires = {}
                for i = 1, #(r.meta.require or {}) do
                    local s = data:read(("req/%s/%d.js"):format(r.id, i))
                    if s then requires[#requires + 1] = s end
                end
                local resources = {}
                for _, res in ipairs(r.meta.resource or {}) do
                    local m = data:read_json(("res/%s/%s.json"):format(r.id, res.name))
                    if m then resources[res.name] = m end
                end
                out.scripts[#out.scripts + 1] = { info = slim_meta(r.meta), code = code, requires = requires, resources = resources }
                out.values[r.id] = values_of(r.id)
            end
        end
    end
    bundle_json = json.encode(out)
    return #out.scripts
end

local function push_pid(pid)
    if not bundle_json then build_bundle() end
    lx.web.send_pid(CHANNEL, pid, "scripts", bundle_json)
end
local function push_all()
    local n = build_bundle()
    lx.web.broadcast(CHANNEL, "scripts", bundle_json)
    return n
end

-- ===== 安装 =====
local function is_text_ok(r) return r and r.ok and r.status and r.status >= 200 and r.status < 400 and type(r.body) == "string" end

-- 顺序下载一组 URL，cb(results, errors)
local function fetch_all(urls, cb, opts)
    local results, errors = {}, {}
    local i = 0
    local function step()
        i = i + 1
        if i > #urls then return cb(results, errors) end
        lx.fetch(urls[i], opts or { timeout = 30000 }, function(r)
            if is_text_ok(r) or (r and r.ok and r.base64) then results[i] = r else errors[i] = (r and (r.error or ("HTTP " .. tostring(r.status)))) or "?" end
            step()
        end)
    end
    step()
end

local function mime_of(r, url)
    local ct = r.headers and (r.headers["content-type"] or r.headers["Content-Type"])
    if ct then return ct:match("^[^;]+") end
    local ext = url:match("%.(%w+)$")
    local map = { png = "image/png", jpg = "image/jpeg", jpeg = "image/jpeg", gif = "image/gif", svg = "image/svg+xml", css = "text/css", js = "application/javascript", json = "application/json", woff = "font/woff", woff2 = "font/woff2" }
    return map[ext or ""] or "application/octet-stream"
end
local function is_binary_mime(m) return m and not (m:find("^text/") or m:find("javascript") or m:find("json") or m:find("xml") or m:find("svg")) end

-- 把源码 + 依赖落盘；cb(rec | nil, err)
local function install_source(src, source_url, cb, opts)
    opts = opts or {}
    local meta, err = meta_mod.parse(src)
    if not meta then return cb(nil, err) end
    meta.header = meta_mod.header_only(src)
    local id = opts.id or meta_mod.id_for(meta)
    meta.id = id
    if busy[id] then return cb(nil, "正在处理这个脚本") end
    busy[id] = true
    local function done(rec, e) busy[id] = nil cb(rec, e) end

    -- @require
    fetch_all(meta.require, function(req_results, req_errors)
        for i = 1, #meta.require do
            if req_results[i] then
                data:write(("req/%s/%d.js"):format(id, i), req_results[i].body)
            else
                lx.log("userscripts: @require %s failed: %s", meta.require[i], tostring(req_errors[i]))
                add_log(id, "warn", "@require 下载失败：" .. meta.require[i] .. " — " .. tostring(req_errors[i]))
            end
        end
        -- @resource（二进制 base64）
        local res_urls = {}
        for i, r in ipairs(meta.resource) do res_urls[i] = r.url end
        fetch_all(res_urls, function(res_results, res_errors)
            for i, r in ipairs(meta.resource) do
                local rr = res_results[i]
                if rr then
                    local mime = mime_of(rr, r.url)
                    if rr.base64 then
                        data:write_json(("res/%s/%s.json"):format(id, r.name), { mime = mime, b64 = rr.body })
                    else
                        data:write_json(("res/%s/%s.json"):format(id, r.name), { mime = mime, text = rr.body })
                    end
                else
                    add_log(id, "warn", "@resource 下载失败：" .. r.url .. " — " .. tostring(res_errors[i]))
                end
            end
            -- 落源码 + 索引
            data:write(src_path(id), src)
            local rec = index_by_id[id]
            local now = util.now_ms()
            if not rec then
                rec = { id = id, enabled = true, installed = now }
                index[#index + 1] = rec
                index_by_id[id] = rec
            end
            rec.updated = now
            rec.source_url = source_url or rec.source_url
            rec.meta = meta
            rec.size = #src
            rec.last_error = nil
            save_index()
            push_all()
            done(rec)
        end, { timeout = 30000, binary = true })
    end)
end

local function install_from_url(url, cb, opts)
    lx.fetch(url, { timeout = 30000 }, function(r)
        if not is_text_ok(r) then return cb(nil, "下载失败：" .. tostring(r and (r.error or r.status))) end
        install_source(r.body, url, cb, opts)
    end)
end

local function remove_script(id)
    local rec = index_by_id[id]
    if not rec then return false end
    for i, r in ipairs(index) do if r.id == id then table.remove(index, i) break end end
    index_by_id[id] = nil
    values_cache[id] = nil
    data:remove(src_path(id))
    data:remove("values/" .. id .. ".json")
    for i = 1, #(rec.meta.require or {}) do data:remove(("req/%s/%d.js"):format(id, i)) end
    for _, res in ipairs(rec.meta.resource or {}) do data:remove(("res/%s/%s.json"):format(id, res.name)) end
    save_index()
    push_all()
    return true
end

-- ===== 更新检查 =====
local function check_update(rec, cb, force)
    local url = rec.meta.updateURL or rec.meta.downloadURL or rec.source_url
    if not url then return cb(false, "没有更新地址") end
    lx.fetch(url, { timeout = 20000 }, function(r)
        if not is_text_ok(r) then return cb(false, "下载失败：" .. tostring(r and (r.error or r.status))) end
        local m = meta_mod.parse(r.body)
        if not m then return cb(false, "更新地址返回的不是用户脚本") end
        rec.last_check = util.now_ms()
        if not force and meta_mod.compare_version(m.version, rec.meta.version) <= 0 then
            save_index()
            return cb(false, "已是最新（" .. tostring(rec.meta.version) .. "）")
        end
        -- updateURL 可能只是 .meta.js：完整源码从 downloadURL / source_url 拿
        local full_url = rec.meta.downloadURL or rec.source_url or url
        local function finish_install(src, from)
            install_source(src, from, function(rec2, err)
                if rec2 then cb(true, "已更新到 " .. tostring(rec2.meta.version)) else cb(false, err) end
            end, { id = rec.id })
        end
        if full_url ~= url or not r.body:find("==/UserScript==%s*\n.-%S") then
            lx.fetch(full_url, { timeout = 30000 }, function(r2)
                if not is_text_ok(r2) then return cb(false, "下载失败：" .. tostring(r2 and (r2.error or r2.status))) end
                finish_install(r2.body, full_url)
            end)
        else
            finish_install(r.body, url)
        end
    end)
end

local function auto_update()
    if not S.settings:get("auto_update") then return end
    local hours = tonumber(S.settings:get("update_hours")) or 24
    local now = util.now_ms()
    local i = 0
    local function step()
        i = i + 1
        local rec = index[i]
        if not rec then return end
        if rec.enabled ~= false and (now - (rec.last_check or 0)) > hours * 3600 * 1000 and (rec.meta.updateURL or rec.meta.downloadURL or rec.source_url) then
            check_update(rec, function(updated, msg)
                if updated then lx.toast("用户脚本已更新：" .. (rec.meta.display_name or rec.id)) end
                lx.after(500, step)
            end)
        else
            step()
        end
    end
    step()
end

-- ===== 注册 =====
S = lx.register({
    id = ID, name = "用户脚本", version = "1.0.0", icon = "🐒",
    description = "Tampermonkey 兼容：打开任意 .user.js 链接即可安装，支持 GM_* API、@require / @resource、自动更新、脚本菜单命令。",
    replaces = "Tampermonkey · Violentmonkey",
    settings = { enabled = true, auto_update = true, update_hours = 24, intercept_userjs = true, show_badge = true },
    schema = {
        { key = "enabled", type = "bool", label = "启用用户脚本", section = "总开关" },
        { key = "intercept_userjs", type = "bool", label = "打开 .user.js 链接时弹出安装页", desc = "关掉后需要在本页手动粘贴地址安装" },
        { key = "auto_update", type = "bool", label = "自动检查更新", section = "更新" },
        { key = "update_hours", type = "select", label = "检查频率", options = { { 6, "每 6 小时" }, { 24, "每天" }, { 168, "每周" } } },
    },
    menu = {
        { id = "open", title = "用户脚本", page = "main" },
        { id = "cmds", title = "脚本命令…", page = "main", onClick = function() S.show_commands() end },
    },
})
local settings = S.settings

-- ===== 渲染进程通道 =====
local ch = lx.web.require("userscripts/web")
if ch then
    ch:add_signal("hello", function(_, pid) if type(pid) == "number" then push_pid(pid) end end)
    lx.web.on_process(push_pid)

    ch:add_signal("set", function(_, script_id, key, value, del)
        if type(script_id) ~= "string" or type(key) ~= "string" then return end
        local v = values_of(script_id)
        if del then v[key] = nil else v[key] = value end
        save_values_later(script_id)
        -- 同步给所有渲染进程（包括来源进程：同进程其他页面也要更新）
        lx.web.broadcast(CHANNEL, "value", script_id, key, value, del and true or false)
    end)

    ch:add_signal("xhr", function(_, pid, req_id, details_json)
        local d = json.decode(details_json or "") or {}
        local headers = d.headers or {}
        local opts = {
            method = d.method or "GET", headers = headers, body = d.body, timeout = (d.timeout and d.timeout > 0) and d.timeout or 60000,
            binary = d.binary and true or false, redirect = d.redirect ~= "manual" and d.redirect ~= "error",
        }
        if d.base64 and d.body then opts.base64 = true end
        -- 浏览器进程直连用的是独立的 HTTP 栈，不带 Chromium 的 cookie：像 Tampermonkey 一样把站点 cookie 带上
        local has_cookie = false
        for k in pairs(headers) do if k:lower() == "cookie" then has_cookie = true end end
        if not d.anonymous and not has_cookie and lemurx.cookie and lemurx.cookie.get then
            local ok, list = pcall(lemurx.cookie.get, d.url or "")
            if ok and type(list) == "table" then
                local parts = {}
                for _, c in ipairs(list) do if c.name then parts[#parts + 1] = c.name .. "=" .. tostring(c.value or "") end end
                if #parts > 0 then headers["Cookie"] = table.concat(parts, "; ") end
            end
        end
        if d.user and d.password then headers["Authorization"] = "Basic " .. util.base64_encode(d.user .. ":" .. d.password) end
        if d.nocache then headers["Cache-Control"] = "no-cache" end
        lx.fetch(d.url or "", opts, function(r)
            r = r or { ok = false, error = "no response" }
            local out = {
                status = r.status or 0, statusText = r.statusText or "", headers = r.headers or {}, url = r.url or d.url,
                body = r.body, base64 = r.base64 and true or false, bytes = r.bytes, error = (not r.status or r.status == 0) and (r.error or "network error") or nil,
            }
            lx.web.send_pid(CHANNEL, pid, "xhr_result", req_id, json.encode(out))
        end)
    end)

    ch:add_signal("call", function(_, pid, page_id, req_id, args_json)
        local a = json.decode(args_json or "") or {}
        local result, is_err = "1", false
        local fn = a.fn
        if fn == "openInTab" then
            local ok, tid = pcall(lemurx.tabs.open, a.url, { background = a.active == false, incognito = a.incognito or nil })
            result = json.encode({ tab = ok and tid or nil })
        elseif fn == "clipboard" then
            pcall(lemurx.clipboard.set, a.text or "")
        elseif fn == "notification" then
            lx.notify(a.title or "用户脚本", a.text or "", { url = a.url })
        elseif fn == "download" then
            local ok = false
            if lemurx.downloads and lemurx.downloads.enqueue then ok = pcall(lemurx.downloads.enqueue, a.url, page_id) end
            if not ok then
                local name = a.name or (a.url or ""):match("([^/?#]+)[?#]?[^/]*$") or "download"
                lx.fetch(a.url, { binary = true, timeout = 120000 }, function(r)
                    if r and r.ok then pcall(lemurx.fs.write, "downloads/" .. name, r.body, { base64 = r.base64 }) lx.toast("已保存到 lua/downloads/" .. name) end
                end)
            end
        elseif fn == "log" then
            add_log(a.script or "?", a.level or "info", a.text or "")
        else
            result, is_err = json.encode({ error = "unknown call " .. tostring(fn) }), true
        end
        lx.web.send_pid(CHANNEL, pid, "call_result", req_id, result, is_err)
    end)

    ch:add_signal("menu", function(_, page_id, script_id, name, cmds)
        if type(page_id) ~= "number" then return end
        tab_menus[page_id] = tab_menus[page_id] or {}
        tab_menus[page_id][script_id] = { name = name, cmds = cmds or {} }
    end)
    ch:add_signal("ran", function(_, page_id, ids)
        if type(page_id) ~= "number" then return end
        tab_ran[page_id] = ids
        tab_menus[page_id] = nil
        lx.after(0, function() S.update_badge(page_id) end)
    end)
    ch:add_signal("log", function(_, script_id, level, text)
        add_log(script_id, level, text)
        local rec = index_by_id[script_id]
        if rec and level == "error" then rec.last_error = tostring(text):sub(1, 300) end
    end)
end

pcall(lemurx.tabs.on, "closed", function(t) if t and t.id then tab_menus[t.id] = nil tab_ran[t.id] = nil end end)
pcall(lemurx.tabs.on, "started", function(t) if t and t.id then tab_ran[t.id] = nil tab_menus[t.id] = nil S.update_badge(t.id) end end)
pcall(lemurx.tabs.on, "selected", function() S.update_badge() end)

-- ===== 角标 / 脚本命令面板 =====
function S.update_badge(tab_id)
    if not settings:get("show_badge") then return end
    local cur = lx.tabs.current()
    if not cur then return end
    if tab_id and cur.id ~= tab_id then return end
    local ran = tab_ran[cur.id]
    local n = ran and #ran or 0
    if n == 0 then pcall(lemurx.ui.unmount, "lx_us_badge") return end
    local h = lemurx.ui.h
    pcall(lemurx.ui.render, "toolbar.end", h("button", {
        id = "lx_us_badge", text = "🐒" .. n, size = 11, paddingH = 6, paddingV = 2,
        background = { color = "#FF37474F", radius = 10 }, color = "#FFFFFFFF",
        onClick = function() S.show_commands() end,
    }))
end

function S.show_commands()
    local cur = lx.tabs.current()
    if not cur then return end
    local menus = tab_menus[cur.id] or {}
    local ran = tab_ran[cur.id] or {}
    local h = lemurx.ui.h
    local rows = {}
    rows[#rows + 1] = h("row", { padding = { 12, 8 } }, {
        h("text", { text = ("本页用户脚本 · %d 个"):format(#ran), bold = true, weight = 1 }),
        h("button", { text = "管理", onClick = function() lemurx.ui.unmount("page.bottom") lx.open(ID) end }),
        h("button", { text = "收起", onClick = function() lemurx.ui.unmount("page.bottom") end }),
    })
    local any = false
    for script_id, m in pairs(menus) do
        for _, c in ipairs(m.cmds or {}) do
            any = true
            rows[#rows + 1] = h("button", {
                text = (m.name or script_id) .. " › " .. (c.caption or c.id), width = "match", margin = { 8, 2 },
                onClick = function()
                    lemurx.ui.unmount("page.bottom")
                    lx.web.broadcast(CHANNEL, "run_menu", cur.id, script_id, c.id)
                end,
            })
        end
    end
    if not any then
        local names = {}
        for _, id in ipairs(ran) do local r = index_by_id[id] names[#names + 1] = r and (r.meta.display_name or id) or id end
        rows[#rows + 1] = h("text", { text = #names > 0 and ("已运行：" .. table.concat(names, "、") .. "\n（这些脚本没有注册菜单命令）") or "本页没有匹配的用户脚本", padding = { 12, 8 }, color = "#FF9E9E9E" })
    end
    pcall(lemurx.ui.render, "page.bottom", h("column", { background = "#F2263238" }, rows))
end

-- ===== 导航拦截：.user.js =====
lx.on_navigation(function(view, uri, ev)
    if not settings:get("intercept_userjs") or not settings:get("enabled") then return nil end
    if ev.main_frame == false then return nil end
    local path = uri:match("^https?://[^?#]+")
    if path and path:find("%.user%.js$") and not uri:find("^lemurx://") then
        return "lemurx://userscripts/install?url=" .. util.url.encode(uri)
    end
    return nil
end, 20)

-- ===== 设置变化 =====
settings:on_change(function(key, value)
    if key == "enabled" then
        lx.web.broadcast(CHANNEL, "enable", value ~= false)
        if not value then pcall(lemurx.ui.unmount, "lx_us_badge") end
    elseif key == "show_badge" and not value then
        pcall(lemurx.ui.unmount, "lx_us_badge")
    end
end)

-- ===== API =====
local function rec_json(r)
    local m = r.meta
    return {
        id = r.id, enabled = r.enabled ~= false, name = m.display_name or m.name, version = m.version, description = m.display_description or m.description,
        author = m.author, icon = m.icon, homepage = m.homepage, match = m.match, include = m.include, exclude = m.exclude,
        grant = m.grant, run_at = m.run_at, require = m.require, resource = m.resource, installed = r.installed, updated = r.updated,
        source_url = r.source_url, updateURL = m.updateURL, downloadURL = m.downloadURL, size = r.size, last_error = r.last_error,
        last_check = r.last_check, busy = busy[r.id] and true or false,
    }
end

S.api.list = function()
    local out = {}
    for _, r in ipairs(index) do out[#out + 1] = rec_json(r) end
    return { scripts = out, enabled = settings:get("enabled") }
end
S.api.get = function(args)
    local r = index_by_id[args.id or ""]
    if not r then return nil, "没有这个脚本" end
    local o = rec_json(r)
    o.values = values_of(r.id)
    o.logs = logs[r.id] or {}
    o.source = read_source(r.id)
    return o
end
S.api.toggle = function(args)
    local r = index_by_id[args.id or ""]
    if not r then return nil, "没有这个脚本" end
    r.enabled = args.enabled ~= false
    save_index()
    push_all()
    return { ok = true, enabled = r.enabled }
end
S.api.remove = function(args)
    if not remove_script(args.id or "") then return nil, "没有这个脚本" end
    return { ok = true, message = "已卸载", reload = true }
end
S.api.move = function(args)
    local from, dir = nil, args.dir == "up" and -1 or 1
    for i, r in ipairs(index) do if r.id == args.id then from = i break end end
    if not from then return nil, "没有这个脚本" end
    local to = from + dir
    if to < 1 or to > #index then return { ok = true } end
    index[from], index[to] = index[to], index[from]
    save_index()
    push_all()
    return { ok = true, reload = true }
end
S.api.source = function(args)
    local r = index_by_id[args.id or ""]
    if not r then return nil, "没有这个脚本" end
    return { source = read_source(r.id) or "" }
end
S.api.save_source = function(args, ctx)
    if type(args.source) ~= "string" then return nil, "source required" end
    install_source(args.source, nil, function(rec, err)
        if rec then ctx.reply({ ok = true, id = rec.id, message = "已保存", reload = args.id == nil }) else ctx.fail(err) end
    end, { id = args.id })
    return "async"
end
S.api.preview = function(args, ctx)
    -- 安装页：拉源码 + 解析头，不落盘
    local url = args.url or ""
    if url == "" then return nil, "url required" end
    lx.fetch(url, { timeout = 30000 }, function(r)
        if not is_text_ok(r) then return ctx.fail("下载失败：" .. tostring(r and (r.error or r.status))) end
        local m, err = meta_mod.parse(r.body)
        if not m then return ctx.fail(err) end
        m.id = meta_mod.id_for(m)
        local existing = index_by_id[m.id]
        ctx.reply({
            id = m.id, name = m.display_name, version = m.version, description = m.display_description, author = m.author,
            match = m.match, include = m.include, exclude = m.exclude, grant = m.grant, require = m.require, resource = m.resource,
            connect = m.connect, run_at = m.run_at, icon = m.icon, homepage = m.homepage, size = #r.body,
            existing = existing and existing.meta.version or nil, antifeature = m.antifeature, source = r.body,
        })
    end)
    return "async"
end
S.api.install = function(args, ctx)
    if type(args.source) == "string" then
        install_source(args.source, args.url, function(rec, err)
            if rec then ctx.reply({ ok = true, id = rec.id, name = rec.meta.display_name, message = "已安装 " .. rec.meta.display_name }) else ctx.fail(err) end
        end)
        return "async"
    end
    local url = args.url or ""
    if url == "" then return nil, "url required" end
    install_from_url(url, function(rec, err)
        if rec then ctx.reply({ ok = true, id = rec.id, name = rec.meta.display_name, message = "已安装 " .. rec.meta.display_name }) else ctx.fail(err) end
    end)
    return "async"
end
S.api.update = function(args, ctx)
    local r = index_by_id[args.id or ""]
    if not r then return nil, "没有这个脚本" end
    check_update(r, function(updated, msg) ctx.reply({ ok = true, updated = updated, message = msg, reload = updated }) end, args.force)
    return "async"
end
S.api.update_all = function(args, ctx)
    local i, n, msgs = 0, 0, {}
    local function step()
        i = i + 1
        local rec = index[i]
        if not rec then return ctx.reply({ ok = true, message = n > 0 and (n .. " 个脚本已更新") or "全部已是最新", reload = n > 0 }) end
        if rec.meta.updateURL or rec.meta.downloadURL or rec.source_url then
            check_update(rec, function(updated) if updated then n = n + 1 end step() end)
        else
            step()
        end
    end
    step()
    return "async"
end
S.api.values_set = function(args)
    local r = index_by_id[args.id or ""]
    if not r then return nil, "没有这个脚本" end
    local v = values_of(r.id)
    if args.value == nil then v[args.key] = nil else v[args.key] = args.value end
    save_values_later(r.id)
    lx.web.broadcast(CHANNEL, "value", r.id, args.key, args.value, args.value == nil)
    return { ok = true }
end
S.api.values_clear = function(args)
    local r = index_by_id[args.id or ""]
    if not r then return nil, "没有这个脚本" end
    values_cache[r.id] = {}
    data:write_json("values/" .. r.id .. ".json", {})
    push_all()
    return { ok = true, message = "已清空", reload = true }
end
S.api.clear_logs = function(args) logs[args.id or ""] = nil return { ok = true, reload = true } end
S.api.reinstall_deps = function(args, ctx)
    local r = index_by_id[args.id or ""]
    if not r then return nil, "没有这个脚本" end
    local src = read_source(r.id)
    if not src then return nil, "源码丢失" end
    install_source(src, r.source_url, function(rec, err)
        if rec then ctx.reply({ ok = true, message = "依赖已重新下载", reload = true }) else ctx.fail(err) end
    end, { id = r.id })
    return "async"
end
S.api.export = function()
    local out = {}
    for _, r in ipairs(index) do
        out[#out + 1] = { id = r.id, enabled = r.enabled ~= false, source_url = r.source_url, source = read_source(r.id), values = values_of(r.id) }
    end
    return { scripts = out }
end
S.api.import = function(args, ctx)
    local list = args.scripts
    if type(list) ~= "table" then return nil, "scripts required" end
    local i, n = 0, 0
    local function step()
        i = i + 1
        local s = list[i]
        if not s then return ctx.reply({ ok = true, message = ("导入 %d 个脚本"):format(n), reload = true }) end
        if type(s.source) == "string" then
            install_source(s.source, s.source_url, function(rec)
                if rec then
                    n = n + 1
                    rec.enabled = s.enabled ~= false
                    if type(s.values) == "table" then values_cache[rec.id] = s.values data:write_json("values/" .. rec.id .. ".json", s.values) end
                end
                step()
            end)
        else
            step()
        end
    end
    step()
    return "async"
end

-- ===== 页面 =====
local TEMPLATE = [[// ==UserScript==
// @name         新脚本
// @namespace    lemurx
// @version      0.1
// @description  写点什么
// @match        https://example.com/*
// @grant        GM_addStyle
// @run-at       document-idle
// ==/UserScript==

(function () {
  'use strict';
  GM_addStyle('body { outline: 2px solid #0a84ff; }');
  console.log('hello from LemurX userscript');
})();
]]

local PAGE_CSS = [[
.us-name{font-weight:600}.us-meta{font-size:12px;color:var(--muted);margin-top:2px}
.tag{display:inline-block;font-size:11px;padding:1px 6px;border-radius:6px;background:var(--line);color:var(--muted);margin:2px 4px 0 0}
.tag.warn{background:rgba(255,149,0,.15);color:#ff9500}
textarea.code{min-height:60vh;font-size:12px;white-space:pre;overflow:auto}
.err{color:var(--danger);font-size:12px;margin-top:4px;word-break:break-all}
.kv{font-family:ui-monospace,Menlo,monospace;font-size:12px;word-break:break-all}
]]

S.page = function(ctx)
    local rows = {}
    for _, r in ipairs(index) do
        local m = r.meta
        local tags = {}
        if m.version then tags[#tags + 1] = "v" .. esc(m.version) end
        if r.source_url or m.updateURL then tags[#tags + 1] = "可更新" end
        if r.last_error then tags[#tags + 1] = "<span class=\"tag warn\">最近出错</span>" end
        rows[#rows + 1] = ("<div class=\"row\"><a class=\"l\" href=\"lemurx://userscripts/script?id=%s\" style=\"text-decoration:none;color:inherit\"><div class=\"us-name\">%s</div><div class=\"us-meta\">%s</div><div>%s</div></a><label class=\"sw\"><input type=\"checkbox\" data-toggle=\"%s\"%s><span></span></label></div>")
            :format(esc(r.id), esc(m.display_name or r.id), esc((m.display_description or ""):sub(1, 120)),
                table.concat((function() local t = {} for _, x in ipairs(tags) do t[#t + 1] = x:find("^<") and x or ("<span class=\"tag\">" .. x .. "</span>") end return t end)()),
                esc(r.id), r.enabled ~= false and " checked" or "")
    end
    local body = {
        ("<div class=\"card\"><div class=\"row\"><div class=\"icon\">🐒</div><div class=\"l\"><div class=\"t\">用户脚本 <span class=\"badge\">%d 个</span></div><div class=\"d\">Tampermonkey 兼容。打开任意 .user.js 链接即可安装（如 Greasy Fork）。</div></div></div></div>"):format(#index),
        "<div class=\"actions\"><button data-api=\"update_all\">检查全部更新</button><a class=\"btn sec\" href=\"lemurx://userscripts/new\">新建脚本</a><button class=\"sec\" id=\"btn-url\">从地址安装</button><a class=\"btn sec\" href=\"https://greasyfork.org/zh-CN/scripts\">浏览 Greasy Fork</a></div>",
        "<div class=\"card\" id=\"urlbox\" style=\"display:none\"><div class=\"row\" style=\"display:block\"><div class=\"t\">脚本地址（.user.js）</div><div style=\"margin:8px 0\"><input type=\"url\" id=\"url\" placeholder=\"https://…/xxx.user.js\"></div><button id=\"go\">下一步</button></div></div>",
        #index > 0 and ("<div class=\"sec\">已安装</div><div class=\"card list\">" .. table.concat(rows) .. "</div>") or "<div class=\"card\"><div class=\"row\"><div class=\"l muted\">还没有安装脚本。去 Greasy Fork 找一个，或者新建一个。</div></div></div>",
        lx.html.settings(S),
        "<div class=\"actions\"><button class=\"sec\" data-api=\"settings.reset\">恢复默认</button><button class=\"sec\" id=\"exp\">导出全部</button><button class=\"sec\" id=\"imp\">从剪贴板导入</button></div>",
    }
    local js = [[
document.addEventListener('change',function(e){var el=e.target;if(!el.dataset.toggle)return;lx.api('toggle',{id:el.dataset.toggle,enabled:el.checked}).then(function(){lx.toast(el.checked?'已启用':'已停用')}).catch(function(e){lx.toast(e.message)})});
lx.q('#btn-url').onclick=function(){var b=lx.q('#urlbox');b.style.display=b.style.display==='none'?'':'none';lx.q('#url').focus()};
lx.q('#go').onclick=function(){var u=lx.q('#url').value.trim();if(u)location.href='lemurx://userscripts/install?url='+encodeURIComponent(u)};
lx.q('#exp').onclick=function(){lx.api('export').then(function(r){var s=JSON.stringify(r,null,1);navigator.clipboard&&navigator.clipboard.writeText(s).then(function(){lx.toast('已复制到剪贴板（'+r.scripts.length+' 个脚本）')},function(){prompt('复制下面内容',s)})})};
lx.q('#imp').onclick=function(){var s=prompt('粘贴导出的 JSON');if(!s)return;try{var j=JSON.parse(s)}catch(e){lx.toast('不是有效 JSON');return}lx.api('import',{scripts:j.scripts||j}).then(function(r){lx.toast(r.message);if(r.reload)setTimeout(function(){location.reload()},600)}).catch(function(e){lx.toast(e.message)})};
]]
    return lx.html.page({ title = "用户脚本", icon = "🐒", body = table.concat(body), css = PAGE_CSS, js = js })
end

S.routes["/script"] = function(ctx)
    local r = index_by_id[ctx.query.id or ""]
    if not r then return lx.html.page({ title = "没有这个脚本", back_url = "lemurx://userscripts/" }) end
    local m = r.meta
    local function list(title, t)
        if not t or #t == 0 then return "" end
        local items = {}
        for _, x in ipairs(t) do items[#items + 1] = "<div class=\"kv\">" .. esc(type(x) == "table" and (x.name .. " ← " .. x.url) or x) .. "</div>" end
        return "<div class=\"row\" style=\"display:block\"><div class=\"t\">" .. title .. "</div>" .. table.concat(items) .. "</div>"
    end
    local vals = values_of(r.id)
    local vrows = {}
    for k, v in pairs(vals) do
        vrows[#vrows + 1] = ("<div class=\"row\"><div class=\"l kv\"><b>%s</b> = %s</div><button class=\"sec\" data-del=\"%s\">删</button></div>"):format(esc(k), esc(json.encode(v):sub(1, 300)), esc(k))
    end
    local lrows = {}
    for i = #(logs[r.id] or {}), 1, -1 do
        local l = logs[r.id][i]
        lrows[#lrows + 1] = ("<div class=\"row\"><div class=\"l kv\"><span class=\"tag%s\">%s</span> %s</div></div>"):format(l.level == "error" and " warn" or "", esc(l.level), esc(l.text))
    end
    local body = {
        ("<div class=\"card\"><div class=\"row\"><div class=\"icon\">%s</div><div class=\"l\"><div class=\"t\">%s <span class=\"badge\">v%s</span></div><div class=\"d\">%s</div><div class=\"us-meta\">%s%s · 安装于 %s</div></div><label class=\"sw\"><input type=\"checkbox\" id=\"en\"%s><span></span></label></div></div>")
            :format(m.icon and ("<img src=\"" .. esc(m.icon) .. "\" style=\"width:32px;height:32px;border-radius:6px\">") or "📜", esc(m.display_name or r.id), esc(m.version or "?"), esc(m.display_description or ""),
                m.author and ("作者 " .. esc(m.author) .. " · ") or "", esc(r.id), os.date("%Y-%m-%d", math.floor((r.installed or 0) / 1000)), r.enabled ~= false and " checked" or ""),
        r.last_error and ("<div class=\"card\"><div class=\"row\"><div class=\"l err\">最近错误：" .. esc(r.last_error) .. "</div></div></div>") or "",
        "<div class=\"actions\">",
        ("<a class=\"btn\" href=\"lemurx://userscripts/edit?id=%s\">编辑源码</a>"):format(esc(r.id)),
        (r.source_url or m.updateURL or m.downloadURL) and ("<button data-api=\"update\" data-args='" .. esc(json.encode({ id = r.id })) .. "'>检查更新</button>") or "",
        ("<button class=\"sec\" data-api=\"reinstall_deps\" data-args='%s'>重下依赖</button>"):format(esc(json.encode({ id = r.id }))),
        m.homepage and ("<a class=\"btn sec\" href=\"" .. esc(m.homepage) .. "\">主页</a>") or "",
        ("<button class=\"danger\" id=\"rm\">卸载</button>"),
        "</div>",
        "<div class=\"sec\">匹配</div><div class=\"card\">",
        list("@match", m.match), list("@include", m.include), list("@exclude", m.exclude),
        (#(m.match or {}) + #(m.include or {}) == 0) and "<div class=\"row\"><div class=\"l muted\">没有写匹配规则：所有页面都跑</div></div>" or "",
        ("<div class=\"row\"><div class=\"l\"><div class=\"t\">运行时机</div><div class=\"d\">%s</div></div></div>"):format(esc(m.run_at or "document-idle")),
        "</div>",
        "<div class=\"sec\">权限与依赖</div><div class=\"card\">",
        list("@grant", m.grant), list("@require", m.require), list("@resource", m.resource), list("@connect", m.connect),
        r.source_url and ("<div class=\"row\" style=\"display:block\"><div class=\"t\">来源</div><div class=\"kv\">" .. esc(r.source_url) .. "</div></div>") or "",
        "</div>",
        ("<div class=\"sec\">存储值（%d）</div><div class=\"card\">%s<div class=\"row\"><div class=\"l\"></div><button class=\"sec\" data-api=\"values_clear\" data-args='%s'>清空</button></div></div>"):format(#vrows, table.concat(vrows), esc(json.encode({ id = r.id }))),
        #lrows > 0 and ("<div class=\"sec\">日志</div><div class=\"card\">" .. table.concat(lrows) .. ("<div class=\"row\"><div class=\"l\"></div><button class=\"sec\" data-api=\"clear_logs\" data-args='%s'>清空</button></div>"):format(esc(json.encode({ id = r.id }))) .. "</div>") or "",
    }
    local js = ([[
var ID=%s;
lx.q('#en').onchange=function(){lx.api('toggle',{id:ID,enabled:this.checked}).then(function(){lx.toast('已保存')})};
lx.q('#rm').onclick=function(){if(!confirm('卸载这个脚本？存储值也会删掉'))return;lx.api('remove',{id:ID}).then(function(){location.href='lemurx://userscripts/'})};
document.addEventListener('click',function(e){var b=e.target.closest('button[data-del]');if(!b)return;lx.api('values_set',{id:ID,key:b.dataset.del}).then(function(){location.reload()})});
]]):format(json.encode(r.id))
    return lx.html.page({ title = m.display_name or r.id, back_url = "lemurx://userscripts/", back_label = "用户脚本", body = table.concat(body), css = PAGE_CSS, js = js })
end

local function editor_page(title, id, source)
    local body = ([[
<div class="card"><div class="row" style="display:block"><textarea id="src" class="code" spellcheck="false" autocapitalize="off" autocorrect="off">%s</textarea><div class="err" id="err"></div></div></div>
<div class="actions"><button id="save">保存</button><a class="btn sec" href="%s">取消</a></div>
<p class="muted">保存时重新解析 ==UserScript== 头并下载 @require / @resource。id 由 @namespace + @name 决定，改名会变成新脚本。</p>]]):format(esc(source), id and ("lemurx://userscripts/script?id=" .. esc(id)) or "lemurx://userscripts/")
    local js = ([[
var ID=%s;var ta=lx.q('#src');
ta.addEventListener('keydown',function(e){if(e.key==='Tab'){e.preventDefault();var s=ta.selectionStart;ta.value=ta.value.slice(0,s)+'  '+ta.value.slice(ta.selectionEnd);ta.selectionStart=ta.selectionEnd=s+2}});
lx.q('#save').onclick=function(){var b=this;b.disabled=true;lx.q('#err').textContent='';lx.api('save_source',{id:ID||undefined,source:ta.value}).then(function(r){lx.toast(r.message);setTimeout(function(){location.href='lemurx://userscripts/script?id='+encodeURIComponent(r.id)},500)}).catch(function(e){lx.q('#err').textContent=e.message;b.disabled=false})};
]]):format(id and json.encode(id) or "null")
    return lx.html.page({ title = title, back_url = id and ("lemurx://userscripts/script?id=" .. id) or "lemurx://userscripts/", back_label = "返回", body = body, css = PAGE_CSS, js = js })
end
S.routes["/edit"] = function(ctx)
    local r = index_by_id[ctx.query.id or ""]
    if not r then return lx.html.page({ title = "没有这个脚本", back_url = "lemurx://userscripts/" }) end
    return editor_page("编辑 · " .. (r.meta.display_name or r.id), r.id, read_source(r.id) or "")
end
S.routes["/new"] = function(ctx) return editor_page("新建脚本", nil, TEMPLATE) end

S.routes["/install"] = function(ctx)
    local url = ctx.query.url or ""
    local body = ([[
<div class="card" id="box"><div class="row"><div class="l"><div class="t">正在下载脚本…</div><div class="d kv">%s</div></div></div></div>
<div class="actions" id="acts" style="display:none"><button id="inst">安装</button><a class="btn sec" href="%s">查看源码</a><button class="sec" onclick="history.back()">取消</button></div>
<div class="card" id="src" style="display:none"><pre id="code" style="max-height:50vh"></pre></div>]]):format(esc(url), esc(url))
    local js = ([[
var URL_=%s,SRC=null;
function tags(a){return (a||[]).map(function(x){return '<span class="tag">'+String(typeof x==='object'?x.name+' ← '+x.url:x).replace(/</g,'&lt;')+'</span>'}).join('')}
lx.api('preview',{url:URL_}).then(function(p){SRC=p.source;
 var risky=(p.grant||[]).some(function(g){return /xmlhttpRequest|cookie|download/.test(g)});
 lx.q('#box').innerHTML='<div class="row"><div class="icon">'+(p.icon?'<img src="'+p.icon+'" style="width:32px;height:32px;border-radius:6px">':'📜')+'</div><div class="l"><div class="t">'+(p.name||'').replace(/</g,'&lt;')+' <span class="badge">v'+(p.version||'?')+'</span></div><div class="d">'+(p.description||'').replace(/</g,'&lt;')+'</div><div class="us-meta">'+(p.author?'作者 '+p.author+' · ':'')+lx.fmtBytes(p.size||0)+(p.existing?' · 已安装 v'+p.existing+'，将覆盖':'')+'</div></div></div>'+
 '<div class="row" style="display:block"><div class="t">运行于</div>'+(tags(p.match)+tags(p.include)||'<span class="tag warn">所有页面</span>')+(p.exclude&&p.exclude.length?'<div class="d">排除：</div>'+tags(p.exclude):'')+'</div>'+
 '<div class="row" style="display:block"><div class="t">权限</div>'+(tags(p.grant)||'<span class="tag">无</span>')+(risky?'<div class="d">⚠️ 该脚本可以跨站请求 / 下载文件，请确认来源可信</div>':'')+'</div>'+
 (p.require&&p.require.length?'<div class="row" style="display:block"><div class="t">依赖库</div>'+tags(p.require)+'</div>':'')+
 (p.resource&&p.resource.length?'<div class="row" style="display:block"><div class="t">资源</div>'+tags(p.resource)+'</div>':'')+
 (p.antifeature?'<div class="row"><div class="l err">⚠️ 脚本声明含 antifeature：'+p.antifeature.join('，')+'</div></div>':'');
 lx.q('#acts').style.display='';lx.q('#code').textContent=SRC;lx.q('#src').style.display='';
}).catch(function(e){lx.q('#box').innerHTML='<div class="row"><div class="l err">'+e.message+'</div></div>'});
lx.q('#inst').onclick=function(){var b=this;b.disabled=true;b.textContent='安装中…';lx.api('install',{url:URL_,source:SRC}).then(function(r){lx.toast(r.message);setTimeout(function(){location.replace('lemurx://userscripts/script?id='+encodeURIComponent(r.id))},600)}).catch(function(e){lx.toast(e.message);b.disabled=false;b.textContent='安装'})};
]]):format(json.encode(url))
    return lx.html.page({ title = "安装用户脚本", back_url = "lemurx://userscripts/", back_label = "用户脚本", body = body, css = PAGE_CSS, js = js })
end

-- ===== 启动 =====
load_index()
build_bundle()
lx.log("userscripts: %d installed, %d enabled", #index, (function() local n = 0 for _, r in ipairs(index) do if r.enabled ~= false then n = n + 1 end end return n end)())
lx.after(20000, auto_update)
lx.every(6 * 3600 * 1000, auto_update)

return S
