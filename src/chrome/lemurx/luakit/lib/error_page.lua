-- LemurX · luakit-compatible library · error_page
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "error_page" module API. No luakit code is used.
--
-- 统一风格的错误页：加载失败、证书不可信、渲染进程崩溃，以及供其他模块调用的
--   error_page.show_error_page(view, { heading=, content=, buttons={{label=, callback=fn(view)}}, style= })
-- style ∈ "normal" | "security" | "crash"。页面用 view:load_string 直接写进标签页，
-- 基址保持为出错的 URI，这样地址栏和历史记录仍指向原地址。
-- 按钮回调通过 error_page_wm.lua（渲染进程）→ ipc_channel("error_page_wm") 回到这里；
-- "Try again" 这类只需要重新导航的按钮同时给了纯 JS 的兜底（location.replace）。

local M = {}

local escapes = { ["&"] = "&amp;", ["<"] = "&lt;", [">"] = "&gt;", ['"'] = "&quot;", ["'"] = "&#39;" }
local function esc(s) return (tostring(s == nil and "" or s):gsub("[&<>\"']", escapes)) end

local function js_str(s)
    return '"' .. tostring(s):gsub('[%c"\\]', function(c)
        local map = { ['"'] = '\\"', ["\\"] = "\\\\", ["\n"] = "\\n", ["\r"] = "\\r" }
        return map[c] or string.format("\\u%04x", c:byte())
    end) .. '"'
end

-- ====================================================================
-- 可定制的模板 / 样式（公开字段，用户 rc.lua 可以覆盖）
-- ====================================================================
M.cert_db_path = nil   -- 兼容字段：证书白名单由 luakit.allow_certificate 记录，这里不再单独落盘

