-- @name 网页剪藏
-- @description Google Keep / Save to Google Drive / Evernote Web Clipper 替代：一键把正文提取成 Markdown 存为本地笔记，或直接分享到 Keep、Drive、Notion、微信等任意 App；支持剪选区、整页、导出 .md 到下载目录
-- @version 1.0.0
-- @icon ✂️
-- @category 生产力
-- @replaces Google Keep Chrome Extension · Save to Google Drive · Evernote Web Clipper
-- @page lemurx://clipper/
--
-- 结构：
--   clipper.lua          浏览器进程：笔记库（files/lua/official_data/clipper/notes/*.md + index.json）、菜单、分享/保存、笔记页
--   clipper/web.lua      渲染进程：按需注入运行时并执行提取
--   clipper/runtime.lua  页面内 JS：Readability 式正文提取 + HTML→Markdown
--
-- "保存到 Keep / Drive"在 Android 上就是系统分享面板选对应 App（Keep 收文本、Drive 收文件），
-- 不需要 Google OAuth，也不限于 Google：Notion / Obsidian / 微信文件传输助手 都能收。

local lx = require("lx")
local json = require("lx.json")
local util = require("lx.util")

local ID, CHANNEL = "clipper", "lx.clipper"
local S
S = lx.register({
    id = ID, name = "网页剪藏", version = "1.0.0", icon = "✂️",
    description = "把网页正文提取成 Markdown：存成本地笔记，或分享到 Keep / Drive / Notion / 微信等任意 App。支持剪选区、整页、导出 .md。",
    replaces = "Google Keep Chrome Extension · Save to Google Drive · Evernote Web Clipper",
    settings = {
        enabled = true, default_mode = "article", front_matter = true, include_source = true,
        after_clip = "toast", keep = 500, filename = "{title}",
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用剪藏", section = "总开关" },
        { key = "default_mode", type = "select", label = "菜单“剪藏本页”默认范围", section = "剪藏", options = { { "article", "正文（自动识别）" }, { "page", "整页" } } },
        { key = "front_matter", type = "bool", label = "Markdown 开头加 YAML 元信息（title / url / date）", desc = "Obsidian / Hugo 等能直接识别" },
        { key = "include_source", type = "bool", label = "末尾附来源链接" },
        { key = "after_clip", type = "select", label = "剪藏后", options = { { "toast", "只提示" }, { "open", "打开笔记" }, { "share", "弹出分享面板" } } },
        { key = "filename", type = "string", label = "导出文件名模板", section = "导出", desc = "可用 {title} {site} {date}" },
        { key = "keep", type = "number", label = "最多保留笔记数", min = 20, max = 5000 },
        { key = "export_all", type = "action", label = "全部导出到 下载/LemurX/", api = "export.all" },
        { key = "clear", type = "action", label = "清空所有笔记", api = "clear", style = "danger", confirm = "确定删除全部笔记？不可恢复" },
    },
    menu = {
        { id = "clip", title = "剪藏本页", onClick = function() S.clip_current(nil) end },
        { id = "clip_sel", title = "剪藏选中内容", onClick = function() S.clip_current("selection") end },
        { id = "share_md", title = "分享为 Markdown", onClick = function() S.clip_current(nil, { share = true }) end },
        { id = "notes", title = "我的笔记", onClick = function() lx.tabs.open("lemurx://clipper/") end },
    },
    api = {},
})
local settings = S.settings
local data = lx.data(ID)

-- ===== 笔记库 =====
local index = data:read_json("index.json") or { notes = {} }
local function save_index()
    local keep = tonumber(settings:get("keep")) or 500
    while #index.notes > keep do
        local old = table.remove(index.notes)
        data:remove("notes/" .. old.file)
    end
    data:write_json("index.json", index)
end

local function slug(s)
    s = (s or ""):gsub("[%c/\\:%*%?\"<>|]", " "):gsub("%s+", " ")
    s = util.trim(s)
    if #s > 80 then s = s:sub(1, 80) end
    return s ~= "" and s or "untitled"
end

local function render_md(clip)
    local parts = {}
    if settings:get("front_matter") then
        parts[#parts + 1] = ("---\ntitle: %q\nsource: %s\nsite: %s\ndate: %s\n%s---\n"):format(
            clip.title or "", clip.url or "", clip.site or "", os.date("%Y-%m-%d %H:%M"),
            (clip.byline and util.trim(clip.byline) ~= "") and ("author: " .. ("%q"):format(util.trim(clip.byline)) .. "\n") or "")
    end
    parts[#parts + 1] = "# " .. (clip.title or "") .. "\n\n"
    parts[#parts + 1] = clip.markdown or clip.text or ""
    if settings:get("include_source") and clip.url then
        parts[#parts + 1] = ("\n\n---\n来源：[%s](%s) · %s 剪藏于 LemurX"):format(clip.site or util.host_of(clip.url), clip.url, os.date("%Y-%m-%d"))
    end
    return table.concat(parts)
end

function S.add(clip, mode)
    local id = ("%d_%04d"):format(util.now_ms(), math.random(0, 9999))
    local file = id .. ".md"
    local md = render_md(clip)
    pcall(lemurx.fs.mkdir, data:path("notes"))
    if not data:write("notes/" .. file, md) then return nil, "写入失败" end
    local note = {
        id = id, file = file, title = clip.title ~= "" and clip.title or (clip.url or "无标题"), url = clip.url, site = clip.site,
        excerpt = (clip.excerpt or ""):sub(1, 200), words = clip.words or 0, image = clip.image, mode = mode or "article",
        at = util.now_ms(), bytes = #md,
    }
    table.insert(index.notes, 1, note)
    save_index()
    return note
end

function S.get(id)
    for i, n in ipairs(index.notes) do if n.id == id then return n, i end end
end
function S.read(id)
    local n = S.get(id)
    if not n then return nil end
    return data:read("notes/" .. n.file), n
end

-- ===== 与渲染进程 =====
local ch = lx.web.require("clipper/web")
local waiters, seq = {}, 0
if ch then
    ch:add_signal("extracted", function(_, req, payload)
        local w = waiters[tonumber(req)]
        if not w then return end
        waiters[tonumber(req)] = nil
        lx.cancel(w.timer)
        local clip = json.decode(payload or "")
        if type(clip) ~= "table" then return w.cb(nil, "提取失败") end
        w.cb(clip)
    end)
    ch:add_signal("clip", function(_, pid, payload)
        local clip = json.decode(payload or "")
        if type(clip) == "table" then S.after_clip(S.add(clip, "script")) end
    end)
end

function S.extract(tab, mode, cb)
    if not settings:get("enabled") then return cb(nil, "剪藏已停用") end
    if not (tab and (tab.url or ""):match("^https?://")) then return cb(nil, "这不是网页") end
    seq = seq + 1
    local req = seq
    waiters[req] = { cb = cb }
    waiters[req].timer = lx.after(8000, function()
        if waiters[req] then waiters[req] = nil cb(nil, "页面没有响应") end
    end)
    lx.web.broadcast(CHANNEL, "extract", tab.id, mode or settings:get("default_mode") or "article", req)
end

function S.after_clip(note, err, opts)
    opts = opts or {}
    if not note then lx.toast("剪藏失败：" .. tostring(err)) return end
    local how = opts.share and "share" or settings:get("after_clip")
    if how == "open" then lx.tabs.open("lemurx://clipper/note?id=" .. note.id)
    elseif how == "share" then S.share(note.id)
    else lx.toast(("已剪藏：%s（%d 字）"):format(note.title, note.words or 0)) end
end

function S.clip_current(mode, opts)
    local t = lx.tabs.current()
    S.extract(t, mode, function(clip, err)
        if not clip then return S.after_clip(nil, err) end
        if mode == "selection" and util.trim(clip.markdown or "") == "" then return S.after_clip(nil, "没有选中任何文字") end
        local note, e = S.add(clip, mode)
        S.after_clip(note, e, opts)
    end)
end

-- ===== 分享 / 导出 =====
local function export_name(n)
    local tpl = settings:get("filename") or "{title}"
    local name = tpl:gsub("{title}", slug(n.title)):gsub("{site}", slug(n.site or "")):gsub("{date}", os.date("%Y-%m-%d", math.floor((n.at or util.now_ms()) / 1000)))
    return slug(name) .. ".md"
end

function S.share(id, as_file)
    local md, n = S.read(id)
    if not md then lx.toast("笔记不存在") return false end
    if as_file then
        return pcall(lemurx.share, { path = data:path("notes/" .. n.file), mime = "text/markdown", title = n.title, name = export_name(n) })
    end
    return pcall(lemurx.share, { text = md, mime = "text/plain", title = n.title, subject = n.title })
end

function S.export(id)
    local md, n = S.read(id)
    if not md then return nil, "笔记不存在" end
    local ok, r = pcall(lemurx.media.save, { path = data:path("notes/" .. n.file), name = export_name(n), mime = "text/markdown", album = "LemurX" })
    if ok and type(r) == "table" and r.ok then return r end
    return nil, ok and (type(r) == "table" and r.error or "保存失败") or tostring(r)
end

-- ===== API =====
S.api.clip = function(args, ctx)
    local t = args.tab and { id = args.tab, url = args.url } or nil
    if not t then
        for _, x in ipairs(lx.tabs.list()) do
            if (x.url or ""):match("^https?://") and (not t or (x.lastActive or 0) > (t.lastActive or 0)) then t = x end
        end
    end
    S.extract(t, args.mode, function(clip, err)
        if not clip then return ctx.reply({ ok = false, message = err }) end
        local note, e = S.add(clip, args.mode)
        if not note then return ctx.reply({ ok = false, message = e }) end
        ctx.reply({ ok = true, note = note, message = "已剪藏：" .. note.title, reload = true })
    end)
    return "async"
end
S.api.list = function(args)
    local q = util.trim((args and args.q or ""):lower())
    if q == "" then return { items = index.notes } end
    local out = {}
    for _, n in ipairs(index.notes) do
        if (n.title or ""):lower():find(q, 1, true) or (n.excerpt or ""):lower():find(q, 1, true) or (n.url or ""):lower():find(q, 1, true) then out[#out + 1] = n end
    end
    return { items = out }
end
S.api.get = function(args)
    local md, n = S.read(args.id or "")
    if not md then return nil, "笔记不存在" end
    return { note = n, markdown = md }
end
S.api.update = function(args)
    local n = S.get(args.id or "")
    if not n then return nil, "笔记不存在" end
    if type(args.markdown) == "string" then
        data:write("notes/" .. n.file, args.markdown)
        n.bytes = #args.markdown
    end
    if type(args.title) == "string" and util.trim(args.title) ~= "" then n.title = util.trim(args.title) end
    save_index()
    return { ok = true, message = "已保存" }
end
S.api.remove = function(args)
    local n, i = S.get(args.id or "")
    if not n then return nil, "笔记不存在" end
    table.remove(index.notes, i)
    data:remove("notes/" .. n.file)
    save_index()
    return { ok = true, reload = true }
end
S.api.share = function(args) return { ok = S.share(args.id, args.file) } end
S.api.export = function(args)
    local r, err = S.export(args.id)
    if not r then return nil, err end
    return { ok = true, message = "已保存到 下载/LemurX/" .. tostring(r.name or "") }
end
S.api["export.all"] = function()
    local n, fail = 0, 0
    for _, note in ipairs(index.notes) do if S.export(note.id) then n = n + 1 else fail = fail + 1 end end
    return { ok = true, message = ("已导出 %d 篇%s"):format(n, fail > 0 and ("，失败 " .. fail) or "") }
end
S.api.clear = function()
    for _, n in ipairs(index.notes) do data:remove("notes/" .. n.file) end
    index.notes = {}
    save_index()
    return { ok = true, message = "已清空", reload = true }
end

-- ===== 页面 =====
local esc = lx.html.escape
S.page = function(ctx)
    local rows = {}
    for _, n in ipairs(index.notes) do
        rows[#rows + 1] = ([[<div class="row"><div class="l"><div class="t"><a href="lemurx://clipper/note?id=%s">%s</a></div><div class="d">%s · %s · %d 字 · %s</div></div><button class="sec" data-api="share" data-args='{"id":"%s"}'>分享</button><button class="sec" data-api="remove" data-args='{"id":"%s"}' data-confirm="删除这篇笔记？">删</button></div>]]):format(
            n.id, esc(n.title or ""), esc(n.site or util.host_of(n.url or "")), os.date("%m-%d %H:%M", math.floor((n.at or 0) / 1000)), n.words or 0, esc(n.mode == "selection" and "选区" or n.mode == "page" and "整页" or "正文"), n.id, n.id)
    end
    local body = ([[
<div class="card"><div class="row"><div class="icon">✂️</div><div class="l"><div class="t">网页剪藏 <span class="badge">v1.0.0</span></div><div class="d">Lua 实现，替代 Keep / Save to Drive / Evernote Clipper。菜单里“剪藏本页”把正文变成 Markdown 存在这里；“分享为 Markdown”直接丢给 Keep、Drive、Notion、微信等任意 App。</div></div></div>
<div class="actions"><button data-api="clip" data-args='{"mode":"article"}'>剪藏最近看的网页</button><button class="sec" data-api="clip" data-args='{"mode":"page"}'>剪整页</button></div></div>
<div class="card"><div class="h">笔记（%d）<input id="q" placeholder="搜索标题 / 摘要 / 网址" style="float:right;max-width:50%%"></div><div id="list">%s</div>%s</div>
]]):format(#index.notes, table.concat(rows), #rows == 0 and '<div class="d" style="padding:12px">还没有笔记</div>' or "")
    return lx.html.page({ title = "网页剪藏", icon = "✂️", body = body .. lx.html.settings(S) .. [[
<div class="actions"><button class="sec" data-api="settings.reset">恢复默认设置</button><a class="btn sec" href="lemurx://scripts/source?f=official/clipper.lua">查看源码</a></div>]],
        js = [[
var q=lx.q('#q');if(q){q.addEventListener('input',function(){var v=q.value.toLowerCase();lx.qa('#list .row').forEach(function(r){r.style.display=r.textContent.toLowerCase().indexOf(v)>=0?'':'none'})})}]] })
end

S.routes["/note"] = function(ctx)
    local id = ctx.query.id or ""
    local md, n = S.read(id)
    if not md then return lx.html.page({ title = "笔记", body = '<div class="card"><div class="d">笔记不存在</div></div>' }), "text/html", 404 end
    local body = ([[
<div class="card"><div class="row"><div class="l"><input id="title" value="%s" style="font-size:16px;font-weight:600;width:100%%"><div class="d"><a href="%s" target="_blank">%s</a> · %s · %d 字</div></div></div>
<textarea id="md" style="width:100%%;min-height:60vh;font:13px/1.5 ui-monospace,monospace;box-sizing:border-box">%s</textarea>
<div class="actions"><button id="save">保存修改</button><button class="sec" data-api="share" data-args='{"id":"%s"}'>分享文本</button><button class="sec" data-api="share" data-args='{"id":"%s","file":true}'>分享 .md 文件</button><button class="sec" data-api="export" data-args='{"id":"%s"}'>存到下载目录</button><button class="sec" id="copy">复制 Markdown</button><a class="btn sec" href="lemurx://clipper/">返回</a></div></div>]]):format(
        esc(n.title or ""), esc(n.url or "#"), esc(n.site or util.host_of(n.url or "")), os.date("%Y-%m-%d %H:%M", math.floor((n.at or 0) / 1000)), n.words or 0, esc(md), id, id, id)
    return lx.html.page({ title = n.title or "笔记", icon = "✂️", body = body, js = ([[
lx.q('#save').onclick=function(){lx.api('update',{id:%q,title:lx.q('#title').value,markdown:lx.q('#md').value}).then(function(r){lx.toast(r&&r.message||'ok')})};
lx.q('#copy').onclick=function(){navigator.clipboard.writeText(lx.q('#md').value).then(function(){lx.toast('已复制')})};]]):format(id) })
end

return S
