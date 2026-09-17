-- LemurX · luakit-compatible library · chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "chrome" module API. No luakit code is used.
--
-- luakit:// 内置页面路由。
--   chrome.add(name, fn(view, meta) -> html[, mime], on_first_visual, exports)
--   chrome.remove(name)          chrome.available_handlers()
--   chrome.stylesheet            所有内置页共用的基础样式（深色、移动优先）
--   chrome.render{...}           把标题 / 正文 / 样式 / 脚本拼成完整 HTML（便利函数）
--   chrome.escape(s)             HTML 转义
-- exports：name -> fn(view, ...)，页面 JS 里以 window.<name>(...) 调用并拿到 Promise。
-- 桥接由渲染进程侧的 chrome_wm.lua 完成：本模块通过 ipc_channel("chrome_wm") 告知
-- 每个页面导出了哪些函数，渲染进程用 luakit.register_function 暴露它们，调用经 IPC
-- 回到这里执行后再把结果送回去 resolve。

local M = {}

local pages = {}            -- name -> { func=, first_visual=, exports= }
local views = {}            -- view.id -> view（已挂钩的 webview）
local hooked = setmetatable({}, { __mode = "k" })
local scheme_registered = false

-- ====================================================================
-- 小工具
-- ====================================================================
local escapes = { ["&"] = "&amp;", ["<"] = "&lt;", [">"] = "&gt;", ['"'] = "&quot;", ["'"] = "&#39;" }
function M.escape(s)
    return (tostring(s == nil and "" or s):gsub("[&<>\"']", escapes))
end

local function uri_decode(s)
    return (s:gsub("+", " "):gsub("%%(%x%x)", function(h) return string.char(tonumber(h, 16)) end))
end

function M.parse_query(q)
    local out = {}
    if type(q) ~= "string" then return out end
    for pair in q:gmatch("[^&]+") do
        local k, v = pair:match("^([^=]*)=?(.*)$")
        if k and k ~= "" then out[uri_decode(k)] = uri_decode(v or "") end
    end
    return out
end

-- luakit://<page>/<path>?<query>#<fragment>
function M.parse_uri(uri)
    local rest = uri:match("^luakit://(.*)$")
    if not rest then return nil end
    local frag
    rest, frag = rest:match("^([^#]*)#?(.*)$")
    local body, query = rest:match("^([^?]*)%??(.*)$")
    local page, path = body:match("^([^/]*)/?(.*)$")
    return {
        uri = uri,
        page = page or "",
        path = path or "",
        query = query ~= "" and query or nil,
        params = M.parse_query(query),
        fragment = frag ~= "" and frag or nil,
    }
end

