-- 案例 20 · Lua 直接跑在渲染进程里，摸真 DOM
--
-- 扩展做不到的点：
--   * 内容脚本是 JS，跑在隔离世界，靠消息和后台通信；这里是 Lua，跑在渲染进程本身，
--     拿到的是主世界的真实 DOM 节点对象（同一节点 == 同一个 Lua 对象，可做表键）。
--   * 页面 CSP 再严也拦不住：不是 <script>，不走页面的 JS 加载管线。
--   * luakit.register_function 把 Lua 函数挂到 window 上，网页 JS 直接 await 调用，
--     Lua 侧能读浏览器进程的一切（历史、标签、本地文件）后再 resolve 回去。
--
-- 结构：这个文件在 UI 进程（浏览器）里跑；它把一份 web module 写进 luakit 的 config 目录，
-- 每个渲染进程启动时会加载它（require_web_module）。两边用 ipc_channel 互发信号。

local WM_NAME = "dom_probe_wm"

-- ======================= 渲染进程那半边（写成文件供 require_web_module）=======================
local WM_SOURCE = [==[
-- dom_probe_wm.lua —— 跑在每个渲染进程里的 Lua
local ui = ipc_channel("dom_probe_wm")

local TRACKER_HOSTS = { "doubleclick", "googlesyndication", "google%-analytics", "facebook%.net",
                        "hm%.baidu", "cnzz", "umeng", "growingio" }

luakit.add_signal("page-created", function(page)
    page:add_signal("document-loaded", function(p)
        local doc = p.document
        if not doc or not doc.body then return end

        -- 1) 统计：第三方脚本、iframe、外链图片 —— 直接遍历真 DOM
        local stats = { scripts = 0, trackers = 0, iframes = 0, images = 0, blank_links = 0 }
        for _, s in ipairs(doc.body:query("script[src]")) do
            stats.scripts = stats.scripts + 1
            local src = s.src or ""
            for _, pat in ipairs(TRACKER_HOSTS) do
                if src:find(pat) then stats.trackers = stats.trackers + 1 break end
            end
        end
        stats.iframes = #doc.body:query("iframe")
        stats.images = #doc.body:query("img")

        -- 2) 手术：target=_blank 的链接改成当前页打开（移动端不想开一堆标签）
        for _, a in ipairs(doc.body:query("a[target=_blank]")) do
            a.attr.target = "_self"
            a.attr.rel = "noopener"
            stats.blank_links = stats.blank_links + 1
        end

        -- 3) 加一条页内提示（元素由 Lua 创建、Lua 挂事件）
        local bar = doc:create_element("div", {
            style = "position:fixed;left:0;right:0;bottom:0;z-index:2147483647;"
                 .. "background:#102a43;color:#d9e2ec;font:12px sans-serif;padding:6px 10px;"
        }, ("Lua 在渲染进程里：%d 个脚本（%d 个追踪器）· %d iframe · 已把 %d 个新窗口链接改为本页打开 · 点我关闭")
            :format(stats.scripts, stats.trackers, stats.iframes, stats.blank_links))
        bar:add_event_listener("click", false, function(el) el:remove() end)
        doc.body:append(bar)

        -- 4) 汇报给浏览器进程那边的 Lua
        ui:emit_signal("stats", p.id, p.uri, stats)
    end)
end)

-- 5) 给网页暴露一个原生函数：window.lemurxHistory("关键字") → Promise<[...]>
--    JS 调它时，这里先转给 UI 进程去查历史库，结果回来再 resolve
local pending = {}
local next_id = 0
luakit.register_function("^https?://", "lemurxHistory", function(page, resolve, reject, query)
    next_id = next_id + 1
    pending[next_id] = resolve
    ui:emit_signal("history_query", page.id, next_id, tostring(query or ""))
end)
ui:add_signal("history_result", function(_, _, id, rows)
    local resolve = pending[id]
    pending[id] = nil
    if resolve then resolve(rows) end
end)
]==]

-- 把 web module 装进 luakit 的 config 目录（require_web_module 会在这里找）
local function install_wm(name, src)
    local path = luakit.config_dir .. "/" .. name .. ".lua"
    local f = io.open(path, "r")
    if f then
        local old = f:read("a")
        f:close()
        if old == src then return end
    end
    f = assert(io.open(path, "w"))
    f:write(src)
    f:close()
end
install_wm(WM_NAME, WM_SOURCE)

-- ======================= 浏览器进程这半边 =======================
local wm = require_web_module(WM_NAME)
local h = lemurx.ui.h

-- 渲染进程汇报 → 顶部原生状态条
wm:add_signal("stats", function(_, page_id, uri, stats)
    local host = (uri or ""):match("^%a+://([^/]+)") or uri
    lemurx.ui.render("page.top", h("row", { id = "probe_strip", background = "#E6102A43", padding = { 10, 4 } }, {
        h("text", {
            id = "probe_text", weight = 1, color = "#FFD9E2EC", size = 12, maxLines = 1, ellipsize = true,
            text = ("%s · 脚本 %d / 追踪 %d / iframe %d / 图 %d"):format(
                host or "?", stats.scripts or 0, stats.trackers or 0, stats.iframes or 0, stats.images or 0),
        }),
        h("button", { id = "probe_close", text = "×", size = 12, onClick = function() lemurx.ui.unmount("probe_strip") end }),
    }))
end)

-- 网页 JS 调 window.lemurxHistory("xx") → 这里查浏览器历史库 → 回给渲染进程 resolve
wm:add_signal("history_query", function(_, page_id, req_id, query)
    local rows = lemurx.history.query(query) or {}
    local out = {}
    for i = 1, math.min(#rows, 20) do
        out[i] = { url = rows[i].url, title = rows[i].title }
    end
    -- 只回给发起那一页所在的进程（page_id == webview.id）
    local view = __lk.webview_for_tab(page_id)
    if view then
        wm:emit_signal(view, "history_result", req_id, out)
    else
        wm:emit_signal("history_result", req_id, out)
    end
end)

-- 试一下：在任意 https 页面的控制台里输入
--   await lemurxHistory("github")
-- 会返回浏览器历史里匹配的最近 20 条；这在扩展里意味着 content script → background → history API
-- 三跳消息，这里是页面里一行 await。
lemurx.log("[案例20] 渲染进程 Lua 已就位；打开任意网页看底部蓝条")
