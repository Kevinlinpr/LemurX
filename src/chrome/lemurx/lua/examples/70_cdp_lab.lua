-- 案例 70 · 整张 DevTools 协议在手：不用 USB、不用电脑，Lua 就是 DevTools
--
-- 扩展做不到的点：Android 上的扩展没有 chrome.debugger；桌面版即便有，也要弹一条黄色
-- 警告条，且不能跟别的调试器共存。这里 Lua 直接 attach 到 Tab 的 DevToolsAgentHost，
-- Network / Emulation / Performance / Page / DOM 域全开，还能收事件流。
--
-- 做的事：
--   1. 每站记忆：上次在这个站用了「桌面版 / 地理伪装 / 时区 / 弱网」哪几项，回来自动应用
--   2. 网页上方一条“实验台”：一键切换弱网 3G、假 GPS（东京）、时区、桌面 UA、慢速 CPU
--   3. 实时统计这一页的请求数/字节数/失败数（Network.* 事件流），并抓最慢的 5 个请求
--   4. 「体检」：Performance.getMetrics + 截图 + 请求榜，写成一份 HTML 报告到 files/lua/reports/

local h = lemurx.ui.h

local TOKYO = { lat = 35.6762, lng = 139.6503 }
local lab = {}       -- tabId -> { attached, stats = {...}, opts = {...} }

local function host_of(url) return (url or ""):match("^%a+://([^/]+)") or "" end

local function state_for(tab_id)
    local s = lab[tab_id]
    if not s then
        s = { attached = false, stats = { req = 0, bytes = 0, fail = 0, slow = {} }, pending = {}, opts = {} }
        lab[tab_id] = s
    end
    return s
end

local function ensure_attached(tab_id)
    local s = state_for(tab_id)
    if not s.attached then
        lemurx.cdp.attach(tab_id)
        lemurx.cdp.send(tab_id, "Network.enable", {})
        lemurx.cdp.send(tab_id, "Performance.enable", {})
        s.attached = true
    end
    return s
end

-- ---------- 每站记忆 ----------
local function load_opts(url)
    local raw = lemurx.storage.get("cdplab:" .. host_of(url))
    return raw and __luakit.json_decode(raw) or {}
end
local function save_opts(url, opts)
    lemurx.storage.set("cdplab:" .. host_of(url), __luakit.json_encode(opts))
end

local function apply(tab_id, opts)
    ensure_attached(tab_id)
    -- 弱网：Network.emulateNetworkConditions（3G 档）
    lemurx.cdp.send(tab_id, "Network.emulateNetworkConditions", opts.slow3g and {
        offline = false, latency = 300, downloadThroughput = 400 * 1024 / 8, uploadThroughput = 200 * 1024 / 8,
    } or { offline = false, latency = 0, downloadThroughput = -1, uploadThroughput = -1 })
    -- 假 GPS
    if opts.geo then
        lemurx.cdp.send(tab_id, "Emulation.setGeolocationOverride", { latitude = TOKYO.lat, longitude = TOKYO.lng, accuracy = 20 })
    else
        lemurx.cdp.send(tab_id, "Emulation.clearGeolocationOverride", {})
    end
    -- 时区
    lemurx.cdp.send(tab_id, "Emulation.setTimezoneOverride", { timezoneId = opts.tz and "Asia/Tokyo" or "" })
    -- 慢 CPU
    lemurx.cdp.send(tab_id, "Emulation.setCPUThrottlingRate", { rate = opts.slowcpu and 4 or 1 })
    -- 桌面版走LemurX自己的接口（UA + 视口一起换）
    lemurx.tabs.setDesktop(tab_id, opts.desktop and true or false)
end

