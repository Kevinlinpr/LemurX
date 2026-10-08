-- @name 隐私保护
-- @description Privacy Badger + ClearURLs + DDG Privacy Essentials 替代：第三方追踪器拦截（内置名单 + 启发式学习）、链接去追踪参数与拆跳转、GPC/DNT 信号、第三方 Cookie 拦截、站点隐私评级、一键清除
-- @version 1.0.0
-- @icon 🦡
-- @category 隐私与安全
-- @replaces Privacy Badger · ClearURLs · DuckDuckGo Privacy Essentials · Disconnect · Ghostery
-- @page lemurx://privacy/
--
-- privacy · 浏览器进程半边
--
-- 三个插件合一：
--   * Privacy Badger：第三方追踪器。内置名单直接拦；没在名单里的第三方，若在 ≥3 个不同站点
--     出现且带着 Cookie（说明能跨站识别你），就学习为追踪器（yellowlist 里的降级为去 Cookie 放行）。
--   * ClearURLs：导航 URL 去追踪参数 / 拆跳转（渲染进程那半边管子资源）。
--   * DDG Privacy Essentials：GPC / DNT 信号、第三方 Cookie 拦截（Chromium 偏好）、站点隐私评级、
--     一键"🔥 清除"（历史 / 缓存 / 站点数据 + 关闭全部标签）、HTTPS 升级。
--
-- 数据：
--   lua/official/privacy/learned.json   { host = { sites = {site=ts}, status, cookies, first, last } }
--   lua/official/privacy/stats.json     { total = {blocked, cookieblocked, cleaned, unwrapped}, companies = {name=n} }

local lx = require("lx")
local util = lx.util
local json = lx.json
local clearurls = require("privacy.clearurls")
local trackers = require("privacy.trackers")

local ID = "privacy"
local CHANNEL = "lx.privacy"
local data = lx.data(ID)

local S
S = lx.register({
    id = ID, name = "隐私保护", version = "1.0.0", icon = "🦡",
    description = "拦截第三方追踪器、链接去追踪、GPC/DNT、第三方 Cookie 拦截、一键清除。追踪器名单内置，并像 Privacy Badger 一样边浏览边学习。",
    replaces = "Privacy Badger · ClearURLs · DuckDuckGo Privacy Essentials · Disconnect · Ghostery",
    settings = {
        enabled = true,
        trackers = true,          -- 拦截追踪器（内置名单 + 学习）
        learn = true,             -- 启发式学习
        learn_threshold = 3,
        clearurls = true,
        custom_params = {},       -- 额外要去掉的参数名（末尾 * 前缀匹配）
        gpc = true,
        dnt = true,
        third_party_cookies = false,   -- 用 Chromium 偏好拦第三方 Cookie
        https_upgrade = false,
        badge = true,
        disabled_sites = {},      -- host 模式
        user = {},                -- host -> "block"|"cookieblock"|"allow"
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用隐私保护", section = "总开关" },
        { key = "badge", type = "bool", label = "顶栏显示站点隐私评级", desc = "A～D 字母角标，点开看本页追踪器" },
        { key = "trackers", type = "bool", label = "拦截第三方追踪器", section = "追踪器", desc = "内置 190+ 追踪域名单；CDN / 登录 / 支付等功能依赖的第三方只去 Cookie 不拦" },
        { key = "learn", type = "bool", label = "启发式学习", desc = "没在名单里的第三方，在多个站点出现且带 Cookie 就判定为追踪器（Privacy Badger 算法）" },
        { key = "learn_threshold", type = "select", label = "学习阈值", desc = "同一第三方在多少个不同站点出现后判定", options = { { 2, "2 个站点（激进）" }, { 3, "3 个站点（Privacy Badger 默认）" }, { 5, "5 个站点（保守）" } } },
        { key = "clearurls", type = "bool", label = "链接去追踪", section = "链接", desc = "去掉 utm_* / fbclid / spm / share_source 等 200+ 追踪参数，拆掉 google/facebook/知乎 等跳转链接" },
        { key = "custom_params", type = "list", label = "额外去掉的参数", desc = "一行一个参数名，末尾 * 表示前缀匹配", placeholder = "ref\nsource_*" },
        { key = "gpc", type = "bool", label = "发送 GPC 信号", section = "信号", desc = "Sec-GPC: 1 请求头 + navigator.globalPrivacyControl，加州/欧盟法律要求网站尊重" },
        { key = "dnt", type = "bool", label = "发送 DNT 信号", desc = "DNT: 1 请求头 + navigator.doNotTrack" },
        { key = "third_party_cookies", type = "bool", label = "拦截第三方 Cookie", section = "Cookie", desc = "Chromium 原生开关（profile.cookie_controls_mode）。部分嵌入登录会受影响" },
        { key = "https_upgrade", type = "bool", label = "HTTPS 升级", section = "连接", desc = "http:// 导航自动改 https://，失败自动退回。Chromium 自带 HTTPS-Upgrades 时可不开" },
        { key = "disabled_sites", type = "list", label = "站点白名单", section = "白名单", desc = "一行一个域名，这些站完全不动。*.example.com 含所有子域", placeholder = "example.com" },
    },
    menu = {
        { id = "open", title = "隐私保护", page = "main" },
    },
    api = {},
})
local settings = S.settings

