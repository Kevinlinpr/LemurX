-- LemurX · luakit-compatible library · bookmarks_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "bookmarks_chrome" module API. No luakit code is used.
--
-- luakit://bookmarks/ 页面：标签过滤（?tag=）、搜索（?q=）、新增 / 编辑 / 删除（走导出函数）。
-- 命令：:bookmarks   :bookmark [uri] [tags...]
-- 按键：B（新标签打开书签页）  gb / gB（当前标签 / 新标签打开）
--       a（进入 ":bookmark <当前地址> " 待补标签） A（直接收藏当前页）

local chrome = require("chrome")
local bookmarks = require("bookmarks")
local modes = require("modes")

local M = {}

M.chrome_page = "luakit://bookmarks/"
M.show_uri = true
M.stylesheet = [[
.bm .lx-title { color: var(--lx-text); }
.bm .desc { color: var(--lx-muted); font-size: 13px; margin-top: 2px; }
.bm .tags { margin-top: 4px; }
.bm .act { flex: none; display: flex; gap: 4px; }
.editor { display: none; margin-top: 8px; }
.editor.open { display: grid; gap: 8px; }
.editor .row2 { display: flex; gap: 8px; }
.addbox { display: grid; gap: 8px; }
.tagbar { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
]]

local esc = chrome.escape

local function tag_link(name, active)
    local href = name == "" and "luakit://bookmarks/" or chrome.page_uri("bookmarks", "", { tag = name })
    return ("<a class=\"lx-tag%s\" href=\"%s\">%s</a>"):format(active and " lx-on" or "", esc(href), esc(name == "" and "all" or name))
end

local function entry_html(b)
    local title = (b.title and b.title ~= "") and b.title or b.uri
    local tags = {}
    for _, t in ipairs(b.tag_list) do tags[#tags + 1] = tag_link(t, false) end
    return table.concat({
        "<div class=\"lx-row bm\" data-id=\"", tostring(b.id), "\">",
        "<div class=\"lx-grow\">",
        "<a class=\"lx-title\" href=\"", esc(b.uri), "\">", esc(title), "</a>",
        M.show_uri and ("<span class=\"lx-sub\">" .. esc(b.uri) .. "</span>") or "",
        (b.desc and b.desc ~= "") and ("<div class=\"desc\">" .. esc(b.desc) .. "</div>") or "",
        #tags > 0 and ("<div class=\"tags\">" .. table.concat(tags) .. "</div>") or "",
        "<div class=\"editor\" id=\"ed", tostring(b.id), "\">",
        "<input type=\"url\" name=\"uri\" value=\"", esc(b.uri), "\" placeholder=\"URL\">",
        "<input type=\"text\" name=\"title\" value=\"", esc(b.title or ""), "\" placeholder=\"Title\">",
        "<input type=\"text\" name=\"tags\" value=\"", esc(b.tags or ""), "\" placeholder=\"Tags (space separated)\">",
        "<textarea name=\"desc\" rows=\"2\" placeholder=\"Notes\">", esc(b.desc or ""), "</textarea>",
        "<div class=\"row2\"><button class=\"lx-primary\" onclick=\"lxSave(", tostring(b.id), ")\">Save</button>",
        "<button onclick=\"lxToggle(", tostring(b.id), ")\">Cancel</button></div>",
        "</div></div>",
        "<div class=\"act\"><button class=\"lx-small\" onclick=\"lxToggle(", tostring(b.id), ")\">Edit</button>",
        "<button class=\"lx-small lx-danger\" onclick=\"lxRemove(", tostring(b.id), ", this)\">&times;</button></div>",
        "</div>",
    })
end

local script = [[
function need(name) { if (typeof window[name] !== 'function') { alert('Lua bridge unavailable'); return false; } return true; }
function lxToggle(id) { var e = document.getElementById('ed' + id); if (e) e.classList.toggle('open'); }
function lxSave(id) {
  if (!need('bookmarks_update')) return;
  var e = document.getElementById('ed' + id);
  var f = function (n) { return e.querySelector('[name=' + n + ']').value; };
  bookmarks_update(id, f('uri'), f('title'), f('desc'), f('tags')).then(function () { location.reload(); });
}
function lxRemove(id, btn) {
  if (!need('bookmarks_remove')) return;
  bookmarks_remove(id).then(function () { var r = btn.closest('.bm'); if (r) r.remove(); });
}
function lxAdd() {
  if (!need('bookmarks_add')) return;
  var g = function (n) { return document.getElementById('add-' + n).value; };
  if (!g('uri')) return;
  bookmarks_add(g('uri'), g('title'), g('tags')).then(function () { location.reload(); });
}
]]

local function page(_, meta)
    local q = meta.params.q or ""
    local tag = meta.params.tag or ""
    local rows = bookmarks.find({ query = q, tag = tag })
    local tagbar = { tag_link("", tag == "") }
    for _, t in ipairs(bookmarks.tags()) do tagbar[#tagbar + 1] = tag_link(t.name, t.name == tag) end
    local list = {}
    for _, b in ipairs(rows) do list[#list + 1] = entry_html(b) end
    local body = table.concat({
        "<form class=\"lx-toolbar\" method=\"get\" action=\"luakit://bookmarks/\">",
        "<input type=\"search\" name=\"q\" placeholder=\"Search bookmarks\" value=\"", esc(q), "\" autocomplete=\"off\">",
        tag ~= "" and ("<input type=\"hidden\" name=\"tag\" value=\"" .. esc(tag) .. "\">") or "",
        "<button type=\"submit\">Search</button></form>",
        "<div class=\"tagbar\">", table.concat(tagbar), "</div>",
        "<div class=\"lx-card\"><h2>Add a bookmark</h2><div class=\"addbox\">",
        "<input id=\"add-uri\" type=\"url\" placeholder=\"https://…\">",
        "<input id=\"add-title\" type=\"text\" placeholder=\"Title\">",
        "<input id=\"add-tags\" type=\"text\" placeholder=\"Tags\">",
        "<div><button class=\"lx-primary\" onclick=\"lxAdd()\">Add</button></div></div></div>",
        "<div class=\"lx-card\">",
        #list > 0 and table.concat(list) or "<div class=\"lx-empty\">No bookmarks match.</div>",
        "</div>",
    })
    return chrome.render({
        title = "Bookmarks", heading = "Bookmarks", style = M.stylesheet, body = body, script = script,
        header_extra = ("<span class=\"lx-muted\">%d saved</span>"):format(bookmarks.count()),
    })
end

chrome.add("bookmarks", page, nil, {
    bookmarks_add = function(_, uri, title, tags, desc)
        return bookmarks.add(uri, { title = title, tags = tags, desc = desc })
    end,
    bookmarks_remove = function(_, id) bookmarks.remove(id) return true end,
    bookmarks_update = function(_, id, uri, title, desc, tags)
        return bookmarks.update(id, { uri = uri, title = title, desc = desc, tags = tags })
    end,
    bookmarks_get = function(_, id) return bookmarks.get(id) end,
    bookmarks_find = function(_, query, tag) return bookmarks.find({ query = query, tag = tag }) end,
})

-- 收藏当前页；tags 可选
local function bookmark_current(w, uri, tags)
    local view = w.view
    uri = (uri and uri ~= "") and uri or (view and view.uri)
    if not uri or uri == "" or uri == "about:blank" then
        w:error("Nothing to bookmark")
        return
    end
    local title = (view and view.uri == uri) and view.title or ""
    local id = bookmarks.add(uri, { title = title, tags = tags })
    w:notify(("Bookmarked %s (#%d)"):format(uri, id or 0))
    return id
end

modes.add_cmds({
    { ":bookmarks", "Open the bookmarks page.", function(w) w:new_tab(M.chrome_page) end },
    { ":bookmark", "Bookmark a page: :bookmark [uri] [tag ...] (defaults to the current page).",
        function(w, o)
            local arg = (o and o.arg) or ""
            local first, rest = arg:match("^%s*(%S+)%s*(.*)$")
            local uri, tags
            if first and first:match("^%a[%w+.-]*:") then
                uri, tags = first, rest
            else
                uri, tags = nil, arg
            end
            bookmark_current(w, uri, tags)
        end },
})

modes.add_binds("normal", {
    { "B", "Open the bookmarks page in a new tab.", function(w) w:new_tab(M.chrome_page) end },
    { "^gb$", "Open the bookmarks page in the current tab.", function(w) w:navigate(M.chrome_page) end },
    { "^gB$", "Open the bookmarks page in a new tab.", function(w) w:new_tab(M.chrome_page) end },
    { "a", "Bookmark the current page, prompting for tags.", function(w)
        local uri = w.view and w.view.uri or ""
        w:enter_cmd(":bookmark " .. uri .. " ")
    end },
    { "A", "Bookmark the current page immediately.", function(w) bookmark_current(w) end },
})

return M