M.style = [[
:root { color-scheme: dark; }
* { box-sizing: border-box; }
html, body { margin: 0; height: 100%; }
body {
  display: flex; align-items: center; justify-content: center;
  background: #0f1115; color: #e6e8ee;
  font: 15px/1.5 -apple-system, "Roboto", "Segoe UI", "Noto Sans", system-ui, sans-serif;
  padding: 24px;
}
.ep {
  width: 100%; max-width: 560px;
  background: #171a21; border: 1px solid #2a2f3a; border-radius: 16px;
  padding: 28px 24px 24px; position: relative; overflow: hidden;
}
.ep::before {
  content: ""; position: absolute; left: 0; top: 0; right: 0; height: 4px;
  background: linear-gradient(90deg, var(--ep-accent, #ff7a1a), transparent);
}
.ep .mark {
  width: 48px; height: 48px; border-radius: 14px; display: grid; place-items: center;
  font-weight: 800; font-size: 24px; color: #fff; margin-bottom: 18px;
  background: var(--ep-accent, #ff7a1a);
}
.ep h1 { font-size: 20px; margin: 0 0 10px; font-weight: 650; }
.ep .content { color: #b7bdcc; word-break: break-word; }
.ep .content p { margin: 0 0 10px; }
.ep .uri {
  font-family: "Roboto Mono", Menlo, monospace; font-size: 13px; color: #8b93a5;
  background: #1e222b; border-radius: 8px; padding: 8px 10px; margin: 12px 0; word-break: break-all;
}
.ep .actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 20px; }
.ep button {
  font: inherit; font-weight: 600; padding: 10px 16px; border-radius: 10px; cursor: pointer;
  border: 1px solid #2a2f3a; background: #1e222b; color: #e6e8ee;
}
.ep button.primary { background: var(--ep-accent, #ff7a1a); border-color: transparent; color: #111; }
.ep details { margin-top: 14px; color: #8b93a5; font-size: 13px; }
.ep pre { white-space: pre-wrap; word-break: break-all; font-size: 12px; }
]]

M.cert_style = [[
body { --ep-accent: #ff5d5d; }
.ep .mark { background: #ff5d5d; }
]]

M.crash_style = [[
body { --ep-accent: #9b7bff; }
.ep .mark { background: #9b7bff; }
]]

-- 占位符：{style} {heading} {content} {buttons} {script} {mark} {title}
M.html_template = [[<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title><style>{style}</style></head>
<body><div class="ep"><div class="mark">{mark}</div><h1>{heading}</h1>
<div class="content">{content}</div>
<div class="actions">{buttons}</div></div>
<script>{script}</script></body></html>]]

local marks = { normal = "!", security = "\u{26A0}", crash = "\u{2716}" }

-- ====================================================================
-- 与渲染进程的按钮回调通道
-- ====================================================================
local shown = {}          -- token -> { view=, buttons= }
local next_token = 0
local wm = nil

local function view_id_of(view)
    local ok, id = pcall(function() return view.id end)
    return ok and id or nil
end

local function channel()
    if wm ~= nil then return wm end
    if not rawget(_G, "require_web_module") then wm = false return wm end
    local ok, ch = pcall(require_web_module, "error_page_wm")
    if not ok then wm = false return wm end
    wm = ch
    ch:add_signal("action", function(_, _page_id, call_id, token, index)
        local rec = shown[tonumber(token) or token]
        local view = rec and rec.view
        local function reply(ok2, err)
            if view and view.is_alive then ch:emit_signal(view, "action-result", call_id, ok2, err)
            else ch:emit_signal("action-result", call_id, ok2, err) end
        end
        if not rec then return reply(false, "stale error page") end
        local btn = rec.buttons[tonumber(index) or 0]
        if not btn then return reply(false, "no such button") end
        if not (view and view.is_alive) then return reply(false, "view gone") end
        if type(btn.callback) == "function" then
            local ok2, err = xpcall(btn.callback, debug.traceback, view)
            if not ok2 then
                msg.warn("error_page button '%s' failed: %s", tostring(btn.label), tostring(err))
                return reply(false, tostring(err))
            end
        end
        reply(true)
    end)
    return wm
end

local function arm(view, token, uri)
    local ch = channel()
    if not ch then return end
    ch:emit_signal(view, "arm", token, uri)
end

-- ====================================================================
-- 渲染
-- ====================================================================
local function buttons_html(buttons, token)
    local out = {}
    for i, b in ipairs(buttons or {}) do
        local cls = (i == 1) and "primary" or ""
        local onclick = ("lxAction(%d, %s)"):format(i, b.href and js_str(b.href) or "null")
        out[#out + 1] = ("<button class=\"%s\" onclick=\"%s\">%s</button>"):format(cls, esc(onclick), esc(b.label or "OK"))
    end
    local _ = token
    return table.concat(out)
end

local function content_html(info)
    local parts = {}
    if info.content then parts[#parts + 1] = info.content end
    if info.uri then parts[#parts + 1] = "<div class=\"uri\">" .. esc(info.uri) .. "</div>" end
    if info.details then
        parts[#parts + 1] = "<details><summary>Details</summary><pre>" .. esc(info.details) .. "</pre></details>"
    end
    return table.concat(parts)
end

local function build_html(info, token)
    local style = M.style
    if info.style == "security" then style = style .. "\n" .. M.cert_style
    elseif info.style == "crash" then style = style .. "\n" .. M.crash_style end
    if info.extra_style then style = style .. "\n" .. info.extra_style end
    local script = table.concat({
        "var LX_TOKEN=", tostring(token), ";",
        "function lxAction(i, href){",
        "  var f = window.__lemurErrorPageAction;",
        "  if (typeof f === 'function') { f(LX_TOKEN, i).catch(function(){ if (href) location.replace(href); }); }",
        "  else if (href) { location.replace(href); }",
        "}",
    })
    local map = {
        title = esc(info.title or info.heading or "Error"),
        style = style,
        mark = marks[info.style or "normal"] or marks.normal,
        heading = esc(info.heading or "Something went wrong"),
        content = content_html(info),
        buttons = buttons_html(info.buttons, token),
        script = script,
    }
    return (M.html_template:gsub("{(%w+)}", function(k) return map[k] or "" end))
end

-- 公开：把 view 的内容替换成错误页
function M.show_error_page(view, info)
    if type(info) ~= "table" then error("error_page.show_error_page expects an info table", 2) end
    next_token = next_token + 1
    local token = next_token
    local uri = info.uri or (view and view.uri) or "about:blank"
    -- 只保留一个活着的记录 / view
    for t, rec in pairs(shown) do
        if rec.view == view or not (rec.view and rec.view.is_alive) then shown[t] = nil end
    end
    shown[token] = { view = view, buttons = info.buttons or {}, uri = uri }
    local html = build_html(info, token)
    local base = uri
    if not base:match("^%a[%w+.-]*:") then base = "about:blank" end
    view:load_string(html, base)
    arm(view, token, base)
    M.emit_signal("shown", view, info)
    return html
end

-- ====================================================================
-- 默认接入：加载失败 / 证书 / 崩溃
-- ====================================================================
local IGNORED_ERRORS = { "cancel", "abort", "ERR_ABORTED", "ERR_BLOCKED_BY_CLIENT", "frame load interrupted" }

local function ignorable(err)
    if type(err) ~= "string" then return false end
    for _, pat in ipairs(IGNORED_ERRORS) do
        if err:lower():find(pat:lower(), 1, true) then return true end
    end
    return false
end

local function is_cert_error(err)
    if type(err) ~= "string" then return false end
    local l = err:lower()
    return l:find("cert", 1, true) ~= nil or l:find("ssl", 1, true) ~= nil or l:find("tls", 1, true) ~= nil
end

local function try_again(uri)
    return { label = "Try again", href = uri, callback = function(v) v.uri = uri end }
end

local function go_back()
    return { label = "Go back", callback = function(v)
        if v:can_go_back() then v:go_back() else v.uri = "luakit://newtab/" end
    end }
end

local recent = setmetatable({}, { __mode = "k" }) -- view -> { uri=, t= }

local function on_failed(view, uri, err)
    uri = uri or view.uri or ""
    if uri:match("^luakit://") or uri == "" then return end
    if ignorable(err) then return end
    -- 我们自己写进去的错误页再次报失败：不要循环
    local r = recent[view]
    if r and r.uri == uri and (luakit.time() - r.t) < 0.5 then return end
    recent[view] = { uri = uri, t = luakit.time() }

    local trusted = true
    pcall(function() trusted = view:ssl_trusted() end)
    if trusted == false or is_cert_error(err) then
        M.show_error_page(view, {
            style = "security",
            heading = "This connection is not private",
            content = "<p>The site's certificate could not be verified. Someone may be intercepting "
                .. "your connection, or the site may be misconfigured.</p>",
            uri = uri,
            details = err,
            buttons = {
                go_back(),
                { label = "Ignore certificate", callback = function(v)
                    pcall(v.allow_certificate, v)
                    v.uri = uri
                end },
            },
        })
        return
    end
    M.show_error_page(view, {
        style = "normal",
        heading = "Page could not be loaded",
        content = "<p>" .. esc(err or "The request failed.") .. "</p>",
        uri = uri,
        buttons = { try_again(uri), go_back() },
    })
end

local function on_crashed(view)
    local uri = view.uri or ""
    M.show_error_page(view, {
        style = "crash",
        heading = "This tab crashed",
        content = "<p>The renderer for this page stopped unexpectedly.</p>",
        uri = uri,
        buttons = { { label = "Reload", href = uri, callback = function(v) v.uri = uri end }, go_back() },
    })
end

local hooked = setmetatable({}, { __mode = "k" })
local function hook(view)
    if hooked[view] then return end
    hooked[view] = true
    view:add_signal("load-status", function(v, status, uri, err)
        if status == "failed" then on_failed(v, uri, err) end
    end)
    view:add_signal("crashed", function(v) on_crashed(v) end)
end

M.hook_view = hook

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
