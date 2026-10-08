-- @name 网站技术栈
-- @description 探测当前网站用了什么：CMS、前端框架、UI 库、服务器、CDN、统计 / 广告 / 客服 / 支付等 200+ 种技术，附版本号与命中依据。
-- @version 1.0.0
-- @icon 🔬
-- @category 开发
-- @page lemurx://wappalyzer/
-- @replaces Wappalyzer · BuiltWith · WhatRuns
--
-- 渲染进程扫 DOM / 全局变量 / 脚本 / meta / Cookie（wappalyzer/web.lua），浏览器进程按需 HEAD 一次拿响应头，
-- 合并后按分类展示。指纹库在 wappalyzer/techs.lua，纯数据表，用户可以 fork 到本地加自己的。
local lx = require("lx")
local json = require("lx.json")
local util = require("lx.util")
local detect = require("wappalyzer.detect")

local ID, CHANNEL = "wappalyzer", "lx.wappalyzer"
local S

S = lx.register({
    id = ID, name = "网站技术栈", version = "1.0.0", icon = "🔬",
    description = "探测当前网站用了什么：CMS、前端框架、UI 库、服务器、CDN、统计 / 广告 / 客服 / 支付等 200+ 种技术，附版本号与命中依据。",
    replaces = "Wappalyzer · BuiltWith · WhatRuns",
    settings = {
        enabled = true,
        auto = true,          -- 页面加载完自动扫（很轻）
        headers = true,       -- 打开结果页时 HEAD 一次拿响应头
        badge = false,        -- 工具栏角标显示技术数
        history_keep = 200,
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用" },
        { key = "auto", type = "bool", label = "页面加载后自动探测", desc = "关掉则只在打开结果页时探测" },
        { key = "headers", type = "bool", label = "补充响应头探测（多一次 HEAD 请求）" },
        { key = "badge", type = "bool", label = "工具栏角标显示技术数量" },
        { key = "history_keep", type = "number", label = "保留站点记录数", min = 0, max = 2000 },
        { key = "clear", type = "action", label = "清空记录", api = "history.clear", style = "danger" },
    },
    menu = {
        { id = "open", title = "网站技术栈", onClick = function() S.open_current() end },
    },
    api = {},
})
local settings = S.settings
local data = lx.data(ID)

