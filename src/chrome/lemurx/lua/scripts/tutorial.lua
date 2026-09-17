-- LemurX Lua 魔改教程（可运行案例）
-- Lua 魔改教程 v1.12.1
--
-- 每个场景先写用户真实痛点，再动手改浏览器。这不是 API 清单。
-- 首次启动会复制到 filesDir/lua/00_tutorial.lua 并自动加载。
-- 右下角「Lua教程」是原生覆盖层，不是网页 DOM，扩展做不出这个按钮。
--
-- 测试建议：
-- 1. 点「Lua教程」展开，用「上一个 / 演示本页 / 下一个」对号入座自己骂过的烦心事
-- 2. 「一键演示」一次打开最常骂的三件：网页当 App、广告改包、手滑返回
-- 3. 「还原」把 chrome / 菜单 / 网络规则 / 返回键 / 皮肤 / 原生控件恢复；20 演示拆底栏和抽屉
-- 4. 不想再弹面板：点「关掉面板」，或 storage 写入 lua_tutorial_panel=0
-- 5. 删掉 00_tutorial.lua 再启动，会从 apk 资源重新生成

local TUTORIAL = {
    panel_key = "lua_tutorial_panel",
    scene_key = "lua_tutorial_scene",
}

local state = {
    open = false,
    index = 1,
    back_armed = false,
    back_once = false,
    hidden_tab = nil,
    rule_ids = {},
    hidden_menus = {},
    inject_on_load = false,
}

local function log(...)
    lemurx.log("[tutorial]", ...)
end

local function say(msg)
    log(msg)
    lemurx.toast(msg)
end

local function ok(fn, ...)
    local pass, err = pcall(fn, ...)
    if not pass then
        say("这一步失败: " .. tostring(err))
        return false
    end
    return true
end

local function current_tab()
    local tab = lemurx.tabs.current()
    if tab and tab.id then
        return tab
    end
    return nil
end

local function open_demo_page()
    local tab = current_tab()
    if tab and tab.url and string.find(tab.url, "example.com", 1, true) then
        return tab.id
    end
    return lemurx.tabs.open("https://example.com")
end

