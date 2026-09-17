-- LemurX · luakit-compatible library · downloads_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "downloads_chrome" module API. No luakit code is used.
--
-- luakit://downloads/ 实时下载列表：页面 JS 每秒调用导出的 downloads_list() 刷新进度条，
-- 打开 / 取消 / 重试 / 移除 / 清空也都走导出函数。首屏在浏览器进程侧渲染。
-- 命令 :downloads；按键 gd / gD（当前标签 / 新标签打开）

local chrome = require("chrome")
local downloads = require("downloads")
local modes = require("modes")

local M = {}

M.chrome_page = "luakit://downloads/"
M.stylesheet = [[
.dl { flex-direction: column; align-items: stretch; gap: 6px; }
.dl .head { display: flex; gap: 10px; align-items: baseline; }
.dl .name { font-weight: 600; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dl .state { font-size: 12px; color: var(--lx-muted); flex: none; }
.dl .state.finished { color: var(--lx-ok); }
.dl .state.failed, .dl .state.cancelled { color: var(--lx-danger); }
.dl .meta { font-size: 12px; color: var(--lx-muted); display: flex; gap: 12px; flex-wrap: wrap; }
.dl .btns { display: flex; gap: 6px; flex-wrap: wrap; }
]]

local esc = chrome.escape

local function human(bytes)
    bytes = tonumber(bytes) or 0
    local units = { "B", "KB", "MB", "GB", "TB" }
    local i = 1
    while bytes >= 1024 and i < #units do bytes = bytes / 1024; i = i + 1 end
    if i == 1 then return ("%d %s"):format(bytes, units[i]) end
    return ("%.1f %s"):format(bytes, units[i])
end

local function snapshots()
    local out = {}
    for _, d in ipairs(downloads.get_all()) do
        out[#out + 1] = downloads.snapshot(d)
    end
    return out
end

local function item_html(s)
    local pct = math.floor((tonumber(s.progress) or 0) * 100 + 0.5)
    local running = s.status == "started" or s.status == "created"
    local meta = {}
    if s.total_size and s.total_size > 0 then
        meta[#meta + 1] = human(s.current_size) .. " / " .. human(s.total_size)
    elseif s.current_size and s.current_size > 0 then
        meta[#meta + 1] = human(s.current_size)
    end
    if running and s.speed and s.speed > 0 then meta[#meta + 1] = human(s.speed) .. "/s" end
    if s.destination then meta[#meta + 1] = esc(s.destination) end
    if s.error then meta[#meta + 1] = "<span style=\"color:var(--lx-danger)\">" .. esc(s.error) .. "</span>" end
    local btns = {}
    if s.status == "finished" then
        btns[#btns + 1] = ("<button class=\"lx-small lx-primary\" onclick=\"dlOpen(%d)\">Open</button>"):format(s.id)
    end
    if running then
        btns[#btns + 1] = ("<button class=\"lx-small\" onclick=\"dlCancel(%d)\">Cancel</button>"):format(s.id)
    else
        btns[#btns + 1] = ("<button class=\"lx-small\" onclick=\"dlRestart(%d)\">Retry</button>"):format(s.id)
        btns[#btns + 1] = ("<button class=\"lx-small lx-danger\" onclick=\"dlRemove(%d)\">Remove</button>"):format(s.id)
    end
    return table.concat({
        "<div class=\"lx-row dl\" data-id=\"", tostring(s.id), "\">",
        "<div class=\"head\"><span class=\"name\" title=\"", esc(s.uri), "\">", esc(s.filename or s.uri), "</span>",
        "<span class=\"state ", esc(s.status or ""), "\">", esc(s.status or ""), running and (" · " .. pct .. "%") or "", "</span></div>",
        running and ("<div class=\"lx-bar\"><i style=\"width:" .. pct .. "%\"></i></div>") or "",
        "<div class=\"meta\">", table.concat(meta, "<span>·</span>"), "</div>",
        "<div class=\"btns\">", table.concat(btns), "</div>",
        "</div>",
    })
end

local function list_html(list)
    if #list == 0 then return "<div class=\"lx-empty\">No downloads yet.</div>" end
    local out = {}
    for i = #list, 1, -1 do out[#out + 1] = item_html(list[i]) end
    return table.concat(out)
end

local script = [[
function has(n) { return typeof window[n] === 'function'; }
function refresh() {
  if (!has('downloads_render')) return;
  downloads_render().then(function (html) {
    var el = document.getElementById('list'); if (el && html) el.innerHTML = html;
  });
}
function dlOpen(id) { if (has('downloads_open')) downloads_open(id); }
function dlCancel(id) { if (has('downloads_cancel')) downloads_cancel(id).then(refresh); }
function dlRestart(id) { if (has('downloads_restart')) downloads_restart(id).then(refresh); }
function dlRemove(id) { if (has('downloads_remove')) downloads_remove(id).then(refresh); }
function dlClear() { if (has('downloads_clear')) downloads_clear().then(refresh); }
setInterval(refresh, 1000);
]]

local function page()
    local body = table.concat({
        "<div class=\"lx-toolbar\"><span class=\"lx-muted\">Downloads go to <code>", esc(downloads.default_dir), "</code></span>",
        "<button class=\"lx-danger\" onclick=\"dlClear()\">Clear finished</button></div>",
        "<div class=\"lx-card\" id=\"list\">", list_html(snapshots()), "</div>",
    })
    return chrome.render({ title = "Downloads", heading = "Downloads", style = M.stylesheet, body = body, script = script })
end

chrome.add("downloads", page, nil, {
    downloads_list = function() return snapshots() end,
    downloads_render = function() return list_html(snapshots()) end,
    downloads_open = function(view, id)
        local w = nil
        pcall(function() w = require("webview").window(view) end)
        return downloads.open(tonumber(id), w)
    end,
    downloads_cancel = function(_, id) return downloads.cancel(tonumber(id)) end,
    downloads_restart = function(_, id)
        local d = downloads.restart(tonumber(id))
        return d ~= nil
    end,
    downloads_remove = function(_, id) return downloads.remove(tonumber(id)) end,
    downloads_clear = function() downloads.clear() return true end,
})

modes.add_cmds({
    { ":downloads", "Open the downloads page.", function(w) w:new_tab(M.chrome_page) end },
})

modes.add_binds("normal", {
    { "gd", "Open the downloads page in the current tab.", function(w) w:navigate(M.chrome_page) end },
    { "gD", "Open the downloads page in a new tab.", function(w) w:new_tab(M.chrome_page) end },
})

return M
