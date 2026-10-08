-- @name 专注与时间
-- @description 给分心网站设定每日时间预算，超额即封锁（StayFocusd）；核弹模式一键屏蔽；自动记录每个网站花了多少时间，按天/周看报表（Toggl Track / RescueTime）。
-- @version 1.0.0
-- @icon 🎯
-- @category 效率
-- @page lemurx://focus/
-- @replaces StayFocusd · Toggl Track · RescueTime · BlockSite · LeechBlock
--
-- 机制：
--   * 计时：每 10 秒看一眼当前标签（应用在前台 && http(s) 页面）→ 今天该站点 +10s。
--     前后台由 chrome.on("app") 告知（Java 侧 ApplicationStatus），后台不计时。
--   * 预算：block_list 里的站点（支持 *.host）每天 daily_minutes 分钟（可按站单独设），
--     只在 active_days / active_from~active_to 内生效；用完 → 导航拦到 lemurx://focus/blocked。
--     正在看的页面用完预算也会被当场跳走。
--   * 核弹：nuclear_until 之前，屏蔽 block_list（mode=list）或除 allow_list 外全部站点（mode=all），无视预算。
--   * 锁定：lock=true 时，在生效时段内放宽限制（加分钟、删站点、关掉）要抄一段话确认——StayFocusd 的经典设计。
local lx = require("lx")
local json = require("lx.json")
local util = require("lx.util")

local ID = "focus"
local TICK = 10 -- 秒
local CHALLENGE = "我知道自己在做什么，我选择现在分心。"
local S

S = lx.register({
    id = ID, name = "专注与时间", version = "1.0.0", icon = "🎯",
    description = "给分心网站设定每日时间预算，超额即封锁（StayFocusd）；核弹模式一键屏蔽一切；自动记录每个网站花的时间，按天 / 周看报表（Toggl Track）。",
    replaces = "StayFocusd · Toggl Track · RescueTime · BlockSite · LeechBlock",
    settings = {
        enabled = true,
        track = true,
        block_list = {},            -- host 模式列表
        daily_minutes = 30,
        budgets = {},               -- host -> 分钟（覆盖 daily_minutes）
        active_days = { 1, 2, 3, 4, 5 },   -- 1=周一 … 7=周日
        active_from = "00:00",
        active_to = "23:59",
        lock = false,
        nuclear_until = 0,
        nuclear_mode = "list",      -- list | all
        allow_list = {},            -- 核弹 all 模式下仍可访问
        keep_days = 60,
        warn_minutes = 5,           -- 剩余多少分钟提醒一次
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用封锁", section = "时间预算" },
        { key = "block_list", type = "list", label = "分心网站", desc = "每行一个 host，支持 *.example.com；预算用完当天就打不开" },
        { key = "daily_minutes", type = "number", label = "每日预算（分钟）", min = 0, max = 1440 },
        { key = "warn_minutes", type = "number", label = "剩余几分钟时提醒", min = 0, max = 60 },
        { key = "active_days", type = "list", label = "生效日", desc = "1=周一 … 7=周日，每行一个数字" },
        { key = "active_from", type = "string", label = "生效开始（HH:MM）", placeholder = "09:00" },
        { key = "active_to", type = "string", label = "生效结束（HH:MM）", placeholder = "18:00" },
        { key = "lock", type = "bool", label = "锁定：生效时段内放宽限制要抄一句话确认" },
        { key = "nuclear_mode", type = "select", label = "核弹模式屏蔽", section = "核弹", options = { { "list", "只屏蔽分心网站" }, { "all", "除白名单外全部屏蔽" } } },
        { key = "allow_list", type = "list", label = "核弹白名单", desc = "all 模式下仍可访问的 host" },
        { key = "track", type = "bool", label = "记录网站使用时间", section = "时间记录" },
        { key = "keep_days", type = "number", label = "保留天数", min = 7, max = 365 },
        { key = "clear", type = "action", label = "清空时间记录", api = "usage.clear", style = "danger" },
    },
    menu = {
        { id = "open", title = "专注与时间", page = "main" },
        { id = "block_current", title = "把当前网站加入分心列表", onClick = function() S.block_current() end },
    },
    api = {},
})
local settings = S.settings
local data = lx.data(ID)

-- ===== 用量存储：usage[YYYY-MM-DD][host] = 秒 =====
local usage = data:read_json("usage.json") or {}
local dirty, save_timer = false, nil
local function save_later()
    dirty = true
    if save_timer then return end
    save_timer = lx.after(15000, function()
        save_timer = nil
        if dirty then dirty = false data:write_json("usage.json", usage) end
    end)