local function add_rule(rule)
    local id = lemurx.net.addRule(rule)
    if id then
        state.rule_ids[#state.rule_ids + 1] = id
    end
    return id
end

local function clear_tutorial_rules()
    for _, id in ipairs(state.rule_ids) do
        lemurx.net.removeRule(id)
    end
    state.rule_ids = {}
end

local function hide_menu(name)
    lemurx.menu.hide(name, true)
    state.hidden_menus[name] = true
end

local function restore_menus()
    for name, _ in pairs(state.hidden_menus) do
        lemurx.menu.hide(name, false)
    end
    state.hidden_menus = {}
    lemurx.menu.clear()
end

local function restore_chrome()
    ok(lemurx.chrome.controls, "both")
    ok(lemurx.chrome.hideUrlBar, false)
    ok(lemurx.chrome.fullscreen, false)
    ok(lemurx.chrome.hideBottomToolbar, false)
    ok(lemurx.chrome.setMenuButtonVisible, true)
    ok(lemurx.chrome.setPullRefresh, true)
    for _, name in ipairs({
        "back", "forward", "home", "tabs", "tools", "extensions", "settings", "search", "menu",
    }) do
        ok(lemurx.chrome.hideButton, name, false)
    end
    ok(lemurx.chrome.barLayout, "1-2-3-4-5")
    ok(lemurx.input.interceptBack, false)
    state.back_armed = false
    state.back_once = false
end

local function restore_all()
    restore_chrome()
    restore_menus()
    clear_tutorial_rules()
    state.inject_on_load = false
    if state.hidden_tab then
        ok(lemurx.tabs.close, state.hidden_tab)
        state.hidden_tab = nil
    end
    if state.skin_on then
        ok(lemurx.theme.reset)
        state.skin_on = false
    end
    if state.slots_on then
        for _, slot in ipairs({ "toolbar.end", "bottom.start", "page.top" }) do
            ok(lemurx.ui.unmount, slot)
        end
        state.slots_on = false
    end
    ok(lemurx.ui.unmount, "*")
    ok(lemurx.ui.restore)
    ok(lemurx.ui.off, "*")
    say("已还原浏览器外壳 / 菜单 / 网络规则 / 返回键 / 皮肤 / 原生控件")
end

local function count_table(value)
    if type(value) ~= "table" then
        return 0
    end
    if value[1] then
        return #value
    end
    local n = 0
    for _ in pairs(value) do
        n = n + 1
    end
    return n
end

-- 场景 1：沉浸阅读。痛点：看小说/漫画时顶栏底栏挡字，下拉一下整页刷新。
local function scene_shell()
    restore_chrome()
    lemurx.chrome.controls("hidden")
    lemurx.chrome.hideUrlBar(true)
    lemurx.chrome.hideBottomToolbar(true)
    lemurx.chrome.setMenuButtonVisible(false)
    lemurx.chrome.setPullRefresh(false)
    lemurx.chrome.setToolbarColor("#FF121212")
    lemurx.chrome.setStatusBarColor("#FF000000")
    say("阅读模式：工具栏、地址栏、底栏、下拉刷新都关掉了。点「还原」退出")
end

-- 场景 2：网页当 App。痛点：常用站还要绕扩展商店，底栏全是浏览器自己的入口。
local function scene_app()
    restore_menus()
    hide_menu("EXTENSION_STORE")
    hide_menu("extension_manager")
    hide_menu("extension_store")
    lemurx.chrome.setToolbarColor("#FF1B5E20")
    lemurx.chrome.setStatusBarColor("#FF0D3B12")
    lemurx.chrome.hideButton("extensions", true)
    lemurx.menu.add({
        id = "tutorial_grab",
        title = "抓取",
        page = "main",
        onClick = function()
            local tab = current_tab()
            say(tab and ("已记下 " .. tostring(tab.url)) or "当前没有标签")
        end,
    })
    lemurx.menu.add({
        id = "tutorial_restore",
        title = "还原",
        page = "main",
        onClick = restore_all,
    })
    say("这个站现在更像 App：扩展入口藏了，底栏只留「抓取 / 还原」。打开底栏看")
end

-- 场景 3：广告和改包。痛点：扩展拦请求，改不了 HTML；脚本还经常被 CSP 掐死。
local function scene_rewrite()
    clear_tutorial_rules()
    add_rule({
        match = "*://*.doubleclick.net/*",
        action = "block",
        types = { "script", "image", "xmlhttprequest", "sub_frame", "media" },
    })
    add_rule({
        match = "*://*.googlesyndication.com/*",
        action = "block",
        types = { "script", "image", "xmlhttprequest", "sub_frame", "media" },
    })
    add_rule({
        match = "*://example.com/*",
        action = "modify",
        types = { "document" },
        replaceBody = {
            ["Example Domain"] = "广告域名已拦，正文被 Lua 改写（扩展改不了响应体）",
        },
        removeResponseHeaders = { "Content-Security-Policy" },
    })
    state.inject_on_load = true
    open_demo_page()
    say("已拦截广告域名，并改了 example.com 的标题。刷新看正文")
end

-- 场景 4：盯价格别弄脏标签栏。痛点：后台刷价/刷库存会多出一堆标签，还容易误点。
local function scene_hidden()
    if state.hidden_tab then
        ok(lemurx.tabs.close, state.hidden_tab)
        state.hidden_tab = nil
    end
    local id = lemurx.tabs.open("https://example.com", { hidden = true, background = true })
    state.hidden_tab = id
    lemurx.timer.after(1800, function()
        local html = lemurx.tabs.inject(
            id,
            "document.title + ' | ' + location.host",
            { world = "isolated", frames = "all" }
        )
        say("后台盯页结果: " .. tostring(html) .. "。标签栏里不应出现它")
        lemurx.tabs.close(id)
        if state.hidden_tab == id then
            state.hidden_tab = nil
        end
    end)
    say("正在后台打开页面盯数据，你不用切走当前阅读")
end

-- 场景 5：手滑返回。痛点：长文看到一半、表单填到一半，返回键直接把进度清了。
local function scene_back()
    state.back_armed = true
    state.back_once = false
    lemurx.input.interceptBack(true)
    say("返回键加了确认。按一次会拦住，两秒内再按才真正离开")
end

-- 场景 6：登录框/广告 iframe 打不进去。痛点：content script 过不了 CSP，也进不了跨域 iframe。
local function scene_inject()
    local tab = current_tab() or {}
    local id = tab.id or open_demo_page()
    local banner = [[
        (function(){
            if (document.getElementById('lemurx-banner')) return 'exists';
            var d = document.createElement('div');
            d.id = 'lemurx-banner';
            d.setAttribute('style',
                'position:fixed;top:0;left:0;right:0;z-index:2147483647;' +
                'padding:10px;background:#E64CAF50;color:#fff;' +
                'font:14px sans-serif;text-align:center');
            d.textContent = '登录框/iframe 里也能注入，扩展经常做不到这一步';
            (document.body || document.documentElement).appendChild(d);
            return location.href;
        })()
    ]]
    local result = lemurx.tabs.inject(id, banner, { world = "isolated", frames = "all" })
    say("已打进当前页全部 iframe: " .. tostring(result))
end

-- 场景 7：手机版太挤。痛点：政务/银行/文档站只有桌面版能用，系统「桌面网站」藏得深，字还是太小。
local function scene_site()
    local tab = current_tab()
    if not tab then
        say("先打开一个网页，再强制桌面站")
        return
    end
    lemurx.tabs.setDesktop(tab.id, true)
    lemurx.tabs.setZoom(tab.id, 150)
    say("已切桌面站并放大到 " .. tostring(lemurx.tabs.getZoom(tab.id)) .. "% ，适合挤在一起的后台页")
end

-- 场景 8：登录莫名掉了。痛点：换机、清数据、被踢下线时，手机上看不到 Cookie/历史到底还在不在。
local function scene_sandbox()
    if not lemurx.isPrivileged() then
        say("UGC 脚本读不了 Cookie/历史，避免魔改被分享到社区")
        return
    end
    local tab = current_tab()
    local url = tab and tab.url or "https://example.com"
    local cookies = lemurx.cookie.get(url)
    local history = lemurx.history.query("")
    local downloads = lemurx.downloads.list()
    say(string.format(
        "当前站 Cookie %s 条，本机历史 %s 条，下载 %s 个。登录掉了先看这三项",
        tostring(count_table(cookies)),
        tostring(count_table(history)),
        tostring(count_table(downloads))
    ))
end

-- 场景 9：把网页丢给别的 App。痛点：浏览器分享面板经常缺微信/笔记，用户只能复制链接再切走。
local function scene_intent()
    local tab = current_tab()
    local url = (tab and tab.url) or "https://example.com"
    lemurx.intent.startActivity({
        action = "android.intent.action.VIEW",
        url = url,
    })
    say("已把当前页交给系统打开。这是 Android Intent，不是网页 share")
end

local function scene_schema()
    lemurx.schema.launch("chat_ai", { from = "lua_tutorial" })
    say("当前页可以直接跳进LemurX AI，不用自己找入口")
end

-- 场景 11：看视频被下拉刷新打断。痛点：全屏刷视频时手滑一下，页面重载、进度清零。
local function scene_fullscreen()
    lemurx.chrome.fullscreen(true)
    lemurx.chrome.setPullRefresh(false)
    say("全屏且禁止下拉刷新。看视频时不会再被手滑毁掉进度")
end

-- 场景 12：页面接口跨域拿不到。痛点：DevTools 里看得到 JSON，页面 fetch 被 CORS 拦，扩展也过不了。
local function scene_http()
    local r = lemurx.http.fetch("https://example.com", { timeout = 8000 })
    if not r or not r.ok then
        say("直连失败: " .. tostring(r and r.error or "nil"))
        return
    end
    local body = tostring(r.body or "")
    lemurx.fs.write("captures/example_fetch.txt", body:sub(1, 400))
    say("浏览器进程已直连接口 " .. tostring(r.status) .. "，写入 lua/captures/example_fetch.txt")
end

-- 场景 13：客服要截图证明。痛点：系统截图带着浏览器栏；要复现「我点过这里」还得人手点。
local function scene_rpa()
    local tab = current_tab()
    if not tab then
        say("先打开要证明的页面再截图")
        return
    end
    local shot = lemurx.tabs.screenshot(tab.id)
    if shot and shot.ok then
        log("screenshot", shot.path, shot.width, shot.height)
    end
    local w = (shot and shot.width) or 400
    local h = (shot and shot.height) or 800
    ok(lemurx.input.tap, w / 2, h / 3)
    say("已截当前页到 " .. tostring(shot and shot.path or "?") .. "，并点了页面中上部（给客服看的那种）")
end

-- 场景 14：页面上的价是 JS 算出来的。痛点：查看网页源代码里没有现价，扩展还要用户点允许调试。
local function scene_cdp()
    local tab = current_tab()
    if not tab then
        say("先打开一个网页，再读 JS 渲染后的真数据")
        return
    end
    local attached = false
    ok(function()
        attached = lemurx.cdp.attach(tab.id)
    end)
    if not attached then
        say("调试通道被占用，稍后再试")
        return
    end
    local title, href, text
    ok(function()
        title = select(1, lemurx.v8.eval(tab.id, "document.title"))
        href = select(1, lemurx.v8.eval(tab.id, "location.href"))
        text = select(1, lemurx.v8.eval(
            tab.id,
            "(document.body && (document.body.innerText||'').replace(/\\s+/g,' ').slice(0,80)) || ''"
        ))
    end)
    say("JS 渲染后的标题: " .. tostring(title) .. " / " .. tostring(text))
    log("cdp href", href)
end

-- 场景 15：比价点进去回不去。痛点：商品详情连跳三层，系统返回一次只退一页，用户要的是「回到列表」。
local function scene_nav()
    local tab = current_tab()
    if not tab then
        say("先打开一个网页再看浏览路径")
        return
    end
    local hist = lemurx.tabs.history(tab.id)
    local frames = lemurx.tabs.frames(tab.id)
    local n = hist and hist.entries and #hist.entries or 0
    local f = frames and frames.frames and #frames.frames or 0
    if hist and hist.canGoBack then
        lemurx.tabs.offset(tab.id, -1)
        say(string.format("浏览路径共 %s 步、%s 个 frame。已直接退回上一层，不用连按返回", tostring(n), tostring(f)))
        return
    end
    say(string.format("这条路径还只有 %s 步、%s 个 frame。先点进子页再演示「回到列表」", tostring(n), tostring(f)))
end

-- 场景 16：网站更新了还是旧页面。痛点：Service Worker / 后台 worker 把旧资源钉死，扩展的 debugger 看不到它们。
local function scene_lab()
    if not lemurx.isPrivileged() then
        say("UGC 看不到后台 worker，避免把调试能力分享到社区")
        return
    end
    local tab = current_tab()
    if tab then
        ok(lemurx.tabs.reloadBypassCache, tab.id)
    end
    local targets = lemurx.cdp.targets()
    local list = targets and targets.targets or {}
    local workers = 0
    local kinds = {}
    for i = 1, #list do
        local t = list[i]
        if t and t.type then
            kinds[t.type] = (kinds[t.type] or 0) + 1
            if t.type == "service_worker" or t.type == "shared_worker" or t.type == "worker" then
                workers = workers + 1
            end
        end
    end
    local parts = {}
    for k, v in pairs(kinds) do
        parts[#parts + 1] = k .. "=" .. tostring(v)
    end
    table.sort(parts)
    say(string.format(
        "已强制绕过缓存刷新。后台还挂着 %s 个 worker（共 %s 个调试目标）",
        tostring(workers),
        tostring(#list)
    ))
    log("targets", table.concat(parts, " "))
end

-- 场景 17：直接操作原生 UI。痛点：扩展只能改网页 DOM，点不到工具栏、底栏、系统对话框。
local function scene_native_ui()
    local found = lemurx.ui.find({ text = "Lua教程" })
    local n = found and found.count or 0
    lemurx.ui.dialog({
        title = "这是系统对话框",
        message = "Lua 已经扫到 " .. tostring(n) .. " 个带「Lua教程」的原生控件。扩展点不到浏览器自己的按钮。",
        ok = "知道了",
        cancel = "去点它",
        onOk = function()
            say("对话框确定。也可以 lemurx.ui.click({text='Lua教程'}) 直接点原生按钮")
        end,
        onCancel = function()
            local hit = lemurx.ui.click({ text = "Lua教程" })
            say(hit and hit.ok and "已经直接点了原生「Lua教程」按钮" or "没点到，试试底栏菜单")
        end,
    })
    say("弹出了 Android AlertDialog。点「去点它」会去点原生按钮")
end

-- 场景 18：换皮肤。痛点：浏览器长啥样只能在设置里挑两三个主题，想要自己的配色、底栏排布做不到。
local SKINS = { "forest", "midnight", "sakura", "paper" }
local function scene_skin()
    state.skin_index = (state.skin_index or 0) % #SKINS + 1
    local name = SKINS[state.skin_index]
    -- 预设只是几组颜色，theme.set 里任何键都能自己改；bottomStyle=2 把底栏换成「主页/标签/搜索」
    local r = lemurx.theme.apply(name, { bottomStyle = 2 })
    state.skin_on = true
    if r and r.ok then
        say("皮肤已换成 " .. name .. "，重启也会记住。再点一次换下一套，「还原」回默认")
    else
        say("换肤失败: " .. tostring(r and r.error))
    end
end

-- 场景 19：自己的工具条。痛点：想在顶栏放「翻译」、底栏放「回顶部」、网页上方来条状态栏，
-- 扩展只能在网页 DOM 里画，碰不到浏览器自己的栏。
local function strip_text()
    local tabs = lemurx.tabs.list() or {}
    local tab = current_tab()
    return tostring(#tabs) .. " 个标签 · " .. tostring(tab and tab.title or "")
end

local function scene_toolbar()
    local h = lemurx.ui.h
    -- 顶栏右侧：一键把当前页丢给谷歌翻译
    lemurx.ui.render("toolbar.end", h("icon", {
        id = "tut_tb_translate",
        text = "译",
        desc = "翻译当前页",
        size = 16,
        bold = true,
        onClick = function()
            local tab = current_tab()
            if not tab then
                return
            end
            lemurx.tabs.open("https://translate.google.com/translate?sl=auto&tl=zh-CN&u=" .. tab.url)
        end,
    }))
    -- 底栏第一格：回到顶部
    lemurx.ui.render("bottom.start", h("icon", {
        id = "tut_bb_top",
        text = "⬆",
        desc = "回顶部",
        size = 20,
        onClick = function()
            local tab = current_tab()
            if tab then
                lemurx.tabs.eval(tab.id, "window.scrollTo({top:0,behavior:'smooth'})")
            end
        end,
        onLongClick = function()
            local tab = current_tab()
            if tab then
                lemurx.tabs.eval(tab.id, "window.scrollTo({top:document.body.scrollHeight})")
                say("长按：到底部")
            end
        end,
    }))
    -- 网页上方一条自己的状态栏：标签数、桌面版开关、收起
    lemurx.ui.render("page.top", h("row", {
        id = "tut_strip",
        background = "#E61B5E20",
        padding = { 12, 6 },
        elevation = 4,
    }, {
        h("text", {
            id = "tut_strip_text",
            text = strip_text(),
            color = "#FFFFFFFF",
            size = 12,
            weight = 1,
            maxLines = 1,
            ellipsize = true,
        }),
        h("switch", {
            id = "tut_strip_desktop",
            text = "桌面版",
            color = "#FFFFFFFF",
            size = 12,
            onChange = function(ev)
                local tab = current_tab()
                if tab then
                    lemurx.tabs.setDesktop(tab.id, ev.checked and true or false)
                end
            end,
        }),
        h("button", {
            id = "tut_strip_close",
            text = "收起",
            size = 12,
            margin = { left = 8 },
            onClick = function()
                lemurx.ui.unmount("page.top")
            end,
        }),
    }))
    state.slots_on = true
    say("顶栏多了「译」，底栏第一格是「回顶部」，网页上方是 Lua 画的状态栏。整段就是一棵 Lua 表")
end

-- 场景 20：把底栏、地址栏、工具抽屉当成自己的 View 树来改。
local function scene_surgery()
    restore_all()
    local h = lemurx.ui.h
    local shell = lemurx.ui.shell()
    local alive = 0
    if shell and shell.views then
        for _, item in ipairs(shell.views) do
            if item.found then
                alive = alive + 1
            end
        end
    end
    -- 底栏主页按钮换成「顶」：真正换掉原生 View，不是叠一层
    lemurx.ui.replace({ id = "home_button" }, h("button", {
        id = "lua_home",
        text = "顶",
        size = 13,
        bold = true,
        background = "#FF00C853",
        color = "#FFFFFFFF",
        onClick = function()
            local tab = current_tab()
            if tab then
                lemurx.tabs.eval(tab.id, "window.scrollTo({top:0,behavior:'smooth'})")
            end
        end,
    }))
    -- 地址栏右边插一颗「译」
    lemurx.ui.insert({ id = "url_bar" }, h("button", {
        id = "lua_tr",
        text = "译",
        size = 12,
        padding = { 10, 4 },
        background = "#FF2962FF",
        color = "#FFFFFFFF",
        onClick = function()
            local tab = current_tab()
            if not tab then
                return
            end
            lemurx.tabs.eval(tab.id, [[
                (function(){
                  var t = window.getSelection && window.getSelection().toString();
                  if (!t) t = document.title;
                  location.href = "https://translate.google.com/?sl=auto&tl=zh-CN&text="
                    + encodeURIComponent(t);
                })()
            ]])
        end,
    }), { position = "after" })
    -- 工具抽屉按钮从地址栏右侧摘走（点还原会回来）
    lemurx.ui.detach({ id = "menu_tools" })
    -- 打开主菜单抽屉后，Lua 能扫到 Dialog 窗口，把「历史」标红、「下载」藏掉
    lemurx.ui.on({ id = "menu_button_lemur" }, "click", function()
        lemurx.timer.after(450, function()
            lemurx.ui.style({ text = "历史" }, { color = "#FFFF5252", size = 15, bold = true, once = true })
            lemurx.ui.style({ text = "下载" }, { visible = false, once = true })
        end)
    end, { consume = false })
    lemurx.chrome.barLayout({ "home", "tabs", "search", "menu", "tools" })
    local menus = lemurx.menu.list()
    local n = 0
    if menus and menus.pages then
        for _, page in ipairs(menus.pages) do
            n = n + (page.items and #page.items or 0)
        end
    end
    state.slots_on = true
    say("主页→「顶」，地址栏旁「译」，工具按钮摘走。外壳活着 "
        .. tostring(alive) .. " 个控件，抽屉 " .. tostring(n)
        .. " 项。点右侧菜单看历史变红")
end

local scenes = {
    { title = "1 沉浸阅读", pain = "小说漫画时工具栏挡字、下拉误刷新", run = scene_shell },
    { title = "2 网页当 App", pain = "常用站还要绕扩展商店，底栏全是浏览器入口", run = scene_app },
    { title = "3 广告去不掉", pain = "扩展能拦请求，改不了 HTML，脚本还被 CSP 掐", run = scene_rewrite },
    { title = "4 盯价格别脏栏", pain = "后台刷价会多出一堆标签，还容易误点", run = scene_hidden },
    { title = "5 手滑返回", pain = "长文/表单看到一半，返回键把进度清了", run = scene_back },
    { title = "6 脚本打不进框", pain = "登录框、广告 iframe、CSP，content script 过不去", run = scene_inject },
    { title = "7 手机版太挤", pain = "政务银行文档只有桌面版能用，字还太小", run = scene_site },
    { title = "8 登录莫名掉了", pain = "换机被踢时，手机上看不到 Cookie/历史还在不在", run = scene_sandbox },
    { title = "9 丢给别的 App", pain = "分享面板缺微信/笔记，只能复制链接再切走", run = scene_intent },
    { title = "10 一键进 AI", pain = "看网页时想问LemurX，还得自己找入口", run = scene_schema },
    { title = "11 视频被刷新", pain = "全屏刷视频手滑一下，进度清零", run = scene_fullscreen },
    { title = "12 接口跨域", pain = "DevTools 看得到 JSON，页面 fetch 被 CORS 拦", run = scene_http },
    { title = "13 客服要截图", pain = "系统截图带着浏览器栏，还无法证明「我点过」", run = scene_rpa },
    { title = "14 现价在 JS 里", pain = "查看源代码没有现价，扩展调试还要用户点允许", run = scene_cdp },
    { title = "15 比价回不去", pain = "详情连跳三层，返回一次只退一页，回不到列表", run = scene_nav },
    { title = "16 更新还是旧页", pain = "Service Worker 把旧资源钉死，扩展看不见后台 worker", run = scene_lab },
    { title = "17 直接点原生 UI", pain = "扩展只能改网页，点不到工具栏、底栏、系统对话框", run = scene_native_ui },
    { title = "18 换皮肤", pain = "主题只能在设置里挑两三个，配色、底栏排布都不能自己定", run = scene_skin },
    { title = "19 自己的工具条", pain = "想在顶栏/底栏加自己的按钮，扩展只能在网页里画", run = scene_toolbar },
    { title = "20 拆原生外壳", pain = "底栏、搜索框、工具抽屉都是写死的，想换成自己的布局没门", run = scene_surgery },
}

local draw_panel

local function scene_at()
    if state.index < 1 then
        state.index = 1
    end
    if state.index > #scenes then
        state.index = #scenes
    end
    return scenes[state.index]
end

local function run_scene()
    local scene = scene_at()
    log("run", scene.title, scene.pain)
    scene.run()
    lemurx.storage.set(TUTORIAL.scene_key, tostring(state.index))
    draw_panel()
end

local function run_all_visible()
    restore_all()
    scene_app()
    scene_rewrite()
    scene_back()
    say("一键演示最常骂的三件：网页当 App、广告改包、手滑返回")
end

local COLORS = {
    fab = "#E64CAF50",
    run = "#E62196F3",
    nav = "#E6333333",
    reset = "#E6D32F2F",
    title = "#CC000000",
}

local function btn(id, text, gravity, x, y, bg, click)
    lemurx.ui.show({
        id = id,
        type = "button",
        text = text,
        gravity = gravity,
        x = x,
        y = y,
        background = bg,
        color = "#FFFFFFFF",
        textSize = 13,
        radius = 18,
        onClick = click,
    })
end

draw_panel = function()
    -- 先清掉教程自己的按钮，避免残影。不要调用 ui.clear，免得干掉别人的覆盖层。
    for _, id in ipairs({
        "tut_fab", "tut_title", "tut_hint", "tut_prev", "tut_run",
        "tut_next", "tut_all", "tut_reset", "tut_hide",
    }) do
        pcall(lemurx.ui.remove, id)
    end

    if lemurx.storage.get(TUTORIAL.panel_key, "1") == "0" then
        return
    end

    if not state.open then
        btn("tut_fab", "Lua教程", "end|bottom", 16, 168, COLORS.fab, function()
            state.open = true
            draw_panel()
        end)
        return
    end

    local scene = scene_at()
    lemurx.ui.show({
        id = "tut_title",
        type = "text",
        text = scene.title,
        gravity = "end|bottom",
        x = 16,
        y = 412,
        background = COLORS.title,
        color = "#FFFFFFFF",
        textSize = 13,
        radius = 12,
    })
    lemurx.ui.show({
        id = "tut_hint",
        type = "text",
        text = "痛点：" .. (scene.pain or ""),
        gravity = "end|bottom",
        x = 16,
        y = 368,
        background = "#99000000",
        color = "#FFFFFFFF",
        textSize = 11,
        radius = 12,
    })
    btn("tut_prev", "上一个", "end|bottom", 16, 316, COLORS.nav, function()
        state.index = state.index - 1
        if state.index < 1 then
            state.index = #scenes
        end
        draw_panel()
    end)
    btn("tut_run", "演示本页", "end|bottom", 16, 264, COLORS.run, run_scene)
    btn("tut_next", "下一个", "end|bottom", 16, 212, COLORS.nav, function()
        state.index = state.index + 1
        if state.index > #scenes then
            state.index = 1
        end
        draw_panel()
    end)
    btn("tut_all", "一键演示", "end|bottom", 16, 160, COLORS.fab, run_all_visible)
    btn("tut_reset", "还原", "end|bottom", 16, 108, COLORS.reset, restore_all)
    btn("tut_hide", "关掉面板", "end|bottom", 16, 56, COLORS.nav, function()
        state.open = false
        lemurx.storage.set(TUTORIAL.panel_key, "0")
        draw_panel()
        say("面板已关。storage 里把 lua_tutorial_panel 改回 1 可再打开")
    end)
end

lemurx.tutorial = lemurx.tutorial or {}

function lemurx.tutorial.redraw()
    draw_panel()
end

function lemurx.tutorial.open()
    lemurx.storage.set(TUTORIAL.panel_key, "1")
    state.open = true
    draw_panel()
    say("对着痛点点「演示本页」。底栏菜单第一项「Lua教程」也能进来")
end

lemurx.tabs.on("loaded", function(ev)
    if not state.inject_on_load or not ev or not ev.url then
        return
    end
    if not string.find(ev.url, "example.com", 1, true) then
        return
    end
    ok(lemurx.tabs.inject, ev.id, [[
        (function(){
            if (document.getElementById('lemurx-banner')) return;
            var d = document.createElement('div');
            d.id = 'lemurx-banner';
            d.setAttribute('style',
                'position:fixed;bottom:0;left:0;right:0;z-index:2147483647;' +
                'padding:8px;background:#FF1B5E20;color:#fff;' +
                'font:12px sans-serif;text-align:center');
            d.textContent = '广告域名已拦，正文被改写。这是用户真会骂的那类页';
            (document.body || document.documentElement).appendChild(d);
        })()
    ]], { world = "isolated", frames = "all" })
end)

lemurx.tabs.on("created", function(ev)
    log("tab created", ev and ev.id, ev and ev.url)
end)

lemurx.tabs.on("closed", function(ev)
    log("tab closed", ev and ev.id)
end)

lemurx.tabs.on("selected", function(ev)
    log("tab selected", ev and ev.id, ev and ev.url)
    if state.slots_on then
        -- 场景 19 的状态栏跟着当前标签刷新：改已挂载节点只要 ui.update
        pcall(lemurx.ui.update, "tut_strip_text", { text = strip_text() })
    end
end)

lemurx.chrome.on("omnibox", function(ev)
    log("omnibox", ev and ev.focus, ev and ev.text)
end)

lemurx.input.onBack(function(ev)
    if not state.back_armed then
        lemurx.chrome.back()
        return
    end
    if state.back_once then
        state.back_once = false
        lemurx.input.interceptBack(false)
        state.back_armed = false
        lemurx.chrome.back()
        say("第二次返回，已离开这个页")
        return
    end
    state.back_once = true
    say("先拦住了。两秒内再按一次才退出，避免手滑丢掉进度")
    lemurx.timer.after(2000, function()
        state.back_once = false
    end)
end)

lemurx.db.exec("CREATE TABLE IF NOT EXISTS lua_tutorial(id INTEGER PRIMARY KEY, note TEXT)")
lemurx.db.exec("INSERT INTO lua_tutorial(note) VALUES(?)", {
    "tutorial loaded " .. tostring(lemurx.version),
})

local saved = tonumber(lemurx.storage.get(TUTORIAL.scene_key, "1")) or 1
if saved >= 1 and saved <= #scenes then
    state.index = saved
end

lemurx.timer.after(500, function()
    local info = lemurx.browser.info() or {}
    log("boot", lemurx.version, info.channel, info.versionName, "privileged", lemurx.isPrivileged())
    if lemurx.storage.get(TUTORIAL.panel_key, "1") ~= "0" then
        state.open = false
        draw_panel()
        say("入口：底栏菜单第一项「Lua教程」，右下角也有绿色按钮")
    end
end)
