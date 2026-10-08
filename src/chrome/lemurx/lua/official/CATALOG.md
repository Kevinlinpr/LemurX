# 官方 Lua 脚本 · Chrome 热门插件覆盖表

这个目录里的每个 `*.lua` 都是一个用 Lua 完整重写的 Chrome 热门扩展，随 apk 打包
（`assets/lua/official/`），首次启动解包到 `files/lua/official/`，升级时覆盖。
用户在三点菜单 **「Lua 脚本」** 里看到它们、开关它们、读源码、复制一份到本地改。

* 每个脚本一个 `lemurx://<id>/` 页面：设置、统计、操作，全部由脚本自己用 `lx.html` 渲染
* 浏览器进程半边 `xxx.lua`，渲染进程半边 `xxx/web.lua`，页面内 JS `xxx/runtime.lua`（作为 Lua 长字串）
* 公共框架在 `lx/`：`init.lua`（注册 / 设置 / 数据 / 路由 / 标签 / IPC）、`web.lua`（渲染进程钩子）、
  `abp.lua`（ABP 过滤引擎）、`util.lua`、`json.lua`

## 覆盖情况（按 chrome热点插件.xlsx 的 50 个）

状态：✅ 已实现　🟡 部分覆盖 / 用平台能力替代　⛔ 不做（原因见备注）

