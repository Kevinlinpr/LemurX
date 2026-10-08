-- @name 标签收纳
-- @description OneTab + Session Buddy + The Great Suspender 替代：一键把所有标签收进列表省内存，分组管理 / 恢复 / 导出导入；会话自动快照与恢复；久不用的标签自动休眠
-- @version 1.0.0
-- @icon 🗂
-- @category 效率
-- @replaces OneTab · Session Buddy · The Great Suspender · Tab Manager Plus
-- @page lemurx://tabs/
--
-- 数据：
--   lua/official/tabs/groups.json    [{ id, name, created, locked, tabs = [{url, title, added}] }]
--   lua/official/tabs/sessions.json  [{ id, name, created, auto, tabs = [{url, title}] }]

local lx = require("lx")
local util = lx.util
local json = lx.json

local ID = "tabs"
local data = lx.data(ID)

local S
S = lx.register({
    id = ID, name = "标签收纳", version = "1.0.0", icon = "🗂",
    description = "把所有标签收进一个列表（OneTab），随时恢复；会话自动快照（Session Buddy）；不活动的标签自动休眠释放内存（The Great Suspender）。",
    replaces = "OneTab · Session Buddy · The Great Suspender · Tab Manager Plus",
    settings = {
        enabled = true,
        dedupe = true,               -- 收纳时去重
        keep_current = false,        -- 收纳"全部"时保留当前标签
        restore_removes = true,      -- 恢复后从列表移除
        open_after_collect = true,   -- 收纳后打开列表页
        session_auto = true,
        session_interval_min = 5,
        session_keep = 30,
        suspend = false,
        suspend_after_min = 30,
        suspend_whitelist = {},
        toolbar_button = false,
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用标签收纳", section = "总开关" },
        { key = "toolbar_button", type = "bool", label = "顶栏收纳按钮", desc = "地址栏右侧一个 🗂 按钮，点一下收纳全部" },
        { key = "dedupe", type = "bool", label = "收纳时去重", section = "收纳", desc = "同一 URL 只保留一条" },
        { key = "keep_current", type = "bool", label = "收纳全部时保留当前标签" },
        { key = "restore_removes", type = "bool", label = "恢复后从列表移除", desc = "关掉就是 OneTab 的\"恢复并保留\"" },
        { key = "open_after_collect", type = "bool", label = "收纳后打开列表页" },
        { key = "session_auto", type = "bool", label = "自动会话快照", section = "会话", desc = "标签变化后定期保存一份全部打开标签的快照，浏览器崩了也能恢复" },
        { key = "session_interval_min", type = "select", label = "快照间隔", options = { { 2, "2 分钟" }, { 5, "5 分钟" }, { 15, "15 分钟" }, { 30, "30 分钟" } } },
        { key = "session_keep", type = "select", label = "保留自动快照数", options = { { 10, "10 份" }, { 30, "30 份" }, { 100, "100 份" } } },
        { key = "suspend", type = "bool", label = "自动休眠不活动标签", section = "休眠", desc = "把久没看的后台标签的网页内容丢弃（状态保留），切回时重新加载。省内存、省电" },
        { key = "suspend_after_min", type = "select", label = "多久不用后休眠", options = { { 10, "10 分钟" }, { 30, "30 分钟" }, { 60, "1 小时" }, { 180, "3 小时" } } },
        { key = "suspend_whitelist", type = "list", label = "不休眠的站点", desc = "一行一个域名（播放器、聊天页）", placeholder = "music.163.com" },
    },
    menu = {
        { id = "collect", title = "收纳所有标签", onClick = function() S.collect("all") end },
        { id = "collect_others", title = "收纳其他标签", onClick = function() S.collect("others") end },
        { id = "open", title = "标签收纳列表", page = "main" },
    },
    api = {},
})
local settings = S.settings

local groups = {}
local sessions = {}
local dirty_groups, dirty_sessions = false, false
local last_session_fp = nil
local suspended = {}     -- tab id -> true

