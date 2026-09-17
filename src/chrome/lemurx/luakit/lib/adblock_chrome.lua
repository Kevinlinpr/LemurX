-- LemurX · luakit-compatible library · adblock_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "adblock_chrome" module API. No luakit code is used.
--
-- luakit://adblock/ 管理页：显示每个过滤列表的启用状态与规则计数，提供
-- 总开关、按列表开关、重新加载，以及"按 URL 添加列表"（用 lemurx.http.fetch
-- 下载到 adblock 数据目录后立即加载）。
-- 绑定：ga（打开）、gA（新标签打开）；命令 :adblock

local lousy = require("lousy")
local chrome = require("chrome")
local modes = require("modes")
local window = require("window")
local adblock = require("adblock")

local _M = {}

local PAGE = "adblock"
local PAGE_URI = "luakit://" .. PAGE .. "/"

local escape = lousy.util.escape or function(s)
    return (tostring(s):gsub("[&<>\"']", { ["&"] = "&amp;", ["<"] = "&lt;", [">"] = "&gt;", ['"'] = "&quot;", ["'"] = "&#39;" }))
end

_M.stylesheet = [[
:root { color-scheme: light dark; }
body.lx-adblock { margin: 0; padding: 0 0 3rem; }
.lx-adblock header { display: flex; align-items: center; gap: 1rem; padding: 1.2rem 1.5rem;
  border-bottom: 1px solid rgba(127,127,127,.35); }
.lx-adblock header h1 { font-size: 1.3rem; margin: 0; flex: 1; }
.lx-adblock .pill { border-radius: 999px; padding: .2rem .8rem; font-size: .85rem; border: 1px solid currentColor; }
.lx-adblock .pill.on { color: #1b8a3e; }
.lx-adblock .pill.off { color: #b3261e; }
.lx-adblock main { padding: 1rem 1.5rem; max-width: 60rem; }
.lx-adblock ul.lists { list-style: none; margin: 0; padding: 0; }
.lx-adblock li.list { display: grid; grid-template-columns: auto 1fr auto; gap: .8rem; align-items: center;
  padding: .7rem .4rem; border-bottom: 1px dashed rgba(127,127,127,.3); }
.lx-adblock li.list.disabled .name { opacity: .5; text-decoration: line-through; }
.lx-adblock .counts { font-variant-numeric: tabular-nums; font-size: .85rem; opacity: .75; }
.lx-adblock .path { font-size: .75rem; opacity: .6; word-break: break-all; }
.lx-adblock button { cursor: pointer; padding: .35rem .9rem; border-radius: .4rem; border: 1px solid rgba(127,127,127,.5);
  background: transparent; color: inherit; }
.lx-adblock button:hover { background: rgba(127,127,127,.15); }
.lx-adblock form.add { display: flex; gap: .6rem; margin-top: 1.5rem; }
.lx-adblock form.add input { flex: 1; padding: .4rem .6rem; border-radius: .4rem; border: 1px solid rgba(127,127,127,.5);
  background: transparent; color: inherit; }
.lx-adblock .hint { font-size: .8rem; opacity: .65; margin-top: .4rem; }
.lx-adblock .status { margin-top: 1rem; min-height: 1.2rem; font-size: .9rem; }
]]

local script = [[
(function () {
  function $(sel) { return document.querySelector(sel); }
  function say(text) { $('#status').textContent = text || ''; }
  function reload() { location.reload(); }
  document.addEventListener('click', function (ev) {
    var b = ev.target.closest('button[data-act]');
    if (!b) return;
    var act = b.getAttribute('data-act');
    if (act === 'toggle-all') {
      adblock_set_enabled(b.getAttribute('data-on') === '1').then(reload);
    } else if (act === 'toggle-list') {
      adblock_list_set_enabled(b.getAttribute('data-title'), b.getAttribute('data-on') === '1').then(reload);
    } else if (act === 'reload') {
      say('reloading lists…');
      adblock_reload().then(reload);
    }
  });
  $('#add').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var url = $('#url').value.trim();
    if (!url) return;
    say('downloading ' + url + ' …');
    adblock_add_list(url).then(function (r) {
      if (r && r.ok) { say('saved ' + r.path); setTimeout(reload, 600); }
      else say('failed: ' + (r && r.error || 'unknown error'));
    }, function (e) { say('failed: ' + e); });
  });
})();
]]

local function render_list(sub)
    local cls = sub.enabled and "" or " disabled"
    return table.concat({
        '<li class="list', cls, '">',
        '<button data-act="toggle-list" data-title="', escape(sub.title), '" data-on="', sub.enabled and "0" or "1", '">',
        sub.enabled and "disable" or "enable", '</button>',
        '<div><div class="name">', sub.index, '. ', escape(sub.title), '</div>',
        '<div class="path">', escape(sub.path or ""), '</div></div>',
        '<div class="counts">', sub.black, ' block · ', sub.white, ' allow · ', sub.ignored, ' skipped</div>',
        '</li>',
    })
end