-- 把 Lua 值编码成可以直接嵌进 <script> 的 JSON 字面量
local json_encode
json_encode = function(v, depth)
    depth = depth or 0
    local t = type(v)
    if v == nil then return "null" end
    if t == "boolean" then return v and "true" or "false" end
    if t == "number" then
        if v ~= v or v == math.huge or v == -math.huge then return "null" end
        if math.type(v) == "integer" then return string.format("%d", v) end
        return string.format("%.14g", v)
    end
    if t == "string" then
        return '"' .. v:gsub('[%c"\\/]', function(c)
            if c == "/" then return "\\/" end
            local map = { ['"'] = '\\"', ["\\"] = "\\\\", ["\n"] = "\\n", ["\r"] = "\\r", ["\t"] = "\\t" }
            return map[c] or string.format("\\u%04x", c:byte())
        end) .. '"'
    end
    if t == "table" then
        if depth > 32 then return "null" end
        local n = #v
        local is_array = n > 0 or next(v) == nil
        if is_array then
            local parts = {}
            for i = 1, n do parts[i] = json_encode(v[i], depth + 1) end
            return "[" .. table.concat(parts, ",") .. "]"
        end
        local keys = {}
        for k in pairs(v) do keys[#keys + 1] = tostring(k) end
        table.sort(keys)
        local parts = {}
        for _, k in ipairs(keys) do
            local val = v[k]
            if val == nil then val = v[tonumber(k)] end
            parts[#parts + 1] = json_encode(k) .. ":" .. json_encode(val, depth + 1)
        end
        return "{" .. table.concat(parts, ",") .. "}"
    end
    return json_encode(tostring(v))
end
M.json = json_encode

-- ====================================================================
-- 共享样式：深色、移动优先、系统字体
-- ====================================================================
M.stylesheet = [[
:root {
  color-scheme: dark;
  --lx-bg: #0f1115;
  --lx-panel: #171a21;
  --lx-panel-2: #1e222b;
  --lx-line: #2a2f3a;
  --lx-text: #e6e8ee;
  --lx-muted: #8b93a5;
  --lx-accent: #ff7a1a;
  --lx-accent-2: #ffb36b;
  --lx-danger: #ff5d5d;
  --lx-ok: #53d769;
  --lx-radius: 12px;
  --lx-font: -apple-system, "Roboto", "Segoe UI", "Noto Sans", "Helvetica Neue", system-ui, sans-serif;
  --lx-mono: "Roboto Mono", "SF Mono", Menlo, Consolas, monospace;
}
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body {
  background: var(--lx-bg);
  color: var(--lx-text);
  font: 15px/1.5 var(--lx-font);
  -webkit-text-size-adjust: 100%;
  padding: 0 0 48px;
}
a { color: var(--lx-accent-2); text-decoration: none; }
a:hover { text-decoration: underline; }
code, pre, kbd, .mono { font-family: var(--lx-mono); font-size: 0.92em; }
kbd {
  display: inline-block; padding: 1px 7px; border-radius: 6px;
  background: var(--lx-panel-2); border: 1px solid var(--lx-line); color: var(--lx-accent-2);
}
.lx-top {
  position: sticky; top: 0; z-index: 5;
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; background: rgba(15,17,21,0.92); backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--lx-line);
}
.lx-top h1 { font-size: 18px; margin: 0; font-weight: 600; letter-spacing: 0.2px; }
.lx-top .lx-x {
  width: 28px; height: 28px; border-radius: 8px; flex: none;
  background: linear-gradient(135deg, var(--lx-accent), #ff3d81);
  display: grid; place-items: center; font-weight: 800; color: #fff;
}
.lx-top .lx-spacer { flex: 1; }
.lx-wrap { padding: 16px; max-width: 960px; margin: 0 auto; }
.lx-card {
  background: var(--lx-panel); border: 1px solid var(--lx-line);
  border-radius: var(--lx-radius); padding: 14px 16px; margin-bottom: 12px;
}
.lx-card h2 { margin: 0 0 8px; font-size: 15px; color: var(--lx-muted); font-weight: 600; text-transform: uppercase; letter-spacing: 0.6px; }
.lx-row {
  display: flex; align-items: center; gap: 10px; padding: 10px 0;
  border-bottom: 1px solid var(--lx-line);
}
.lx-row:last-child { border-bottom: 0; }
.lx-row .lx-grow { flex: 1; min-width: 0; }
.lx-row .lx-title { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lx-row .lx-sub { display: block; color: var(--lx-muted); font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lx-muted { color: var(--lx-muted); }
.lx-empty { color: var(--lx-muted); text-align: center; padding: 40px 0; }
input[type=text], input[type=search], input[type=number], input[type=url], select, textarea {
  width: 100%; padding: 10px 12px; border-radius: 10px; font: inherit;
  background: var(--lx-panel-2); color: var(--lx-text); border: 1px solid var(--lx-line);
  outline: none;
}
input:focus, select:focus, textarea:focus { border-color: var(--lx-accent); }
button, .lx-btn {
  font: inherit; font-weight: 600; padding: 9px 14px; border-radius: 10px; cursor: pointer;
  border: 1px solid var(--lx-line); background: var(--lx-panel-2); color: var(--lx-text);
}
button.lx-primary, .lx-btn.lx-primary { background: var(--lx-accent); border-color: var(--lx-accent); color: #111; }
button.lx-danger, .lx-btn.lx-danger { color: var(--lx-danger); }
button.lx-small { padding: 5px 10px; font-size: 13px; }
button:active { transform: translateY(1px); }
.lx-tag {
  display: inline-block; font-size: 12px; padding: 1px 8px; border-radius: 999px;
  background: var(--lx-panel-2); border: 1px solid var(--lx-line); color: var(--lx-muted); margin-right: 4px;
}
.lx-tag.lx-on { color: var(--lx-accent-2); border-color: var(--lx-accent); }
.lx-bar { height: 6px; border-radius: 3px; background: var(--lx-panel-2); overflow: hidden; }
.lx-bar > i { display: block; height: 100%; background: var(--lx-accent); transition: width .3s; }
table.lx-table { width: 100%; border-collapse: collapse; }
table.lx-table th, table.lx-table td { text-align: left; padding: 8px 6px; border-bottom: 1px solid var(--lx-line); vertical-align: top; }
table.lx-table th { color: var(--lx-muted); font-weight: 600; font-size: 13px; }
.lx-toolbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.lx-toolbar > * { flex: 1 1 auto; }
.lx-toolbar > button { flex: 0 0 auto; }
@media (min-width: 720px) {
  body { font-size: 14px; }
  .lx-wrap { padding: 24px; }
}
]]

-- 完整页面骨架
function M.render(opts)
    opts = opts or {}
    local title = M.escape(opts.title or "LemurX")
    local parts = {
        "<!DOCTYPE html><html lang=\"", M.escape(opts.lang or "en"), "\"><head><meta charset=\"utf-8\">",
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
        "<title>", title, "</title>",
        "<style>", M.stylesheet, "\n", opts.style or "", "</style>",
        opts.head or "",
        "</head><body>",
    }
    if opts.header ~= false then
        parts[#parts + 1] = "<header class=\"lx-top\"><div class=\"lx-x\">X</div><h1>"
        parts[#parts + 1] = M.escape(opts.heading or opts.title or "")
        parts[#parts + 1] = "</h1><div class=\"lx-spacer\"></div>"
        parts[#parts + 1] = opts.header_extra or ""
        parts[#parts + 1] = "</header>"
    end
    parts[#parts + 1] = "<main class=\"lx-wrap\">"
    parts[#parts + 1] = opts.body or ""
    parts[#parts + 1] = "</main>"
    if opts.script then
        parts[#parts + 1] = "<script>" .. opts.script .. "</script>"
    end
    parts[#parts + 1] = "</body></html>"
    return table.concat(parts)
end

local function not_found(meta)
    local body = table.concat({
        "<div class=\"lx-card\"><h2>Not found</h2>",
        "<p>No chrome handler is registered for <code>", M.escape(meta.uri), "</code>.</p>",
        "<p class=\"lx-muted\">Known pages:</p><p>",
    })
    local names = M.available_handlers()
    local links = {}
    for _, n in ipairs(names) do
        links[#links + 1] = ("<a class=\"lx-tag\" href=\"luakit://%s/\">%s</a>"):format(M.escape(n), M.escape(n))
    end
    body = body .. table.concat(links, " ") .. "</p></div>"
    return M.render({ title = "Page not found", heading = "luakit://" .. meta.page, body = body })
end

local function error_html(meta, err)
    return M.render({
        title = "Chrome page error",
        heading = "luakit://" .. meta.page,
        body = "<div class=\"lx-card\"><h2>Handler error</h2><pre style=\"white-space:pre-wrap\">"
            .. M.escape(err) .. "</pre></div>",
    })
end

-- ====================================================================
-- 导出函数桥（浏览器进程侧）
-- ====================================================================
local wm = nil
local function channel()
    if wm ~= nil then return wm end
    if not rawget(_G, "require_web_module") then wm = false return wm end
    local ok, ch = pcall(require_web_module, "chrome_wm")
    if not ok then
        msg.warn("chrome: chrome_wm not available (%s); exports disabled", tostring(ch))
        wm = false
        return wm
    end
    wm = ch

    local function export_names(name)
        local page = pages[name]
        local list = {}
        if page and page.exports then
            for fname in pairs(page.exports) do list[#list + 1] = fname end
            table.sort(list)
        end
        return list
    end

    local function view_by_id(id)
        local v = views[id]
        if v and v.is_alive then return v end
        if __lk and __lk.webview_for_tab then
            local ok2, found = pcall(__lk.webview_for_tab, id, false)
            if ok2 and found then return found end
        end
        return nil
    end

    -- 渲染进程刷新 / 页面创建时问：这个页面导出了什么
    ch:add_signal("query", function(_, page_id, name)
        local v = view_by_id(page_id)
        if v then ch:emit_signal(v, "exports", name, export_names(name))
        else ch:emit_signal("exports", name, export_names(name)) end
    end)
    ch:add_signal("hello", function()
        for name in pairs(pages) do
            ch:emit_signal("exports", name, export_names(name))
        end
    end)
    -- 页面 JS 调了导出的函数
    ch:add_signal("call", function(_, page_id, name, fname, call_id, argc, args)
        local page = pages[name]
        local fn = page and page.exports and page.exports[fname]
        local v = view_by_id(page_id)
        local function reply(ok, value)
            if v then ch:emit_signal(v, "result", call_id, ok, value)
            else ch:emit_signal("result", call_id, ok, value) end
        end
        if not fn then
            reply(false, ("luakit://%s/ has no export named %s"):format(tostring(name), tostring(fname)))
            return
        end
        args = type(args) == "table" and args or {}
        argc = tonumber(argc) or #args
        local ok, ret = xpcall(fn, debug.traceback, v, table.unpack(args, 1, argc))
        if ok then
            reply(true, ret)
        else
            msg.warn("chrome export %s.%s failed: %s", tostring(name), tostring(fname), tostring(ret))
            reply(false, tostring(ret))
        end
    end)
    return wm
end

local function announce(name)
    local ch = channel()
    if not ch then return end
    local page = pages[name]
    local list = {}
    if page and page.exports then
        for fname in pairs(page.exports) do list[#list + 1] = fname end
        table.sort(list)
    end
    ch:emit_signal("exports", name, list)
end

-- ====================================================================
-- 请求分发
-- ====================================================================
local function serve(view, uri, request)
    local meta = M.parse_uri(uri)
    if not meta then
        request:finish(M.render({ title = "Bad URI", body = "<p>Malformed luakit:// URI.</p>" }), "text/html")
        return
    end
    local page = pages[meta.page]
    if not page then
        msg.verbose("chrome: no handler for luakit://%s/", meta.page)
        request:finish(not_found(meta), "text/html")
        return
    end
    meta.view = view
    meta.request = request
    local ok, html, mime = xpcall(page.func, debug.traceback, view, meta)
    if not ok then
        msg.warn("chrome: luakit://%s/ handler error: %s", meta.page, tostring(html))
        request:finish(error_html(meta, html), "text/html")
        return
    end
    if type(html) == "string" then
        request:finish(html, mime or "text/html")
    elseif html == false or html == nil then
        -- 处理器自己异步 finish 或明确拒绝
        if not request.finished and html == false then
            request:finish(not_found(meta), "text/html")
        end
    else
        request:finish(tostring(html), mime or "text/plain")
    end
end

local first_visual_done = setmetatable({}, { __mode = "k" }) -- view -> uri

local function on_load_status(view, status, uri)
    if status ~= "first-visual" and status ~= "finished" then return end
    uri = uri or view.uri or ""
    local meta = M.parse_uri(uri)
    if not meta then return end
    local page = pages[meta.page]
    if not page or not page.first_visual then return end
    -- 同一次导航只触发一次（first-visual 与 finished 都可能到）
    if first_visual_done[view] == uri then return end
    first_visual_done[view] = uri
    meta.view = view
    local ok, err = xpcall(page.first_visual, debug.traceback, view, meta)
    if not ok then msg.warn("chrome: on_first_visual for luakit://%s/ failed: %s", meta.page, tostring(err)) end
end

local function hook(view)
    if hooked[view] then return end
    hooked[view] = true
    views[view.id] = view
    view:add_signal("scheme-request::luakit", function(v, uri, request) serve(v, uri, request) end)
    view:add_signal("load-status", function(v, status, uri)
        if status == "provisional" or status == "committed" then first_visual_done[v] = nil end
        on_load_status(v, status, uri)
    end)
    view:add_signal("destroy", function(v)
        for id, x in pairs(views) do if x == v then views[id] = nil end end
    end)
end

local function ensure_scheme()
    if scheme_registered then return end
    scheme_registered = true
    local ok, err = pcall(luakit.register_scheme, "luakit")
    if not ok then msg.warn("chrome: register_scheme(luakit): %s", tostring(err)) end
end

local function install_hooks()
    ensure_scheme()
    -- 首选 webview 模块的 init 信号；同时兜底监听 widget 的 create（原生 UI 自动包装的
    -- 标签页也会经过它），hooked 表保证每个 view 只挂一次。
    local ok, webview = pcall(require, "webview")
    if ok and type(webview) == "table" and webview.add_signal then
        webview.add_signal("init", function(view) hook(view) end)
    else
        msg.verbose("chrome: webview module not loaded yet, relying on widget.create")
    end
    widget.add_signal("create", function(w)
        if w.type == "webview" then hook(w) end
    end)
    -- 已经存在的 webview 也挂上
    if __lk and __lk.webviews then
        for _, v in pairs(__lk.webviews) do
            if v.is_alive then hook(v) end
        end
    end
end

-- ====================================================================
-- 公共 API
-- ====================================================================
function M.available_handlers()
    local names = {}
    for name in pairs(pages) do names[#names + 1] = name end
    table.sort(names)
    return names
end

function M.add(name, func, on_first_visual, exports)
    if type(name) ~= "string" or not name:match("^[%w_%-]+$") then
        error("chrome.add: page name must be a plain identifier (got " .. tostring(name) .. ")", 2)
    end
    if type(func) ~= "function" then error("chrome.add: handler must be a function", 2) end
    if on_first_visual ~= nil and type(on_first_visual) ~= "function" then
        error("chrome.add: on_first_visual must be a function", 2)
    end
    if exports ~= nil and type(exports) ~= "table" then error("chrome.add: exports must be a table", 2) end
    for k, v in pairs(exports or {}) do
        if type(k) ~= "string" or type(v) ~= "function" then
            error("chrome.add: exports must map names to functions", 2)
        end
    end
    pages[name] = { func = func, first_visual = on_first_visual, exports = exports }
    if exports and next(exports) then announce(name) end
    M.emit_signal("page-added", name)
end

function M.remove(name)
    if pages[name] then
        pages[name] = nil
        announce(name)
        M.emit_signal("page-removed", name)
    end
end

function M.hook_view(view) hook(view) end

function M.page_uri(name, path, params)
    local uri = "luakit://" .. name .. "/" .. (path or "")
    if type(params) == "table" and next(params) then
        local q = {}
        for k, v in pairs(params) do
            q[#q + 1] = luakit.uri_encode(tostring(k)) .. "=" .. luakit.uri_encode(tostring(v))
        end
        table.sort(q)
        uri = uri .. "?" .. table.concat(q, "&")
    end
    return uri
end

-- 模块级信号（page-added / page-removed）
do
    local ok, lousy = pcall(require, "lousy")
    if ok and lousy and lousy.signal and lousy.signal.setup then
        lousy.signal.setup(M, true)
    else
        local list = {}
        M.add_signal = function(n, fn) list[n] = list[n] or {}; table.insert(list[n], fn) end
        M.remove_signal = function(n, fn)
            for i, f in ipairs(list[n] or {}) do if f == fn then table.remove(list[n], i) return fn end end
        end
        M.remove_signals = function(n) list[n] = nil end
        M.emit_signal = function(n, ...)
            for _, fn in ipairs(list[n] or {}) do
                local r = table.pack(fn(...))
                if r.n > 0 and r[1] ~= nil then return table.unpack(r, 1, r.n) end
            end
        end
    end
end

install_hooks()

return M
