# LemurX Lua 魔改开发指南（v1.12.1）

LemurX把 Chromium 浏览器进程里的能力通过 Lua 5.4 开放出来：标签、网页、网络、外壳 UI、皮肤、原生控件、系统 Intent、CDP……
你写的不是"扩展"，而是跑在浏览器进程里的脚本，所以扩展做不到的事（改响应体、点原生按钮、换工具栏、绕 CORS）这里都能做。

---

## 1. 五分钟上手

### 1.1 脚本放哪

| 目录 | 权限 | 加载时机 |
|---|---|---|
| apk 内 `assets/lua/init.lua` | 运行时自带 | 最先加载，定义 `lemurx.*` 便捷函数 |
| `/data/data/<包名>/files/lua/official/*.lua` | **官方脚本**（本地特权） | luakit 内核之后；从 apk `assets/lua/official/` 解包，升级时覆盖，用户只能开关 / 复制，不能改 |
| `/data/data/<包名>/files/lua/*.lua` | **本地特权**（全部能力） | 官方脚本之后，按文件名排序依次加载 |
| `/data/data/<包名>/files/lua/ugc/*.lua` | **UGC 受限**（可分享、可上架） | 本地脚本之后加载 |

官方脚本是用 Lua 完整重写的 Chrome 热门扩展（去广告、用户脚本、暗色、翻译、隐私、标签收纳、
新标签页、JSON 查看器、视频增强、AI 助手、专注、UA 切换、技术栈、截图、GitHub 文件树、剪藏），
每个都有 `lemurx://<id>/` 设置页。清单与覆盖表见 `chrome/lemurx/lua/official/CATALOG.md`，
写法见 [第 8 章](#8-官方脚本与-lx-框架)。

首次启动会把 `tutorial.lua` 复制成 `files/lua/00_tutorial.lua`，它就是一份可运行的范例（20 个场景）。
建议命名 `10_xxx.lua`、`20_xxx.lua`，数字决定加载顺序。

推送脚本（adb，需要 root 或 debuggable 包）：

```bash
adb push my.lua /sdcard/my.lua
adb shell run-as <包名> cp /sdcard/my.lua files/lua/10_my.lua
# 重启浏览器生效，或在三点菜单「Lua 脚本」里点「应用」重载
```

### 1.1.1 开关与重载：三点菜单「Lua 脚本」

三点菜单最底下永远有一项 **「Lua 脚本」**——原生 Android 界面（`LemurXScriptsActivity`），
不经过 Lua，脚本隐藏不了、拦截不了。里面是：

* **启用 Lua 脚本** 总开关。关掉 = 引擎不启动、任何脚本不加载、所有钩子走
  「无规则」分支，浏览器就是官方 Chromium；菜单里只剩这一项用来再打开。
* 三组列表：**官方脚本**、**本地脚本**、**UGC 脚本**，每个一个开关。点进去看详情
  （文件头 `-- @name / @description / @version / @icon / @category / @page / @replaces`
  注释解析出来的元数据）、**查看源码**；本地 / UGC 脚本可以直接**编辑源码**、删除；
  官方脚本可以**复制到本地**改成自己的版本（复制件是本地特权脚本，官方原件照常升级）。
* 有 `@page` 的脚本还能一键打开它的 `lemurx://` 设置页。
* 点「应用」立即生效：先把脚本写进原生层的一切清干净（网络规则、导航否决、
  证书放行、CDP 会话、自定义 scheme、定时器、覆盖层、控件、皮肤、逐 tab 的
  UA / 请求头），停掉 Lua 引擎，再重建界面。总开关开着的话新界面会重新起一个
  干净的 `lua_State` 把脚本从头跑一遍——改完脚本不用重启浏览器。

这些设置存在独立的偏好文件里，`lemurx.storage` / `lemurx.prefs` 都碰不到；
脚本不能把自己重新打开或把别的脚本关掉。

**启动保护**：脚本跑起来后 20 秒内进程死掉算一次；连续两次就自动关掉总开关并
提示，保证你永远进得了浏览器去修脚本。正常退出（哪怕很快）不计入。

### 1.2 第一个脚本

```lua
-- files/lua/10_hello.lua
lemurx.log("hello from lua", lemurx.version)      -- logcat: tag LemurX
lemurx.toast("Lua 已加载")                         -- 屏幕底部 Toast

-- 右下角挂一个按钮，点一下把当前网页标题复制走
lemurx.ui.show({
    id = "copy_title", type = "button", text = "复制标题",
    gravity = "end|bottom", x = 16, y = 96,
    onClick = function()
        local tab = lemurx.tabs.current()
        if tab then
            lemurx.clipboard.set(tab.title .. "\n" .. tab.url)
            lemurx.toast("已复制")
        end
    end,
})
```

### 1.3 调试

- `lemurx.log(...)` / `print(...)` → `adb logcat -s LemurX`
- `lemurx.toast(msg)` 看得见的调试
- `lemurx.help()` 打印全部接口；`lemurx.help("tabs")` 只看一块
- 脚本报错会在 logcat 打出 `LemurX eval error: <文件名>:<行>: <原因>`，不会影响其他脚本
- 改完脚本重启浏览器；教程面板里的「还原」可以把外壳恢复到默认

---

## 2. 必须知道的约定

### 2.1 线程模型

Lua 跑在浏览器进程一条独立线程上，**单线程、不可重入**。绝大多数 `lemurx.*` 调用是同步的：需要 UI 线程的操作会阻塞等待 UI 线程做完再返回。

后果：

- 不要写死循环、`os.time()` 忙等；用 `lemurx.timer.after / every`
- 同步 `lemurx.http.fetch` 最多阻塞 20s（UGC）/120s（本地），期间**所有**脚本的回调排队（UI 线程不受影响，浏览器本身不卡）
- 回调（事件、点击、定时器）都在 Lua 线程执行，不用担心并发

**阻塞与异步的最佳实践**：只要脚本会等网络，就别用同步 fetch。

```lua
-- 方式一：回调。fetch 立刻返回请求 id，网络在 Java 线程池跑，结果回投 Lua 线程
lemurx.http.fetch(url, { timeout = 8000 }, function(r)
    if r.ok then lemurx.toast(#r.body) end
end)

-- 方式二：协程。await 让出线程，写起来像同步
lemurx.async(function()
    local a = lemurx.await(lemurx.http.fetch, "https://a.example/api")
    local b = lemurx.await(lemurx.http.fetch, "https://b.example/api", { method = "POST", body = "{}" })
    lemurx.sleep(300)                          -- 同样不卡线程
    lemurx.ui.update("fx", { text = a.status .. "/" .. b.status })
end)
```

`lemurx.await(fn, ...)` 对任何"最后一个参数收回调"的函数都有效（`http.fetch`、`timer.after`、你自己写的）。
`await` 只能在 `lemurx.async` 里调，在外面会 error。协程里出错会打到 `lemurx.log`，不会吞掉。
`tabs.eval / cdp.send / ui.*` 仍是同步的——它们等的是 UI/渲染进程，通常几十毫秒，没必要异步。

### 2.2 返回值约定

- 查询类返回 table：成功 `{ok=true, ...}`，失败 `{ok=false, error="原因"}`
- 动作类返回 boolean
- 找不到目标一般返回 `nil`，不抛异常
- 参数类型错误、UGC 调用特权接口会 **抛 Lua error**，用 `pcall` 兜底：

```lua
local ok, err = pcall(lemurx.cookie.get, "https://example.com")
if not ok then lemurx.log("没权限:", err) end
```

### 2.3 本地特权 vs UGC：两个信任域、两套 Lua 状态

LemurX的原则是**最大自由度**：你自己设备上的脚本，LemurX不设限；限制只落在"别人写的、你装进来的" UGC 上。

| | 本地 `files/lua/*.lua` | UGC `files/lua/ugc/*.lua` |
|---|---|---|
| Lua 状态 | 所有本地脚本共享一个主 `lua_State` | **每个文件一个独立 `lua_State`** |
| 标准库 | 全开：`io` `os` `debug` `package` `require` `dofile` `loadfile` `load`（含字节码） | `base` `table` `string` `math` `utf8` `coroutine`；`load` 只收文本，无 `dofile/loadfile` |
| `lemurx.*` | 全部 | 见下方禁用列表 |
| `require` | `package.path` 已指向 `files/lua/?.lua` `files/lua/?/init.lua` `files/lua/lib/?.lua` | 无 |
| `lemurx.isPrivileged()` | `true` | `false` |
| `lemurx.scriptName()` | `""` | `"ugc/xxx.lua"` |

UGC 脚本禁止：`lemurx.cookie` `lemurx.history` `lemurx.downloads` `lemurx.perm` `lemurx.cdp` `lemurx.v8` `lemurx.dom` `lemurx.ax` `lemurx.blink` `lemurx.prefs` `lemurx.data` `lemurx.page` `lemurx.network` `lemurx.emulation` `lemurx.overlay` `lemurx.css`、`net.addRule` 的 `requestBody/replaceRequestBody`、`intent` 的任意 action、`fs` 跳出 `lua/ugc`、`http.fetch` 超过 2MB/20s。

**为什么 UGC 要独立状态**：如果共享，UGC 只需 `lemurx.tabs.eval = function(...) lemurx.cookie.get(...) end` 覆盖一个全局函数，等本地脚本的回调调到它，就在本地特权下跑了 UGC 的代码（confused deputy）。独立状态之后 UGC 连本地脚本的 `lemurx` 表都摸不到，这一类攻击整体消失，**本地脚本因此不需要做任何防御，想怎么写就怎么写**。

**为什么 UGC 的 `load` 只收文本**：Lua VM 不校验字节码，一段精心构造的字节码就能在浏览器进程里读写任意内存。文本 `load` 照常可用，动态拼字符串跑代码没问题——它跑出来的仍然是这个 UGC 状态里的、没有特权的代码，静态扫描 `lemurx.ugc.scan` 绕过了也没用，因为特权接口在运行时是按状态判的，不看源码。

**本地 ↔ UGC 桥**：本地脚本想给 UGC 提供能力（比如封装好的登录态请求），显式暴露：

```lua
-- 本地 files/lua/10_bridge.lua
lemurx.expose("shop", {
    price = function(sku)                      -- 以本地特权执行
        local r = lemurx.http.fetch("https://shop.example/api/" .. sku)
        return r.ok and r.body or nil
    end,
    site = "shop.example",                     -- 非函数成员按值拷贝
})

-- UGC files/lua/ugc/widget.lua
local shop = lemurx.import("shop")             -- 没暴露返回 nil
if shop then lemurx.toast(shop.price("A1")) end
```

参数和返回值按 JSON 编组（nil/bool/number/string/table），**函数不能跨界传递**；被暴露的函数出错会在 UGC 侧变成 Lua error。暴露什么、暴露多少，是本地脚本作者的决定。`lemurx.exposed()` 列出当前所有名字。

上架前用 `lemurx.ugc.scan(src)` 扫一下源码，返回命中的禁用项——这只是给作者的提示，真正的边界在运行时。

### 2.4 会不会把页面搞崩

Lua 在浏览器进程，**不会**因为脚本错误崩掉页面。历史上唯一的崩页原因（向子 frame 用主世界注入）已修复。
放心用 `tabs.inject`，默认隔离世界即可；只有确实需要访问页面自己的 JS 变量时才 `world="main"`。

---

## 3. 事件与回调

```lua
-- 标签生命周期
lemurx.tabs.on("started"|"loaded"|"document"|"created"|"closed"|"selected", function(ev)
    -- ev = {id, url, title, incognito, loading, canGoBack, canGoForward, muted, event, eventUrl}
end)

-- 外壳事件
lemurx.chrome.on("omnibox", function(ev) end)  -- {focus=true|false, text}
lemurx.chrome.on("back", function(ev) end)     -- {id, url, title}
lemurx.chrome.on("menu", function(ev) end)     -- {id, title, lua}
lemurx.chrome.on("app", function(ev) end)      -- {state="foreground"|"background"}：整个应用切前后台（时间追踪、暂停轮询用）

-- 返回键接管：onBack 只登记回调，不改浏览器行为；
-- 只有显式 interceptBack(true) 之后返回键才归 Lua（想放行就 lemurx.chrome.back()）。
-- 不拦截时 Chrome 的预测返回手势、动画一切照旧。
lemurx.input.onBack(function(ev)
    lemurx.chrome.back()
end)
lemurx.input.interceptBack(true)   -- 开始接管
lemurx.input.interceptBack(false)  -- 交还系统

-- 定时器（毫秒，最小 10）
local id = lemurx.timer.every(60000, function() ... end)
lemurx.timer.after(2000, function() ... end)
lemurx.timer.cancel(id)
```

事件名 `started` = 开始导航，`document` = DOM 可用（注入 JS 的最佳时机），`loaded` = 加载完成。

---

## 4. API 参考

### 4.1 基础 `lemurx`

| 接口 | 说明 |
|---|---|
| `lemurx.version` | 运行时版本字符串 |
| `lemurx.log(...)` / `print(...)` | 写 logcat（tag `LemurX`） |
| `lemurx.toast(msg)` | 系统 Toast |
| `lemurx.help([module])` | 打印接口清单 |
| `lemurx.isPrivileged()` | 是否本地特权脚本 |
| `lemurx.open(url[, opts])` | `tabs.open` 别名 |
| `lemurx.browser.info()` | `{isLemurX, versionName, versionCode}` |

### 4.2 标签与网页 `lemurx.tabs`

```lua
lemurx.tabs.list()                 -- [{id,url,title,incognito,loading,canGoBack,canGoForward,muted,
                                   --   active,hidden,frozen,native,lastActive(ms),parentId}]
lemurx.tabs.current()              -- 同上单个，或 nil
lemurx.tabs.open(url[, {background=true, hidden=true, incognito=true}])  -- 返回 tabId
lemurx.tabs.close(id) / select(id) / hide(id) / show(id) / freeze(id)
lemurx.tabs.discard(id)            -- 丢弃渲染进程释放内存，标签留着，再切回来自动重载（Great Suspender）
lemurx.tabs.navigate(id, url) / reload(id) / reloadBypassCache(id) / stop(id)
lemurx.tabs.back(id) / forward(id) / go(id, index) / offset(id, n)
lemurx.tabs.history(id)            -- {ok, current, entries=[{index,url,virtualUrl,title,transition,timestamp}], canGoBack, canGoForward}
lemurx.tabs.frames(id)             -- RenderFrameHost 树 {main={...}, frames=[...]}
```

**执行 JS**

```lua
-- 主世界执行，返回 JSON 可序列化的结果（字符串/数字/表）
local title = lemurx.tabs.eval(id, "document.title")

-- 注入脚本：默认隔离世界、仅主 frame
lemurx.tabs.inject(id, js, { world = "isolated"|"main", frames = "main"|"all" })

-- 当前文档 outerHTML
local html = lemurx.tabs.html(id)
```

`eval` 和 `inject` 的区别：`eval` 拿返回值、在主世界；`inject` 用来"种"脚本，隔离世界不受页面 CSP 和页面 JS 干扰，`frames="all"` 能打进 iframe（登录框、广告框）。

**页面表现**

```lua
lemurx.tabs.setDesktop(id, true)      -- 桌面版 UA + 视口
lemurx.tabs.setZoom(id, 150) / getZoom(id)   -- 百分比
lemurx.tabs.mute(id, true) / isMuted(id)
lemurx.tabs.setUserAgent(id, ua[, { platform = "Windows", mobile = false, reload = true }])
    -- 原生 WebContents UA 覆盖：请求头 + navigator.userAgent + Client Hints（Sec-CH-UA-Platform / Mobile）；
    -- 传 nil/"" 还原；不会被 Chrome 的"桌面版站点"逻辑覆盖掉
lemurx.chrome.userAgent()             -- 浏览器默认 UA 字符串（拼自定义 UA 用）
lemurx.tabs.setHeaders(id, { ["X-Foo"] = "bar" })
lemurx.tabs.setJavaScript(false) / isJavaScript()   -- 全局开关
lemurx.tabs.screenshot(id[, {base64=true}])  -- {ok, path, abs, width, height[, data]}，JPEG 存 lua/captures
```

### 4.3 网络规则 `lemurx.net`

```lua
local ruleId = lemurx.net.addRule({
    match = "*://*.doubleclick.net/*",     -- 通配符 URL、host 片段，或 "<all_urls>"
    action = "block" | "redirect" | "modify",
    types = { "document", "sub_frame", "script", "image", "xmlhttprequest", "media" },
    redirectUrl = "https://...",
    requestHeaders = { ["X-Foo"] = "bar" },      removeRequestHeaders = { "Cookie" },
    responseHeaders = { ["X-Bar"] = "baz" },     removeResponseHeaders = { "Content-Security-Policy" },
    replaceBody = { ["old"] = "new" },   -- 或 { {from="old", to="new"} }，仅文本类响应，≤2MB
    requestBody = "...",                 -- 本地特权：整段替换 POST 体
    replaceRequestBody = { a = "b" },    -- 本地特权
})
lemurx.net.removeRule(ruleId) / clearRules() / listRules()
```

> 当前限制：规则对**导航请求**（document / sub_frame）和浏览器发起的请求完全生效；渲染进程发起的子资源（页面里的 img/script/xhr）暂时拦不到，正在补。去广告先用 `tabs.inject` 注入 CSS 隐藏，或拦主文档 `replaceBody`。

### 4.4 HTTP 直连 `lemurx.http`

```lua
local r = lemurx.http.fetch(url, {
    method = "POST", headers = { ["Content-Type"] = "application/json" },
    body = '{"a":1}', timeout = 10000, redirect = true, binary = false,
})
-- r = {ok, status, headers, url, bytes, body[, base64=true]}
lemurx.http.save(url, "downloads/a.png")   -- fetch 后写 fs

-- 异步：最后一个参数给函数，立刻返回请求 id，不阻塞 Lua 线程
local id = lemurx.http.fetch(url, nil, function(r) ... end)
-- 协程写法见 §2.1
lemurx.async(function()
    local r = lemurx.await(lemurx.http.fetch, url, { timeout = 8000 })
end)
```

浏览器进程发起，没有 CORS。UGC 限 http(s)、2MB、20s；本地 8MB、120s。会等网络的地方优先用异步。

### 4.5 文件 `lemurx.fs`

```lua
lemurx.fs.root()                            -- 本地: files/lua ；UGC: files/lua/ugc
lemurx.fs.read(path[, {base64=true}])       -- {ok, path, size, data}
lemurx.fs.write(path, data[, {append=true, base64=true}])
lemurx.fs.list(path)                        -- [{name, size, dir, mtime}]
lemurx.fs.exists(path) / mkdir(path) / remove(path)
```

路径相对 root，`..` 跳出会被拒。

### 4.6 存储 / 剪贴板 / 数据库

```lua
lemurx.storage.get(key[, default]) / set(key, value) / delete(key) / list()   -- 字符串 KV
lemurx.clipboard.get() / set(text)
lemurx.db.exec(sql[, {bind...}])    -- {ok, rowsAffected}  文件 files/lua/lemurx.db
lemurx.db.query(sql[, {bind...}])   -- {ok, rows=[{col=value}]}
```

### 4.7 浏览器外壳 `lemurx.chrome`

```lua
lemurx.chrome.controls("shown"|"hidden"|"both")   -- 顶栏/底栏随滚动的策略
lemurx.chrome.hideUrlBar(true)
lemurx.chrome.setUrlBarText(text) / getUrlBarText() / focusUrlBar(true)
lemurx.chrome.setToolbarColor("#FF212121") / setStatusBarColor("#FF000000")
lemurx.chrome.setDarkMode(true) / isDarkMode()
lemurx.chrome.fullscreen(true)
lemurx.chrome.setPullRefresh(false)               -- 关闭下拉刷新
lemurx.chrome.setMenuButtonVisible(false)
lemurx.chrome.hideBottomToolbar(true)             -- 藏地址栏左右两侧按钮组
lemurx.chrome.hideButton("back"|"forward"|"home"|"tabs"|"tools"|"menu"|"search", true)
lemurx.chrome.back() / forward()
lemurx.chrome.info()                              -- {controls, darkMode, urlBarText, urlBarFocused}

-- 网页内容强制暗色（Chromium 自带 Force Dark，C++ 直控，不是 CSS 滤镜）
lemurx.chrome.setForceDark({ enabled = true, exclude = { "*.github.com", "example.com" } })
lemurx.chrome.setForceDark(nil)                   -- 交还给 Chrome 设置
lemurx.chrome.getForceDark()                      -- {active, enabled, exclude}

-- 新标签页整页替换：之后每个新标签都打开这个地址（一般是脚本自己的 lemurx:// 页面）
lemurx.chrome.setNewTabUrl("lemurx://newtab/page") / setNewTabUrl(nil) / getNewTabUrl()
```

### 4.8 底栏菜单 `lemurx.menu`

```lua
lemurx.menu.add({ id = "grab", title = "抓取", page = "main"|"expand"|"second",
                 onClick = function(ev) end })
lemurx.menu.remove(id) / clear()
lemurx.menu.hide("history"[, true])   -- 隐藏内置项
lemurx.menu.on("history", function(ev) end)  -- 截获内置项，不再走默认逻辑
lemurx.menu.invoke("history")         -- 手动触发内置逻辑
```

### 4.9 皮肤 `lemurx.theme`（持久化，自动重放）

```lua
lemurx.theme.set({
    toolbar = "#FF1B5E20", statusBar = "#FF0D3B13", navBar = "#FF1B5E20", bottomBar = "#FF1B5E20",
    iconTint = "#FFE8F5E9",       -- 顶栏/底栏图标颜色
    accent = "#FF00C853",         -- ui.render 里 button 的默认底色
    dark = true,                  -- 暗色模式（切换会触发应用重启标记）
    hideButtons = { "tools" },
    urlBarHidden = false, bottomBarHidden = false, menuButton = true, pullRefresh = false,
})
lemurx.theme.get()                 -- {ok, theme, dark}
lemurx.theme.reset()               -- 清掉并尽力还原
lemurx.theme.apply("forest"|"paper"|"midnight"|"sakura"[, extra])   -- 预设 + 覆盖
lemurx.theme.presets.<name>        -- 预设表，可自己加
```

`set` 是合并语义：只传要改的键；传 `false`/空串不会删除，删除某项用 `lemurx.theme.set({ key = nil })` 无效（Lua 表里 nil 不存在），请 `reset()` 后重设。
每次进入浏览器（含 Activity 重建）自动重放。

### 4.10 挂载点与声明式控件树 `lemurx.ui.render`

**挂载点**（`lemurx.ui.slots()` 可列出）：

| slot | 位置 |
|---|---|
| `toolbar.start` / `toolbar.end` | 顶栏左/右按钮条（与原按钮等权分宽） |
| `bottom.start` / `bottom.bar` | 底栏第一格 / 末尾 |
| `page.top` / `page.bottom` | 网页正上方 / 正下方通栏 |
| `page.center` / `page.float` | 居中 / 浮动（`gravity` `x` `y`） |
| `view:<原生 id>` | 任意 `ui.dump` 里看到的 ViewGroup |

```lua
local h = lemurx.ui.h   -- h(type, props, children)

lemurx.ui.render("page.top", h("row", { background = "#E61B5E20", padding = {12, 6} }, {
    h("text",   { id = "st", text = "状态", color = "#FFFFFFFF", weight = 1, maxLines = 1, ellipsize = true }),
    h("switch", { id = "sw", text = "桌面版", color = "#FFFFFFFF",
                  onChange = function(ev) lemurx.tabs.setDesktop(lemurx.tabs.current().id, ev.checked) end }),
    h("button", { id = "close", text = "收起", onClick = function() lemurx.ui.unmount("page.top") end }),
}))

lemurx.ui.update("st", { text = "新文字" })  -- 改已挂载节点
lemurx.ui.unmount("page.top" | "st" | "*")
```

同一 slot 再次 `render` 即替换。Activity 重建、底栏样式切换后自动重挂。

**节点类型**：`row` `column` `stack` `scroll` `hscroll` `text` `button` `icon` `image` `edit` `switch` `progress` `divider` `spacer`

**通用属性**

| 属性 | 取值 |
|---|---|
| `id` | 字符串，回调与 `ui.find/update` 用 |
| `width` / `height` | `"match"` `"wrap"` 数字(dp) |
| `weight` `gravity` `align` | 线性布局权重 / 自身在父容器位置 / 内容对齐（`"center"` `"end\|bottom"`…） |
| `padding` / `margin` | `8` / `{h, v}` / `{l, t, r, b}` / `{left=8}`，或 `paddingH` `paddingV` `marginH` `marginV` |
| `background` | `"#AARRGGBB"` / `"drawable:名"` / `{color, radius, stroke, strokeColor, gradient={...}, orientation, ripple=true}` |
| `radius` `visible` `enabled` `alpha` `elevation` `rotation` `desc` `tooltip` `minWidth` `minHeight` | |
| `onClick` `onLongClick` `onChange` | Lua 函数 |

**文本类**（text/button/edit/switch）：`text` `color` `size`(sp) `bold` `italic` `font`(`"monospace"` `"serif"` `"file:x.ttf"`) `maxLines` `ellipsize` `singleLine` `underline` `strike` `lineSpacing`

**图标/图片**：`icon`（`"ic_xxx"` apk 内 drawable、`"android:ic_menu_search"`、`"file:icons/a.png"`、`"data:image/png;base64,..."`），没图时 `text` 兜底（emoji 很好用）；`tint` `scale`(`fit` `crop` `center` `fill`)

**输入**：`hint` `value` `inputType`(`text` `number` `url` `password` `multiline`) `maxLength` `hintColor`；`onChange` 每次输入触发 `ev.action="change"`，键盘确认 `ev.action="submit"`，`ev.text` 是当前内容

**开关**：`checked`，`onChange` 收 `ev.checked`；**进度**：`value` `max` `indeterminate` `color`

回调参数统一：`ev = {id, action = "click"|"longclick"|"change"|"submit", slot, text?, checked?}`

### 4.11 样式手术 `lemurx.ui.style`

对浏览器**任何现有 View** 改属性，属性同 4.10，Activity 重建后自动重放。

```lua
lemurx.ui.style({ id = "url_bar" }, { size = 16, bold = true })
lemurx.ui.style({ id = "home_button" }, { visible = false })
lemurx.ui.style({ class = "TabSwitcherButtonView" }, { tint = "#FFFF5252" })
lemurx.ui.style({ text = "Lua教程" }, { background = "#FF000000", once = true })  -- once：不重放
```

查询条件：`id`（资源名或 Lua id）、`text`（包含）、`desc`、`class`（类名包含）、`clickable`、`max`。用 `lemurx.ui.dump()` 或 `lemurx.ui.shell()` 找 id。

### 4.11b 拆原生外壳 `replace` / `insert` / `detach` / `move` / `on`

底栏、地址栏、工具抽屉、系统 Dialog 都是普通 Android View。Lua 可以直接换掉、插入、摘走、挪位置、接管点击。`dump`/`find` 会扫 `WindowManager` 里**所有窗口**（Activity + Dialog + PopupWindow），所以打开工具抽屉之后也能改里面的格子。

```lua
local h = lemurx.ui.h

-- 底栏主页按钮换成自己的
lemurx.ui.replace({ id = "home_button" }, h("button", {
    id = "my_home", text = "顶", background = "#FF00C853", color = "#FFFFFFFF",
    onClick = function()
        local t = lemurx.tabs.current()
        if t then lemurx.tabs.eval(t.id, "window.scrollTo({top:0,behavior:'smooth'})") end
    end,
}))

-- 地址栏右边插一颗按钮
lemurx.ui.insert({ id = "url_bar" }, h("button", { id = "tr", text = "译" }), { position = "after" })
-- position: after（默认） / before / into（塞进目标 ViewGroup）

-- 把扩展按钮从底栏摘走；restore() 或 unmount 对应 replace 时放回
lemurx.ui.detach({ id = "bt_extensions" })
lemurx.ui.restore()                         -- 全部放回；restore({id=...}) 只放这一条

-- 把设置按钮挪到返回按钮前面
lemurx.ui.move({ id = "menu_tools" }, { before = { id = "home_button" } })

-- 接管原生点击（consume=false 不吞掉原逻辑）
local r = lemurx.ui.on({ id = "menu_button_wrapper" }, "click", function(ev)
    lemurx.timer.after(400, function()
        -- 抽屉已经弹出来，现在它是另一个窗口，dump/find/style 都能碰到
        lemurx.ui.style({ text = "历史" }, { color = "#FFFF5252", once = true })
    end)
end, { consume = false })
lemurx.ui.off(r.key)   -- 或 off("*")

lemurx.ui.shell()      -- 已知外壳 id：底栏/地址栏/搜索框，带 found + 坐标
lemurx.ui.children({ id = "toolbar_buttons_left" })
lemurx.menu.list()     -- 抽屉 main/expand/second 当前有哪些项
```

`unmount("*")` 会把 `replace`/`insert` 的 Lua 树拆掉，被换下来的原生 View 放回原位。Activity 重建后这些手术会按 query 重放。

LemurX现行壳的按钮在 **地址栏左右两侧**（`ToolbarPhoneLemur`），不是独立的 Duet 底栏。

| 组 | id |
|---|---|
| 按钮组 | `toolbar` `toolbar_buttons_left` `toolbar_buttons` |
| 按钮 | `home_button` `tab_switcher_button` `tab_back_button` `tab_forward_button` `menu_tools` `menu_button_lemur` |
| 搜索框 | `location_bar` `url_bar` |
| 新标签页 | `search_box` `search_box_text` `ivLogo` `ntp_favorites` |

### 4.12 原生 UI 自动化 `lemurx.ui`（旧接口，继续可用）

```lua
lemurx.ui.show({ id, type = "button"|"text"|"edit", text, gravity = "end|bottom", x, y,
                background, color, textSize, radius, paddingH, paddingV, width, height, hint,
                onClick = fn })              -- 单个浮动控件，返回 id
lemurx.ui.remove(id) / clear()
lemurx.ui.dump([{maxDepth=12, maxNodes=800, gone=true}])  -- {ok, nodes, windows, roots=[树]}
lemurx.ui.find(query)                        -- {ok, count, nodes=[{class,id,text,desc,clickable,enabled,visible,x,y,w,h}]}
lemurx.ui.click(query) / longClick(query)
lemurx.ui.setText(query, text) / getText(query)
lemurx.ui.visible(query, false) / enabled(query, false)
lemurx.ui.dialog({ title, message, ok, cancel, onOk = fn, onCancel = fn })   -- ev = {id, action}
lemurx.ui.dialog({ title, items = { "1.0×", "1.5×", "2×" }, onSelect = function(ev) ev.index, ev.text end })  -- 单选列表，index 从 0 起
lemurx.ui.prompt({ title, hint, value, onOk = function(ev) ev.text end })
lemurx.ui.alert(title, message[, okText])
```

### 4.13 输入模拟 `lemurx.input`

```lua
lemurx.input.tap(x, y[, tabId[, "dp"|"px"]])   -- 或 tap({x=, y=, tab=, unit=})
lemurx.input.swipe(x1, y1, x2, y2[, durationMs[, tabId]])
lemurx.input.type(text[, tabId])               -- 写入当前焦点
lemurx.input.key("enter"|"back"|"tab"|"space"|"esc"|"delete"[, tabId])
```

坐标默认 dp、相对网页内容区。

### 4.14 系统 Intent

```lua
lemurx.intent.startActivity({ action = "android.intent.action.VIEW", url = "https://...",
                             package = "com.tencent.mm", extras = { k = "v" } })
lemurx.intent.sendBroadcast({ action = "lemurx.example", extras = {...} })
-- UGC 仅允许 VIEW / SEND / SENDTO / WEB_SEARCH / MAIN 与 lemurx.* action
```

### 4.14b 分享 / 相册 / 通知

```lua
-- 系统分享面板：文件走 FileProvider（path 相对 lua 目录），或直接分享一段文字
lemurx.share({ path = "captures/x.jpg", mime = "image/jpeg", title = "分享截图" })
lemurx.share({ text = "# 笔记\n...", mime = "text/plain", title = "标题", subject = "邮件主题" })

-- 存进系统相册 / 下载（MediaStore，Android 10+ 不要存储权限）
-- 图片 → Pictures/<album>，视频 → Movies/<album>，其它 → Download/<album>
lemurx.media.save({ path = "captures/x.jpg", name = "shot.jpg", mime = "image/jpeg", album = "LemurX" })
    -- {ok, uri, name, path}

-- 系统通知：点开跳 url；同 id 覆盖；ongoing=true 不可划掉
lemurx.notify.show({ id = 1, title = "专注", text = "还剩 10 分钟", url = "lemurx://focus/", ongoing = false })
lemurx.notify.cancel(1)
```

### 4.15 本地特权专区（UGC 不可用）

```lua
lemurx.cookie.get(url)                        -- [{name, value, domain, path, secure, httpOnly, expires}]
lemurx.cookie.set(url, "name=value; path=/; secure")
lemurx.history.query([text])                  -- [{url, title, timestamp, domain}]
lemurx.downloads.list() / enqueue(url[, tabId])
lemurx.perm.set(origin, type, "allow"|"block"|"ask")   -- type: geolocation camera microphone notifications javascript cookies images popups sound autoplay
lemurx.perm.get(origin, type)
lemurx.prefs.get(name[, "bool"|"int"|"double"|"string"|"long"])   -- {ok, name, exists, value, isDefault, managed}
lemurx.prefs.set(name, value) / set(name, type, value) / clear(name)
lemurx.data.clear({"history","cache","site_data"}[, "15m"|"hour"|"day"|"week"|"all"])
lemurx.features.enabled(name) / param(feature, key)   -- ChromeFeatureList 只读
```

### 4.16 CDP / V8 / DOM（本地特权）

整张 Chrome DevTools Protocol 直通，不需要 USB 调试。

```lua
lemurx.cdp.attach(tabId)                      -- 先 attach，Runtime/Page 自动 enable，其他域首次调用自动 enable
lemurx.cdp.send(tabId, "DOM.getDocument", { depth = 1 }[, timeoutMs | { timeout, sessionId }])
--  -> {ok, id, result=...} 或 {ok=false, error=...}
lemurx.cdp.on("Network.responseReceived" | "*", function(ev) end)
lemurx.cdp.detach(tabId) / inspect(tabId, x, y) / version()
lemurx.cdp.targets()                          -- 所有 target（page / iframe / worker / service_worker / browser）
lemurx.cdp.host(targetId | "browser", method, params)   -- 对任意 target 发消息

-- 便捷封装
lemurx.v8.eval(tab, "location.href")          -- 返回值, 原始响应
lemurx.v8.call(tab, "function(a){return a*2}", "[21]")
lemurx.dom.document(tab) / query(tab, "css选择器") / box(tab, "css选择器")
lemurx.ax.tree(tab)                           -- 无障碍树
lemurx.blink.frames(tab) / metrics(tab)
lemurx.page.navigate(tab, url) / reload(tab, ignoreCache) / stop(tab) / enablePaint(tab, false) / cookies(tab)
lemurx.page.screenshot(tab, opts)             -- 走 tabs.screenshot
lemurx.network.enable(tab) / setOffline(tab, true) / emulate(tab, {latency, download, upload}) / clear(tab) / headers(tab, {...})
lemurx.emulation.setUA(tab, ua) / setDevice(tab, {width, height, scale, mobile}) / setGeo(tab, lat, lng) / setTimezone(tab, id) / setTouch(tab, true)
lemurx.overlay.inspect(tab[, mode]) / hide(tab)
lemurx.css.getComputed(tab, nodeId)
lemurx.sw.targets() / send(target, method, params)   -- Service Worker
```

### 4.17 AI Agent 自动化底座 `lemurx.agent` / `lemurx.system`

一个 GUI Agent 的循环是「观察 → 决策 → 执行」。决策是模型的事（脚本自己接 LLM，`lemurx.http.fetch` 就够），另外两步 LemurX 做成了三层能力，**默认都是关的，不调用就等于不存在**：

| 层 | 范围 | 观察 | 定位 | 执行 | 权限 |
|---|---|---|---|---|---|
| 网页 | 当前标签的页面 | `tabs.screenshot` | `agent.mark`（set-of-marks）、CDP | `input.tap/type`、JS | 无 |
| 浏览器外壳 | 工具栏 / 菜单 / 对话框 / Lua 控件 | `ui.screenshot` | `ui.dump/find`（带 `ref`） | `ui.click/setText/tap/swipe` | 无 |
| 其他 App | 整机 | `system.screenshot` | `system.tree/find` | `system.click/tap/swipe/global` | 用户在系统设置里开一次无障碍 |

三层坐标统一为 **屏幕像素**（`getLocationOnScreen` / `boundsInScreen`），网页元素由 `lemurx.tabs.viewport()` 给出的映射换算过来。

**一次观察 + 一次执行**

```lua
local obs = lemurx.agent.observe()      -- {mode="browser"|"system", tab, viewport, screenshot, elements=[...]}
print(lemurx.agent.describe(obs))       -- 给模型看的文本：
-- PAGE 商品页 — https://…
-- SCREENSHOT /data/…/captures/window_….jpg (540x1200, scale 0.50)
-- [w1] link "首页" @(24,180 96x40)
-- [w7] textbox "搜索" (editable) @(120,260 700x56)
-- [n12] ImageButton "菜单" #menu_button (clickable) @(980,90 96x96)

lemurx.agent.act({ type = "tap",  ref = "w7" })                 -- 按 ref 点
lemurx.agent.act({ type = "type", ref = "w7", text = "耳机", enter = true })
lemurx.agent.act({ type = "swipe", dir = "down" })              -- 看下面的内容
lemurx.agent.act({ type = "tap",  x = 540, y = 1600 })          -- 也可以直接给屏幕坐标
lemurx.agent.act("back")
```

`ref` 前缀：`w` 网页元素（`data-lemurx-mark`）、`n` 原生控件（`ui.dump` 的 `ref`）、`s` 其他 App 的节点（`system.tree` 的 `ref`）。`act` 会按来源选择最可靠的执行方式：网页元素 → 相对网页 View 的 `input.tap`（`via="js"` 改为 `el.click()`）；原生控件 → `performClick`，句柄失效退回坐标；系统节点 → `performAction(ACTION_CLICK)`，再退回手势。

**动作表** `act{type=...}`：`tap|click{ref|x,y,long,dx,dy,via}`、`longPress`、`type|text{text,ref,clear,enter,append}`、`swipe|scroll{dir,ref,distance,duration|x1,y1,x2,y2}`、`key{key}`、`back`、`home`、`recents`、`notifications`、`navigate{url}`、`open{url}`、`eval{js}`、`launch{package|url}`、`wait{ms}`、`screenshot`、`done{result}`。

**observe 选项**：`{tab, screenshot=true, scale=0.5, quality=60, base64=false, web=true, maxWeb=120, text=false, native=true, maxNative=200, system=nil|true|false}`。`system=nil` 表示自动：无障碍服务已连接且前台不是 LemurX 时改看整机 UI 树（`mode="system"`）。

**循环**（在协程里跑，动作之间会等 `settle` 毫秒让界面稳定）：

```lua
lemurx.agent.run(function(obs, text, step)
    local reply = lemurx.http.fetch("https://my-llm/plan", { method = "POST", body = text })
    return decodeActionFrom(reply.body)      -- 返回 action 表；返回 nil 或 {type="done"} 结束
end, { maxSteps = 20, settle = 600,
       onStep = function(step, obs, action, result) lemurx.log(step, action.type, result.ok) end,
       onDone = function(out) lemurx.log("done", out.steps, out.result) end })   -- 在协程外调用时结果走 onDone
```

其他：`agent.mark(tab, {max, text})` 单独打标；`agent.find(obs, "文本" | {role="button"})`；`agent.look()` 观察并记住，之后 `act` 不传 obs；`agent.wait(ms)`。

**新增的底层接口**

```lua
lemurx.ui.tap(x, y[, "dp"]) / longPress(x, y) / swipe(x1, y1, x2, y2[, ms])   -- 屏幕坐标，落到最上层窗口
lemurx.ui.screenshot({scale=, quality=, base64=})                            -- 整个浏览器窗口 JPEG
lemurx.ui.dump / find  -- 每个节点多了 ref；lemurx.ui.click({ref=12}) 精确指回
lemurx.tabs.viewport([tab])  -- {x,y,w,h, contentOffsetY, pageScale, density, viewportWidth/Height, scrollX/Y}
```

**跨 App：`lemurx.system.*`（本地特权）**

底层是一个 `AccessibilityService`，等价于 adb 的 `uiautomator dump` + `input tap` + `screencap`，但不需要 shell 权限：

```lua
lemurx.system.status()            -- {connected, enabledInSettings, canScreenshot, component}
lemurx.system.openSettings()      -- 带用户去「设置 › 无障碍」开启「LemurX 自动化」；LemurX 不会自己开
lemurx.system.ensure()            -- 没开就跳设置页，返回是否就绪
lemurx.system.foreground()        -- {package, class, title, self}
lemurx.system.windows()
lemurx.system.tree({flat=true, interactiveOnly=true, all=false, maxNodes=1500})   -- 节点 {ref,class,id,text,desc,clickable,editable,...,x,y,w,h}
lemurx.system.find("文本") / find({id="send", clickable=true, package="com.tencent.mm"})
lemurx.system.click(ref | {text="发送"}) / longClick / focus / scroll(ref, "down")
lemurx.system.setText(ref | {editable=true}, "文本")   -- ACTION_SET_TEXT，退路剪贴板粘贴
lemurx.system.tap(x, y) / longPress(x, y) / swipe(x1, y1, x2, y2[, ms])   -- dispatchGesture，屏幕像素
lemurx.system.global("back"|"home"|"recents"|"notifications"|"quickSettings"|"lock"|"power")
lemurx.system.screenshot({scale=0.5})   -- API 30+，整屏
lemurx.system.launch("com.tencent.mm") / launch("https://…") / launch({action=, data=, extras=})
lemurx.system.on("window"|"content"|"click"|"focus"|"text"|"notification"|"connected"|"disconnected", fn)
```

用户始终赢：服务默认关闭；开着时任何时候都能在系统设置里关掉；Lua 总开关关掉后 `lemurx.system` 不再接受调用、不再派发事件；UGC 脚本完全拿不到它。

---

## 5. 实战食谱

### 5.1 站点自动换肤

```lua
local skins = { ["bilibili.com"] = "sakura", ["github.com"] = "midnight", ["zhihu.com"] = "paper" }
lemurx.tabs.on("selected", function(ev)
    for host, name in pairs(skins) do
        if ev.url and ev.url:find(host, 1, true) then return lemurx.theme.apply(name) end
    end
    lemurx.theme.apply("forest")
end)
```

### 5.2 顶栏加「翻译」按钮

```lua
lemurx.ui.render("toolbar.end", lemurx.ui.h("icon", {
    id = "tr", text = "译", bold = true, desc = "翻译当前页",
    onClick = function()
        local t = lemurx.tabs.current()
        if t then lemurx.tabs.open("https://translate.google.com/translate?sl=auto&tl=zh-CN&u=" .. t.url) end
    end,
}))
```

### 5.3 每站记忆：自动桌面版 / 缩放 / 静音

```lua
local function host_of(url) return (url or ""):match("^%a+://([^/]+)") or "" end
lemurx.tabs.on("document", function(ev)
    local cfg = lemurx.storage.get("site:" .. host_of(ev.url))
    if not cfg then return end
    if cfg:find("desktop") then lemurx.tabs.setDesktop(ev.id, true) end
    if cfg:find("zoom150") then lemurx.tabs.setZoom(ev.id, 150) end
    if cfg:find("mute") then lemurx.tabs.mute(ev.id, true) end
end)
-- 记录：lemurx.storage.set("site:example.com", "desktop,zoom150")
```

### 5.4 阅读模式开关

```lua
local reading = false
local function set_reading(on)
    reading = on
    lemurx.chrome.controls(on and "hidden" or "both")
    lemurx.chrome.setPullRefresh(not on)
    local t = lemurx.tabs.current()
    if t then
        lemurx.tabs.inject(t.id, on and [[
            (function(){var s=document.createElement('style');s.id='lemurx-read';
            s.textContent='header,nav,aside,[class*=banner],[class*=fixed]{display:none!important}';
            document.head.appendChild(s)})()
        ]] or "document.getElementById('lemurx-read')?.remove()")
    end
end
lemurx.ui.render("page.float", lemurx.ui.h("button", { id = "rd", text = "阅读", onClick = function() set_reading(not reading) end }))
```

### 5.5 防手滑返回

```lua
local armed = false
lemurx.input.onBack(function()
    if armed then armed = false; lemurx.input.interceptBack(false); lemurx.chrome.back(); return end
    armed = true
    lemurx.toast("再按一次返回")
    lemurx.timer.after(2000, function() armed = false end)
end)
-- 只在需要保护的页面上开（比如表单页 loaded 时），别在脚本加载时就全局开
lemurx.tabs.on("loaded", function(ev)
    if ev.url:find("/checkout") then lemurx.input.interceptBack(true) end
end)
```

### 5.6 去掉跳转中间页

```lua
lemurx.net.addRule({
    match = "*://link.zhihu.com/*", action = "redirect",
    redirectUrl = "https://example.com",   -- 实际可在 tabs.on("started") 里解析 target 参数后 navigate
    types = { "document" },
})
```

### 5.7 跨域抓数据做浮窗

```lua
local h = lemurx.ui.h
lemurx.timer.every(60000, function()
    -- 异步 fetch：网络慢也不会卡住别的脚本
    lemurx.http.fetch("https://api.exchangerate.host/latest?base=USD&symbols=CNY", nil, function(r)
        if r.ok then
            local rate = r.body:match('"CNY":([%d%.]+)')
            lemurx.ui.render("page.float", h("text", { id = "fx", text = "USD/CNY " .. rate,
                background = "#CC000000", color = "#FFFFFFFF", padding = {10, 6}, radius = 12, y = 120 }))
        end
    end)
end)
```

### 5.8 用 CDP 读 JS 里的现价（本地）

```lua
local t = lemurx.tabs.current()
lemurx.cdp.attach(t.id)
local price = lemurx.v8.eval(t.id, "window.__INITIAL_STATE__?.price?.current")
lemurx.log("现价", price)
```

---

## 6. 坑与限制

- **子资源拦截**：`net.addRule` 只对导航和浏览器发起的请求生效。页面内 img/script/xhr 的同步拦截在**渲染进程**里：
  把标签包成 luakit `webview` 后，渲染进程 Lua 的 `page` 会收到 `send-request(uri, headers, info)`，
  返回新 uri / `false` 阻断 / 改 headers，第三个返回值 `{credentials="omit"}` 可去掉 cookie。
  官方框架 `lx.web.on_request` 已经封装好了（见第 8 章）。
- **`tabs.hide` 当前标签**会让屏幕空白，先 `select` 别的标签再 hide。
- **`theme.set({dark=...})`** 会走LemurX自己的暗色切换，带应用重启标记；频繁切换体验差。
- **`toolbar.*` 挂载点**宽度固定 150dp，塞太多按钮会挤；一般放 1 个。
- **`page.top/bottom`** 是 PopupWindow，横竖屏切换时会重建；`ui.update` 改文字比重新 `render` 便宜。
- **UI 调用同步阻塞**：一次回调里别连续做几十次 `ui.find`，先 `dump` 一次自己在 Lua 里筛。
- **`eval` 只能返回可 JSON 序列化的值**，DOM 节点会变成 `nil`。
- **文件名排序决定加载顺序**，依赖别的脚本定义的全局要注意前缀数字；更稳的做法是拆成模块放 `files/lua/lib/`，用 `require`。
- **本地脚本全开 `os`/`io`**：`os.exit()` 会直接杀掉浏览器进程，`io.open` 能读写应用私有目录任何文件——这是刻意给的自由，别在教程里教新手乱用。
- **UGC 之间也互相隔离**：两个 UGC 脚本不共享全局，想通信走 `lemurx.storage` 或让本地脚本 `expose` 一个中转。
- **跨界桥只传数据**：`lemurx.import` 拿到的函数不能收 Lua 函数作参数（回调传不过去）；需要回调就让本地侧写 `lemurx.storage`，UGC 侧轮询或监听事件。

---

## 7. UGC 发布规范

1. 放在 `files/lua/ugc/`，运行时自动降权并跑在独立 Lua 状态里，不用自己判断
2. 上架前 `lemurx.ugc.scan(源码)` 返回空表；`load(字符串)` 可以用但只收文本，`dofile/loadfile/require/io/os` 在 UGC 里不存在，别依赖
3. 会等网络的地方用 `http.fetch(url, opts, cb)` 或 `lemurx.async/await`，同步 fetch 会让所有脚本一起排队
4. 需要本地能力（登录态、cookie）时，文档里写清要求用户装哪个本地桥脚本，用 `lemurx.import("name")` 取，取不到要能降级
5. 不要隐藏地址栏做"钓鱼"式全屏，审核会拒
6. 所有对外请求走 `lemurx.http.fetch`，域名写在文件头注释里
7. 提供 `reset`：脚本卸载时应能 `theme.reset()` + `ui.unmount("*")` + `net.clearRules()` 还原
8. 版本号写在首行注释，例如 `-- myskin v1.0.0`，方便用户更新覆盖

---

## 8. 官方脚本与 `lx` 框架

`files/lua/official/` 里的脚本就是"用 Lua 重写的 Chrome 热门扩展"。它们全部建在一个很薄的
框架 `lx` 上（`official/lx/`），本地脚本也可以 `require("lx")` 直接用同一套东西。
覆盖表、能力清单见 `chrome/lemurx/lua/official/CATALOG.md`。

### 8.1 一个官方脚本长什么样

```lua
-- @name 我的脚本            ← 文件头注释 = 管理界面里显示的元数据
-- @description 一句话
-- @version 1.0.0
-- @icon 🧩
-- @category 效率
-- @page lemurx://myscript/
-- @replaces 某个 Chrome 扩展
local lx = require("lx")
local S = lx.register({
    id = "myscript", name = "我的脚本", version = "1.0.0", icon = "🧩",
    settings = { enabled = true, sites_off = {} },                  -- 默认值，自动持久化
    schema = {                                                        -- 设置页表单，自动渲染
        { key = "enabled", type = "bool", label = "启用", section = "总开关" },
        { key = "sites_off", type = "list", label = "例外站点" },     -- bool/number/string/url/text/list/select/action
        { key = "clear", type = "action", label = "清空", api = "clear", style = "danger", confirm = "确定？" },
    },
    menu = { { id = "open", title = "我的脚本" } },                   -- 三点菜单项，默认打开 lemurx://myscript/
    api = {                                                           -- lemurx://myscript/api/<name>?a=<json>  或 POST JSON
        stats = function(args, ctx) return { n = 1 } end,             -- 返回 table → JSON；返回 nil, "err" → 400
        slow  = function(args, ctx) lx.after(100, function() ctx.reply({ ok = true }) end) return "async" end,
    },
})
S.page = function(ctx) return lx.html.page({ title = "我的脚本", body = "…" .. lx.html.settings(S) }) end
S.routes["/img"] = function(ctx) return bytes, "image/png", 200 end  -- 自定义路由；ctx.query / ctx.method / ctx.body
```

浏览器进程侧还有：

```lua
lx.on_navigation(function(view, uri, ev) return false | "new-url" | nil end, priority)  -- 主/子框架导航否决、改写
lx.on_load(function(view, status, uri) end)      -- load-status
lx.on_webview(function(view) end)                -- 所有已包装 + 之后新建的标签
lx.tabs.current() / list() / open(url) / inject(id, js, opts) / eval(id, js)
lx.fetch(url, opts, cb) / lx.fetch_sync(url, opts)    lx.after(ms, fn) / lx.every(ms, fn) / lx.cancel(id)
lx.data(id):read(name) / write(name, data) / read_json / write_json / exists / remove / path(name)  -- files/lua/official_data/<id>/
lx.toast(msg) / lx.notify(title, text, {url=...}) / lx.log(fmt, ...)
lx.html.page{ title, icon, body, js, back_url } / lx.html.settings(S) / lx.html.escape(s)
```

设置页 JS 里可用 `lx.api(name, args) → Promise`、`lx.toast`、`lx.q/qa`；`<button data-api="x" data-args='{}' data-confirm="…">`
自动调 API 并 toast 返回的 `message`，返回 `reload = true` 会刷新页面。参数超过 1.5 KB 自动改 POST。

### 8.2 渲染进程半边 `xxx/web.lua`

浏览器侧 `local ch = lx.web.require("myscript/web")` 会把 `official/myscript/web.lua` 装进**每个**渲染进程
（现有的和以后新建的），并返回 `ipc_channel("lx.myscript")`。渲染进程里：

```lua
local W = require("lx.web")
local ch = W.channel("lx.myscript")
W.on_request(function(page, url, headers, info)          -- 每个子资源，同步
    -- info = { type = "script"|"image"|"xmlhttprequest"|"sub_frame"|"font"|"media"|"websocket"|"ping"|"other",
    --          destination, method, initiator, main_frame, mode, credentials }
    if bad(url) then return false end                     -- 阻断
    headers["Referer"] = nil                              -- 改头
    info.opts = { credentials = "omit" }                  -- 不带 Cookie
    return "https://cleaned.example/…"                    -- 改写
end, 10)
W.on_window_cleared(function(page, uri) W.css(page, "body{background:#111}", "dark") end)  -- 新文档最早时机，CSS 绕 CSP
W.on_document_loaded(function(page) W.eval(page, "…") end)                                 -- 主世界执行 JS
W.on_page_destroyed(function(page) end)
W.expose("__my_bridge", function(page, arg) return "value" end)             -- 页面 JS：await window.__my_bridge(arg)
W.expose_async("__my_async", function(page, resolve, reject, arg) … end)    -- 等 IPC 往返后再 resolve
W.state(page, "ns")                                                          -- 跟 page 生命周期绑定的表
W.page_host(page) / W.js_string(s)
ch:emit_signal("hello", W.pid)                          -- → 浏览器进程 ch:add_signal("hello", function(_, pid) …)
ch:add_signal("config", function(_, _page, json) end)   -- ← 浏览器进程 lx.web.send_pid("lx.myscript", pid, "config", json)
```

约定：浏览器进程持有一切敏感东西（API 密钥、cookie、规则编译），渲染进程只拿编译好的数据；
每个渲染进程就位会发 `hello(pid)`，浏览器侧用 `lx.web.on_process(fn(pid))` / `send_pid` 下发配置，
配置变了用 `lx.web.broadcast(channel, "config", …)`。

### 8.3 页面内 JS 放哪

大段页面 JS 写成 `xxx/runtime.lua`，内容是 `return [==[ …js… ]==]`，渲染进程 `W.eval(page, require("myscript.runtime"))`
注入（注意 `(require(...))` 加括号，Lua 5.4 的 `require` 返回两个值）。页面 JS 通过 `W.expose` 出来的
`window.__xxx` 与 Lua 通话，永远不要把密钥、cookie 之类塞进页面。

### 8.4 测试

官方脚本的逻辑都能在桌面 Lua 5.4 里跑：把 `lemurx.*`、`luakit`、`ipc_channel`、`__lk` 用仿真表替掉，
浏览器进程半边和渲染进程半边分别加载即可；页面 JS 用 Node `vm.Script` 检查语法，需要 DOM 的用一个
极简 DOM 替身。C++ / Java 部分用增量编译验证（见 CATALOG.md 末尾）。