end
local function prune()
    local keep = tonumber(settings:get("keep_days")) or 60
    local cutoff = os.date("%Y-%m-%d", os.time() - keep * 86400)
    for day in pairs(usage) do if day < cutoff then usage[day] = nil dirty = true end end
end
prune()

local function today_usage() local d = util.today() usage[d] = usage[d] or {} return usage[d] end

-- ===== 规则 =====
local function in_list(host, list)
    if host == "" then return false end
    for _, pat in ipairs(list or {}) do if util.host_matches(host, pat) then return true, pat end end
    return false
end
local function hm(s)
    local h, m = tostring(s or ""):match("^(%d%d?):(%d%d)$")
    if not h then return nil end
    return tonumber(h) * 60 + tonumber(m)
end
local function active_now(now)
    now = now or os.time()
    local wday = tonumber(os.date("%w", now)) -- 0=周日
    local d = wday == 0 and 7 or wday
    local days = settings:get("active_days") or {}
    local ok = false
    for _, x in ipairs(days) do if tonumber(x) == d then ok = true break end end
    if not ok then return false end
    local from, to = hm(settings:get("active_from")) or 0, hm(settings:get("active_to")) or 1439
    local cur = tonumber(os.date("%H", now)) * 60 + tonumber(os.date("%M", now))
    if from <= to then return cur >= from and cur <= to end
    return cur >= from or cur <= to -- 跨午夜
end
S.active_now = active_now

-- 该 host 的预算（秒）；匹配到的模式若单独设了 budgets 就用它
local function budget_for(host)
    local hit, pat = in_list(host, settings:get("block_list"))
    if not hit then return nil end
    local b = settings:get("budgets") or {}
    local m = tonumber(b[pat]) or tonumber(b[host]) or tonumber(settings:get("daily_minutes")) or 30
    return m * 60, pat
end
-- 今天在这条规则下已用秒数：同一模式下所有匹配 host 的和（*.twitter.com 覆盖 twitter.com 和 mobile.twitter.com）
local function used_for(pat)
    local sum = 0
    for h, sec in pairs(today_usage()) do if util.host_matches(h, pat) then sum = sum + sec end end
    return sum
end

local function nuclear_active(now)
    local until_ms = tonumber(settings:get("nuclear_until")) or 0
    return until_ms > (now or util.now_ms())
end

-- blocked(host) -> reason|nil ； reason = "nuclear" | "budget"
function S.blocked(host, now)
    if not settings:get("enabled") or host == "" then return nil end
    now = now or os.time()
    if nuclear_active(now * 1000) then
        if settings:get("nuclear_mode") == "all" then
            if not in_list(host, settings:get("allow_list")) and not in_list(host, { "lemurx" }) then return "nuclear" end
        elseif in_list(host, settings:get("block_list")) then
            return "nuclear"
        end
    end
    if not active_now(now) then return nil end
    local budget, pat = budget_for(host)
    if budget and used_for(pat) >= budget then return "budget" end
    return nil
end
function S.remaining(host)
    local budget, pat = budget_for(host)
    if not budget then return nil end
    return math.max(0, budget - used_for(pat)), budget
end

local function block_url(host, url, reason)
    return "lemurx://focus/blocked?" .. util.url.build_query({ host = host, url = url, reason = reason })
end

-- ===== 导航拦截 =====
lx.on_navigation(function(view, uri, ev)
    if ev.main_frame == false or not uri:match("^https?://") then return nil end
    local host = util.host_of(uri)
    local reason = S.blocked(host)
    if reason then return block_url(host, uri, reason) end
    return nil
end, 20)

-- ===== 计时 =====
local foreground = true
local warned = {}
local last_tick_day = util.today()
pcall(lemurx.chrome.on, "app", function(ev)
    foreground = not (type(ev) == "table" and ev.state == "background")
end)

local function tick()
    local day = util.today()
    if day ~= last_tick_day then last_tick_day = day warned = {} prune() end
    if not foreground then return end
    local t = lx.tabs.current()
    if not t or not (t.url or ""):match("^https?://") then return end
    local host = util.host_of(t.url)
    if host == "" then return end
    if settings:get("track") then
        local u = today_usage()
        u[host] = (u[host] or 0) + TICK
        save_later()
    end
    if not settings:get("enabled") then return end
    local reason = S.blocked(host)
    if reason then
        pcall(lemurx.tabs.navigate, t.id, block_url(host, t.url, reason))
        return
    end
    local rem, budget = S.remaining(host)
    if rem and active_now() then
        local warn = (tonumber(settings:get("warn_minutes")) or 0) * 60
        if warn > 0 and rem <= warn and not warned[host] then
            warned[host] = true
            lx.toast(("🎯 %s 今天还剩 %d 分钟"):format(host, math.ceil(rem / 60)))
        end
    end