| # | Chrome 插件 | 状态 | LemurX 官方脚本 | 备注 |
|---|---|---|---|---|
| 1 | Adobe Acrobat: PDF edit, convert, sign | ⛔ | — | PDF 编辑 / 签署是 Adobe 云服务；Android Chromium 用系统 PDF 查看器。长网页"导出 PDF"用 **screenshot** 的整页长图替代 |
| 2 | McAfee WebAdvisor | 🟡 | privacy | 网页信誉 / 钓鱼拦截由 Chromium 自带 Safe Browsing 提供；privacy 再补追踪器拦截与站点隐私评级 |
| 3 | Application Launcher For Drive | 🟡 | clipper | Android 上 Drive 文件交给 Drive App；剪藏"分享 .md 文件"可直接投到 Drive |
| 4 | AdBlock | ✅ | **adblock** | ABP / uBO 兼容过滤引擎（网络 + 元素隐藏），EasyList / EasyPrivacy / 中文列表，导航级 + 渲染进程同步子资源拦截 |
| 5 | Grammarly | ✅ | **ai** | 输入框写作助手 ✨：润色 / 语法 / 续写 / 改语气（整段改，不做逐词下划线） |
| 6 | Adblock Plus | ✅ | **adblock** | 同上；"可接受广告"不做 |
| 7 | uBlock Origin | ✅ | **adblock** | 同上；`$important` / `$domain` / `$3p` / 类型选项 / 通用与站点元素隐藏 / 例外规则 |
| 8 | Google Translate | ✅ | **translate** | 划词翻译 + 整页翻译，Google / 微软 / DeepL / OpenAI 兼容引擎 |
| 9 | Chrome Remote Desktop | ⛔ | — | 需要桌面端主机程序与 Google 中继，不属于浏览器扩展能力范围 |
| 10 | Microsoft Single Sign On | ⛔ | — | 企业设备管理 / 系统账户集成，Android 上由 Authenticator / Intune 承担 |
| 11 | Cisco Webex Extension | ⛔ | — | Webex 已改用 App / WebRTC 直连，扩展本身已无必要 |
| 12 | Kaspersky Protection | 🟡 | privacy + adblock | 安全浏览由 Chromium Safe Browsing 提供；追踪拦截 / 广告拦截由两脚本提供 |
| 13 | Tampermonkey | ✅ | **userscripts** | `==UserScript==` 元数据、`@match/@include/@exclude`、`@require/@resource`、`@run-at`、`GM_*`/`GM.*` API（xmlhttpRequest 跨域、setValue、menu command、notification、clipboard…）、从 greasyfork 等一键安装、更新、值编辑 |
| 14 | Sider | ✅ | **ai** | 侧边面板：页面总结 / 对页面提问 / 自由对话，多家 OpenAI 兼容服务商 |
| 15 | Monica | ✅ | **ai** | 同上 + 划词工具条（解释 / 翻译 / 润色 / 改写） |
| 16 | OneTab | ✅ | **tabs** | 一键收纳全部标签成分组、恢复、导入导出、共享为文本 |
| 17 | Bitwarden | ⛔ | — | 密码管理器必须做到端到端加密与自动填充安全模型，半成品比没有更危险；Android 已有系统级自动填充服务承接 Bitwarden App。后续如做需先补 `lemurx.crypto`（PBKDF2/AES-GCM）能力 |
| 18 | Dark Reader | ✅ | **darkmode** | 三引擎：Chromium 原生 Force Dark（C++ 直控）/ CSS filter / 静态 CSS；亮度 / 对比度、按站点例外、定时、自动识别已暗色站点 |
| 19 | 沉浸式翻译 | ✅ | **translate** | 双语对照（原文 + 译文并列）、按站点自动翻译、缓存、多引擎 |
| 20 | QuillBot | ✅ | **ai** | 改写 / 润色 / 语法检查 |
| 21 | Momentum | ✅ | **newtab** | 时钟、问候、今日焦点、待办、快捷链接、天气、每日一句、Bing / Earth View / 自定义壁纸；原生新标签页被 Lua 页面替换 |
| 22 | LanguageTool | ✅ | **ai** | 多语言语法检查走模型 |
| 23 | Loom | ⛔ | — | 录屏 + 摄像头需要 MediaProjection 前台服务与云端上传分享，超出浏览器脚本边界；Android 系统录屏可替代 |
| 24 | Todoist | ✅ | **newtab** | 新标签页待办（本地存储） |
| 25 | Toggl Track | ✅ | **focus** | 按站点时间追踪、日 / 周报表，前后台切换感知 |
| 26 | Session Buddy | ✅ | **tabs** | 会话快照（自动 + 手动）、恢复、导入导出 |
| 27 | StayFocusd | ✅ | **focus** | 站点每日预算、生效时段 / 日期、核弹模式、锁定口令 |
| 28 | Privacy Badger | ✅ | **privacy** | 内置追踪器库 + 跨站学习启发式（≥3 个站点携 cookie 出现 → 拦截 / 去 cookie），黄名单 |
| 29 | ClearURLs | ✅ | **privacy** | 追踪参数剥离（全局 + 站点规则）、跳转中间页解包，导航级 + 子资源级 |
| 30 | DuckDuckGo Privacy Essentials | ✅ | **privacy** | GPC / DNT 头与 JS 信号、第三方 cookie 拦截、HTTPS 升级、站点隐私评级、一键清理 |
| 31 | AdGuard | ✅ | **adblock** | 同 uBO；区域列表可自选 |
| 32 | JSON Viewer | ✅ | **jsonviewer** | 自动识别 JSON 响应，折叠树、搜索、复制路径 / 值、原文切换、JSONP、暗色 |
| 33 | Wappalyzer | ✅ | **wappalyzer** | JS 全局 / 脚本 / HTML / meta / DOM / cookie / 响应头指纹，版本提取、隐含技术、历史 |
| 34 | React Developer Tools | ⛔ | — | 需要 DevTools 前端面板 + React 内部 hook 协议；手机上用 `lemurx.cdp` 自己做检查器更实际 |
| 35 | FireShot | ✅ | **screenshot** | 可见区 / 整页长图（CDP `captureBeyondViewport`）/ 元素截图，画布编辑器（裁剪、矩形、箭头、画笔、模糊、文字），存相册 / 分享 |
| 36 | HARPA AI | ✅ | **ai** | 页面总结 / 提问 / 生成；网页自动化部分由平台 `lemurx.agent` 承担 |
| 37 | Checker Plus for Gmail | ⛔ | — | 需要 Gmail OAuth 与推送，Android 上 Gmail App 本身就是这个功能 |
| 38 | Global Speed | ✅ | **video** | 0.1× – 16× 倍速、按站点记忆、手势 / 快捷键 |
| 39 | Octotree | ✅ | **octotree** | GitHub 仓库文件树侧栏，懒加载、过滤、当前文件高亮、Turbo 导航跟随、Token / GHE |
| 40 | Wordtune | ✅ | **ai** | 改写：不同语气 / 长度 |
| 41 | Bardeen | 🟡 | ai + 平台 `lemurx.agent` | 网页自动化 / 抓取用平台自带的 agent 底座（观察 / 定位 / 动作）编排，本目录不单独重写 |
| 42 | Volume Master | ✅ | **video** | WebAudio 增益最高 600% |
| 43 | Google Keep Chrome Extension | ✅ | **clipper** | 正文提取 → Markdown 本地笔记；"分享文本"投给 Keep |
| 44 | Office Editing for Docs, Sheets & Slides | ⛔ | — | Office 文档编辑器是 Google 的私有 NaCl/Wasm 组件；Android 上交给系统打开方式 |
| 45 | Google Input Tools | ⛔ | — | 输入法是系统层能力，Android 用系统 IME |
| 46 | Save to Google Drive | ✅ | **clipper** | "分享 .md 文件" / "存到下载目录"，分享面板选 Drive |
| 47 | Earth View from Google Earth | ✅ | **newtab** | 壁纸来源选 Earth View |
| 48 | Picture-in-Picture Extension | ✅ | **video** | 原生 PiP |
| 49 | Google Voice | ⛔ | — | VoIP 电话服务，仅美国，无扩展可重写的部分 |
| 50 | User-Agent Switcher | ✅ | **useragent** | 全局 / 按站点 UA，预设 + 自定义，影响请求头、`navigator.userAgent`、Client Hints；与 Chrome "桌面版站点"逻辑互不打架（TabImpl patch） |

