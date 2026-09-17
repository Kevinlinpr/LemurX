-- 案例 10 · 重塑浏览器外壳：把LemurX改成一个「只读新闻」的 App
--
-- 扩展做不到的点：扩展只能在网页 DOM 里画东西，碰不到浏览器自己的工具栏、底栏、
-- 系统状态栏、菜单、返回键。这里全都是原生 View 和原生行为，网页 CSP 管不着。
--
-- 放到 files/lua/10_shell_remix.lua，重启生效。底栏菜单里多一项「退出阅读壳」可还原。

local h = lemurx.ui.h

local SITES = {
    { name = "科技", url = "https://news.ycombinator.com/" },
    { name = "维基", url = "https://zh.m.wikipedia.org/" },
    { name = "示例", url = "https://example.com/" },
}

local state = { on = false }

local function current()
    local t = lemurx.tabs.current()
    return t and t.id or nil
end

local function enter()
    if state.on then return end
    state.on = true

    -- 1) 皮肤：一次设完，Activity 重建自动重放（扩展连状态栏颜色都改不了）
    lemurx.theme.set({
        toolbar = "#FF102A43", statusBar = "#FF0B1F33", bottomBar = "#FF102A43",
        iconTint = "#FFD9E2EC", accent = "#FF3E8ED0", dark = true,
        hideButtons = { "tools", "home" },   -- 藏掉工具抽屉和主页
        pullRefresh = false,
    })

    -- 2) 顶栏右侧：一个「专注」按钮，点了把网页里的侧栏/评论/广告一把藏掉
    lemurx.ui.render("toolbar.end", h("icon", {
        id = "remix_focus", text = "◎", desc = "专注模式", size = 18, bold = true,
        onClick = function()
            local id = current()
            if not id then return end
            lemurx.tabs.inject(id, [[
                (function(){
                  var kill = ['aside','[class*=sidebar]','[id*=sidebar]','[class*=comment]','[id*=comment]',
                              '[class*=ad-]','[id*=ad-]','[class*=banner]','iframe[src*=ads]'];
                  document.querySelectorAll(kill.join(',')).forEach(function(e){ e.style.display='none'; });
                  document.body.style.maxWidth='720px'; document.body.style.margin='0 auto';
                })();
            ]], { world = "isolated", frames = "all" })
            lemurx.toast("专注模式：侧栏/评论/广告已隐藏")
        end,
    }))

    -- 3) 网页正上方一条原生站点切换条（不在网页 DOM 里，滚动/跳转都不丢）
    local chips = {}
    for i, s in ipairs(SITES) do
        chips[#chips + 1] = h("button", {
            id = "remix_site_" .. i, text = s.name, size = 12, radius = 14,
            padding = { 12, 4 }, margin = { right = 8 },
            onClick = function()
                local id = current()
                if id then lemurx.tabs.navigate(id, s.url) else lemurx.tabs.open(s.url) end
            end,
        })
    end
    chips[#chips + 1] = h("spacer", { weight = 1 })
    chips[#chips + 1] = h("text", { id = "remix_clock", text = os.date("%H:%M"), color = "#FFD9E2EC", size = 12 })
    lemurx.ui.render("page.top", h("row", {
        id = "remix_strip", background = "#FF0B1F33", padding = { 10, 6 }, elevation = 3,
    }, chips))
    state.clock = lemurx.timer.every(30000, function()
        lemurx.ui.update("remix_clock", { text = os.date("%H:%M") })
    end)

    -- 4) 原生手术：把原生「主页」按钮换成「回顶部」（直接替换 View，而不是盖一层）
    lemurx.ui.replace({ id = "home_button" }, h("icon", {
        id = "remix_top", text = "⬆", size = 20, desc = "回顶部",
        onClick = function()
            local id = current()
            if id then lemurx.tabs.eval(id, "window.scrollTo({top:0,behavior:'smooth'})") end
        end,
    }))

    -- 5) 返回键接管：在站点首页按返回不退出 App，而是回到第一个站
    lemurx.input.onBack(function(ev)
        local t = lemurx.tabs.current()
        if t and t.canGoBack then
            lemurx.chrome.back()
        elseif t and t.url ~= SITES[1].url then
            lemurx.tabs.navigate(t.id, SITES[1].url)
        else
            lemurx.toast("已经在首页了（再按一次菜单里的「退出阅读壳」可还原）")
        end
    end)

    -- 6) 底栏菜单：只留自己的项
    lemurx.menu.add({ id = "remix_exit", title = "退出阅读壳", page = "main", onClick = function() leave() end })
    lemurx.menu.hide("EXTENSION_STORE")
    lemurx.toast("阅读壳已启用")
end

function leave()
    if not state.on then return end
    state.on = false
    lemurx.timer.cancel(state.clock)
    lemurx.ui.unmount("*")   -- 拆掉 strip / 顶栏按钮，被 replace 掉的原生「主页」自动放回
    lemurx.input.interceptBack(false)
    lemurx.menu.remove("remix_exit")
    lemurx.theme.reset()
    lemurx.toast("已还原")
end

-- 启动即进入；想手动控制就把下面这行改成 lemurx.menu.add(...)
enter()
