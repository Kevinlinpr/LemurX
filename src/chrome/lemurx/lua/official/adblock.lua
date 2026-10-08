-- @name 去广告
-- @description 拦截广告与追踪器：EasyList / EasyPrivacy / EasyList China 订阅 + 自定义规则，元素隐藏，站点白名单，拦截统计
-- @version 1.0.0
-- @icon 🛡
-- @category 隐私与安全
-- @replaces uBlock Origin · AdBlock · Adblock Plus · AdGuard
--
-- 界面是原生控件（lemurx.ui），不是网页。三点菜单「去广告」或顶栏角标打开面板。
-- 源码在「Lua 脚本」里看：本文件 + adblock/web.lua + adblock/builtin.lua。
--
-- 结构：
--   浏览器进程（本文件）：订阅下载 / 编译（lx.abp.compiler）/ 缓存 / 推给渲染进程 / 导航级拦截 / 统计 / 原生面板
--   渲染进程（official/adblock/web.lua）：每个子资源同步判定 + 元素隐藏 CSS
--   引擎（official/lx/abp.lua）：ABP 语法编译与匹配，两边共用
--
-- 与 Chrome 扩展版的差别：不需要 declarativeNetRequest 的 30 万条上限，规则在渲染进程里
-- 同步判定，$domain / $third-party / 例外 / $important 完整语义；元素隐藏走 Blink 用户样式表，
-- 不受页面 CSP 限制、没有闪烁。

local lx = require("lx")
local abp = require("lx.abp")
local util, json = lx.util, lx.json
local builtin = require("adblock.builtin")

local ID = "adblock"
local CHANNEL = "lx.adblock"

