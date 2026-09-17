-- LemurX Lua 运行时初始化脚本。
-- 底层 C 绑定已经注册全局表 lemurx，这里补充版本号和便捷别名。
--
-- 用户脚本目录：应用 filesDir/lua/*.lua（本地特权，随 init 之后按文件名排序加载）
-- UGC 脚本目录：filesDir/lua/ugc/*.lua（无 cookie/history/downloads/requestBody/perm，不可上架特权能力）
--
-- ===== 运行模型（最大自由度）=====
-- 本地脚本：一个共享的主 lua_State，标准库全开——io / os / debug / package / require /
--   dofile / loadfile / load(含字节码) 全部可用。这是你自己的设备、你自己的脚本，LemurX不拦。
--   package.path 已指向 filesDir/lua/?.lua ; filesDir/lua/?/init.lua ; filesDir/lua/lib/?.lua，
--   直接 require("mylib") 即可。
-- UGC 脚本：每个文件一个独立 lua_State，只开 base/table/string/math/utf8/coroutine，
--   load 只收文本、没有 dofile/loadfile。与本地脚本不共享任何全局表，
--   改不了本地脚本的 lemurx.* 或 string 元表，也就借不到本地脚本的特权。
-- 跨状态桥：本地 lemurx.expose("name", fn|table) → UGC lemurx.import("name")
--   参数/返回值按 JSON 编组（nil/bool/number/string/table），函数不能跨界；
--   被暴露的函数以本地特权执行，暴露什么由本地脚本作者决定。lemurx.exposed() 列出全部。
-- lemurx.scriptName()  -- UGC 里返回自己的文件名，本地脚本返回 ""
--
-- ===== 异步 =====
-- 所有 Lua（所有状态）都跑在同一条线程上。同步 http.fetch 会卡住整条线程，其他脚本的
-- 定时器/点击回调也得排队。给 fetch 传回调就是真正异步（网络在 Java 线程池跑，结果回投）：
--   lemurx.http.fetch(url, opts, function(r) ... end)   -- 立刻返回请求 id
-- 不想写回调金字塔就用协程封装：
--   lemurx.async(function()
--       local r = lemurx.await(lemurx.http.fetch, "https://example.com")  -- 让出线程，不卡
--       lemurx.sleep(300)                                                -- 同上
--       lemurx.toast(#r.body)
--   end)
-- lemurx.await(fn, ...) 适用于任何「最后一个参数收回调」的函数，如 lemurx.timer.after。
-- 实战教程：apk 首次启动会把 lua/tutorial.lua 复制成 filesDir/lua/00_tutorial.lua。
-- 右下角「Lua教程」按用户痛点排列（阅读挡字、手滑返回、广告改不了、登录掉了…），不是 API 清单。
--
-- lemurx.help([module])          -- 打印接口清单，module 如 "tabs"/"cdp"/"ui"
-- lemurx.log(msg, ...)
-- lemurx.toast(msg)
-- lemurx.browser.info()          -- {isLemurX, channel, experienceMode, versionName, versionCode}
-- lemurx.tabs.list()             -- [{id, url, title, incognito, loading, canGoBack, canGoForward, muted}, ...]
-- lemurx.tabs.current()          -- 当前标签，或 nil
-- lemurx.tabs.open(url[, opts])  -- opts: {background=true, hidden=true, incognito=true}
-- lemurx.tabs.close(id)
-- lemurx.tabs.select(id)
-- lemurx.tabs.hide(id) / show(id) / freeze(id)
-- lemurx.tabs.navigate(id, url)
-- lemurx.tabs.reload(id)
-- lemurx.tabs.back(id)
-- lemurx.tabs.forward(id)
-- lemurx.tabs.stop(id)
-- lemurx.tabs.eval(id, js)       -- 主世界执行 JS（浏览器进程直通，不受页面 CORS 限制）
-- lemurx.tabs.inject(id, js[, {world="isolated"|"main", frames="all"|"main"}])  -- 默认隔离世界主 frame
-- lemurx.tabs.html(id)           -- 当前文档 outerHTML
-- lemurx.tabs.screenshot(id[, {base64=true}])  -- JPEG 写入 lua/captures，返回 {ok, path, width, height}
-- lemurx.tabs.mute(id, true) / isMuted(id)
-- lemurx.tabs.setUserAgent(id, ua)   -- 后续导航带 UA，并改 navigator.userAgent
-- lemurx.tabs.setHeaders(id, { ["X-Foo"] = "bar" })
-- lemurx.tabs.on("started"|"loaded"|"document"|"created"|"closed"|"selected", function(ev) end)
-- lemurx.tabs.setDesktop(id, true|false)
-- lemurx.tabs.setZoom(id, 150)   -- 150 或 1.5 均为 150%
-- lemurx.tabs.getZoom(id)        -- 百分比
-- lemurx.tabs.setJavaScript(true|false) / isJavaScript()
-- lemurx.tabs.history(id)        -- NavigationController 快照 {ok, current, entries, canGoBack, canGoForward}
-- lemurx.tabs.go(id, index) / offset(id, n) / reloadBypassCache(id)
-- lemurx.tabs.frames(id)         -- RenderFrameHost 树（C++/Java 直出，不走 CDP）
-- lemurx.http.fetch(url[, {method, headers, body, timeout, redirect, binary, base64}][, callback])
--   浏览器进程直出，无 CORS；UGC 限 http(s) 2MB/20s，本地 8MB/120s
--   带 callback 为异步（返回请求 id，结果在回调里）；不带则同步阻塞 Lua 线程
-- lemurx.fs.root() / read(path[, {base64=true}]) / write(path, data[, {append, base64}])
-- lemurx.fs.list(path) / exists(path) / mkdir(path) / remove(path)
--   本地：filesDir/lua ；UGC：filesDir/lua/ugc ，禁止跳出目录
-- lemurx.input.tap(x, y[, tabId[, "dp"|"px"]]) 或 {x, y, tab, unit}
-- lemurx.input.swipe(x1, y1, x2, y2[, durationMs[, tabId]])
-- lemurx.input.type(text[, tabId])  -- 写入当前焦点
-- lemurx.input.key("enter"|"back"|"tab"|"space"|"esc"|"delete"[, tabId])
-- lemurx.perm.set(origin, "geolocation"|"camera"|"microphone"|"notifications"|"javascript"|"cookies"|"images"|"popups"|"sound"|"autoplay", "allow"|"block"|"ask")
-- lemurx.perm.get(origin, type)     -- 仅本地特权，UGC 调用报错
-- lemurx.chrome.controls("shown"|"hidden"|"both")  -- 浏览器控件约束，走 compositor
-- lemurx.chrome.hideUrlBar(true) / setUrlBarText(text) / getUrlBarText() / focusUrlBar(true)
-- lemurx.chrome.setToolbarColor("#FF212121") / setStatusBarColor("#FF000000")
-- lemurx.chrome.setDarkMode(true) / isDarkMode()
-- lemurx.chrome.fullscreen(true)
-- lemurx.chrome.setPullRefresh(false)
-- lemurx.chrome.setMenuButtonVisible(false)
-- lemurx.chrome.hideBottomToolbar(true)
-- lemurx.chrome.hideButton("back"|"forward"|"home"|"tabs"|"tools"|"menu"|"search", true)
-- lemurx.chrome.barLayout() / barLayout("1-2-3-4-5") / barLayout({"home","tabs","search","tools","menu"})
--   槽位：1 home  2 tabs  3 search(地址栏占位)  4 tools  5 menu  6 back  7 forward
--   现行壳是 ToolbarPhoneLemur：按钮在地址栏左右两侧，不是独立 Duet 底栏
-- lemurx.chrome.back() / forward() / info()
-- lemurx.chrome.on("omnibox"|"back"|"menu", function(ev) end)
-- lemurx.menu.add({id="grab", title="抓取", page="main"|"expand"|"second", onClick=function() end})
-- lemurx.menu.remove(id) / clear()
-- lemurx.menu.hide("history"[, true])   -- 隐藏内置底栏项
-- lemurx.menu.on("history", function(ev) end)  -- 截获内置项，不再走默认逻辑
-- lemurx.menu.invoke("history")          -- 强制执行内置逻辑
-- lemurx.input.onBack(function(ev) end)  -- 注册后返回键归 Lua，自行 chrome.back() 放行
-- lemurx.input.interceptBack(true|false)
-- lemurx.net.addRule({
--   match = "*://*.doubleclick.net/*",  -- 或 host 片段 / <all_urls>
--   action = "block"|"redirect"|"modify",
--   types = {"script", "image", "xmlhttprequest", "document", "sub_frame", "media"},
--   redirectUrl = "https://...",
--   requestHeaders = { ["X-Foo"] = "bar" },
--   removeRequestHeaders = { "Cookie" },
--   responseHeaders = { ["X-Bar"] = "baz" },
--   removeResponseHeaders = { "Content-Security-Policy" },
--   replaceBody = { ["old text"] = "new text" },  -- 或 { {from="old", to="new"} }，仅 text/json/js/xml，2MB
--   requestBody = "...",            -- 本地特权：整段替换 POST body
--   replaceRequestBody = { a = b }, -- 本地特权：仅 bytes 上传体
-- })
-- lemurx.net.removeRule(id)
-- lemurx.net.clearRules()
-- lemurx.net.listRules()
-- lemurx.schema.launch(host, params)
--   host: webview / h5 / chat_ai / vip_center / login / extensions ...
-- lemurx.user.info()             -- {loggedIn, uid, nickname, vip}，不含 token
-- lemurx.clipboard.set(text)
-- lemurx.clipboard.get()
-- lemurx.storage.get(key[, default])
-- lemurx.storage.set(key, value)
-- lemurx.storage.delete(key)
-- lemurx.storage.list()
-- lemurx.ui.show({
--   id = "fab", type = "button"|"text"|"edit", text = "抓取",
--   gravity = "end|bottom", x = 16, y = 80,  -- dp
--   background = "#E64CAF50", color = "#FFFFFF",
--   onClick = function() end,
-- })
-- lemurx.ui.remove(id)
-- lemurx.ui.clear()
-- lemurx.ui.dump([{maxDepth=12, maxNodes=800, gone=true}])  -- 所有窗口的原生 View 树（含对话框/抽屉）
-- lemurx.ui.find({text="Lua教程", id="url_bar", desc="...", class="Button"})
-- lemurx.ui.click({text="Lua教程"}) / longClick(...)
-- lemurx.ui.setText({id="url_bar"}, "https://example.com") / getText({id=...})
-- lemurx.ui.visible({id=...}, false) / enabled({id=...}, false)
-- lemurx.ui.dialog({title="确认", message="...", ok="好", cancel="取消", onOk=function(ev) end})
-- lemurx.ui.prompt({title="输入", hint="网址", value="", onOk=function(ev) print(ev.text) end})
-- lemurx.ui.shell()                 -- 底栏/地址栏/搜索框等已知原生 id 及当前状态
-- lemurx.ui.children(query)         -- 某个 View 的直接孩子
-- lemurx.ui.replace(query, tree)    -- 用 Lua 控件树换掉任意原生 View（unmount 放回）
-- lemurx.ui.insert(query, tree[, {position="after"|"before"|"into", index=n}])
-- lemurx.ui.detach(query) / restore([query])  -- 从父容器摘下 / 放回
-- lemurx.ui.move(query, {parent=, index=, before=, after=, width, height, weight})
-- lemurx.ui.on(query, "click"|"longclick"|"touch"|"focus"|"text", fn[, {consume, move, max}])
-- lemurx.ui.off(key|"*")
-- lemurx.menu.list()                -- 工具抽屉 main/expand/second 的内容（含 Lua 加的项）
--
-- ===== 外壳可编程（皮肤 / 挂载点 / 声明式控件树 / 样式手术）=====
-- lemurx.theme.set({                -- 持久化皮肤，每次进浏览器自动重放，nil 值删除该项
--   toolbar = "#FF1B5E20", statusBar = "#FF0D3B13", navBar = "#FF0D3B13", bottomBar = "#FF1B5E20",
--   iconTint = "#FFFFFFFF", accent = "#FF00C853",  -- accent 给 button 默认底色
--   dark = true, bottomStyle = 0|1|2 或 barLayout="1-2-3-4-5" / {"home","tabs","search","tools","menu"},
--   hideButtons = {"extensions", "settings"}, urlBarHidden = false, bottomBarHidden = false,
--   menuButton = true, pullRefresh = false,
-- })
-- lemurx.theme.get()  -> {ok, theme, dark} / lemurx.theme.reset()
-- lemurx.theme.presets.<name> / lemurx.theme.apply("forest"|"paper"|"midnight"|"sakura")
--
-- lemurx.ui.slots()                 -- 可用挂载点：toolbar.start/end、bottom.start/bar、
--                                  --   page.top/bottom/center/float、view:<任意原生 id>
-- lemurx.ui.render(slot, tree)      -- 同一 slot 再 render 即替换；返回 {ok, mounted, slot, ids}
--   tree 节点：{type=, id=, children={...}, onClick=fn, onLongClick=fn, onChange=fn, ...}
--   type: row column stack scroll hscroll text button icon image edit switch progress divider spacer
--   通用: width/height("match"|"wrap"|dp) weight gravity align padding margin(数字|{h,v}|{l,t,r,b})
--         background("#AARRGGBB"|"drawable:name"|{color,radius,stroke,strokeColor,gradient={...}})
--         radius visible enabled alpha elevation rotation desc tooltip minWidth minHeight
--   文本: text color size bold italic font("monospace"|"serif"|"file:x.ttf") maxLines ellipsize underline strike
--   图标: icon("ic_xxx"|"android:ic_menu_search"|"file:icons/a.png"|"data:image/png;base64,...") tint scale
--   输入: hint value inputType("text"|"number"|"url"|"password"|"multiline") onChange(ev.text / ev.action="submit")
--   开关: checked onChange(ev.checked)   进度: value max indeterminate color
--   回调 ev = {id, action="click"|"longclick"|"change"|"submit", slot, text?, checked?}
-- lemurx.ui.h(type, props, children) -- 建树糖：h("row", {padding=8}, { h("text", {text="hi"}) })
-- lemurx.ui.update(id, props)       -- 改已挂载节点的属性（等价 ui.style({id=id}, props)）
-- lemurx.ui.unmount(slot|id|"*")
-- lemurx.ui.style(query, style)     -- 对任意原生 View 做样式手术（同 render 的属性），attach 时重放
--   例：lemurx.ui.style({id="url_bar"}, {size=16, bold=true})  lemurx.ui.style({id="home_button"}, {visible=false})
--   style.once=true 只应用一次不重放
-- lemurx.db.exec(sql[, {bind...}])   -- filesDir/lua/lemurx.db，拒绝 ATTACH
-- lemurx.db.query(sql[, {bind...}])  -- {ok, rows=[{col=...}]}
-- lemurx.timer.after(ms, fn) / every(ms, fn) / cancel(id)
-- lemurx.intent.startActivity({action="android.intent.action.VIEW", url="https://..."})
--   UGC：VIEW/SEND/SENDTO/WEB_SEARCH/MAIN 以及 lemurx.* ；本地脚本不限 action/scheme
-- lemurx.intent.sendBroadcast({action="lemurx.example", extras={...}})
-- -- 以下仅 filesDir/lua/*.lua 本地特权，lua/ugc/*.lua 调用会报错，不可上架 UGC
-- lemurx.cookie.get(url) / set(url, "name=value; path=/")
-- lemurx.history.query([text])
-- lemurx.downloads.list() / enqueue(url[, tabId])
-- lemurx.perm.set / get
-- lemurx.cdp.send(tabId|"method", method, params[, timeoutMs|{timeout, sessionId}])  -- 整表 Chrome DevTools Protocol，C++ DevToolsAgentHost
-- lemurx.cdp.attach(tabId) / detach(tabId) / inspect(tabId, x, y) / version() / on(method|"*", fn)
-- lemurx.cdp.targets()           -- GetOrCreateAll：page/iframe/worker/service_worker/browser
-- lemurx.cdp.host(id, method, params[, timeout|{sessionId}])  -- id="browser" 走 CreateForDiscovery
-- lemurx.v8.eval(tab, expr[, opts])  -- Runtime.evaluate，直通页面 V8
-- lemurx.dom.document(tab) / query(tab, selector) / box(tab, selector)
-- lemurx.ax.tree(tab)               -- Blink 无障碍树
-- lemurx.blink.frames(tab) / metrics(tab)
-- lemurx.page.navigate/reload/stop/enablePaint / screenshot
-- lemurx.network.enable/setOffline/emulate/clear
-- lemurx.emulation.setUA/setDevice/setGeo/setTimezone/setTouch
-- lemurx.overlay.inspect/hide
-- lemurx.css.getComputed
-- lemurx.prefs.get(name[, "bool"|"int"|"double"|"string"|"long"])  -- PrefService，仅本地
-- lemurx.prefs.set(name, value) / set(name, "bool", true) / clear(name)
-- lemurx.data.clear({"history","cache","site_data"}[, "hour"|"day"|"week"|"all"|"15m"])
-- lemurx.features.enabled(name) / param(feature, key)  -- ChromeFeatureList，只读
-- lemurx.isPrivileged()
-- lemurx.scriptName() / expose(name, v) / import(name) / exposed()
-- lemurx.async(fn, ...) / await(fn, ...) / sleep(ms)

lemurx.version = "1.12.1"

-- ===== 本地脚本：require 直接找 filesDir/lua =====
if package and lemurx.isPrivileged() then
    local ok, root = pcall(lemurx.fs.root)
    if ok and type(root) == "string" and root ~= "" then
        package.path = root .. "/?.lua;" .. root .. "/?/init.lua;" .. root .. "/lib/?.lua;"
            .. (package.path or "")
    end
end

-- ===== 异步：协程封装 =====
-- 把「最后一个参数收回调」的函数改写成顺序代码。只能在 lemurx.async 里用 await。
function lemurx.async(fn, ...)
    local co = coroutine.create(fn)
    local ok, err = coroutine.resume(co, ...)
    if not ok then
        lemurx.log("[async] " .. tostring(err))
    end
    return co
end

function lemurx.await(fn, ...)
    local co, is_main = coroutine.running()
    if is_main then
        error("lemurx.await 只能在 lemurx.async(function() ... end) 里调用", 2)
    end
    local args = table.pack(...)
    local done, results, yielded = false, nil, false
    args[args.n + 1] = function(...)
        if done then
            return
        end
        done = true
        results = table.pack(...)
        if yielded then
            local ok, err = coroutine.resume(co, table.unpack(results, 1, results.n))
            if not ok then
                lemurx.log("[async] " .. tostring(err))
            end
        end
    end
    fn(table.unpack(args, 1, args.n + 1))
    if done then
        -- 回调在 fn 返回前就同步触发了，不用让出
        return table.unpack(results, 1, results.n)
    end
    yielded = true
    return coroutine.yield()
end

function lemurx.sleep(ms)
    return lemurx.await(lemurx.timer.after, ms or 0)
end

lemurx.ugc = lemurx.ugc or {}
lemurx.ugc.forbidden = {
    "lemurx.cookie",
    "lemurx.history",
    "lemurx.downloads",
    "lemurx.perm",
    "lemurx.cdp",
    "lemurx.v8",
    "lemurx.dom",
    "lemurx.ax",
    "lemurx.blink",
    "lemurx.prefs",
    "lemurx.data",
    "lemurx.page",
    "lemurx.network",
    "lemurx.emulation",
    "lemurx.overlay",
    "lemurx.css",
    "requestBody",
    "replaceRequestBody",
}

function lemurx.ugc.scan(src)
    src = src or ""
    local hits = {}
    for _, key in ipairs(lemurx.ugc.forbidden) do
        if string.find(src, key, 1, true) then
            hits[#hits + 1] = key
        end
    end
    return hits
end

function lemurx.open(url, opts)
    return lemurx.tabs.open(url, opts)
end

function lemurx.http.save(url, path, opts)
    local r = lemurx.http.fetch(url, opts)
    if not r or not r.ok then
        return r
    end
    lemurx.fs.write(path, r.body, { base64 = r.base64 and true or false })
    r.path = path
    return r
end

lemurx.v8 = lemurx.v8 or {}
lemurx.dom = lemurx.dom or {}
lemurx.ax = lemurx.ax or {}
lemurx.blink = lemurx.blink or {}

function lemurx.v8.eval(tab, expr, opts)
    opts = opts or {}
    local r = lemurx.cdp.send(tab, "Runtime.evaluate", {
        expression = expr,
        returnByValue = opts.returnByValue ~= false,
        awaitPromise = opts.await or opts.awaitPromise or false,
        userGesture = opts.userGesture or false,
        includeCommandLineAPI = opts.repl or false,
        contextId = opts.contextId,
    }, opts.timeout)
    if not r or not r.ok then
        return nil, r
    end
    local remote = r.result and r.result.result
    if remote and remote.exceptionId then
        return nil, r
    end
    if remote and remote.value ~= nil then
        return remote.value, r
    end
    return remote, r
end

function lemurx.v8.call(tab, fn, args, opts)
    opts = opts or {}
    local expr = "(" .. tostring(fn) .. ").apply(null, " .. (args or "[]") .. ")"
    return lemurx.v8.eval(tab, expr, opts)
end

function lemurx.dom.document(tab)
    return lemurx.cdp.send(tab, "DOM.getDocument", { depth = 0, pierce = true })
end

function lemurx.dom.query(tab, selector)
    local doc = lemurx.dom.document(tab)
    local root = doc and doc.result and doc.result.root and doc.result.root.nodeId
    if not root then
        return nil, doc
    end
    return lemurx.cdp.send(tab, "DOM.querySelector", {
        nodeId = root,
        selector = selector,
    })
end

function lemurx.dom.box(tab, selector)
    local node, err = lemurx.dom.query(tab, selector)
    local id = node and node.result and node.result.nodeId
    if not id then
        return nil, err or node
    end
    return lemurx.cdp.send(tab, "DOM.getBoxModel", { nodeId = id })
end

function lemurx.ax.tree(tab)
    return lemurx.cdp.send(tab, "Accessibility.getFullAXTree", {})
end

function lemurx.blink.frames(tab)
    return lemurx.cdp.send(tab, "Page.getFrameTree", {})
end

function lemurx.blink.metrics(tab)
    return lemurx.cdp.send(tab, "Page.getLayoutMetrics", {})
end

local function cdp(tab, method, params, opts)
    return lemurx.cdp.send(tab, method, params or {}, opts)
end

lemurx.page = lemurx.page or {}
lemurx.network = lemurx.network or {}
lemurx.emulation = lemurx.emulation or {}
lemurx.overlay = lemurx.overlay or {}
lemurx.css = lemurx.css or {}
lemurx.runtime = lemurx.runtime or {}
lemurx.sw = lemurx.sw or {}

function lemurx.page.navigate(tab, url, opts)
    opts = opts or {}
    return cdp(tab, "Page.navigate", {
        url = url,
        referrer = opts.referrer,
        frameId = opts.frameId,
        transitionType = opts.transitionType,
    }, opts)
end

function lemurx.page.reload(tab, ignoreCache)
    return cdp(tab, "Page.reload", { ignoreCache = ignoreCache and true or false })
end

function lemurx.page.stop(tab)
    return cdp(tab, "Page.stopLoading", {})
end

function lemurx.page.enablePaint(tab, enabled)
    return cdp(tab, "Page.setWebLifecycleState", { state = enabled == false and "frozen" or "active" })
end

function lemurx.page.screenshot(tab, opts)
    -- CDP Page.captureScreenshot 走 GPU，Android 上不如 PixelCopy 稳。
    return lemurx.tabs.screenshot(tab, opts)
end

function lemurx.page.cookies(tab)
    return cdp(tab, "Network.getAllCookies", {})
end

function lemurx.network.enable(tab)
    return cdp(tab, "Network.enable", {})
end

function lemurx.network.setOffline(tab, offline)
    return cdp(tab, "Network.emulateNetworkConditions", {
        offline = offline ~= false,
        latency = 0,
        downloadThroughput = -1,
        uploadThroughput = -1,
    })
end

function lemurx.network.emulate(tab, opts)
    opts = opts or {}
    return cdp(tab, "Network.emulateNetworkConditions", {
        offline = opts.offline or false,
        latency = opts.latency or 0,
        downloadThroughput = opts.download or opts.downloadThroughput or -1,
        uploadThroughput = opts.upload or opts.uploadThroughput or -1,
        connectionType = opts.type or opts.connectionType,
    })
end

function lemurx.network.clear(tab)
    cdp(tab, "Network.clearBrowserCache", {})
    return cdp(tab, "Network.clearBrowserCookies", {})
end

function lemurx.network.headers(tab, headers)
    return cdp(tab, "Network.setExtraHTTPHeaders", { headers = headers or {} })
end

function lemurx.emulation.setUA(tab, ua, opts)
    opts = opts or {}
    return cdp(tab, "Emulation.setUserAgentOverride", {
        userAgent = ua,
        acceptLanguage = opts.lang or opts.acceptLanguage,
        platform = opts.platform,
    })
end

function lemurx.emulation.setDevice(tab, opts)
    -- 之前误判会崩页，实际只是 world_id=0 注入的问题；恢复。
    opts = opts or {}
    if opts.clear then
        return cdp(tab, "Emulation.clearDeviceMetricsOverride", {})
    end
    return cdp(tab, "Emulation.setDeviceMetricsOverride", {
        width = opts.width or 0,
        height = opts.height or 0,
        deviceScaleFactor = opts.scale or opts.deviceScaleFactor or 0,
        mobile = opts.mobile ~= false,
        screenOrientation = opts.orientation,
    })
end

function lemurx.emulation.setGeo(tab, lat, lng, accuracy)
    return cdp(tab, "Emulation.setGeolocationOverride", {
        latitude = lat,
        longitude = lng,
        accuracy = accuracy or 1,
    })
end

function lemurx.emulation.setTimezone(tab, id)
    return cdp(tab, "Emulation.setTimezoneOverride", { timezoneId = id })
end

function lemurx.emulation.setTouch(tab, enabled, maxTouchPoints)
    return cdp(tab, "Emulation.setTouchEmulationEnabled", {
        enabled = enabled ~= false,
        maxTouchPoints = maxTouchPoints or 1,
    })
end

function lemurx.overlay.inspect(tab, mode)
    cdp(tab, "DOM.enable", {})  -- Overlay 依赖 DOM 域
    cdp(tab, "Overlay.enable", {})
    return cdp(tab, "Overlay.setInspectMode", {
        mode = mode or "searchForNode",
        highlightConfig = { showInfo = true, contentColor = { r = 111, g = 168, b = 220, a = 0.66 } },
    })
end

function lemurx.overlay.hide(tab)
    return cdp(tab, "Overlay.hideHighlight", {})
end

function lemurx.css.getComputed(tab, nodeId)
    return cdp(tab, "CSS.getComputedStyleForNode", { nodeId = nodeId })
end

function lemurx.runtime.contexts(tab)
    return cdp(tab, "Runtime.evaluate", {
        expression = "1",
        returnByValue = true,
    })
end

function lemurx.sw.targets()
    local all = lemurx.cdp.targets()
    local out = {}
    local list = all and all.targets or {}
    for i = 1, #list do
        local t = list[i]
        if t and (t.type == "service_worker" or t.type == "shared_worker" or t.type == "worker") then
            out[#out + 1] = t
        end
    end
    return out, all
end

function lemurx.sw.send(target, method, params, opts)
    local id = type(target) == "table" and target.id or target
    return lemurx.cdp.host(id, method, params, opts)
end

function lemurx.ui.alert(title, message, ok)
    return lemurx.ui.dialog({
        title = title,
        message = message,
        ok = ok or "好",
    })
end

function lemurx.ui.prompt(opts)
    opts = opts or {}
    opts.prompt = true
    if opts.ok == nil then
        opts.ok = "确定"
    end
    if opts.cancel == nil then
        opts.cancel = "取消"
    end
    return lemurx.ui.dialog(opts)
end

-- ===== 外壳可编程：建树糖 / 节点更新 / 皮肤预设 =====
lemurx.theme = lemurx.theme or {}

-- h(type, props, children)：props 可省略直接给 children；children 里允许字符串当文本节点
function lemurx.ui.h(kind, props, children)
    if type(props) == "table" and props[1] ~= nil and children == nil then
        children = props
        props = nil
    end
    local node = {}
    if props then
        for k, v in pairs(props) do
            node[k] = v
        end
    end
    node.type = kind
    if children then
        node.children = {}
        for i = 1, #children do
            local c = children[i]
            if type(c) == "string" then
                c = { type = "text", text = c }
            end
            node.children[#node.children + 1] = c
        end
    end
    return node
end

function lemurx.ui.update(id, props)
    return lemurx.ui.style({ id = id, max = 1 }, props)
end

-- 一些拿来即用的皮肤，lemurx.theme.apply("forest") 即可换；reset() 还原。
lemurx.theme.presets = {
    forest = {
        toolbar = "#FF1B5E20", statusBar = "#FF0D3B13", navBar = "#FF1B5E20",
        bottomBar = "#FF1B5E20", iconTint = "#FFE8F5E9", accent = "#FF00C853",
    },
    paper = {
        toolbar = "#FFFAF3E0", statusBar = "#FFEADFC4", navBar = "#FFFAF3E0",
        bottomBar = "#FFFAF3E0", iconTint = "#FF5D4037", accent = "#FF8D6E63",
    },
    midnight = {
        toolbar = "#FF0B1020", statusBar = "#FF05080F", navBar = "#FF0B1020",
        bottomBar = "#FF0B1020", iconTint = "#FF9FB3FF", accent = "#FF3D5AFE", dark = true,
    },
    sakura = {
        toolbar = "#FFFFE4EC", statusBar = "#FFF8BBD0", navBar = "#FFFFE4EC",
        bottomBar = "#FFFFE4EC", iconTint = "#FFAD1457", accent = "#FFEC407A",
    },
}

function lemurx.theme.apply(name, extra)
    local preset = type(name) == "table" and name or lemurx.theme.presets[tostring(name)]
    if not preset then
        return { ok = false, error = "unknown preset " .. tostring(name) }
    end
    local merged = {}
    for k, v in pairs(preset) do
        merged[k] = v
    end
    for k, v in pairs(extra or {}) do
        merged[k] = v
    end
    return lemurx.theme.set(merged)
end

-- 接口清单。lemurx.help() 打印全部，lemurx.help("tabs") 只看一块。
lemurx.catalog = {
    {
        name = "core",
        title = "基础",
        priv = false,
        apis = {
            "lemurx.log(...) / print(...)",
            "lemurx.toast(msg)",
            "lemurx.version",
            "lemurx.isPrivileged()",
            "lemurx.help([module])",
            "lemurx.open(url, opts)  -- tabs.open 别名",
            "lemurx.browser.info()  -- 渠道/版本/体验模式",
            "lemurx.scriptName()  -- UGC 文件名，本地为 ''",
        },
    },
    {
        name = "async",
        title = "异步 / 协程",
        priv = false,
        apis = {
            "http.fetch(url, opts, function(r) end)  -- 有回调即异步，不卡 Lua 线程",
            "lemurx.async(function() ... end)  -- 起一个协程任务",
            "lemurx.await(fn, ...)  -- 在 async 里等「末参数收回调」的函数，如 await(http.fetch, url)",
            "lemurx.sleep(ms)  -- 在 async 里让出 ms 毫秒",
        },
    },
    {
        name = "bridge",
        title = "本地 ↔ UGC 桥",
        priv = false,
        apis = {
            "lemurx.expose(name, fn|table)  -- 本地：把工具暴露给 UGC，以本地特权执行",
            "lemurx.import(name)  -- UGC：拿到代理，参数/返回值 JSON 编组，函数不可跨界",
            "lemurx.exposed()  -- 已暴露的名字列表",
            "本地：io/os/debug/package/require/dofile/load(字节码) 全开；UGC：仅纯计算库，load 只收文本",
        },
    },
    {
        name = "tabs",
        title = "标签 / 网页",
        priv = false,
        apis = {
            "list() current() open(url[, {background,hidden,incognito}])",
            "close(id) select(id) hide(id) show(id) freeze(id)",
            "navigate(id,url) reload(id) reloadBypassCache(id) stop(id)",
            "back(id) forward(id) go(id,index) offset(id,n) history(id)",
            "eval(id,js)  -- 主世界",
            "inject(id,js[, {world='isolated'|'main', frames='all'|'main'}])",
            "html(id) screenshot(id[, {base64=true}])",
            "mute(id,true) isMuted(id) setDesktop(id,true) setZoom(id,150) getZoom(id)",
            "setUserAgent(id,ua) setHeaders(id,{...}) frames(id)",
            "setJavaScript(true) isJavaScript()",
            "on('started'|'loaded'|'document'|'created'|'closed'|'selected', fn)",
        },
    },
    {
        name = "net",
        title = "网络规则",
        priv = false,
        apis = {
            "addRule({match, action='block'|'redirect'|'modify', types, redirectUrl,",
            "  requestHeaders, removeRequestHeaders, responseHeaders, removeResponseHeaders,",
            "  replaceBody})  -- 改响应体，扩展做不到",
            "requestBody / replaceRequestBody  -- 仅本地，改 POST 体",
            "removeRule(id) clearRules() listRules()",
        },
    },
    {
        name = "http",
        title = "浏览器进程直连",
        priv = false,
        apis = {
            "fetch(url[, {method,headers,body,timeout,redirect,binary,base64}][, cb])  -- 无 CORS，带 cb 异步",
            "lemurx.http.save(url, path[, opts])",
        },
    },
    {
        name = "fs",
        title = "文件",
        priv = false,
        apis = {
            "root() read(path[, {base64}]) write(path,data[, {append,base64}])",
            "list(path) exists(path) mkdir(path) remove(path)",
            "本地 jail：filesDir/lua ；UGC jail：filesDir/lua/ugc",
        },
    },
    {
        name = "chrome",
        title = "浏览器外壳",
        priv = false,
        apis = {
            "controls('shown'|'hidden'|'both') hideUrlBar(true) fullscreen(true)",
            "setUrlBarText(text) getUrlBarText() focusUrlBar(true)",
            "setToolbarColor('#FF212121') setStatusBarColor('#FF000000')",
            "setDarkMode(true) isDarkMode() setPullRefresh(false)",
            "setMenuButtonVisible(false) hideBottomToolbar(true)",
            "hideButton('back'|'forward'|'home'|'tabs'|'tools'|'menu'|'search', true)",
            "barLayout() / barLayout('1-2-3-4-5') / barLayout({'home','tabs','search','tools','menu'})",
            "back() forward() info() on('omnibox'|'back'|'menu', fn)",
        },
    },
    {
        name = "menu",
        title = "底栏 / 菜单",
        priv = false,
        apis = {
            "add({id,title,page='main'|'expand'|'second', onClick=fn})",
            "remove(id) clear() hide(name[, true]) on(name, fn) invoke(name) list()",
        },
    },
    {
        name = "ui",
        title = "原生界面",
        priv = false,
        apis = {
            "show({id,type='button'|'text'|'edit', text, gravity, x, y, onClick})",
            "remove(id) clear()",
            "dump([{maxDepth,maxNodes,gone}])  -- 所有窗口，含工具抽屉 Dialog",
            "find({text,id,desc,class}) click(query) longClick(query)",
            "setText(query, text) getText(query) visible(query,false) enabled(query,false)",
            "dialog({title,message,ok,cancel,onOk,onCancel}) alert() prompt()",
            "shell()  -- 底栏/地址栏/搜索框已知 id + 是否还在",
            "children(query)  replace(query, tree)  insert(query, tree[, {position='after'|'before'|'into'}])",
            "detach(query) restore([query]) move(query, {parent,index,before,after})",
            "on(query, 'click'|'longclick'|'touch'|'focus'|'text', fn[, {consume,move,max}])  off(key|'*')",
        },
    },
    {
        name = "skin",
        title = "外壳可编程：皮肤 / 挂载点 / 控件树",
        priv = false,
        apis = {
            "theme.set({toolbar,statusBar,navBar,bottomBar,iconTint,accent,dark,bottomStyle/barLayout,",
            "  hideButtons,urlBarHidden,bottomBarHidden,menuButton,pullRefresh})  -- 持久化，自动重放",
            "theme.get() theme.reset() theme.apply('forest'|'paper'|'midnight'|'sakura'[, extra])",
            "ui.slots()  -- toolbar.start/end bottom.start/bar page.top/bottom/center/float view:<id>",
            "ui.render(slot, tree)  -- 声明式控件树，节点 onClick/onLongClick/onChange 直接给函数",
            "  type: row column stack scroll hscroll text button icon image edit switch progress divider spacer",
            "ui.h(type, props, children)  ui.update(id, props)  ui.unmount(slot|id|'*')",
            "ui.style(query, style)  -- 对任意原生 View 改字号/颜色/背景/显隐/边距，attach 重放",
        },
    },
    {
        name = "input",
        title = "输入",
        priv = false,
        apis = {
            "tap(x,y[,tabId[,unit]]) swipe(x1,y1,x2,y2[,ms[,tabId]])",
            "type(text[,tabId]) key('enter'|'back'|'tab'|'space'|'esc'|'delete'[,tabId])",
            "onBack(fn) interceptBack(true)",
        },
    },
    {
        name = "intent",
        title = "系统 Intent / Schema",
        priv = false,
        apis = {
            "startActivity({action,url,package,extras}) sendBroadcast({action,extras})",
            "UGC 仅 VIEW/SEND/SENDTO/WEB_SEARCH/MAIN 与 lemurx.*",
            "lemurx.schema.launch(host, params)  -- webview/h5/chat_ai/vip_center/login/extensions",
            "lemurx.user.info()  -- {loggedIn,uid,nickname,vip}，无 token",
        },
    },
    {
        name = "storage",
        title = "存储 / 剪贴板 / 定时 / 库",
        priv = false,
        apis = {
            "storage.get/set/delete/list",
            "clipboard.get/set",
            "timer.after(ms,fn) every(ms,fn) cancel(id)",
            "db.exec(sql[,bind]) db.query(sql[,bind])  -- filesDir/lua/lemurx.db",
        },
    },
    {
        name = "priv",
        title = "本地特权（filesDir/lua，UGC 不可用、不可上架）",
        priv = true,
        apis = {
            "cookie.get(url) cookie.set(url, 'name=value; path=/')",
            "history.query([text])",
            "downloads.list() downloads.enqueue(url[,tabId])",
            "perm.set(origin, type, 'allow'|'block'|'ask') perm.get(origin, type)",
            "prefs.get(name[,type]) prefs.set(name,value) prefs.clear(name)",
            "data.clear({'history','cache','site_data'}[, 'hour'|'day'|'week'|'all'|'15m'])",
            "features.enabled(name) features.param(feature, key)",
        },
    },
    {
        name = "cdp",
        title = "CDP / V8 / DOM（仅本地）",
        priv = true,
        apis = {
            "cdp.attach(tab) detach(tab) send(tab, method, params[, timeout|{sessionId}])",
            "cdp.host(id, method, params)  -- id='browser' 为浏览器目标",
            "cdp.targets() inspect(tab,x,y) version() on(method|'*', fn)",
            "v8.eval(tab, expr[, opts]) v8.call(tab, fn, args)",
            "dom.document(tab) query(tab, selector) box(tab, selector)",
            "ax.tree(tab) blink.frames(tab) blink.metrics(tab)",
            "page.navigate/reload/stop  page.screenshot -> 走 tabs.screenshot",
            "network.enable/setOffline/emulate/clear/headers",
            "emulation.setUA/setDevice/setGeo/setTimezone/setTouch",
            "overlay.inspect(tab[, mode]) overlay.hide(tab)",
            "css.getComputed(tab, nodeId) sw.targets() sw.send(target, method, params)",
        },
    },
}

function lemurx.help(mod)
    mod = mod and tostring(mod) or ""
    local lines = {
        "LemurX Lua " .. tostring(lemurx.version) ..
            "  privileged=" .. tostring(lemurx.isPrivileged()),
        "脚本：filesDir/lua/*.lua 本地特权；filesDir/lua/ugc/*.lua 可分享、无特权",
        "调用：lemurx.help() 或 lemurx.help('tabs')",
    }
    for _, group in ipairs(lemurx.catalog) do
        if mod == "" or group.name == mod or group.title == mod then
            local mark = group.priv and " [仅本地]" or ""
            lines[#lines + 1] = ""
            lines[#lines + 1] = "## " .. group.title .. "  (" .. group.name .. ")" .. mark
            for _, line in ipairs(group.apis) do
                lines[#lines + 1] = "  " .. line
            end
        end
    end
    if #lines <= 3 then
        lines[#lines + 1] = "未知模块: " .. mod .. "，可选 core/async/bridge/tabs/net/http/fs/chrome/menu/ui/skin/input/intent/storage/priv/cdp"
    end
    local text = table.concat(lines, "\n")
    print(text)
    return text
end

do
    local who = lemurx.isPrivileged() and "local" or ("ugc:" .. tostring(lemurx.scriptName()))
    print("LemurX Lua runtime ready", lemurx.version, who, "lemurx.help() 查看接口")
end