local function page_html(view, meta)
    local lists = adblock.list_subscriptions()
    local rows = {}
    for _, sub in ipairs(lists) do rows[#rows + 1] = render_list(sub) end
    if #rows == 0 then
        rows[1] = '<li class="list"><span></span><em>No filter lists yet. Put *.txt files into '
            .. escape(adblock.dir) .. ' or add one below.</em><span></span></li>'
    end
    local on = adblock.enabled
    return table.concat({
        "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Adblock</title>",
        "<style>", chrome.stylesheet or "", _M.stylesheet, "</style></head>",
        "<body class='lx-adblock'><header><h1>Request filtering</h1>",
        "<span class='pill ", on and "on" or "off", "'>", on and "active" or "paused", "</span>",
        "<button data-act='toggle-all' data-on='", on and "0" or "1", "'>", on and "pause" or "activate", "</button>",
        "<button data-act='reload'>reload lists</button></header>",
        "<main><ul class='lists'>", table.concat(rows), "</ul>",
        "<form class='add' id='add'><input id='url' type='url' placeholder='https://…/filterlist.txt' required>",
        "<button type='submit'>add list</button></form>",
        "<div class='hint'>Lists are stored in ", escape(adblock.dir), ". Element-hiding rules are skipped.</div>",
        "<div class='status' id='status'></div></main>",
        "<script>", script, "</script></body></html>",
    })
end

-- ---------------------------------------------------------------------------
-- 下载并保存一个过滤列表
-- ---------------------------------------------------------------------------
local function filename_for(url, body)
    local title = body and body:match("^%[?[^\n]*\n?!%s*Title:%s*([^\r\n]+)")
    if not title then title = body and body:match("!%s*Title:%s*([^\r\n]+)") end
    local name = title or url:match("([^/?#]+)%.txt") or url:match("([^/?#]+)$") or "list"
    name = name:gsub("[^%w%-%._]+", "_"):gsub("^_+", ""):gsub("_+$", "")
    if name == "" then name = "list" end
    return name .. ".txt"
end

local function extract_body(...)
    local a, b = ...
    if type(a) == "table" then
        if a.status and (a.status < 200 or a.status >= 300) then
            return nil, "HTTP " .. tostring(a.status)
        end
        return a.body or a.text or a.data, a.error
    end
    if type(a) == "string" then return a, nil end
    if a == nil and b ~= nil then return nil, tostring(b) end
    return nil, "empty response"
end

function _M.add_list(url, cb)
    cb = cb or function() end
    if type(url) ~= "string" or not url:match("^https?://") then
        cb({ ok = false, error = "not an http(s) URL" })
        return
    end
    if not (lemurx and lemurx.http and lemurx.http.fetch) then
        cb({ ok = false, error = "lemurx.http.fetch unavailable" })
        return
    end
    local ok, err = pcall(lemurx.http.fetch, url, {}, function(...)
        local body, ferr = extract_body(...)
        if not body then
            cb({ ok = false, error = ferr or "download failed" })
            return
        end
        pcall(lfs.mkdir, adblock.dir)
        local path = adblock.dir .. "/" .. filename_for(url, body)
        local f, werr = io.open(path, "wb")
        if not f then
            cb({ ok = false, error = tostring(werr) })
            return
        end
        f:write(body)
        f:close()
        adblock.load(true, path)
        cb({ ok = true, path = path })
    end)
    if not ok then cb({ ok = false, error = tostring(err) }) end
end

-- ---------------------------------------------------------------------------
-- 页面注册
-- ---------------------------------------------------------------------------
local exports = {
    adblock_set_enabled = function(_, on)
        adblock.enabled = on and true or false
        return true
    end,
    adblock_list_set_enabled = function(_, title, on)
        local ok, err = adblock.list_set_enabled(title, on and true or false)
        return ok and true or false, err
    end,
    adblock_reload = function()
        adblock.load(true)
        return true
    end,
    adblock_add_list = function(_, url)
        -- chrome 导出函数一般是同步返回；这里下载是异步的，返回一个"已开始"结果，
        -- 完成后刷新页面由 rules-updated 处理
        local result = { ok = false, error = "pending" }
        _M.add_list(url, function(r) result = r end)
        if result.error == "pending" then
            return { ok = true, path = "(downloading…)" }
        end
        return result
    end,
    adblock_status = function()
        return { enabled = adblock.enabled, lists = adblock.list_subscriptions() }
    end,
}

chrome.add(PAGE, page_html, nil, exports)

-- 规则变化时刷新已打开的管理页
adblock.add_signal("rules-updated", function()
    for _, w in pairs(window.bywidget or {}) do
        local ok, n = pcall(function() return w.tabs:count() end)
        if ok and n then
            for i = 1, n do
                local view = w.tabs:atindex(i)
                if view and (view.uri or ""):sub(1, #PAGE_URI) == PAGE_URI then
                    pcall(view.reload, view)
                end
            end
        end
    end
end)

modes.add_binds("normal", {
    { "ga", "Open the adblock management page.", function (w) w:navigate(PAGE_URI) end },
    { "gA", "Open the adblock management page in a new tab.", function (w) w:new_tab(PAGE_URI) end },
})

modes.add_cmds({
    { ":adblock", "Open the adblock management page.", function (w) w:navigate(PAGE_URI) end },
})

_M.page_uri = PAGE_URI
return _M