-- ---------- Network 事件流 → 实时统计 ----------
lemurx.cdp.on("Network.requestWillBeSent", function(ev)
    local s = lab[ev.tab]
    if not s then return end
    local p = ev.params or {}
    s.stats.req = s.stats.req + 1
    s.pending[p.requestId] = { url = p.request and p.request.url, t0 = p.timestamp }
end)
lemurx.cdp.on("Network.loadingFinished", function(ev)
    local s = lab[ev.tab]
    if not s then return end
    local p = ev.params or {}
    s.stats.bytes = s.stats.bytes + (p.encodedDataLength or 0)
    local r = s.pending[p.requestId]
    if r and r.t0 and p.timestamp then
        r.ms = math.floor((p.timestamp - r.t0) * 1000)
        r.bytes = p.encodedDataLength or 0
        local slow = s.stats.slow
        slow[#slow + 1] = r
        table.sort(slow, function(a, b) return (a.ms or 0) > (b.ms or 0) end)
        if #slow > 5 then slow[6] = nil end
    end
    s.pending[p.requestId] = nil
end)
lemurx.cdp.on("Network.loadingFailed", function(ev)
    local s = lab[ev.tab]
    if s then s.stats.fail = s.stats.fail + 1 end
end)

-- ---------- 实验台 UI ----------
local function strip_text(s)
    return ("请求 %d · %.1f KB · 失败 %d"):format(s.stats.req, s.stats.bytes / 1024, s.stats.fail)
end

local function toggle(name, label)
    return h("switch", {
        id = "cdplab_" .. name, text = label, size = 11, color = "#FFD9E2EC",
        onChange = function(ev)
            local t = lemurx.tabs.current()
            if not t then return end
            local s = ensure_attached(t.id)
            s.opts[name] = ev.checked or nil
            save_opts(t.url, s.opts)
            apply(t.id, s.opts)
            lemurx.toast(label .. (ev.checked and " 开" or " 关") .. "，刷新页面生效")
        end,
    })
end

local function render_strip(tab_id)
    local s = state_for(tab_id)
    lemurx.ui.render("page.top", h("column", { id = "cdplab_strip", background = "#E6102A43", padding = { 8, 4 } }, {
        h("row", {}, {
            h("text", { id = "cdplab_stats", text = strip_text(s), color = "#FFFFFFFF", size = 11, weight = 1 }),
            h("button", { id = "cdplab_report", text = "体检", size = 11, onClick = function() report(tab_id) end }),
            h("button", { id = "cdplab_close", text = "×", size = 11, onClick = function() lemurx.ui.unmount("cdplab_strip") end }),
        }),
        h("hscroll", {}, {
            h("row", {}, {
                toggle("slow3g", "弱网3G"), toggle("geo", "东京GPS"), toggle("tz", "东京时区"),
                toggle("desktop", "桌面版"), toggle("slowcpu", "慢CPU×4"),
            }),
        }),
    }))
    for k, v in pairs(s.opts) do lemurx.ui.update("cdplab_" .. k, { checked = v and true or false }) end
end

-- 每秒刷一次统计
lemurx.timer.every(1000, function()
    local t = lemurx.tabs.current()
    if t and lab[t.id] and lab[t.id].attached then
        lemurx.ui.update("cdplab_stats", { text = strip_text(lab[t.id]) })
    end
end)

-- 新文档：清零统计 + 应用该站记忆
lemurx.tabs.on("started", function(ev)
    local s = lab[ev.id]
    if s then s.stats = { req = 0, bytes = 0, fail = 0, slow = {} } s.pending = {} end
    local opts = load_opts(ev.url)
    if next(opts) then
        local st = ensure_attached(ev.id)
        st.opts = opts
        apply(ev.id, opts)
    end
end)

-- ---------- 体检报告 ----------
function report(tab_id)
    ensure_attached(tab_id)
    local t = lemurx.tabs.current()
    local s = state_for(tab_id)
    local m = lemurx.cdp.send(tab_id, "Performance.getMetrics", {})
    local metrics = {}
    for _, kv in ipairs(m and m.result and m.result.metrics or {}) do metrics[kv.name] = kv.value end
    local shot = lemurx.tabs.screenshot(tab_id, { base64 = true })
    local rows = {}
    for _, r in ipairs(s.stats.slow) do
        rows[#rows + 1] = ("<tr><td>%d ms</td><td>%.1f KB</td><td>%s</td></tr>"):format(r.ms or 0, (r.bytes or 0) / 1024, r.url or "")
    end
    local html = ([[
<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<style>body{font:13px sans-serif;background:#0b1f33;color:#d9e2ec;padding:12px}td{padding:3px 6px;word-break:break-all}
img{max-width:100%%;border-radius:8px}</style>
<h2>%s</h2><p>%s · %s</p>
<p>请求 %d · %.1f KB · 失败 %d · DOM 节点 %d · JS 堆 %.1f MB · 布局 %d 次 · 脚本耗时 %.0f ms</p>
<h3>最慢请求</h3><table>%s</table>
<h3>截图</h3>%s]]):format(
        t and t.title or "", t and t.url or "", os.date("%Y-%m-%d %H:%M"),
        s.stats.req, s.stats.bytes / 1024, s.stats.fail,
        metrics.Nodes or 0, (metrics.JSHeapUsedSize or 0) / 1048576, metrics.LayoutCount or 0, (metrics.ScriptDuration or 0) * 1000,
        table.concat(rows),
        shot and shot.data and ('<img src="data:image/jpeg;base64,' .. shot.data .. '">') or "(无)")
    lemurx.fs.mkdir("reports")
    local name = "reports/" .. os.date("%Y%m%d_%H%M%S") .. "_" .. host_of(t and t.url):gsub("[^%w%.]", "_") .. ".html"
    lemurx.fs.write(name, html)
    lemurx.toast("报告已写到 files/lua/" .. name)
    lemurx.tabs.open("file://" .. lemurx.fs.root() .. "/" .. name)
end

-- 入口：顶栏一颗按钮
lemurx.ui.render("toolbar.end", h("icon", {
    id = "cdplab_btn", text = "⚗", size = 18, desc = "CDP 实验台",
    onClick = function()
        local t = lemurx.tabs.current()
        if not t then return end
        local s = ensure_attached(t.id)
        s.opts = load_opts(t.url)
        render_strip(t.id)
    end,
}))

lemurx.log("[案例70] CDP 实验台：顶栏 ⚗")