统计：✅ 35 · 🟡 4 · ⛔ 11。

## 每个脚本一句话

| 脚本 | 名称 | 页面 | 半边 |
|---|---|---|---|
| `adblock.lua` | 去广告 | `lemurx://adblock/` | `adblock/web.lua`（子资源拦截 + 元素隐藏 CSS）、`adblock/builtin.lua`（离线兜底规则） |
| `userscripts.lua` | 用户脚本 | `lemurx://userscripts/` | `userscripts/web.lua`、`userscripts/gm.lua`（页面内 GM 运行时）、`meta.lua`、`match.lua` |
| `darkmode.lua` | 网页暗色 | `lemurx://darkmode/` | `darkmode/web.lua` |
| `translate.lua` | 网页翻译 | `lemurx://translate/` | `translate/web.lua`、`translate/runtime.lua` |
| `privacy.lua` | 隐私保护 | `lemurx://privacy/` | `privacy/web.lua`、`privacy/clearurls.lua`、`privacy/trackers.lua` |
| `tabs.lua` | 标签收纳 | `lemurx://tabs/` | — |
| `newtab.lua` | 新标签页 | `lemurx://newtab/` | — |
| `jsonviewer.lua` | JSON 查看器 | `lemurx://jsonviewer/` | `jsonviewer/web.lua`、`jsonviewer/viewer.lua` |
| `video.lua` | 视频增强 | `lemurx://video/` | `video/web.lua`、`video/runtime.lua` |
| `ai.lua` | AI 助手 | `lemurx://ai/` | `ai/web.lua`、`ai/runtime.lua` |
| `focus.lua` | 专注与时间 | `lemurx://focus/` | — |
| `useragent.lua` | UA 切换 | `lemurx://useragent/` | — |
| `wappalyzer.lua` | 网站技术栈 | `lemurx://wappalyzer/` | `wappalyzer/web.lua`、`wappalyzer/detect.lua`、`wappalyzer/techs.lua` |
| `screenshot.lua` | 网页截图 | `lemurx://screenshot/` | — （编辑器页面由脚本内路由 `/edit` 渲染） |
| `octotree.lua` | GitHub 文件树 | `lemurx://octotree/` | `octotree/web.lua`、`octotree/runtime.lua` |
| `clipper.lua` | 网页剪藏 | `lemurx://clipper/` | `clipper/web.lua`、`clipper/runtime.lua` |

