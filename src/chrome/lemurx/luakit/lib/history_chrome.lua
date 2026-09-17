-- LemurX · luakit-compatible library · history_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "history_chrome" module API. No luakit code is used.
--
-- luakit://history/ 页面：搜索框、按天分组、逐条删除、分页。列表在浏览器进程侧渲染
-- （?q=词&page=N），因此没有渲染进程 Lua 桥时也能用；删除 / 清空按钮走导出函数。
-- 命令 :history [词]

local chrome = require("chrome")
local history = require("history")
local modes = require("modes")

local M = {}

M.chrome_page = "luakit://history/"
M.page_size = 60
M.stylesheet = [[
.day { margin: 18px 0 6px; color: var(--lx-accent-2); font-weight: 600; font-size: 13px; letter-spacing: .5px; }
.hit .lx-title { color: var(--lx-text); }
.hit .when { color: var(--lx-muted); font-size: 12px; flex: none; width: 46px; text-align: right; }
.hit .del { flex: none; opacity: .6; }
.hit .del:hover { opacity: 1; color: var(--lx-danger); }
.pager { display: flex; justify-content: space-between; margin-top: 16px; }
]]

local esc = chrome.escape

local function day_label(ts)
    ts = tonumber(ts) or 0
    local today = os.date("*t")
    local d = os.date("*t", ts)
    local midnight = os.time({ year = today.year, month = today.month, day = today.day, hour = 0 })
    if ts >= midnight then return "Today" end
    if ts >= midnight - 86400 then return "Yesterday" end
    if d.year == today.year then return os.date("%A, %B %d", ts) end
    return os.date("%B %d, %Y", ts)
end

local function rows_html(rows)
    if #rows == 0 then return "<div class=\"lx-empty\">Nothing here yet.</div>" end
    local out, current = {}, nil
    for _, r in ipairs(rows) do
        local label = day_label(r.last_visit)
        if label ~= current then
            current = label
            out[#out + 1] = "<div class=\"day\">" .. esc(label) .. "</div>"
        end
        local title = (r.title and r.title ~= "") and r.title or r.uri
        out[#out + 1] = table.concat({
            "<div class=\"lx-row hit\" data-id=\"", tostring(r.id), "\">",
            "<span class=\"when\">", os.date("%H:%M", tonumber(r.last_visit) or 0), "</span>",
            "<div class=\"lx-grow\"><a class=\"lx-title\" href=\"", esc(r.uri), "\">", esc(title), "</a>",
            "<span class=\"lx-sub\">", esc(r.uri), "</span></div>",
            "<button class=\"lx-small del\" title=\"Remove\" onclick=\"lxDel(", tostring(r.id), ", this)\">&times;</button>",
            "</div>",
        })
    end
    return table.concat(out)
end

local script = [[
function lxDel(id, btn) {
  if (typeof history_delete !== 'function') { alert('Lua bridge unavailable'); return; }
  history_delete([id]).then(function () {
    var row = btn.closest('.hit'); if (row) row.remove();
  });
}
function lxClear() {
  if (!confirm('Delete all browsing history?')) return;
  if (typeof history_clear !== 'function') { alert('Lua bridge unavailable'); return; }
  history_clear().then(function () { location.reload(); });
}
var q = document.getElementById('q');
if (q) { q.addEventListener('keydown', function (e) { if (e.key === 'Escape') { q.value = ''; } }); }
]]

local function page(_, meta)
    local q = meta.params.q or ""
    local pageno = math.max(1, math.floor(tonumber(meta.params.page) or 1))
    local size = M.page_size
    local rows = history.search({ query = q, limit = size + 1, offset = (pageno - 1) * size })
    local has_more = #rows > size
    if has_more then rows[size + 1] = nil end

    local function link(p, text)
        local href = chrome.page_uri("history", "", { q = q, page = p })
        return ("<a class=\"lx-btn\" href=\"%s\">%s</a>"):format(esc(href), text)
    end
    local pager = "<div class=\"pager\">"
        .. (pageno > 1 and link(pageno - 1, "&larr; Newer") or "<span></span>")
        .. (has_more and link(pageno + 1, "Older &rarr;") or "<span></span>")
        .. "</div>"

    local body = table.concat({
        "<form class=\"lx-toolbar\" method=\"get\" action=\"luakit://history/\">",
        "<input id=\"q\" type=\"search\" name=\"q\" placeholder=\"Search history\" value=\"", esc(q), "\" autocomplete=\"off\">",
        "<button type=\"submit\">Search</button>",
        "<button type=\"button\" class=\"lx-danger\" onclick=\"lxClear()\">Clear all</button>",
        "</form>",
        "<div class=\"lx-card\">", rows_html(rows), "</div>",
        pager,
    })
    return chrome.render({
        title = "History", heading = "History",
        style = M.stylesheet, body = body, script = script,
        header_extra = ("<span class=\"lx-muted\">%d pages</span>"):format(history.count()),
    })
end

chrome.add("history", page, nil, {
    history_search = function(_, query, limit, offset)
        return history.search({ query = query, limit = limit, offset = offset })
    end,
    history_delete = function(_, ids)
        if type(ids) == "table" then
            for _, id in ipairs(ids) do history.remove(tonumber(id) or id) end
        else
            history.remove(tonumber(ids) or ids)
        end
        return true
    end,
    history_clear = function()
        history.clear()
        return true
    end,
})

modes.add_cmds({
    { ":history", "Open the browsing history page (optionally searching for the given words).",
        function(w, o)
            local q = o and o.arg
            if q and q ~= "" then
                w:new_tab(chrome.page_uri("history", "", { q = q }))
            else
                w:new_tab(M.chrome_page)
            end
        end },
})

return M