local function save()
    if dirty_groups then data:write_json("groups.json", groups) dirty_groups = false end
    if dirty_sessions then data:write_json("sessions.json", sessions) dirty_sessions = false end
end
local function new_id() return ("%x%04x"):format(os.time(), math.random(0, 0xffff)) end

local function collectable(t)
    if not t or not t.url or t.url == "" then return false end
    if t.incognito then return false end
    if t.url:match("^lemurx://") or t.url:match("^chrome") or t.url:match("^about:") then return false end
    return true
end

local function count_tabs()
    local n = 0
    for _, g in ipairs(groups) do n = n + #g.tabs end
    return n
end

-- ===== 收纳 =====
-- mode: "all" | "others" | "current" | { ids }
function S.collect(mode, name)
    if not settings:get("enabled") then return nil, "已禁用" end
    local all = lx.tabs.list()
    local cur = lx.tabs.current()
    local pick = {}
    for _, t in ipairs(all) do
        local take = false
        if type(mode) == "table" then
            for _, id in ipairs(mode) do if id == t.id then take = true end end
        elseif mode == "current" then
            take = cur and t.id == cur.id
        elseif mode == "others" then
            take = not (cur and t.id == cur.id)
        else
            take = not (settings:get("keep_current") and cur and t.id == cur.id)
        end
        if take and collectable(t) then pick[#pick + 1] = t end
    end
    if #pick == 0 then
        lx.toast("没有可收纳的标签")
        return nil, "没有可收纳的标签"
    end
    local g = { id = new_id(), name = name or os.date("%m-%d %H:%M"), created = os.time(), locked = false, tabs = {} }
    local seen = {}
    if settings:get("dedupe") then
        for _, og in ipairs(groups) do for _, t in ipairs(og.tabs) do seen[t.url] = true end end
    end
    for _, t in ipairs(pick) do
        if not (settings:get("dedupe") and seen[t.url]) then
            seen[t.url] = true
            g.tabs[#g.tabs + 1] = { url = t.url, title = t.title ~= "" and t.title or t.url, added = os.time() }
        end
    end
    if #g.tabs > 0 then
        table.insert(groups, 1, g)
        dirty_groups = true
        save()
    end
    -- 先开列表页再关（避免关掉最后一个标签时浏览器新建空白页）
    local opened = nil
    if settings:get("open_after_collect") then
        local list_tab = nil
        for _, t in ipairs(all) do if t.url and t.url:match("^lemurx://tabs/") then list_tab = t break end end
        if list_tab then
            pcall(lemurx.tabs.select, list_tab.id)
            pcall(lemurx.tabs.reload, list_tab.id)
            opened = list_tab.id
        else
            opened = lx.tabs.open("lemurx://tabs/")
        end
    end
    for _, t in ipairs(pick) do
        if t.id ~= opened then pcall(lemurx.tabs.close, t.id) end
    end
    lx.toast(("已收纳 %d 个标签"):format(#pick))
    return { ok = true, count = #pick, group = g.id }
end

local function find_group(id)
    for i, g in ipairs(groups) do if g.id == id then return g, i end end
end

function S.restore(group_id, index, opts)
    opts = opts or {}
    local g, gi = find_group(group_id)
    if not g then return nil, "分组不存在" end
    local list = {}
    if index then
        if g.tabs[index] then list[1] = g.tabs[index] end
    else
        for _, t in ipairs(g.tabs) do list[#list + 1] = t end
    end
    if #list == 0 then return nil, "没有标签" end
    for i, t in ipairs(list) do
        pcall(lemurx.tabs.open, t.url, { background = i > 1 or #list > 1 })
    end
    local remove = settings:get("restore_removes")
    if opts.keep ~= nil then remove = not opts.keep end
    if remove and not g.locked then
        if index then
            table.remove(g.tabs, index)
        else
            g.tabs = {}
        end
        if #g.tabs == 0 then table.remove(groups, gi) end
        dirty_groups = true
        save()
    end
    return { ok = true, count = #list }
end

-- ===== 会话 =====
local function snapshot_tabs()
    local out = {}
    for _, t in ipairs(lx.tabs.list()) do
        if collectable(t) then out[#out + 1] = { url = t.url, title = t.title ~= "" and t.title or t.url } end
    end
    return out
end
local function fingerprint(tabs)
    local parts = {}
    for _, t in ipairs(tabs) do parts[#parts + 1] = t.url end
    table.sort(parts)
    return table.concat(parts, "\n")
end

function S.snapshot(name, auto)
    local tabs = snapshot_tabs()
    if #tabs == 0 then return nil, "没有打开的网页" end
    local fp = fingerprint(tabs)
    if auto and fp == last_session_fp then return nil, "unchanged" end
    last_session_fp = fp
    local s = { id = new_id(), name = name or (auto and "自动快照" or os.date("%m-%d %H:%M")), created = os.time(), auto = auto or false, tabs = tabs }
    table.insert(sessions, 1, s)
    -- 自动快照只保留 N 份；手动保存的不删
    local keep = tonumber(settings:get("session_keep")) or 30
    local n = 0
    for i = #sessions, 1, -1 do
        if sessions[i].auto then n = n + 1 end
    end
    if n > keep then
        for i = #sessions, 1, -1 do
            if sessions[i].auto and n > keep then table.remove(sessions, i) n = n - 1 end
        end
    end
    dirty_sessions = true
    save()
    return { ok = true, id = s.id, count = #tabs }
end

local session_timer = nil
local session_pending = false
local function schedule_session()
    if not (settings:get("enabled") and settings:get("session_auto")) then return end
    session_pending = true
end
lx.every(30000, function()
    if not session_pending then return end
    local interval = (tonumber(settings:get("session_interval_min")) or 5) * 60
    local last = sessions[1] and sessions[1].auto and sessions[1].created or 0
    if os.time() - last >= interval then
        session_pending = false
        S.snapshot(nil, true)
    end
end)
pcall(lemurx.tabs.on, "loaded", schedule_session)
pcall(lemurx.tabs.on, "closed", function(t) schedule_session() if t and t.id then suspended[t.id] = nil end end)
pcall(lemurx.tabs.on, "created", schedule_session)

-- ===== 休眠 =====
local function suspend_whitelisted(url)
    local host = util.host_of(url)
    for _, w in ipairs(settings:get("suspend_whitelist")) do
        if type(w) == "string" and util.host_matches(host, util.trim(w)) then return true end
    end
    return false
end

function S.suspend_check()
    if not (settings:get("enabled") and settings:get("suspend")) then return 0 end
    local after = (tonumber(settings:get("suspend_after_min")) or 30) * 60 * 1000
    local now = util.now_ms()
    local cur = lx.tabs.current()
    local n = 0
    for _, t in ipairs(lx.tabs.list()) do
        if collectable(t) and not (cur and t.id == cur.id) and not t.loading and not t.frozen and not t.native
            and t.lastActive and (now - t.lastActive) > after and not suspend_whitelisted(t.url) then
            local ok, done = pcall(lemurx.tabs.discard, t.id)
            if ok and done then
                suspended[t.id] = true
                n = n + 1
            end
        end
    end
    if n > 0 then lx.log("tabs: suspended %d tabs", n) end
    return n
end
lx.every(60000, S.suspend_check)
pcall(lemurx.tabs.on, "selected", function(t) if t and t.id then suspended[t.id] = nil end end)

-- ===== 顶栏按钮 =====
local function toolbar()
    if not (settings:get("enabled") and settings:get("toolbar_button")) then
        pcall(lemurx.ui.unmount, "lx_tabs_btn")
        return
    end
    pcall(lemurx.ui.render, "toolbar.end", lemurx.ui.h("button", {
        id = "lx_tabs_btn", text = "🗂", size = 14, background = { color = "#00000000" }, paddingH = 6,
        onClick = function() S.collect("all") end,
        onLongClick = function() lx.open(ID) end,
    }))
end

settings:on_change(function(key, value)
    if key == "toolbar_button" or key == "enabled" then toolbar() end
end)

-- ===== API =====
S.api["list"] = function()
    local open = {}
    for _, t in ipairs(lx.tabs.list()) do
        open[#open + 1] = { id = t.id, url = t.url, title = t.title, frozen = t.frozen or suspended[t.id] or false, lastActive = t.lastActive, incognito = t.incognito }
    end
    local cur = lx.tabs.current()
    return { groups = groups, sessions = sessions, open = open, current = cur and cur.id, total = count_tabs() }
end
S.api["collect"] = function(args)
    local mode = args.mode or "all"
    if type(args.ids) == "table" then mode = args.ids end
    local r, err = S.collect(mode, args.name)
    if not r then return nil, err end
    return { ok = true, message = ("已收纳 %d 个标签"):format(r.count), reload = true }
end
S.api["restore"] = function(args)
    local r, err = S.restore(args.group, tonumber(args.index), { keep = args.keep })
    if not r then return nil, err end
    return { ok = true, message = ("已恢复 %d 个标签"):format(r.count), reload = true }
end
S.api["group.remove"] = function(args)
    local g, i = find_group(args.group)
    if not g then return nil, "分组不存在" end
    if g.locked then return nil, "分组已锁定" end
    if args.index then
        table.remove(g.tabs, tonumber(args.index))
        if #g.tabs == 0 then table.remove(groups, i) end
    else
        table.remove(groups, i)
    end
    dirty_groups = true save()
    return { ok = true, message = "已删除", reload = true }
end
S.api["group.update"] = function(args)
    local g = find_group(args.group)
    if not g then return nil, "分组不存在" end
    if args.name ~= nil then g.name = tostring(args.name) end
    if args.locked ~= nil then g.locked = args.locked and true or false end
    dirty_groups = true save()
    return { ok = true, message = "已更新", reload = true }
end
S.api["group.move"] = function(args)
    -- 把 (from, index) 的标签移到 to 分组
    local from, to = find_group(args.from), find_group(args.to)
    local idx = tonumber(args.index)
    if not (from and to and from.tabs[idx]) then return nil, "参数错误" end
    local t = table.remove(from.tabs, idx)
    table.insert(to.tabs, 1, t)
    if #from.tabs == 0 then local _, i = find_group(from.id) if i then table.remove(groups, i) end end
    dirty_groups = true save()
    return { ok = true, message = "已移动", reload = true }
end
S.api["group.merge_all"] = function()
    if #groups <= 1 then return nil, "只有一个分组" end
    local g = { id = new_id(), name = "合并 " .. os.date("%m-%d %H:%M"), created = os.time(), locked = false, tabs = {} }
    local seen = {}
    for _, og in ipairs(groups) do
        for _, t in ipairs(og.tabs) do
            if not seen[t.url] then seen[t.url] = true g.tabs[#g.tabs + 1] = t end
        end
    end
    groups = { g }
    dirty_groups = true save()
    return { ok = true, message = ("已合并为 %d 个标签"):format(#g.tabs), reload = true }
end
S.api["export"] = function(args)
    local fmt = args.format or "text"
    if fmt == "json" then return { text = json.encode({ groups = groups, sessions = sessions, exported = os.time() }) } end
    local lines = {}
    for _, g in ipairs(groups) do
        if args.group == nil or args.group == g.id then
            lines[#lines + 1] = "## " .. g.name .. " (" .. #g.tabs .. ")"
            for _, t in ipairs(g.tabs) do lines[#lines + 1] = t.url .. " | " .. (t.title or "") end
            lines[#lines + 1] = ""
        end
    end
    return { text = table.concat(lines, "\n") }
end
S.api["import"] = function(args)
    local text = tostring(args.text or "")
    if text:match("^%s*{") then
        local t = json.decode(text)
        if type(t) ~= "table" then return nil, "JSON 解析失败" end
        local n = 0
        for _, g in ipairs(type(t.groups) == "table" and t.groups or {}) do
            if type(g.tabs) == "table" then
                g.id = new_id()
                groups[#groups + 1] = g
                n = n + #g.tabs
            end
        end
        for _, s in ipairs(type(t.sessions) == "table" and t.sessions or {}) do
            if type(s.tabs) == "table" then s.id = new_id() sessions[#sessions + 1] = s end
        end
        dirty_groups, dirty_sessions = true, true
        save()
        return { ok = true, message = ("已导入 %d 个标签"):format(n), reload = true }
    end
    -- 文本：每行 "url | title"，"## 名称" 开新分组
    local cur = nil
    local n = 0
    local out = {}
    for line in (text .. "\n"):gmatch("([^\n]*)\n") do
        line = util.trim(line)
        if line:match("^##") then
            cur = { id = new_id(), name = util.trim(line:gsub("^##", ""):gsub("%(%d+%)%s*$", "")), created = os.time(), locked = false, tabs = {} }
            out[#out + 1] = cur
        elseif line:match("^https?://") then
            local u, title = line:match("^(%S+)%s*|%s*(.*)$")
            u = u or line
            if not cur then
                cur = { id = new_id(), name = "导入 " .. os.date("%m-%d %H:%M"), created = os.time(), locked = false, tabs = {} }
                out[#out + 1] = cur
            end
            cur.tabs[#cur.tabs + 1] = { url = u, title = (title and title ~= "") and title or u, added = os.time() }
            n = n + 1
        end
    end
    for _, g in ipairs(out) do if #g.tabs > 0 then table.insert(groups, 1, g) end end
    dirty_groups = true save()
    return { ok = true, message = ("已导入 %d 个标签"):format(n), reload = true }
end
S.api["copy"] = function(args)
    local r = S.api["export"](args)
    pcall(lemurx.clipboard.set, r.text)
    return { ok = true, message = "已复制到剪贴板" }
end
S.api["session.save"] = function(args)
    local r, err = S.snapshot(args.name, false)
    if not r then return nil, err end
    return { ok = true, message = ("已保存 %d 个标签"):format(r.count), reload = true }
end
S.api["session.restore"] = function(args)
    local s
    for _, x in ipairs(sessions) do if x.id == args.id then s = x end end
    if not s then return nil, "会话不存在" end
    if args.replace then
        -- 先关掉当前的（除列表页）
        local cur = lx.tabs.current()
        for _, t in ipairs(lx.tabs.list()) do
            if collectable(t) and not (cur and t.id == cur.id) then pcall(lemurx.tabs.close, t.id) end
        end
    end
    for _, t in ipairs(s.tabs) do pcall(lemurx.tabs.open, t.url, { background = true }) end
    return { ok = true, message = ("已恢复 %d 个标签"):format(#s.tabs) }
end
S.api["session.remove"] = function(args)
    for i, x in ipairs(sessions) do
        if x.id == args.id then table.remove(sessions, i) dirty_sessions = true save() return { ok = true, message = "已删除", reload = true } end
    end
    return nil, "会话不存在"
end
S.api["session.rename"] = function(args)
    for _, x in ipairs(sessions) do
        if x.id == args.id then x.name = tostring(args.name or x.name) x.auto = false dirty_sessions = true save() return { ok = true, message = "已保存", reload = true } end
    end
    return nil, "会话不存在"
end
S.api["tab.close"] = function(args)
    pcall(lemurx.tabs.close, tonumber(args.id))
    return { ok = true, reload = true }
end
S.api["tab.select"] = function(args)
    pcall(lemurx.tabs.select, tonumber(args.id))
    return { ok = true }
end
S.api["suspend.now"] = function()
    local saved = settings:get("suspend")
    settings:set("suspend", true, true)
    local n = S.suspend_check()
    settings:set("suspend", saved, true)
    return { ok = true, message = ("已休眠 %d 个标签"):format(n), reload = true }
end
S.api["suspend.tab"] = function(args)
    local ok, done = pcall(lemurx.tabs.discard, tonumber(args.id))
    if ok and done then suspended[tonumber(args.id)] = true return { ok = true, message = "已休眠", reload = true } end
    return nil, "这个标签不能休眠（当前标签 / 正在加载）"
end

-- ===== 页面 =====
S.page = function(ctx)
    local body = {}
    body[#body + 1] = [[
<div class="card"><div class="row"><div class="icon">🗂</div><div class="l"><div class="t">标签收纳 <span class="badge">v1.0.0</span></div><div class="d">Lua 实现，替代 OneTab / Session Buddy / The Great Suspender。收纳的标签存在本机文件里，随时恢复。</div></div></div>
<div class="stat"><div><b id="s_saved">–</b><small>已收纳</small></div><div><b id="s_open">–</b><small>打开中</small></div><div><b id="s_groups">–</b><small>分组</small></div><div><b id="s_sessions">–</b><small>会话快照</small></div></div>
<div class="actions"><button data-api="collect" data-args='{"mode":"all"}'>收纳全部</button><button class="sec" data-api="collect" data-args='{"mode":"others"}'>收纳其他</button><button class="sec" data-api="session.save">保存会话</button><button class="sec" data-api="suspend.now">立即休眠后台标签</button></div>
</div>
<div class="tabs"><button class="tab on" data-tab="groups">收纳列表</button><button class="tab" data-tab="open">打开中</button><button class="tab" data-tab="sessions">会话</button><button class="tab" data-tab="io">导入导出</button><button class="tab" data-tab="settings">设置</button></div>
<div id="v_groups"></div>
<div id="v_open" style="display:none"></div>
<div id="v_sessions" style="display:none"></div>
<div id="v_io" style="display:none"><div class="card">
 <div class="row" style="display:block"><div class="t">导出</div><div class="d">文本格式每行 "URL | 标题"，可直接粘到别处；JSON 含会话，可用来完整备份。</div>
 <div class="actions"><button class="sec" id="exp_txt">复制文本</button><button class="sec" id="exp_json">复制 JSON</button><button class="sec" id="exp_show">显示在下方</button></div></div>
 <div class="row" style="display:block"><div class="t">导入</div><div class="d">粘贴文本（每行一个 URL，可带 " | 标题"，"## 名称" 开新分组）或导出的 JSON。</div>
 <textarea id="imp" rows="6" style="width:100%;margin:8px 0"></textarea><div class="actions"><button id="imp_btn">导入</button></div></div>
 <pre id="exp_out" style="display:none"></pre>
</div></div>
<div id="v_settings" style="display:none">]]
    body[#body + 1] = lx.html.settings(S)
    body[#body + 1] = [[<div class="actions"><button class="sec" data-api="settings.reset">恢复默认设置</button><a class="btn sec" href="lemurx://scripts/source?f=official/tabs.lua">查看源码</a></div></div>]]
    local js = [[
var D={};
function esc(s){return String(s||'').replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function host(u){try{return new URL(u).host}catch(e){return ''}}
function ago(ts){if(!ts)return '';var s=(Date.now()/1000-ts);if(s<60)return '刚刚';if(s<3600)return Math.floor(s/60)+' 分钟前';if(s<86400)return Math.floor(s/3600)+' 小时前';return Math.floor(s/86400)+' 天前'}
function api(n,a){return lx.api(n,a||{}).then(function(r){if(r.message)lx.toast(r.message);if(r.reload)load();return r}).catch(function(e){lx.toast(e.message)})}
document.querySelectorAll('.tabs .tab').forEach(function(b){b.onclick=function(){document.querySelectorAll('.tabs .tab').forEach(function(x){x.classList.remove('on')});b.classList.add('on');
 ['groups','open','sessions','io','settings'].forEach(function(k){lx.q('#v_'+k).style.display=k==b.dataset.tab?'':'none'})}});
function renderGroups(){var h='';if(!D.groups.length)h='<div class="card"><div class="d" style="text-align:center;padding:24px">还没有收纳的标签。点上面的"收纳全部"，或在菜单里选"收纳所有标签"。</div></div>';
 D.groups.forEach(function(g){h+='<div class="card"><div class="row"><div class="l"><div class="t"><span class="gname" data-g="'+g.id+'">'+esc(g.name)+'</span> <span class="badge">'+g.tabs.length+'</span>'+(g.locked?' 🔒':'')+'</div><div class="d">'+ago(g.created)+'</div></div>'+
  '<button class="sec sm" data-act="restore" data-g="'+g.id+'">全部恢复</button><button class="sec sm" data-act="menu" data-g="'+g.id+'">⋯</button></div>';
  g.tabs.forEach(function(t,i){h+='<div class="row tabrow"><div class="fav">'+esc((host(t.url)[0]||'?').toUpperCase())+'</div><div class="l"><a class="t" href="#" data-act="one" data-g="'+g.id+'" data-i="'+(i+1)+'">'+esc(t.title)+'</a><div class="d">'+esc(host(t.url))+'</div></div><button class="x" data-act="rm" data-g="'+g.id+'" data-i="'+(i+1)+'">×</button></div>'});
  h+='</div>'});
 if(D.groups.length>1)h+='<div class="actions"><button class="sec" data-act="merge">合并所有分组</button></div>';
 lx.q('#v_groups').innerHTML=h}
function renderOpen(){var h='<div class="card">';D.open.forEach(function(t){h+='<div class="row tabrow'+(t.id==D.current?' cur':'')+'"><div class="fav">'+esc((host(t.url)[0]||'?').toUpperCase())+'</div><div class="l"><a class="t" href="#" data-act="sel" data-id="'+t.id+'">'+esc(t.title||t.url)+'</a><div class="d">'+esc(host(t.url))+(t.frozen?' · 💤 已休眠':'')+(t.incognito?' · 隐身':'')+'</div></div>'+
  (t.id==D.current?'':'<button class="sec sm" data-act="susp" data-id="'+t.id+'">休眠</button>')+'<button class="x" data-act="close" data-id="'+t.id+'">×</button></div>'});
 h+='</div>';lx.q('#v_open').innerHTML=h}
function renderSessions(){var h='';if(!D.sessions.length)h='<div class="card"><div class="d" style="text-align:center;padding:24px">还没有会话快照。开着自动快照时，标签变化后会定期保存。</div></div>';
 D.sessions.forEach(function(s){h+='<div class="card"><div class="row"><div class="l"><div class="t">'+esc(s.name)+(s.auto?' <span class="badge">自动</span>':'')+' <span class="badge">'+s.tabs.length+'</span></div><div class="d">'+ago(s.created)+' · '+s.tabs.slice(0,3).map(function(t){return esc(host(t.url))}).join(' · ')+(s.tabs.length>3?' …':'')+'</div></div>'+
  '<button class="sec sm" data-act="srestore" data-id="'+s.id+'">恢复</button><button class="sec sm" data-act="smenu" data-id="'+s.id+'">⋯</button></div></div>'});
 lx.q('#v_sessions').innerHTML=h}
function load(){lx.api('list').then(function(r){D=r;lx.q('#s_saved').textContent=r.total;lx.q('#s_open').textContent=r.open.length;lx.q('#s_groups').textContent=r.groups.length;lx.q('#s_sessions').textContent=r.sessions.length;renderGroups();renderOpen();renderSessions()})}
document.body.addEventListener('click',function(e){var b=e.target.closest('[data-act]');if(!b)return;e.preventDefault();var a=b.dataset.act,g=b.dataset.g,i=b.dataset.i,id=b.dataset.id;
 if(a=='restore')api('restore',{group:g});
 else if(a=='one')api('restore',{group:g,index:parseInt(i)});
 else if(a=='rm')api('group.remove',{group:g,index:parseInt(i)});
 else if(a=='merge'){if(confirm('把所有分组合并成一个？'))api('group.merge_all')}
 else if(a=='sel')api('tab.select',{id:parseInt(id)});
 else if(a=='close')api('tab.close',{id:parseInt(id)});
 else if(a=='susp')api('suspend.tab',{id:parseInt(id)});
 else if(a=='srestore'){var rep=confirm('恢复这份会话。\n确定 = 关掉现在的标签再恢复；取消 = 直接追加打开');api('session.restore',{id:id,replace:rep})}
 else if(a=='smenu'){var s=D.sessions.filter(function(x){return x.id==id})[0];var c=prompt('输入新名称保存为手动会话；留空并确定 = 删除',s.name);if(c===null)return;if(c==='')api('session.remove',{id:id});else api('session.rename',{id:id,name:c})}
 else if(a=='menu'){var gg=D.groups.filter(function(x){return x.id==g})[0];var c=prompt('重命名（输入新名称）\n输入 lock / unlock 切换锁定\n输入 copy 复制为文本\n输入 delete 删除整组\n输入 keep 恢复全部但保留',gg.name);if(c===null)return;
  if(c=='lock')api('group.update',{group:g,locked:true});else if(c=='unlock')api('group.update',{group:g,locked:false});else if(c=='delete'){if(confirm('删除分组 "'+gg.name+'"（'+gg.tabs.length+' 个标签）？'))api('group.remove',{group:g})}
  else if(c=='copy')api('copy',{group:g});else if(c=='keep')api('restore',{group:g,keep:true});else if(c&&c!=gg.name)api('group.update',{group:g,name:c})}});
lx.q('#exp_txt').onclick=function(){api('copy',{format:'text'})};lx.q('#exp_json').onclick=function(){api('copy',{format:'json'})};
lx.q('#exp_show').onclick=function(){lx.api('export',{format:'text'}).then(function(r){var p=lx.q('#exp_out');p.style.display='';p.textContent=r.text})};
lx.q('#imp_btn').onclick=function(){api('import',{text:lx.q('#imp').value}).then(function(){lx.q('#imp').value=''})};
load();
]]
    local css = [[
.tabs{display:flex;gap:4px;margin:12px 0;overflow-x:auto}.tabs .tab{flex:none;background:var(--card);color:var(--fg);border:1px solid var(--line);padding:6px 12px;border-radius:16px;font-size:13px}.tabs .tab.on{background:var(--accent);color:#fff;border-color:var(--accent)}
.tabrow{padding:8px 12px}.tabrow.cur{background:rgba(66,133,244,.08)}.fav{width:28px;height:28px;border-radius:6px;background:var(--line);display:flex;align-items:center;justify-content:center;font-weight:700;font-size:13px;margin-right:10px;flex:none}
.tabrow .t{display:block;color:var(--fg);text-decoration:none;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:70vw}.x{background:none;border:none;color:var(--muted);font-size:18px;padding:0 6px}.sm{padding:4px 10px;font-size:12px}
]]
    return lx.html.page({ title = "标签收纳", icon = "🗂", body = table.concat(body), js = js, css = css })
end

-- ===== 启动 =====
do
    local g = data:read_json("groups.json")
    if type(g) == "table" then groups = g end
    local s = data:read_json("sessions.json")
    if type(s) == "table" then sessions = s end
    -- 启动时若上次会话（自动快照）比现在打开的多，说明可能是崩溃重启：提示恢复
    lx.after(4000, function()
        if not (settings:get("enabled") and settings:get("session_auto")) then return end
        local last = sessions[1]
        local open = snapshot_tabs()
        if last and last.auto and #last.tabs >= 3 and #open <= 1 then
            lx.notify("标签收纳", ("上次会话有 %d 个标签，现在只剩 %d 个。点开恢复。"):format(#last.tabs, #open), { url = "lemurx://tabs/#sessions" })
        end
        S.snapshot(nil, true)
    end)
    toolbar()
end

return S
