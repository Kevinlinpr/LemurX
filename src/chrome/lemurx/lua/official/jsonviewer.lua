-- @name JSON 查看器
-- @description JSON Viewer / JSON Formatter 替代：打开 JSON 接口时自动变成可折叠树，支持搜索、复制路径与值、原文/树切换、JSONP、暗色
-- @version 1.0.0
-- @icon 🧩
-- @category 开发
-- @replaces JSON Viewer · JSON Formatter · JSONView
-- @page lemurx://jsonviewer/
--
-- 浏览器进程半边：只管设置与统计。判定和渲染全在渲染进程（jsonviewer/web.lua + viewer.lua）。

local lx = require("lx")

local ID = "jsonviewer"
local CHANNEL = "lx.jsonviewer"

local S
S = lx.register({
    id = ID, name = "JSON 查看器", version = "1.0.0", icon = "🧩",
    description = "打开 JSON 接口时自动渲染成可折叠树：搜索、复制路径 / 值、原文切换、JSONP、暗色。",
    replaces = "JSON Viewer · JSON Formatter · JSONView",
    settings = {
        enabled = true, theme = "auto", indent = 2, collapse_depth = 2, max_kb = 4096, wrap = false, sites_off = {},
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用 JSON 查看器", section = "总开关" },
        { key = "theme", type = "select", label = "主题", section = "外观", options = { { "auto", "跟随系统" }, { "light", "浅色" }, { "dark", "深色" } } },
        { key = "indent", type = "select", label = "原文缩进", options = { { 2, "2 空格" }, { 4, "4 空格" }, { 1, "1 空格" } } },
        { key = "collapse_depth", type = "select", label = "默认折叠层级", desc = "超过这一层的节点默认收起", options = { { 1, "1 层" }, { 2, "2 层" }, { 3, "3 层" }, { 99, "全部展开" } } },
        { key = "wrap", type = "bool", label = "原文自动换行" },
        { key = "max_kb", type = "select", label = "最大处理体积", section = "性能", options = { { 1024, "1 MB" }, { 4096, "4 MB" }, { 16384, "16 MB" } } },
        { key = "sites_off", type = "list", label = "不处理的站点", section = "例外", placeholder = "example.com" },
    },
    api = {},
})
local settings = S.settings
local shown = 0

local function payload()
    local c = settings:all()
    c.indent, c.collapse_depth, c.max_kb = tonumber(c.indent) or 2, tonumber(c.collapse_depth) or 2, tonumber(c.max_kb) or 4096
    return c
end
local ch = lx.web.require("jsonviewer.web")
lx.web.on_process(function(pid) lx.web.send_pid(CHANNEL, pid, "config", payload()) end)
ch:add_signal("hello", function(_, pid) if type(pid) == "number" then lx.web.send_pid(CHANNEL, pid, "config", payload()) end end)
ch:add_signal("shown", function(_, page_id, bytes) shown = shown + 1 end)
settings:on_change(function() lx.web.broadcast(CHANNEL, "config", payload()) end)

S.api["stats"] = function() return { shown = shown } end

S.page = function(ctx)
    local body = [[
<div class="card"><div class="row"><div class="icon">🧩</div><div class="l"><div class="t">JSON 查看器 <span class="badge">v1.0.0</span></div><div class="d">Lua 实现，替代 JSON Viewer / JSON Formatter。打开任何返回 JSON 的地址（接口、.json 文件）自动生效；点节点看路径，长列表懒加载。</div></div></div>
<div class="row"><div class="l"><div class="d">本次运行已渲染 <b id="n">–</b> 个 JSON 页面</div></div><a class="btn sec" href="https://api.github.com/repos/chromium/chromium">试一下</a></div></div>
]] .. lx.html.settings(S) .. [[
<div class="actions"><button class="sec" data-api="settings.reset">恢复默认设置</button><a class="btn sec" href="lemurx://scripts/source?f=official/jsonviewer.lua">查看源码</a></div>]]
    return lx.html.page({ title = "JSON 查看器", icon = "🧩", body = body, js = "lx.api('stats').then(function(r){lx.q('#n').textContent=r.shown})" })
end

lx.web.broadcast(CHANNEL, "config", payload())
return S