local DEFAULT_LISTS = {
    { id = "easylist", name = "EasyList", desc = "国际通用广告规则", url = "https://easylist.to/easylist/easylist.txt", on = true },
    { id = "easyprivacy", name = "EasyPrivacy", desc = "追踪器 / 统计脚本", url = "https://easylist.to/easylist/easyprivacy.txt", on = true },
    { id = "easylistchina", name = "EasyList China", desc = "中文站广告规则", url = "https://easylist-downloads.adblockplus.org/easylistchina.txt", on = true },
    { id = "cjx", name = "CJX's Annoyance List", desc = "中文站烦人元素（弹窗、悬浮、二维码）", url = "https://raw.githubusercontent.com/cjx82630/cjxlist/master/cjx-annoyance.txt", on = false },
    { id = "peterlowe", name = "Peter Lowe's List", desc = "广告 / 追踪服务器域名表", url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext", on = false },
    { id = "ublock-filters", name = "uBlock filters", desc = "uBlock Origin 自带规则（不支持的语法自动跳过）", url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt", on = false },
    { id = "ublock-badware", name = "uBlock Badware risks", desc = "恶意软件 / 诈骗站", url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt", on = false },
    { id = "ublock-privacy", name = "uBlock Privacy", desc = "uBlock 隐私规则", url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt", on = false },
    { id = "easylist-cookie", name = "EasyList Cookie", desc = "Cookie 同意横幅", url = "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", on = false },
    { id = "fanboy-annoyance", name = "Fanboy's Annoyance", desc = "社交按钮、通知弹窗等烦人元素", url = "https://secure.fanboy.co.nz/fanboy-annoyance.txt", on = false },
}

local S -- script record
local data = lx.data(ID)

local compiled_json = nil     -- 推给渲染进程的编译结果（字符串）
local compiled_stats = nil
local browser_engine = nil    -- 浏览器进程侧引擎（导航级判定）
local generation = 0
local compiling = false
local updating = false
local tab_stats = {}          -- tab id -> { host, count, samples }
local total_blocked = 0
local total_dirty = false
local list_meta = {}          -- id -> { updated, bytes, rules, ok, error }

-- ===== 设置 =====
S = lx.register({
    id = ID, name = "去广告", version = "1.0.0", icon = "🛡",
    description = "拦截广告与追踪器，隐藏广告元素。规则来自 EasyList 等订阅，可加自定义规则和站点白名单。",
    replaces = "uBlock Origin · AdBlock · Adblock Plus · AdGuard",
    settings = {
        enabled = true,
        lists = {},              -- id -> bool（覆盖默认 on）
        custom_urls = {},        -- 额外订阅 URL
        custom_rules = "",       -- ABP 语法
        whitelist = {},          -- host 模式：example.com / *.example.com
        cosmetic = true,
        block_subframes = true,
        update_hours = 72,
        badge = false,
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用去广告", section = "总开关" },
        { key = "cosmetic", type = "bool", label = "元素隐藏", desc = "隐藏页面里的广告占位（EasyList 的 ## 规则）" },
        { key = "block_subframes", type = "bool", label = "拦截广告 iframe 导航", desc = "子框架导航也按 $subdocument 规则判定" },
        { key = "badge", type = "bool", label = "顶栏显示本页拦截数", desc = "在地址栏右侧挂一个小角标" },
        { key = "update_hours", type = "select", label = "订阅更新频率", section = "订阅", options = { { 24, "每天" }, { 72, "每 3 天" }, { 168, "每周" }, { 0, "手动" } } },
        { key = "custom_urls", type = "list", label = "额外订阅地址", desc = "一行一个 URL，ABP / hosts 格式都可以", section = "自定义", placeholder = "https://example.com/list.txt" },
        { key = "custom_rules", type = "text", label = "自定义规则", desc = "ABP 语法：||ads.example.com^   example.com##.banner   @@||cdn.example.com^", placeholder = "||ads.example.com^\nexample.com##.banner" },
        { key = "whitelist", type = "list", label = "站点白名单", desc = "一行一个域名，这些站完全不拦。*.example.com 含所有子域", section = "白名单", placeholder = "example.com" },
    },
    menu = {
        { id = "open", title = "去广告", onClick = function() S.open_panel() end },
    },
    api = {},
})
local settings = S.settings

local function list_enabled(l)
    local o = settings:get("lists")
    if o[l.id] ~= nil then return o[l.id] end
    return l.on
end

local function all_lists()
    local out = {}
    for _, l in ipairs(DEFAULT_LISTS) do out[#out + 1] = l end
    for i, url in ipairs(settings:get("custom_urls")) do
        if type(url) == "string" and url:match("^https?://") then
            out[#out + 1] = { id = "custom" .. i, name = util.host_of(url), desc = url, url = url, on = true, custom = true }
        end
    end
    return out
end

-- ===== 编译（分片，不长时间卡住 Lua 线程）=====
local function chunks_of(text, lines_per)
    local out, buf, n = {}, {}, 0
    for line in (text .. "\n"):gmatch("([^\n]*)\n") do
        buf[#buf + 1] = line
        n = n + 1
        if n >= lines_per then
            out[#out + 1] = table.concat(buf, "\n")
            buf, n = {}, 0
        end
    end
    if n > 0 then out[#out + 1] = table.concat(buf, "\n") end
    return out
end

local push_all

local function compile(done)
    if compiling then return end
    compiling = true
    local t0 = os.clock()
    local c = abp.compiler()
    local sources = { { name = "builtin", text = builtin } }
    for _, l in ipairs(all_lists()) do
        if list_enabled(l) then
            local text = data:read("lists/" .. l.id .. ".txt")
            if text and #text > 0 then sources[#sources + 1] = { name = l.id, text = text } end
        end
    end
    local custom = settings:get("custom_rules")
    if type(custom) == "string" and util.trim(custom) ~= "" then
        sources[#sources + 1] = { name = "custom", text = custom }
    end
    local per_list = {}
    local queue = {}
    for _, src in ipairs(sources) do
        for _, chunk in ipairs(chunks_of(src.text, 4000)) do
            queue[#queue + 1] = { name = src.name, text = chunk }
        end
    end
    local i = 0
    local function step()
        local deadline = os.clock() + 0.04   -- 每片最多 40ms，然后让出
        while i < #queue and os.clock() < deadline do
            i = i + 1
            local q = queue[i]
            local n = c:add(q.text, q.name)
            per_list[q.name] = (per_list[q.name] or 0) + n
        end
        if i < #queue then
            return lx.after(1, step)
        end
        local compiled = c:finish()
        compiled_json = json.encode(compiled)
        compiled_stats = compiled.stats
        generation = os.time()
        browser_engine = abp.engine(compiled)
        compiling = false
        for name, n in pairs(per_list) do
            list_meta[name] = list_meta[name] or {}
            list_meta[name].rules = n
        end
        data:write("compiled.json", compiled_json)
        data:write_json("meta.json", { generation = generation, stats = compiled_stats, lists = list_meta, total_blocked = total_blocked })
        lx.log("adblock: compiled %d network + %d hosts + %d cosmetic (%d skipped) in %.0f ms, %d KB",
            compiled_stats.network, compiled_stats.hosts, compiled_stats.cosmetic, compiled_stats.skipped,
            (os.clock() - t0) * 1000, #compiled_json // 1024)
        collectgarbage("collect")
        push_all()
        if done then done(true) end
    end
    step()
end

-- ===== 推送 =====
local function whitelist_list()
    local out = {}
    for _, w in ipairs(settings:get("whitelist")) do
        if type(w) == "string" and util.trim(w) ~= "" then out[#out + 1] = util.trim(w):lower() end
    end
    return out
end

local function push_pid(pid)
    if not compiled_json then return end
    lx.web.send_pid(CHANNEL, pid, "rules", compiled_json, generation, whitelist_list())
end

push_all = function()
    if not compiled_json then return end
    lx.web.broadcast(CHANNEL, "rules", compiled_json, generation, whitelist_list())
end

local ch = lx.web.require("adblock.web")
lx.web.on_process(push_pid)
ch:add_signal("hello", function(_, pid)
    if type(pid) == "number" then
        push_pid(pid)
        lx.web.send_pid(CHANNEL, pid, "cosmetic", settings:get("cosmetic") ~= false)
    end
end)

-- 统计回报
ch:add_signal("blocked", function(_, page_id, host, n, samples)
    if type(page_id) ~= "number" then return end
    local st = tab_stats[page_id]
    if not st or st.host ~= host then
        st = { host = host, count = 0, samples = {} }
        tab_stats[page_id] = st
    end
    st.count = st.count + (tonumber(n) or 0)
    if type(samples) == "table" then
        for _, s in ipairs(samples) do
            table.insert(st.samples, 1, s)
            if #st.samples > 40 then table.remove(st.samples) end
        end
    end
    total_blocked = total_blocked + (tonumber(n) or 0)
    total_dirty = true
    lx.after(0, function() S.update_badge(page_id) end)
end)

pcall(lemurx.tabs.on, "closed", function(t) if t and t.id then tab_stats[t.id] = nil end end)
pcall(lemurx.tabs.on, "started", function(t)
    if t and t.id and t.event ~= "started" then return end
    if t and t.id then
        local st = tab_stats[t.id]
        if st and st.host ~= util.host_of(t.eventUrl or t.url or "") then tab_stats[t.id] = nil end
    end
end)

lx.every(30000, function()
    if total_dirty then
        total_dirty = false
        local meta = data:read_json("meta.json") or {}
        meta.total_blocked = total_blocked
        data:write_json("meta.json", meta)
    end
end)

-- ===== 角标 =====
function S.update_badge(tab_id)
    if not settings:get("badge") then return end
    local cur = lx.tabs.current()
    if not cur or (tab_id and cur.id ~= tab_id) then return end
    local st = tab_stats[cur.id]
    local n = st and st.count or 0
    local h = lemurx.ui.h
    pcall(lemurx.ui.render, "toolbar.end", h("button", {
        id = "lx_adblock_badge", text = n > 0 and ("🛡" .. n) or "🛡", size = 11,
        background = { color = n > 0 and "#FF1B5E20" or "#00000000", radius = 10 }, color = "#FFFFFFFF",
        paddingH = 6, paddingV = 2,
        onClick = function() S.open_panel() end,
    }))
end
pcall(lemurx.tabs.on, "selected", function() S.update_badge() end)
pcall(lemurx.tabs.on, "loaded", function(t) S.update_badge(t and t.id) end)

-- ===== 导航级拦截：白名单 / $document / 子框架 =====
local function is_whitelisted(host)
    for _, w in ipairs(whitelist_list()) do
        if util.host_matches(host, w) then return true end
    end
    return false
end

lx.on_navigation(function(view, uri, ev)
    if not settings:get("enabled") or not browser_engine then return nil end
    if not uri:match("^https?://") then return nil end
    local host = util.host_of(uri)
    if ev.main_frame ~= false then
        -- 主框架：只处理 $document 拦截（很少见），白名单站放行
        if is_whitelisted(host) then return nil end
        local v = browser_engine:match(uri, { type = "document", page = host, initiator = host })
        if v == "block" then
            lx.toast("去广告：已拦截跳转 " .. host)
            return false
        end
        return nil
    end
    if not settings:get("block_subframes") then return nil end
    local page_uri = view.uri or ""
    local page_host = util.host_of(page_uri)
    if page_host == "" or is_whitelisted(page_host) then return nil end
    if browser_engine:page_flags(page_host).document then return nil end
    local v, by = browser_engine:match(uri, { type = "sub_frame", page = page_host, initiator = page_host })
    if v == "block" then
        local st = tab_stats[view.id]
        if not st or st.host ~= page_host then
            st = { host = page_host, count = 0, samples = {} }
            tab_stats[view.id] = st
        end
        st.count = st.count + 1
        table.insert(st.samples, 1, { url = uri:sub(1, 200), type = "subdocument", filter = browser_engine:describe(by) })
        total_blocked = total_blocked + 1
        total_dirty = true
        return false
    end
    return nil
end, 10)

-- ===== 订阅更新 =====
local function update_lists(force, done)
    if updating then if done then done(false, "正在更新") end return end
    local lists = {}
    for _, l in ipairs(all_lists()) do
        if list_enabled(l) then lists[#lists + 1] = l end
    end
    if #lists == 0 then
        compile(done)
        return
    end
    updating = true
    local pending = #lists
    local changed = false
    local function finish_one()
        pending = pending - 1
        if pending > 0 then return end
        updating = false
        data:write_json("meta.json", { generation = generation, stats = compiled_stats, lists = list_meta, total_blocked = total_blocked, last_update = util.now_ms() })
        if changed or force or not compiled_json then
            compile(done)
        elseif done then
            done(true)
        end
    end
    for _, l in ipairs(lists) do
        local meta = list_meta[l.id] or {}
        list_meta[l.id] = meta
        lx.fetch(l.url, { timeout = 60000 }, function(r)
            if r and r.ok and type(r.body) == "string" and #r.body > 100 then
                local old = data:read("lists/" .. l.id .. ".txt")
                if old ~= r.body then
                    data:write("lists/" .. l.id .. ".txt", r.body)
                    changed = true
                end
                meta.updated = util.now_ms()
                meta.bytes = #r.body
                meta.ok = true
                meta.error = nil
                lx.log("adblock: %s updated (%d KB)", l.id, #r.body // 1024)
            else
                meta.ok = false
                meta.error = r and (r.error or ("HTTP " .. tostring(r.status))) or "no response"
                lx.log("adblock: %s update failed: %s", l.id, tostring(meta.error))
            end
            finish_one()
        end)
    end
end

local function maybe_auto_update()
    local hours = tonumber(settings:get("update_hours")) or 72
    if hours <= 0 then return end
    local meta = data:read_json("meta.json") or {}
    local last = tonumber(meta.last_update) or 0
    if util.now_ms() - last > hours * 3600 * 1000 then
        update_lists(false)
    end
end

-- ===== 设置变化 =====
settings:on_change(function(key, value)
    if key == "enabled" then
        lx.web.broadcast(CHANNEL, "enable", value ~= false)
        if value == false then pcall(lemurx.ui.unmount, "lx_adblock_badge") end
    elseif key == "whitelist" then
        lx.web.broadcast(CHANNEL, "whitelist", whitelist_list())
    elseif key == "custom_rules" or key == "lists" then
        compile()
    elseif key == "custom_urls" then
        update_lists(true)
    elseif key == "badge" then
        if value then S.update_badge() else pcall(lemurx.ui.unmount, "lx_adblock_badge") end
    elseif key == "cosmetic" then
        lx.web.broadcast(CHANNEL, "cosmetic", value ~= false)
    elseif key == nil then
        compile()
    end
end)

-- ===== API（设置页用）=====
S.api["stats"] = function(args)
    local cur = lx.tabs.current()
    local st = cur and tab_stats[cur.id]
    local meta = data:read_json("meta.json") or {}
    return {
        total = total_blocked, page = st and st.count or 0, host = st and st.host or (cur and util.host_of(cur.url or "") or ""),
        page_url = cur and cur.url or "", tab = cur and cur.id or -1,
        rules = compiled_stats or {}, generation = generation, last_update = meta.last_update,
        compiling = compiling, updating = updating,
        whitelisted = (cur and st and is_whitelisted(st.host)) or (cur and is_whitelisted(util.host_of(cur.url or ""))) or false,
        processes = #lx.web.processes(),
    }
end
S.api["log"] = function(args)
    local tab = tonumber(args.tab)
    local cur = tab and { id = tab } or lx.tabs.current()
    local st = cur and tab_stats[cur.id]
    return { host = st and st.host or "", count = st and st.count or 0, samples = st and st.samples or {} }
end
S.api["lists"] = function()
    local out = {}
    for _, l in ipairs(all_lists()) do
        local m = list_meta[l.id] or {}
        local cached = data:exists("lists/" .. l.id .. ".txt")
        out[#out + 1] = { id = l.id, name = l.name, desc = l.desc, url = l.url, enabled = list_enabled(l), custom = l.custom or false,
            updated = m.updated, bytes = m.bytes, rules = m.rules, ok = m.ok, error = m.error, cached = cached }
    end
    return { lists = out }
end
S.api["list.toggle"] = function(args)
    local o = settings:get("lists")
    o[tostring(args.id)] = args.enabled and true or false
    settings:set("lists", o, true)
    if args.enabled and not data:exists("lists/" .. tostring(args.id) .. ".txt") then
        update_lists(true)
        return { ok = true, message = "正在下载订阅…" }
    end
    compile()
    return { ok = true, message = "正在重新编译…" }
end
S.api["update"] = function(args, ctx)
    update_lists(true, function(ok, err)
        ctx.reply({ ok = ok, message = ok and "订阅已更新" or ("更新失败：" .. tostring(err)), reload = true })
    end)
    return "async"
end
S.api["whitelist.toggle"] = function(args)
    local host = tostring(args.host or "")
    if host == "" then return nil, "host required" end
    local wl = settings:get("whitelist")
    local found = nil
    for i, w in ipairs(wl) do if w == host then found = i end end
    if found then table.remove(wl, found) else wl[#wl + 1] = host end
    settings:set("whitelist", wl)
    local tab = tonumber(args.tab)
    if tab and tab >= 0 then lx.after(200, function() pcall(lemurx.tabs.reload, tab) end) end
    return { ok = true, message = found and ("已恢复拦截 " .. host) or ("已加入白名单 " .. host), whitelisted = not found }
end
S.api["test"] = function(args)
    if not browser_engine then return { verdict = "no engine" } end
    local url = tostring(args.url or "")
    local v, by = browser_engine:match(url, { type = args.type or "script", page = args.page or util.host_of(url), initiator = args.page or util.host_of(url) })
    return { verdict = v or "pass", filter = browser_engine:describe(by) }
end
S.api["reset_stats"] = function()
    total_blocked = 0
    total_dirty = true
    tab_stats = {}
    return { ok = true, message = "统计已清零", reload = true }
end

-- ===== 原生面板（lemurx.ui，Android 控件，不是网页）=====
local PANEL = "page.center"
local draft = {}

local function panel_stats()
    return S.api.stats()
end

local function ago(ms)
    if not ms or ms == 0 then return "从未" end
    return os.date("%m-%d %H:%M", math.floor(ms / 1000))
end

function S.close_panel()
    pcall(lemurx.ui.unmount, PANEL)
end

function S.show_source()
    pcall(lemurx.intent.startActivity, {
        action = "lemurx.scripts",
        extras = { lemurx_script = "official/adblock.lua", lemurx_view = "source" },
    })
end

local function sw(id, label, checked, fn)
    local h = lemurx.ui.h
    return h("switch", {
        id = id, text = label, checked = checked and true or false, color = "#FF1D1D1F",
        onChange = function(ev) fn(ev.checked == true) end,
    })
end

local function btn(id, label, fn)
    local h = lemurx.ui.h
    return h("button", {
        id = id, text = label, color = "#FFFFFFFF",
        background = { color = "#FF1B5E20", radius = 8 },
        paddingH = 12, paddingV = 8, margin = { 0, 4, 8, 4 },
        onClick = fn,
    })
end

function S.open_panel(mode)
    mode = mode or "main"
    local h = lemurx.ui.h
    local head = h("row", { padding = { 4, 8, 4, 4 } }, {
        h("text", { text = "去广告", size = 18, bold = true, color = "#FF1D1D1F", weight = 1 }),
        h("button", { id = "ab_close", text = "关闭", onClick = function() S.close_panel() end }),
    })
    local body
    if mode == "lists" then
        local rows = { head, h("text", { text = "订阅", size = 13, color = "#FF6E6E73", padding = { 4, 0 } }) }
        for _, l in ipairs(S.api.lists().lists) do
            local meta = l.enabled and (l.ok == false and ("失败 " .. tostring(l.error))
                or (l.cached and ((l.rules or 0) .. " 条 · " .. util.human_bytes(l.bytes or 0) .. " · " .. ago(l.updated)) or "尚未下载")) or "关闭"
            rows[#rows + 1] = sw("ab_l_" .. l.id, l.name .. (l.custom and "（自定义）" or ""), l.enabled, function(on)
                S.api["list.toggle"]({ id = l.id, enabled = on })
                lx.toast(on and "已打开，正在准备规则" or "已关闭")
                lx.after(400, function() S.open_panel("lists") end)
            end)
            rows[#rows + 1] = h("text", { text = (l.desc or "") .. "\n" .. meta, size = 12, color = "#FF6E6E73", padding = { 8, 0, 8, 6 } })
        end
        rows[#rows + 1] = btn("ab_back", "返回", function() S.open_panel("main") end)
        body = rows
    elseif mode == "rules" then
        draft.rules = settings:get("custom_rules") or ""
        draft.wl = table.concat(settings:get("whitelist") or {}, "\n")
        draft.urls = table.concat(settings:get("custom_urls") or {}, "\n")
        body = {
            head,
            h("text", { text = "自定义规则（ABP 语法，一行一条）", size = 13, color = "#FF6E6E73" }),
            h("edit", { id = "ab_rules", value = draft.rules, inputType = "multiline", hint = "||ads.example.com^\nexample.com##.banner", minHeight = 120,
                onChange = function(ev) draft.rules = ev.text or "" end }),
            h("text", { text = "白名单（一行一个域名）", size = 13, color = "#FF6E6E73" }),
            h("edit", { id = "ab_wl", value = draft.wl, inputType = "multiline", hint = "example.com", minHeight = 72,
                onChange = function(ev) draft.wl = ev.text or "" end }),
            h("text", { text = "额外订阅地址（一行一个 URL）", size = 13, color = "#FF6E6E73" }),
            h("edit", { id = "ab_urls", value = draft.urls, inputType = "multiline", hint = "https://example.com/list.txt", minHeight = 72,
                onChange = function(ev) draft.urls = ev.text or "" end }),
            h("row", {}, {
                btn("ab_save_rules", "保存", function()
                    local function lines(s)
                        local t = {}
                        for line in (tostring(s or "") .. "\n"):gmatch("([^\n]*)\n") do
                            line = util.trim(line)
                            if line ~= "" then t[#t + 1] = line end
                        end
                        return t
                    end
                    settings:set("custom_rules", draft.rules or "")
                    settings:set("whitelist", lines(draft.wl))
                    settings:set("custom_urls", lines(draft.urls))
                    lx.toast("已保存")
                    S.open_panel("main")
                end),
                btn("ab_back2", "返回", function() S.open_panel("main") end),
            }),
        }
    elseif mode == "log" then
        local log = S.api.log({})
        local lines = {}
        for _, s in ipairs(log.samples or {}) do
            lines[#lines + 1] = "[" .. tostring(s.type) .. "] " .. tostring(s.url)
            if s.filter and s.filter ~= "" then lines[#lines + 1] = "    " .. s.filter end
        end
        body = {
            head,
            h("text", { text = #lines > 0 and table.concat(lines, "\n") or "本页还没有拦截记录", size = 12, color = "#FF1D1D1F" }),
            btn("ab_back3", "返回", function() S.open_panel("main") end),
        }
    else
        local st = panel_stats()
        local rules = (st.rules.network or 0) + (st.rules.hosts or 0)
        local host = st.host ~= "" and st.host or "（没有打开网页）"
        body = {
            head,
            h("text", {
                id = "ab_stat", size = 14, color = "#FF1D1D1F",
                text = ("本页 %d    累计 %d\n网络规则 %d    元素隐藏 %d\n%s\n订阅 %s%s%s"):format(
                    st.page or 0, st.total or 0, rules, st.rules.cosmetic or 0, host, ago(st.last_update),
                    st.updating and " · 正在下载" or "", st.compiling and " · 正在编译" or ""),
            }),
            sw("ab_en", "启用去广告", settings:get("enabled"), function(on) settings:set("enabled", on) end),
            sw("ab_cos", "元素隐藏", settings:get("cosmetic"), function(on) settings:set("cosmetic", on) end),
            sw("ab_sub", "拦截广告 iframe", settings:get("block_subframes"), function(on) settings:set("block_subframes", on) end),
            sw("ab_badge", "顶栏显示本页拦截数", settings:get("badge"), function(on) settings:set("badge", on) end),
            h("row", {}, {
                btn("ab_wl_site", st.whitelisted and "恢复本站拦截" or "本站加入白名单", function()
                    if st.host == "" then lx.toast("没有可操作的网页") return end
                    local r = S.api["whitelist.toggle"]({ host = st.host, tab = st.tab })
                    lx.toast(r and r.message or "已更新")
                    S.open_panel("main")
                end),
                btn("ab_upd", "更新订阅", function()
                    lx.toast("开始更新订阅")
                    update_lists(true, function(ok, err)
                        lx.toast(ok and "订阅已更新" or ("更新失败：" .. tostring(err)))
                        S.open_panel("main")
                    end)
                end),
            }),
            h("row", {}, {
                btn("ab_lists", "订阅列表", function() S.open_panel("lists") end),
                btn("ab_rules", "规则与白名单", function() S.open_panel("rules") end),
            }),
            h("row", {}, {
                btn("ab_log", "本页拦截记录", function() S.open_panel("log") end),
                btn("ab_src", "查看 Lua 源码", function() S.show_source() end),
            }),
        }
    end
    pcall(lemurx.ui.render, PANEL, h("scroll", {
        id = "lx_adblock_panel",
        width = "match", height = "match",
        background = "#F7F2F2F7",
        padding = 12,
    }, {
        h("column", { padding = 4 }, body),
    }))
end

-- ===== 启动 =====
do
    local meta = data:read_json("meta.json") or {}
    list_meta = type(meta.lists) == "table" and meta.lists or {}
    total_blocked = tonumber(meta.total_blocked) or 0
    local cached = data:read("compiled.json")
    if cached and #cached > 2 then
        local t = json.decode(cached)
        if type(t) == "table" and t.fp then
            compiled_json = cached
            compiled_stats = t.stats
            generation = tonumber(meta.generation) or os.time()
            browser_engine = abp.engine(t)
            lx.log("adblock: loaded cached rules gen %d (%d KB)", generation, #cached // 1024)
            push_all()
        end
    end
    if not compiled_json then
        -- 首次：先用内置规则顶上，订阅到了再换
        compile()
    end
    lx.after(5000, maybe_auto_update)
    lx.every(6 * 3600 * 1000, maybe_auto_update)
    if settings:get("badge") then lx.after(1500, function() S.update_badge() end) end
end
