-- 案例 50 · 用 luakit 的 widget{} 自己画一个浏览器外壳（原生 View 树）
--
-- 扩展做不到的点：扩展没有任何“原生 UI”能力——不能建窗口、不能做标签栏、
-- 不能有命令行、不能拿硬件按键。这里用 luakit 原样的 widget API 在 Android 上
-- 搭一个 vim 风格外壳：顶部标签条 + 中间网页 + 底部状态栏 + 命令行 entry，
-- 一个 window 盖住原生工具栏；`:q` 退出还原。
--
-- 命令：o <url>  在当前标签打开     t <url>  新标签打开     d  关闭当前标签
--       n / p     下一个 / 上一个标签   r  刷新     b / f  后退 / 前进
--       zi / zo   放大 / 缩小           q  关掉这个壳
-- 用法：底栏菜单「Lua 壳」进入；之后点底部状态栏聚焦命令行。

local shell   -- 当前壳实例

local function tab_views()
    local list = {}
    for _, t in ipairs(lemurx.tabs.list() or {}) do
        local v = __lk.webview_for_tab(t.id, true)   -- LemurX内核：给原生 Tab 配一个 luakit webview 对象
        if v then list[#list + 1] = { tab = t, view = v } end
    end
    return list
end

local function build()
    local win = widget{ type = "window" }
    local layout = widget{ type = "vbox" }
    local tablist = widget{ type = "hbox", homogeneous = false, spacing = 6 }
    local tabstrip = widget{ type = "scrolled" }          -- 横向可滚的标签条
    local nb = widget{ type = "notebook" }
    local status = widget{ type = "hbox" }
    local sb = widget{ type = "eventbox" }              -- 包住状态栏，接点击
    local uri_l = widget{ type = "label", align = { h = "left" }, textwidth = 0 }
    local pos_l = widget{ type = "label", align = { h = "right" } }
    local prompt = widget{ type = "label", text = ":" }
    local entry = widget{ type = "entry" }
    local cmdline = widget{ type = "hbox" }

    win.child = layout
    tabstrip.child = tablist
    layout:pack(tabstrip)
    layout:pack(nb, { expand = true, fill = true })
    status:pack(uri_l, { expand = true, fill = true })
    status:pack(pos_l)
    sb.child = status
    layout:pack(sb)
    cmdline:pack(prompt)
    cmdline:pack(entry, { expand = true, fill = true })
    layout:pack(cmdline)

    -- 配色：这些都是 Pango/GTK 写法，内核翻成 Android 属性
    tablist.bg = "#102a43"
    status.bg = "#0b1f33"
    uri_l.fg, pos_l.fg = "#d9e2ec", "#829ab1"
    uri_l.font, pos_l.font = "monospace 11", "monospace 11"
    prompt.fg, prompt.bg = "#3e8ed0", "#0b1f33"
    prompt.font = "monospace bold 13"
    entry.fg, entry.bg, entry.font = "#ffffff", "#0b1f33", "monospace 13"
    cmdline.bg = "#0b1f33"

    local S = { win = win, nb = nb, tablist = tablist, uri_l = uri_l, pos_l = pos_l, entry = entry, labels = {} }

    -- 标签条：每个标签一个 label（Pango markup），点击切换
    function S.refresh_tabs()
        for _, l in ipairs(S.labels) do l:destroy() end
        S.labels = {}
        local cur = nb:current()
        for i = 1, nb:count() do
            local v = nb:atindex(i)
            local title = (v.title and v.title ~= "" and v.title) or v.uri or "(空)"
            if #title > 14 then title = title:sub(1, 14) .. "…" end
            local l = widget{ type = "label", padding = 6 }
            l.text = ("<span foreground='%s'>%d</span> %s"):format(i == cur and "#3e8ed0" or "#829ab1", i, title)
            l.fg = i == cur and "#ffffff" or "#9fb3c8"
            l.bg = i == cur and "#243b53" or "#102a43"
            l.font = "sans 12"
            local eb = widget{ type = "eventbox" }
            eb.child = l
            eb:add_signal("button-release", function() nb:switch(i) end)
            tablist:pack(eb)
            S.labels[#S.labels + 1] = eb
        end
    end

    function S.refresh_status()
        local v = nb[nb:current()]
        if not v then return end
        uri_l.text = v.uri or ""
        local s = v.scroll
        local pct = (s and s.ymax and s.ymax > 0) and math.floor(s.y * 100 / s.ymax) or 0
        pos_l.text = ("%d%% · %d/%d"):format(pct, nb:current(), nb:count())
    end

    -- 把现有原生标签全部收进 notebook（webview 作为页签，网页真实渲染在它的矩形里）
    for _, e in ipairs(tab_views()) do
        nb:append(e.view)
        e.view:add_signal("load-status", function() S.refresh_tabs() S.refresh_status() end)
        e.view:add_signal("property::title", S.refresh_tabs)
        e.view:add_signal("property::uri", S.refresh_status)
    end
    nb:add_signal("switch-page", function() S.refresh_tabs() S.refresh_status() end)

    -- 命令行：回车执行
    local function cmd(text)
        local c, arg = text:match("^%s*(%S+)%s*(.-)%s*$")
        if not c then return end
        local v = nb[nb:current()]
        if c == "o" and v then
            v.uri = arg:find("^%a+://") and arg or ("https://" .. arg)
        elseif c == "t" then
            local id = lemurx.tabs.open(arg:find("^%a+://") and arg or ("https://" .. arg))
            local nv = __lk.webview_for_tab(id, true)
            if nv then nb:append(nv) nb:switch(nb:count()) end
        elseif c == "d" and v then
            local idx = nb:current()
            nb:remove(v)
            lemurx.tabs.close(v.id)
            if nb:count() > 0 then nb:switch(math.min(idx, nb:count())) end
        elseif c == "n" then nb:switch(nb:current() % nb:count() + 1)
        elseif c == "p" then nb:switch((nb:current() - 2) % nb:count() + 1)
        elseif c == "r" and v then v:reload()
        elseif c == "b" and v then v:go_back()
        elseif c == "f" and v then v:go_forward()
        elseif c == "zi" and v then v.zoom_level = (v.zoom_level or 1) + 0.1
        elseif c == "zo" and v then v.zoom_level = (v.zoom_level or 1) - 0.1
        elseif c == "q" then S.close() return
        else lemurx.toast("未知命令: " .. c) end
        S.refresh_tabs() S.refresh_status()
    end
    entry:add_signal("activate", function(e)
        local t = e.text or ""
        e.text = ""
        cmd(t)
    end)
    -- 硬件/输入法按键：Esc 清空，Ctrl-L 聚焦（同步裁决，返回 true 表示吞掉）
    entry:add_signal("key-press", function(e, mods, key)
        if key == "Escape" then e.text = "" return true end
    end)
    -- 点状态栏 → 聚焦命令行
    sb:add_signal("button-release", function() entry:focus() end)

    function S.close()
        -- 把 webview 从 notebook 里摘出来（不 destroy：Tab 还活着），再销毁窗口
        for i = nb:count(), 1, -1 do nb:remove(nb:atindex(i)) end
        win:destroy()
        shell = nil
        lemurx.toast("Lua 壳已关闭，回到原生外壳")
    end

    win:add_signal("destroy", function() shell = nil end)
    win:set_default_size(0, 0)   -- 0 = 占满
    win:show()
    S.refresh_tabs()
    S.refresh_status()
    return S
end

lemurx.menu.add({
    id = "lua_shell", title = "Lua 壳", page = "main",
    onClick = function()
        if shell then shell.close() else shell = build() end
    end,
})

lemurx.log("[案例50] 底栏菜单「Lua 壳」：用 luakit widget 树接管整个界面")