-- tab_id -> { url, results, headers_results, at }
local by_tab = {}
-- host -> { url, at, names = {...} }（持久，给"记录"页）
local history = data:read_json("history.json") or {}
local hist_dirty
local function save_history()
    if not hist_dirty then return end
    hist_dirty = false
    local keep = tonumber(settings:get("history_keep")) or 200
    local arr = {}
    for host, h in pairs(history) do arr[#arr + 1] = { host, h.at or 0 } end
    table.sort(arr, function(a, b) return a[2] > b[2] end)
    for i = keep + 1, #arr do history[arr[i][1]] = nil end
    data:write_json("history.json", history)
end
lx.every(30000, save_history)

local function merge(tab_id)
    local st = by_tab[tab_id]
    if not st then return {} end
    local byname, out = {}, {}
    for _, src in ipairs({ st.results or {}, st.header_results or {} }) do
        for _, r in ipairs(src) do
            local e = byname[r.name]
            if not e then
                e = { name = r.name, cat = r.cat, cat_name = r.cat_name or (require("wappalyzer.techs").CATS[r.cat] or r.cat), version = r.version, confidence = r.confidence or 0, via = { r.via } }
                byname[r.name] = e
                out[#out + 1] = e
            else
                e.confidence = math.min(100, e.confidence + (r.confidence or 0))
                e.version = e.version or r.version
                if r.via then e.via[#e.via + 1] = r.via end
            end
        end
    end
    table.sort(out, function(a, b) if a.confidence ~= b.confidence then return a.confidence > b.confidence end return a.name < b.name end)
    return out
end
S.merge = merge

local function update_badge(tab_id)
    if not settings:get("badge") then pcall(lemurx.ui.unmount, "lx_wapp_badge") return end
    local cur = lx.tabs.current()
    if not cur or cur.id ~= tab_id then return end
    local n = #merge(tab_id)
    if n == 0 then pcall(lemurx.ui.unmount, "lx_wapp_badge") return end
    pcall(lemurx.ui.render, "lx_wapp_badge", lemurx.ui.h("text", { text = "🔬" .. n, style = { fontSize = 11, color = "#0a84ff" }, onClick = function() S.open_current() end }))
end

local function remember(tab_id, url, results)
    local host = util.host_of(url or "")
    if host == "" or #results == 0 then return end
    local names = {}
    for _, r in ipairs(results) do names[#names + 1] = r.version and (r.name .. " " .. r.version) or r.name end
    history[host] = { url = url, at = util.now_ms(), names = names }
    hist_dirty = true
end

-- ===== 渲染进程 =====
local function config() return { enabled = settings:get("enabled") and true or false, auto = settings:get("auto") and true or false } end
local ch = lx.web.require("wappalyzer/web")
if ch then
    ch:add_signal("hello", function(_, pid) if type(pid) == "number" then lx.web.send_pid(CHANNEL, pid, "config", json.encode(config())) end end)
    lx.web.on_process(function(pid) lx.web.send_pid(CHANNEL, pid, "config", json.encode(config())) end)
    ch:add_signal("result", function(_, page_id, url, results_json)
        local results = type(results_json) == "string" and json.decode(results_json) or results_json
        if type(page_id) ~= "number" or type(results) ~= "table" then return end
        local st = by_tab[page_id] or {}
        if st.url ~= url then st.header_results = nil st.headers = nil end
        st.url, st.results, st.at = url, results, util.now_ms()
        by_tab[page_id] = st
        remember(page_id, url, merge(page_id))
        update_badge(page_id)
    end)
end
settings:on_change(function(key)
    lx.web.broadcast(CHANNEL, "config", json.encode(config()))
    if key == "badge" then local t = lx.tabs.current() if t then update_badge(t.id) end end
end)
pcall(lemurx.tabs.on, "closed", function(t) if t and t.id then by_tab[t.id] = nil end end)
pcall(lemurx.tabs.on, "selected", function(t) if t and t.id then update_badge(t.id) end end)

-- 响应头：HEAD 一次（同源 Cookie 带上，服务器才会给真实的头）
local function fetch_headers(tab_id, cb)
    local st = by_tab[tab_id]
    if not st or not st.url or not settings:get("headers") then return cb(nil) end
    if st.headers then return cb(st.headers) end
    if st.headers_pending then st.headers_pending[#st.headers_pending + 1] = cb return end
    st.headers_pending = { cb }
    lx.fetch(st.url, { method = "HEAD", timeout = 8000, headers = { ["Accept"] = "text/html" } }, function(r)
        local waiters = st.headers_pending or {}
        st.headers_pending = nil
        local headers = {}
        if r and r.ok and type(r.headers) == "table" then
            for k, v in pairs(r.headers) do headers[tostring(k):lower()] = type(v) == "table" and table.concat(v, ", ") or tostring(v) end
        end
        st.headers = headers
        local hr = detect.detect({ url = st.url, headers = headers })
        local slim = {}
        for _, x in ipairs(hr) do slim[#slim + 1] = { name = x.name, cat = x.cat, cat_name = x.cat_name, version = x.version, confidence = x.confidence, via = x.via[1] } end
        st.header_results = slim
        remember(tab_id, st.url, merge(tab_id))
        for _, w in ipairs(waiters) do w(headers) end
    end)
end

function S.scan(tab_id) lx.web.broadcast(CHANNEL, "scan", tab_id) end
function S.open_current()
    local t = lx.tabs.current()
    if not t or not (t.url or ""):match("^https?://") then lx.toast("当前不是网页") return end
    if not by_tab[t.id] then S.scan(t.id) end
    lx.tabs.open("lemurx://wappalyzer/?tab=" .. t.id)
end

-- ===== API / 页面 =====
S.api.results = function(args, ctx)
    local tab_id = tonumber(args.tab)
    if not tab_id then local t = lx.tabs.current() tab_id = t and t.id end
    if not tab_id then return { results = {}, url = "" } end
    fetch_headers(tab_id, function(headers)
        local st = by_tab[tab_id] or {}
        ctx.reply({ url = st.url or "", results = merge(tab_id), headers = headers or {}, at = st.at })
    end)
    return "async"
end
S.api.scan = function(args) local t = tonumber(args.tab) or (lx.tabs.current() or {}).id if t then S.scan(t) end return { ok = true, message = "已重新探测" } end
S.api["history.list"] = function()
    local arr = {}
    for host, h in pairs(history) do arr[#arr + 1] = { host = host, url = h.url, at = h.at, names = h.names } end
    table.sort(arr, function(a, b) return (a.at or 0) > (b.at or 0) end)
    return { items = arr }
end
S.api["history.clear"] = function() history = {} hist_dirty = true save_history() return { ok = true, message = "已清空", reload = true } end
S.api.export = function(args)
    local tab_id = tonumber(args.tab) or (lx.tabs.current() or {}).id
    local st = by_tab[tab_id] or {}
    return { url = st.url, technologies = merge(tab_id), headers = st.headers }
end

local esc = lx.html.escape
S.page_css = [[
.tech{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid var(--line,#eee)}.tech:last-child{border:0}.tech .n{flex:1}.tech .v{font-size:12px;opacity:.6}.tech .c{font-size:11px;padding:2px 6px;border-radius:6px;background:var(--bg2,#f2f2f7)}
.cat{margin:12px 0 4px;font-size:13px;opacity:.6;text-transform:uppercase;letter-spacing:.5px}
.via{font-size:11px;opacity:.5;word-break:break-all}
]]
S.page = function(ctx)
    local tab = tonumber(ctx.query.tab) or (lx.tabs.current() or {}).id or 0
    local hist = S.api["history.list"]().items
    local rows = {}
    for i = 1, math.min(30, #hist) do
        local h = hist[i]
        rows[#rows + 1] = ("<a class=\"row\" href=\"%s\"><div class=\"l\"><div class=\"t\">%s</div><div class=\"d\">%s</div></div></a>"):format(esc(h.url or "#"), esc(h.host), esc(table.concat(h.names or {}, " · "):sub(1, 160)))
    end
    return lx.html.page({
        title = "网站技术栈", icon = "🔬", css = S.page_css,
        body = ([[
<div class="card"><div class="row" style="display:block"><div class="t" id="url">探测中…</div><div id="res" style="margin-top:6px"><div class="d">正在收集页面信号</div></div>
 <div class="actions" style="padding:8px 0 0"><button class="sec" id="rescan">重新探测</button><button class="sec" id="copy">复制 JSON</button></div></div></div>
<div class="card list"><div class="row"><div class="l"><div class="t">最近探测过的网站</div></div></div>%s</div>
%s<p class="muted">指纹库 wappalyzer/techs.lua 是一张纯数据表；想加自己的，把脚本复制到本地目录后改。</p>]]):format(
            #rows > 0 and table.concat(rows) or "<div class=\"row\"><div class=\"d\">还没有记录</div></div>",
            lx.html.settings(S)),
        js = ([[
var TAB=%d;var last=null;
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function render(r){last=r;lx.q('#url').textContent=r.url||'（没有页面）';var g={},order=[];(r.results||[]).forEach(function(t){if(!g[t.cat]){g[t.cat]={name:t.cat_name||t.cat,items:[]};order.push(t.cat)}g[t.cat].items.push(t)});
 if(!order.length){lx.q('#res').innerHTML='<div class="d">没有识别出已知技术。'+(r.results?'':'页面可能还没扫完，点「重新探测」。')+'</div>';return}
 lx.q('#res').innerHTML=order.map(function(c){return '<div class="cat">'+esc(g[c].name)+'</div>'+g[c].items.map(function(t){return '<div class="tech"><div class="n">'+esc(t.name)+(t.version?' <span class="v">'+esc(t.version)+'</span>':'')+'<div class="via">'+esc((t.via||[]).slice(0,2).join(' · '))+'</div></div><span class="c">'+t.confidence+'%%</span></div>'}).join('')}).join('');}
function load(){lx.api('results',{tab:TAB}).then(render).catch(function(e){lx.q('#res').textContent=e.message})}
load();setTimeout(load,3500);
lx.q('#rescan').onclick=function(){lx.api('scan',{tab:TAB}).then(function(){setTimeout(load,1200)})};
lx.q('#copy').onclick=function(){if(!last)return;var t=JSON.stringify({url:last.url,technologies:last.results.map(function(x){return{name:x.name,category:x.cat_name,version:x.version||null,confidence:x.confidence}})},null,2);(navigator.clipboard?navigator.clipboard.writeText(t):Promise.reject()).then(function(){lx.toast('已复制')},function(){lx.toast('复制失败')})};
]]):format(tab),
    })
end

return S