## 为了这些脚本补上的底层能力

写脚本时发现缺什么就补什么，这是清单（C++ / Java / patch 都在仓库 `src/` 与 `patches/` 里）：

| 能力 | 层 | 为谁 |
|---|---|---|
| `send-request` 带 `info`（type / destination / initiator / main_frame / method）并可返回 `opts.credentials` | 渲染进程 C++ | adblock（按类型匹配）、privacy（去 cookie） |
| `page:insert_css / remove_css`（Blink `InsertStyleSheet`，绕 CSP，DOM 就绪前生效） | 渲染进程 C++ | adblock 元素隐藏、darkmode |
| `window-object-cleared` 信号、`webview_created_hooks`、`lx.web.on_process` | 内核 Lua | 所有渲染端模块 |
| `web_emit_pid`：向指定渲染进程发 IPC | 浏览器 C++ | 所有渲染端模块的配置下发 |
| `ResolveModule` 搜索 `files/lua/official/` | 浏览器 C++ | 渲染进程 `require` 官方模块 |
| `lemurx://` 注册为 standard / secure / CORS 可用 scheme；scheme 请求带 `method` 与 `body_b64`（POST） | `chrome_content_client.cc` patch、scheme C++ | 所有设置页（fetch API）、screenshot 编辑器大图回传 |
| 全局 scheme 处理器（`__lk.scheme_handlers`）、`request:finish(data, mime, status)` | 内核 Lua / C++ | lx 路由 |
| `lemurx.chrome.setForceDark(state)` + `OverrideWebPreferences` 钩子 | C++ + `chrome_content_browser_client.cc` patch | darkmode 原生引擎 |
| `lemurx.chrome.setNewTabUrl(url)` + `ChromeTabCreator` patch | Java + patch | newtab |
| `lemurx.tabs.discard(id)`、tab 字段 `lastActive / frozen / hidden / active / parentId` | Java | tabs（Great Suspender） |
| `lemurx.tabs.setUserAgent(id, ua, {platform, mobile, reload})`、`lemurx.chrome.userAgent()`、`TabImpl` patch 防止桌面版逻辑覆盖 | C++ / Java / patch | useragent |
| `lemurx.chrome.on("app")` 前后台事件 | Java | focus 时间追踪 |
| `lemurx.ui.dialog{ items=..., onSelect=... }` 列表对话框 | Java / C++ | video 倍速选择、useragent |
| `lemurx.share{ path | text }`（FileProvider）、`lemurx.media.save{}`（MediaStore）、`lemurx.notify.show / cancel` | Java / C++ | screenshot、clipper、tabs、focus |
| 官方脚本目录种子化 / 加载顺序（init → agent → luakit 内核 → official → local → ugc）、头注释元数据解析 | Java | 管理界面 |
| `LemurXScriptsActivity`：官方 / 本地 / UGC 三组、开关、详情、读源码、编辑、复制官方脚本到本地、删除 | Java | 用户"有地方管理并查看 Lua 脚本" |

## 测试

所有脚本在桌面 Lua 5.4 里用仿真的 `lemurx.*` / `luakit` 环境跑过双端（浏览器进程 / 渲染进程）逻辑测试，页面内 JS 与
设置页脚本用 Node 做过语法与逻辑检查，`lx/abp.lua` 用真实 EasyList 做过基准；这些仿真环境不进仓库。
C++ / Java 用增量编译验证：
`autoninja -C out/lemurx chrome/browser/ui/android/lemurx:lemurx chrome/browser/lemurx:lemurx chrome/renderer/lemurx:lemurx chrome/android:chrome_java`。