end
S.tick = tick
lx.every(TICK * 1000, tick)

-- ===== 操作 =====
function S.block_current()
    local t = lx.tabs.current()
    local host = t and util.host_of(t.url or "") or ""
    if host == "" or not (t.url or ""):match("^https?://") then lx.toast("当前不是网页") return end
    host = host:gsub("^www%.", "")
    local list = settings:get("block_list") or {}
    for _, p in ipairs(list) do if util.host_matches(host, p) then lx.toast(host .. " 已在分心列表") return end end
    list[#list + 1] = "*." .. host
    settings:set("block_list", list)
    lx.toast(("已加入分心列表：%s（每天 %d 分钟）"):format(host, tonumber(settings:get("daily_minutes")) or 30))
end

function S.nuclear(minutes, mode)
    minutes = tonumber(minutes) or 60
    if mode then settings:set("nuclear_mode", mode) end
    settings:set("nuclear_until", util.now_ms() + minutes * 60000)
    lx.toast(("☢️ 核弹模式：%d 分钟"):format(minutes))
    -- 当场把被屏蔽的标签跳走
    for _, t in ipairs(lx.tabs.list()) do
        local host = util.host_of(t.url or "")
        local reason = (t.url or ""):match("^https?://") and S.blocked(host)
        if reason then pcall(lemurx.tabs.navigate, t.id, block_url(host, t.url, reason)) end
    end
end

-- 锁定：生效时段内放宽限制需要抄话
local RELAX_KEYS = { enabled = true, block_list = true, daily_minutes = true, budgets = true, active_days = true, active_from = true, active_to = true, lock = true, allow_list = true }
local base_set = S.api["settings.set"]
S.api["settings.set"] = function(args, ctx)
    if settings:get("lock") and RELAX_KEYS[args.key] and active_now() and args.confirm ~= CHALLENGE then
        -- 收紧不需要确认：加站点 / 减预算
        local tighten = false
        if args.key == "block_list" and type(args.value) == "table" and #args.value > #(settings:get("block_list") or {}) then tighten = true end
        if args.key == "daily_minutes" and tonumber(args.value) and tonumber(args.value) < (tonumber(settings:get("daily_minutes")) or 0) then tighten = true end
        if args.key == "enabled" and args.value == true then tighten = true end
        if not tighten then return nil, "LOCKED:" .. CHALLENGE end
    end
    return base_set(args, ctx)
end
S.api["nuclear.start"] = function(args) S.nuclear(args.minutes, args.mode) return { ok = true, message = "核弹已启动", reload = true } end
S.api["nuclear.stop"] = function(args)
    if settings:get("lock") and args.confirm ~= CHALLENGE then return nil, "LOCKED:" .. CHALLENGE end
    settings:set("nuclear_until", 0)
    return { ok = true, message = "已解除", reload = true }
end
S.api["budget.set"] = function(args, ctx)
    local b = settings:get("budgets") or {}
    if args.minutes == nil or args.minutes == "" then b[args.host] = nil else b[args.host] = tonumber(args.minutes) end
    return S.api["settings.set"]({ key = "budgets", value = b, confirm = args.confirm }, ctx)
end
S.api["usage.today"] = function() return { day = util.today(), usage = today_usage() } end
S.api["usage.range"] = function(args)
    local days = tonumber(args.days) or 7
    local out = {}
    for i = days - 1, 0, -1 do
        local d = os.date("%Y-%m-%d", os.time() - i * 86400)
        out[#out + 1] = { day = d, usage = usage[d] or {} }
    end
    return { days = out }
end
S.api["usage.clear"] = function() usage = {} data:write_json("usage.json", usage) return { ok = true, message = "已清空", reload = true } end
S.api.status = function()
    local t = lx.tabs.current()
    local host = t and util.host_of(t.url or "") or ""
    local rem, budget = S.remaining(host)
    return { host = host, blocked = S.blocked(host), remaining = rem, budget = budget, active = active_now(), nuclear_until = settings:get("nuclear_until"), foreground = foreground }
end

-- ===== 页面 =====
local esc = lx.html.escape
local function fmt_dur(sec)
    sec = math.floor(sec or 0)
    if sec < 60 then return sec .. "s" end
    if sec < 3600 then return math.floor(sec / 60) .. "m" end
    return ("%dh%02dm"):format(math.floor(sec / 3600), math.floor(sec % 3600 / 60))
end
local function sorted_usage(u)
    local arr = {}
    for h, s in pairs(u or {}) do arr[#arr + 1] = { h, s } end
    table.sort(arr, function(a, b) return a[2] > b[2] end)
    return arr
end

S.routes["/blocked"] = function(ctx)
    local q = ctx.query or {}
    local host, reason = q.host or "", q.reason or "budget"
    local rem
    local nuclear_left = math.max(0, math.floor(((tonumber(settings:get("nuclear_until")) or 0) - util.now_ms()) / 60000))
    local title = reason == "nuclear" and "☢️ 核弹模式中" or "🎯 今天的预算用完了"
    local desc = reason == "nuclear"
        and ("核弹模式还有 %d 分钟结束。这段时间 %s 打不开。"):format(nuclear_left, esc(host))
        or ("%s 今天的 %d 分钟已经用完。明天再来，或者现在去做点别的。"):format(esc(host), math.floor((select(2, S.remaining(host)) or 0) / 60))
    local top = sorted_usage(today_usage())
    local rows = {}
    for i = 1, math.min(5, #top) do rows[#rows + 1] = ("<div class=\"row\"><div class=\"l\"><div class=\"t\">%s</div></div><span class=\"badge\">%s</span></div>"):format(esc(top[i][1]), fmt_dur(top[i][2])) end
    return lx.html.page({
        title = "已屏蔽", icon = "🎯", back_url = "lemurx://focus/", back_label = "专注与时间",
        body = ([[
<div class="card" style="text-align:center;padding:28px 16px"><div style="font-size:48px">%s</div><h2 style="margin:12px 0 6px">%s</h2><p class="muted">%s</p>
<div class="actions" style="justify-content:center"><a class="btn" href="lemurx://newtab/">去新标签页</a><a class="btn sec" href="lemurx://focus/">看看今天的时间</a></div></div>
<div class="card list"><div class="row"><div class="l"><div class="t">今天花时间最多的网站</div></div></div>%s</div>
<p class="muted" style="text-align:center">%s</p>]]):format(reason == "nuclear" and "☢️" or "🎯", title, desc, table.concat(rows),
            settings:get("lock") and "已锁定：解除需要抄写确认语。" or "想放宽？去设置里改预算——但真的有必要吗？"),
    }), "text/html", 200
end

S.page_css = [[
.bar{height:8px;border-radius:4px;background:var(--accent,#0a84ff);opacity:.85;margin-top:4px}
.bar.b{background:#ff453a}.wk{display:flex;gap:6px;align-items:flex-end;height:90px;padding:8px 0}.wk div{flex:1;display:flex;flex-direction:column;align-items:center;font-size:11px;color:var(--muted,#888)}.wk i{display:block;width:100%%;border-radius:4px 4px 0 0;background:var(--accent,#0a84ff);opacity:.8}
.nuke{display:flex;gap:8px;flex-wrap:wrap}.nuke button{flex:1;min-width:90px}
input.bud{width:64px;text-align:right}
]]
S.summary = function()
    local u = today_usage()
    local arr, total = sorted_usage(u), 0
    for _, x in ipairs(arr) do total = total + x[2] end
    local max = arr[1] and arr[1][2] or 1
    local rows = {}
    for i = 1, math.min(15, #arr) do
        local h, sec = arr[i][1], arr[i][2]
        local budget, pat = budget_for(h)
        local pct = math.floor(sec / max * 100)
        local blk = budget and used_for(pat) >= budget
        rows[#rows + 1] = ("<div class=\"row\"><div class=\"l\"><div class=\"t\">%s%s</div><div class=\"bar%s\" style=\"width:%d%%\"></div></div><span class=\"badge%s\">%s%s</span></div>")
            :format(esc(h), budget and (" <small style=\"opacity:.6\">预算 " .. math.floor(budget / 60) .. "m</small>") or "", blk and " b" or "", pct, blk and " on" or "", fmt_dur(sec),
                budget and (" / " .. fmt_dur(budget)) or "")
    end
    -- 近 7 天
    local wk = {}
    local wmax = 1
    local days = {}
    for i = 6, 0, -1 do
        local d = os.date("%Y-%m-%d", os.time() - i * 86400)
        local t = 0
        for _, s in pairs(usage[d] or {}) do t = t + s end
        days[#days + 1] = { d, t }
        if t > wmax then wmax = t end
    end
    for _, x in ipairs(days) do
        wk[#wk + 1] = ("<div><i style=\"height:%dpx\" title=\"%s\"></i>%s<br>%s</div>"):format(math.max(2, math.floor(x[2] / wmax * 60)), esc(x[1]), x[1]:sub(6), fmt_dur(x[2]))
    end
    -- 分心站点的预算表
    local buds = {}
    local b = settings:get("budgets") or {}
    for _, pat in ipairs(settings:get("block_list") or {}) do
        local used = used_for(pat)
        local bm = tonumber(b[pat]) or tonumber(settings:get("daily_minutes")) or 30
        buds[#buds + 1] = ("<div class=\"row\"><div class=\"l\"><div class=\"t\">%s</div><div class=\"d\">今天已用 %s</div></div><input class=\"bud\" type=\"number\" min=\"0\" data-host=\"%s\" value=\"%d\" placeholder=\"%d\"> 分钟</div>")
            :format(esc(pat), fmt_dur(used), esc(pat), bm, tonumber(settings:get("daily_minutes")) or 30)
    end
    local nuclear_left = math.max(0, math.floor(((tonumber(settings:get("nuclear_until")) or 0) - util.now_ms()) / 60000))
    return ([[
<div class="card"><div class="row" style="display:block"><div class="t">今天 <span class="badge">%s</span> %s</div><div class="d">%s</div></div>%s</div>
<div class="card"><div class="row" style="display:block"><div class="t">最近 7 天</div><div class="wk">%s</div></div></div>
<div class="card"><div class="row" style="display:block"><div class="t">☢️ 核弹模式 %s</div><div class="d">一段时间内彻底屏蔽（按上面「核弹」设置：只屏分心网站，或除白名单外全部）</div>
 <div class="nuke" style="margin-top:8px">%s</div></div></div>
%s]]):format(fmt_dur(total), active_now() and "<span class=\"badge on\">生效时段</span>" or "<span class=\"badge\">非生效时段</span>",
        #arr == 0 and "还没有记录，浏览一会儿再来看" or "", table.concat(rows), table.concat(wk),
        nuclear_left > 0 and ("<span class=\"badge on\">剩 " .. nuclear_left .. " 分钟</span>") or "",
        nuclear_left > 0 and "<button class=\"sec\" id=\"nstop\">解除</button>"
            or "<button data-nuke=\"25\">25 分钟</button><button data-nuke=\"60\">1 小时</button><button data-nuke=\"180\">3 小时</button><button data-nuke=\"480\">8 小时</button>",
        #buds > 0 and ("<div class=\"card list\"><div class=\"row\"><div class=\"l\"><div class=\"t\">各站预算</div><div class=\"d\">留空 / 不填用默认每日预算</div></div></div>" .. table.concat(buds) .. "</div>") or "")
end
S.page_js = [[
function locked(e){var m=/^LOCKED:(.*)$/.exec(e.message||'');if(!m)return null;var v=prompt('已锁定。要放宽限制，请原样抄写这句话：\n\n'+m[1]);return v===m[1]?v:false}
lx.set=function(k,v,c){return lx.api('settings.set',{key:k,value:v,confirm:c}).catch(function(e){var c2=locked(e);if(c2)return lx.set(k,v,c2);if(c2===false)throw new Error('确认语不对');throw e})};
document.querySelectorAll('[data-nuke]').forEach(function(b){b.onclick=function(){if(!confirm('启动核弹模式 '+b.dataset.nuke+' 分钟？期间无法提前解除（除非未锁定）。'))return;lx.api('nuclear.start',{minutes:+b.dataset.nuke}).then(function(){location.reload()})}});
var ns=lx.q('#nstop');if(ns)ns.onclick=function(){lx.api('nuclear.stop',{}).then(function(){location.reload()}).catch(function(e){var c=locked(e);if(c)lx.api('nuclear.stop',{confirm:c}).then(function(){location.reload()});else lx.toast(e.message)})};
document.querySelectorAll('input.bud').forEach(function(i){i.onchange=function(){lx.api('budget.set',{host:i.dataset.host,minutes:i.value}).then(function(){lx.toast('已保存')}).catch(function(e){var c=locked(e);if(c)lx.api('budget.set',{host:i.dataset.host,minutes:i.value,confirm:c}).then(function(){lx.toast('已保存')});else lx.toast(e.message)})}});
]]
return S