-- ===== 状态 =====
local learned = {}        -- host(base) -> { sites = {}, nsites, status, cookies, first, last, company }
local learned_dirty = false
local stats = { total = { blocked = 0, cookieblocked = 0, cleaned = 0, unwrapped = 0 }, companies = {} }
local stats_dirty = false
local tab_stats = {}      -- tab id -> { host, third = {}, blocked, cookieblocked, cleaned, unwrapped, grade }
local gpc_rule = nil
local https_tried = {}    -- tab id -> http url（升级失败退回用）

local function disabled_list()
    local out = {}
    for _, w in ipairs(settings:get("disabled_sites")) do
        if type(w) == "string" and util.trim(w) ~= "" then out[#out + 1] = util.trim(w):lower() end
    end
    return out
end
local function site_disabled(host)
    for _, pat in ipairs(disabled_list()) do
        if util.host_matches(host, pat) then return true end
    end
    return false
end
local function custom_params()
    local out = {}
    for _, p in ipairs(settings:get("custom_params")) do
        if type(p) == "string" and util.trim(p) ~= "" then out[#out + 1] = util.trim(p) end
    end
    return out
end

-- 推给渲染进程的配置
local function learned_map()
    local out = {}
    for host, rec in pairs(learned) do
        if rec.status and rec.status ~= "track" then out[host] = rec.status end
    end
    return out
end
local function config_payload()
    return {
        enabled = settings:get("enabled"), clearurls = settings:get("clearurls"), custom_params = custom_params(),
        trackers = settings:get("trackers"), learn = settings:get("learn"), gpc = settings:get("gpc"), dnt = settings:get("dnt"),
        disabled_sites = disabled_list(), learned = learned_map(), user = settings:get("user"),
    }
end
local function push_pid(pid) lx.web.send_pid(CHANNEL, pid, "config", config_payload()) end
local function push_all() lx.web.broadcast(CHANNEL, "config", config_payload()) end

local ch = lx.web.require("privacy.web")
lx.web.on_process(push_pid)
ch:add_signal("hello", function(_, pid) if type(pid) == "number" then push_pid(pid) end end)

-- ===== 评级 =====
-- 参考 DDG：A 无追踪器、B 少量已拦、C 有未拦的追踪、D 很多追踪或 http
local function grade_for(st)
    if not st then return "?" end
    local score = 100
    local companies = {}
    for host, rec in pairs(st.third or {}) do
        local action = st.actions and st.actions[host]
        if rec.blocked > 0 or rec.cookieblocked > 0 then
            score = score - 3
        elseif rec.company then
            -- 名单里认识、却没处理（用户放行）
            score = score - 8
        elseif learned[util.base_domain(host)] and learned[util.base_domain(host)].status == "track" then
            score = score - 5
        end
        if rec.company then companies[rec.company] = true end
    end
    if st.host and st.scheme == "http" then score = score - 25 end
    if score >= 90 then return "A" elseif score >= 75 then return "B" elseif score >= 55 then return "C" end
    return "D"
end

-- ===== 角标 =====
function S.update_badge(tab_id)
    if not settings:get("badge") then return end
    local cur = lx.tabs.current()
    if not cur or (tab_id and cur.id ~= tab_id) then return end
    local st = tab_stats[cur.id]
    local g = grade_for(st)
    local n = st and (st.blocked + st.cookieblocked) or 0
    local color = ({ A = "#FF1B5E20", B = "#FF33691E", C = "#FFE65100", D = "#FFB71C1C" })[g] or "#FF424242"
    pcall(lemurx.ui.render, "toolbar.end", lemurx.ui.h("button", {
        id = "lx_privacy_badge", text = g .. (n > 0 and (" " .. n) or ""), size = 11,
        background = { color = color, radius = 10 }, color = "#FFFFFFFF", paddingH = 6, paddingV = 2,
        onClick = function() lx.open(ID) end,
    }))
end
pcall(lemurx.tabs.on, "selected", function() S.update_badge() end)
pcall(lemurx.tabs.on, "loaded", function(t) S.update_badge(t and t.id) end)

-- ===== 学习 =====
local function has_cookies(host)
    local ok, list = pcall(lemurx.cookie.get, "https://" .. host .. "/")
    if ok and type(list) == "table" and #list > 0 then return true end
    ok, list = pcall(lemurx.cookie.get, "http://" .. host .. "/")
    return ok and type(list) == "table" and #list > 0
end

local function learn(page_host, third)
    if not settings:get("learn") then return false end
    local site = util.base_domain(page_host)
    if site == "" then return false end
    local threshold = tonumber(settings:get("learn_threshold")) or 3
    local changed = false
    for host, rec in pairs(third) do
        local base = util.base_domain(host)
        if base ~= "" and base ~= site and not trackers.classify(host) then
            local L = learned[base]
            if not L then
                L = { sites = {}, nsites = 0, first = os.time(), company = rec.company }
                learned[base] = L
            end
            if not L.sites[site] then
                L.sites[site] = os.time()
                L.nsites = L.nsites + 1
                learned_dirty = true
            end
            L.last = os.time()
            if not L.status or L.status == "track" then
                if L.nsites >= threshold then
                    -- 关键：能跨站识别你（带 Cookie）才算追踪器；否则只是"常见第三方"
                    if L.cookies == nil or (os.time() - (L.checked or 0)) > 3600 then
                        L.cookies = has_cookies(host) or has_cookies(base)
                        L.checked = os.time()
                        learned_dirty = true
                    end
                    if L.cookies then
                        L.status = trackers.yellow(host) and "cookieblock" or "block"
                        learned_dirty = true
                        changed = true
                        lx.log("privacy: learned tracker %s (%s) on %d sites -> %s", base, L.company or "?", L.nsites, L.status)
                    else
                        L.status = "track"
                    end
                end
            end
        end
    end
    return changed
end

-- ===== 渲染进程回报 =====
ch:add_signal("seen", function(_, page_id, host, third, cleaned, blocked, cookieblocked)
    if type(page_id) ~= "number" or type(third) ~= "table" then return end
    local st = tab_stats[page_id]
    if not st or st.host ~= host then
        st = { host = host, third = {}, blocked = 0, cookieblocked = 0, cleaned = 0, unwrapped = 0 }
        tab_stats[page_id] = st
    end
    for h, rec in pairs(third) do
        local cur = st.third[h] or { n = 0, blocked = 0, cookieblocked = 0, company = rec.company }
        cur.n = cur.n + (rec.n or 0)
        cur.blocked = cur.blocked + (rec.blocked or 0)
        cur.cookieblocked = cur.cookieblocked + (rec.cookieblocked or 0)
        st.third[h] = cur
        if (rec.blocked or 0) > 0 and rec.company then
            stats.companies[rec.company] = (stats.companies[rec.company] or 0) + rec.blocked
        end
    end
    st.blocked = st.blocked + (tonumber(blocked) or 0)
    st.cookieblocked = st.cookieblocked + (tonumber(cookieblocked) or 0)
    st.cleaned = st.cleaned + (tonumber(cleaned) or 0)
    stats.total.blocked = stats.total.blocked + (tonumber(blocked) or 0)
    stats.total.cookieblocked = stats.total.cookieblocked + (tonumber(cookieblocked) or 0)
    stats.total.cleaned = stats.total.cleaned + (tonumber(cleaned) or 0)
    stats_dirty = true
    if learn(host, third) then push_all() end
    lx.after(0, function() S.update_badge(page_id) end)
end)

-- ===== 导航：ClearURLs + HTTPS 升级 =====
lx.on_navigation(function(view, uri, ev)
    if not settings:get("enabled") then return nil end
    if ev.main_frame == false then return nil end
    if not uri:match("^https?://") then return nil end
    local host = util.host_of(uri)
    if site_disabled(host) then return nil end
    local out = uri
    if settings:get("clearurls") then
        local nu, kind = clearurls.process(uri, custom_params())
        if nu then
            out = nu
            local st = tab_stats[view.id]
            if st then
                if kind == "unwrap" then st.unwrapped = st.unwrapped + 1 else st.cleaned = st.cleaned + 1 end
            end
            if kind == "unwrap" then stats.total.unwrapped = stats.total.unwrapped + 1 else stats.total.cleaned = stats.total.cleaned + 1 end
            stats_dirty = true
        end
    end
    if settings:get("https_upgrade") and out:match("^http://") then
        local h = util.host_of(out)
        local is_ip = h:match("^%d+%.%d+%.%d+%.%d+$") or h:find(":", 1, true)
        if not is_ip and h ~= "localhost" and not h:match("%.local$") and not h:match("^[^.]+$") and https_tried[view.id] ~= out then
            https_tried[view.id] = out
            out = "https://" .. out:sub(8)
        end
    end
    if out ~= uri then return out end
    return nil
end, 20)

-- HTTPS 升级失败：退回 http
lx.on_load(function(view, status, uri, err)
    if status ~= "failed" then return end
    local orig = https_tried[view.id]
    if orig and uri and uri:match("^https://") and ("http://" .. uri:sub(9)) == orig then
        lx.log("privacy: https upgrade failed for %s (%s), falling back", uri, tostring(err))
        lx.after(0, function() pcall(lemurx.tabs.navigate, view.id, orig) end)
    end
end)

pcall(lemurx.tabs.on, "closed", function(t) if t and t.id then tab_stats[t.id] = nil; https_tried[t.id] = nil end end)
pcall(lemurx.tabs.on, "started", function(t)
    if t and t.id then
        local st = tab_stats[t.id]
        local h = util.host_of(t.eventUrl or t.url or "")
        if st and st.host ~= h then tab_stats[t.id] = nil end
        if tab_stats[t.id] then tab_stats[t.id].scheme = (t.eventUrl or t.url or ""):match("^(%a+):") end
    end
end)
pcall(lemurx.tabs.on, "loaded", function(t)
    if t and t.id then
        local st = tab_stats[t.id]
        if st then st.scheme = (t.url or ""):match("^(%a+):") end
    end
end)

-- ===== GPC（导航请求头）/ 第三方 Cookie / DNT 偏好 =====
local function apply_gpc()
    if gpc_rule then pcall(lemurx.net.removeRule, gpc_rule) gpc_rule = nil end
    if not (settings:get("enabled") and (settings:get("gpc") or settings:get("dnt"))) then return end
    local hdr = {}
    if settings:get("gpc") then hdr["Sec-GPC"] = "1" end
    if settings:get("dnt") then hdr["DNT"] = "1" end
    local ok, id = pcall(lemurx.net.addRule, {
        match = "<all_urls>", action = "modify", types = { "document", "sub_frame" }, requestHeaders = hdr,
    })
    if ok then gpc_rule = id end
end

local PREF_COOKIES = "profile.cookie_controls_mode"   -- 0 关 / 1 拦第三方 / 2 仅隐身
local PREF_DNT = "enable_do_not_track"
local function apply_prefs()
    local on = settings:get("enabled")
    local meta = data:read_json("prefs.json") or {}
    if on and settings:get("third_party_cookies") then
        if meta.cookie_prev == nil then
            local ok, r = pcall(lemurx.prefs.get, PREF_COOKIES, "int")
            meta.cookie_prev = (ok and type(r) == "table" and r.value ~= nil) and r.value or 2
            data:write_json("prefs.json", meta)
        end
        pcall(lemurx.prefs.set, PREF_COOKIES, "int", 1)
    elseif meta.cookie_prev ~= nil then
        pcall(lemurx.prefs.set, PREF_COOKIES, "int", meta.cookie_prev)
        meta.cookie_prev = nil
        data:write_json("prefs.json", meta)
    end
    if on and settings:get("dnt") then
        pcall(lemurx.prefs.set, PREF_DNT, "bool", true)
    end
end

settings:on_change(function(key, value)
    if key == "enabled" then
        apply_gpc() apply_prefs() push_all()
        if not value then pcall(lemurx.ui.unmount, "lx_privacy_badge") end
    elseif key == "gpc" or key == "dnt" then
        apply_gpc() apply_prefs() push_all()
    elseif key == "third_party_cookies" then
        apply_prefs()
    elseif key == "badge" then
        if value then S.update_badge() else pcall(lemurx.ui.unmount, "lx_privacy_badge") end
    else
        push_all()
    end
end)

-- ===== 持久化 =====
local function save_learned()
    if not learned_dirty then return end
    learned_dirty = false
    data:write_json("learned.json", learned)
end
lx.every(20000, function()
    save_learned()
    if stats_dirty then
        stats_dirty = false
        data:write_json("stats.json", stats)
    end
end)

-- ===== API =====
S.api["stats"] = function()
    local cur = lx.tabs.current()
    local st = cur and tab_stats[cur.id]
    local list = {}
    if st then
        for host, rec in pairs(st.third) do
            local action
            local user = settings:get("user")[host] or settings:get("user")[util.base_domain(host)]
            if user then action = user .. "(用户)"
            elseif rec.blocked > 0 then action = "block"
            elseif rec.cookieblocked > 0 then action = "cookieblock"
            else
                local L = learned[util.base_domain(host)]
                action = L and L.status or "allow"
            end
            list[#list + 1] = { host = host, n = rec.n, blocked = rec.blocked, cookieblocked = rec.cookieblocked, company = rec.company, action = action }
        end
        table.sort(list, function(a, b) return (a.blocked + a.cookieblocked) > (b.blocked + b.cookieblocked) or ((a.blocked + a.cookieblocked) == (b.blocked + b.cookieblocked) and a.host < b.host) end)
    end
    local nlearned = 0
    for _, L in pairs(learned) do if L.status == "block" or L.status == "cookieblock" then nlearned = nlearned + 1 end end
    return {
        tab = cur and cur.id, host = st and st.host or (cur and util.host_of(cur.url or "") or ""),
        grade = grade_for(st), page = st or { blocked = 0, cookieblocked = 0, cleaned = 0, unwrapped = 0 },
        third = list, total = stats.total, companies = stats.companies,
        builtin = #trackers.BLOCK, learned = nlearned, processes = #lx.web.processes(),
        disabled = st and site_disabled(st.host) or false,
    }
end

S.api["site.toggle"] = function(args)
    local host = args.host
    if type(host) ~= "string" or host == "" then return nil, "没有当前站点" end
    local list = settings:get("disabled_sites")
    local out, found = {}, false
    for _, w in ipairs(list) do
        if w == host then found = true else out[#out + 1] = w end
    end
    if not found then out[#out + 1] = host end
    settings:set("disabled_sites", out)
    if args.tab then lx.after(200, function() pcall(lemurx.tabs.reload, args.tab) end) end
    return { ok = true, disabled = not found, message = found and ("已恢复对 " .. host .. " 的保护") or (host .. " 已加入白名单") }
end

-- 用户对某个第三方的处置：block / cookieblock / allow / 清除（default）
S.api["tracker.set"] = function(args)
    local host = type(args.host) == "string" and args.host:lower() or ""
    if host == "" then return nil, "host required" end
    local user = settings:get("user")
    local copy = {}
    for k, v in pairs(user) do copy[k] = v end
    if args.action == "default" or args.action == nil then copy[host] = nil else copy[host] = args.action end
    settings:set("user", copy)
    return { ok = true, message = "已更新 " .. host }
end

S.api["learned"] = function()
    local out = {}
    for base, L in pairs(learned) do
        local sites = {}
        for s in pairs(L.sites) do sites[#sites + 1] = s end
        table.sort(sites)
        out[#out + 1] = { host = base, status = L.status or "seen", nsites = L.nsites, sites = sites, company = L.company, cookies = L.cookies, last = L.last }
    end
    table.sort(out, function(a, b) return (a.nsites or 0) > (b.nsites or 0) end)
    return { learned = out, user = settings:get("user") }
end

S.api["learned.forget"] = function(args)
    if type(args.host) == "string" then
        learned[args.host] = nil
    else
        learned = {}
    end
    learned_dirty = true
    save_learned()
    push_all()
    return { ok = true, message = args.host and ("已忘记 " .. args.host) or "已清空学习记录", reload = not args.host }
end

S.api["clean"] = function(args)
    local u = tostring(args.url or "")
    local nu, kind, n = clearurls.process(u, custom_params())
    return { url = nu or u, changed = nu ~= nil, kind = kind, removed = n }
end

-- 🔥 一键清除：历史 / 缓存 / 站点数据 + 关闭全部标签
S.api["fire"] = function(args)
    local scope = args.scope or "all"
    local types = { "history", "cache", "site_data" }
    local ok, err = pcall(lemurx.data.clear, types, scope)
    if not ok then return nil, "清除失败：" .. tostring(err) end
    if args.close_tabs ~= false then
        local tabs = lx.tabs.list()
        local keep = args.keep_tab
        for _, t in ipairs(tabs) do
            if t.id ~= keep then pcall(lemurx.tabs.close, t.id) end
        end
    end
    tab_stats = {}
    lx.toast("🔥 已清除浏览数据")
    return { ok = true, message = "已清除历史、缓存和站点数据" }
end

S.api["reset_stats"] = function()
    stats = { total = { blocked = 0, cookieblocked = 0, cleaned = 0, unwrapped = 0 }, companies = {} }
    stats_dirty = true
    tab_stats = {}
    return { ok = true, message = "统计已清零", reload = true }
end

-- ===== 页面 =====
S.page = function(ctx)
    local body = {}
    body[#body + 1] = [[
<div class="card"><div class="row"><div class="icon">🦡</div><div class="l"><div class="t">隐私保护 <span class="badge">v1.0.0</span></div><div class="d">Lua 实现，替代 Privacy Badger / ClearURLs / DuckDuckGo Privacy Essentials。第三方请求在渲染进程里同步判定；学习到的追踪器跨站生效。</div></div></div></div>
<div class="card">
 <div class="row"><div class="icon" id="g_letter" style="font-size:28px;font-weight:700">–</div><div class="l"><div class="t" id="s_host">当前站点</div><div class="d" id="s_sum"></div></div><button id="wl_btn" class="sec">本站白名单</button></div>
 <div class="stat">
  <div><b id="s_blocked">–</b><small>本页拦截</small></div>
  <div><b id="s_cookie">–</b><small>去 Cookie 放行</small></div>
  <div><b id="s_cleaned">–</b><small>链接已清理</small></div>
  <div><b id="s_total">–</b><small>累计拦截</small></div>
 </div>
 <div id="third"></div>
 <div class="actions"><button id="fire_btn" style="background:#b71c1c">🔥 一键清除浏览数据</button><button class="sec" id="learned_btn">学习记录</button><button class="sec" data-api="reset_stats">清零统计</button></div>
 <div id="learned" style="display:none"></div>
</div>
]]
    body[#body + 1] = lx.html.settings(S)
    body[#body + 1] = [[
<div class="sec">链接清理测试</div><div class="card"><div class="row" style="display:block"><div class="d">贴一个链接，看会被清理成什么样。</div>
<div style="display:flex;gap:8px;margin:8px 0"><input type="text" id="t_url" placeholder="https://www.google.com/url?q=https://example.com&utm_source=x"><button id="t_btn" class="sec">清理</button></div><div class="d" id="t_out" style="word-break:break-all"></div></div></div>
<div class="actions"><button class="sec" data-api="settings.reset">恢复默认设置</button><a class="btn sec" href="lemurx://scripts/source?f=official/privacy.lua">查看源码</a></div>
]]
    local js = [[
var CUR={};var COLORS={A:'#1b5e20',B:'#33691e',C:'#e65100',D:'#b71c1c'};
function act(a){return a=='block'?'🛑 已拦截':a=='cookieblock'?'🍪 去 Cookie':a=='track'?'👀 观察中':a.indexOf('(用户)')>=0?'👤 '+a.replace('(用户)',''):'✅ 放行'}
function refresh(){lx.api('stats').then(function(s){CUR=s;
 var g=lx.q('#g_letter');g.textContent=s.grade;g.style.color=COLORS[s.grade]||'#888';
 lx.q('#s_host').textContent=s.host||'（没有打开网页）';
 lx.q('#s_sum').textContent=s.third.length+' 个第三方 · 内置名单 '+s.builtin+' · 已学习 '+s.learned+' · 渲染进程 '+s.processes;
 lx.q('#s_blocked').textContent=s.page.blocked;lx.q('#s_cookie').textContent=s.page.cookieblocked;lx.q('#s_cleaned').textContent=(s.page.cleaned||0)+(s.page.unwrapped||0);
 lx.q('#s_total').textContent=s.total.blocked;
 var b=lx.q('#wl_btn');b.textContent=s.disabled?'恢复本站保护':'本站加入白名单';b.disabled=!s.host;b.className=s.disabled?'':'sec';
 var h='';s.third.forEach(function(t){h+='<div class="row"><div class="l"><div class="t">'+t.host+(t.company?' <span class="badge">'+t.company+'</span>':'')+'</div><div class="d">'+t.n+' 个请求 · '+act(t.action)+'</div></div>'+
  '<select data-host="'+t.host+'"><option value="default">默认</option><option value="block">拦截</option><option value="cookieblock">去 Cookie</option><option value="allow">放行</option></select></div>'});
 lx.q('#third').innerHTML=h;
 document.querySelectorAll('select[data-host]').forEach(function(el){var t=s.third.filter(function(x){return x.host==el.dataset.host})[0];if(t&&t.action.indexOf('(用户)')>=0)el.value=t.action.replace('(用户)','');
  el.onchange=function(){lx.api('tracker.set',{host:el.dataset.host,action:el.value}).then(function(r){lx.toast(r.message);refresh()})}});
})}
lx.q('#wl_btn').onclick=function(){lx.api('site.toggle',{host:CUR.host,tab:CUR.tab}).then(function(r){lx.toast(r.message);refresh()}).catch(function(e){lx.toast(e.message)})};
lx.q('#fire_btn').onclick=function(){if(!confirm('清除全部历史、缓存、Cookie 和站点数据，并关闭其他标签？'))return;lx.api('fire',{keep_tab:CUR.tab}).then(function(r){lx.toast(r.message);refresh()}).catch(function(e){lx.toast(e.message)})};
lx.q('#learned_btn').onclick=function(){var p=lx.q('#learned');if(p.style.display!='none'){p.style.display='none';return}
 lx.api('learned').then(function(r){p.style.display='block';var h='<div class="d" style="margin:8px 0">没在内置名单里、但在多个站点出现过的第三方。带 Cookie 且站点数达到阈值的会被判定为追踪器。</div>';
  if(!r.learned.length)h+='<div class="d">（还没有学习记录）</div>';
  r.learned.forEach(function(L){h+='<div class="row"><div class="l"><div class="t">'+L.host+(L.company?' <span class="badge">'+L.company+'</span>':'')+'</div><div class="d">'+act(L.status)+' · '+L.nsites+' 个站点'+(L.cookies?' · 带 Cookie':'')+'<br><small>'+L.sites.slice(0,6).join(' · ')+(L.sites.length>6?' …':'')+'</small></div></div><button class="sec" data-forget="'+L.host+'">忘记</button></div>'});
  h+='<div class="actions"><button class="sec" data-forget="">清空全部</button></div>';p.innerHTML=h;
  document.querySelectorAll('button[data-forget]').forEach(function(b){b.onclick=function(){lx.api('learned.forget',b.dataset.forget?{host:b.dataset.forget}:{}).then(function(r){lx.toast(r.message);lx.q('#learned_btn').onclick();lx.q('#learned_btn').onclick()})}})})};
lx.q('#t_btn').onclick=function(){lx.api('clean',{url:lx.q('#t_url').value}).then(function(r){lx.q('#t_out').textContent=r.changed?((r.kind=='unwrap'?'↪ 拆跳转：':'🧹 去掉 '+(r.removed||0)+' 个参数：')+r.url):'（没有需要清理的）'})};
refresh();setInterval(refresh,3000);
]]
    return lx.html.page({ title = "隐私保护", icon = "🦡", body = table.concat(body), js = js })
end

-- ===== 启动 =====
do
    local L = data:read_json("learned.json")
    if type(L) == "table" then learned = L end
    local st = data:read_json("stats.json")
    if type(st) == "table" and type(st.total) == "table" then stats = st end
    apply_gpc()
    apply_prefs()
    push_all()
    if settings:get("badge") then lx.after(1500, function() S.update_badge() end) end
end

return S
