-- @name GitHub 文件树
-- @description Octotree 替代：GitHub 仓库页左侧文件树侧栏，懒加载展开、过滤、当前文件高亮，跟随 Turbo 导航；可填 Token 提高 API 限额、支持私有仓库与 GitHub Enterprise
-- @version 1.0.0
-- @icon 🌲
-- @category 开发
-- @replaces Octotree
-- @page lemurx://octotree/
--
-- 结构：
--   octotree.lua          浏览器进程：GitHub API（token 不进页面）、树缓存、设置页
--   octotree/web.lua      渲染进程：注入运行时 / 桥接
--   octotree/runtime.lua  页面内 JS：侧栏
--
-- API：GET /repos/{o}/{r}/git/trees/{ref}?recursive=1（一次拿全树，10 万文件以内；再大 GitHub 会 truncated）
--      ref 缺省时先 GET /repos/{o}/{r} 拿 default_branch
--      未填 token 限额 60 次/小时（按 IP），够日常浏览；填了 5000 次/小时并能看私有仓库

local lx = require("lx")
local json = require("lx.json")
local util = require("lx.util")

local ID, CHANNEL = "octotree", "lx.octotree"
local S
S = lx.register({
    id = ID, name = "GitHub 文件树", version = "1.0.0", icon = "🌲",
    description = "GitHub 仓库页左侧文件树侧栏：懒加载展开、过滤、当前文件高亮、跟随页内导航。填 Token 可看私有仓库并提高限额。",
    replaces = "Octotree",
    settings = {
        enabled = true, token = "", width = 280, auto_open = true, push = true, show_on_tabs = false,
        cache_minutes = 30, hosts = { "github.com" }, api_base = "",
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用文件树", section = "总开关" },
        { key = "token", type = "string", label = "GitHub Token", section = "账号", desc = "Personal access token（只需 repo 读权限）。只存在浏览器进程，不注入网页。不填也能用，公开仓库限额 60 次/小时。" },
        { key = "test", type = "action", label = "测试 Token / 查看剩余限额", api = "ratelimit" },
        { key = "width", type = "number", label = "侧栏宽度（px）", section = "外观", min = 200, max = 480, step = 10 },
        { key = "auto_open", type = "bool", label = "进入仓库自动展开侧栏（宽屏）", desc = "手机竖屏默认收起，点左侧把手展开" },
        { key = "push", type = "bool", label = "宽屏时把页面往右挤，而不是盖在上面" },
        { key = "show_on_tabs", type = "bool", label = "Issues / PR 等标签页也显示" },
        { key = "cache_minutes", type = "number", label = "树缓存（分钟）", section = "高级", min = 0, max = 1440 },
        { key = "hosts", type = "list", label = "生效域名", desc = "GitHub Enterprise 加自己的域名，每行一个" },
        { key = "api_base", type = "url", label = "API 地址", placeholder = "留空 = https://api.github.com；Enterprise 填 https://ghe.example.com/api/v3" },
        { key = "clear_cache", type = "action", label = "清空树缓存", api = "cache.clear" },
    },
    menu = {
        { id = "toggle", title = "GitHub 文件树", onClick = function() S.cmd_current("toggle") end },
    },
    api = {},
})
local settings = S.settings

local cache = {}   -- key -> { at, data }
local stats = { requests = 0, cache_hits = 0, errors = 0, remaining = nil, limit = nil, reset = nil }

local function config()
    return {
        enabled = settings:get("enabled"), hosts = settings:get("hosts") or { "github.com" },
        width = tonumber(settings:get("width")) or 280, auto_open = settings:get("auto_open"),
        push = settings:get("push"), show_on_tabs = settings:get("show_on_tabs"),
    }
end

local function api_base(host)
    local b = (settings:get("api_base") or ""):gsub("/+$", "")
    if b ~= "" then return b end
    if host and host ~= "" and host ~= "github.com" and not host:match("%.github%.com$") then
        return "https://" .. host .. "/api/v3"   -- GHE 默认布局
    end
    return "https://api.github.com"
end

local function headers()
    local h = { Accept = "application/vnd.github+json", ["X-GitHub-Api-Version"] = "2022-11-28", ["User-Agent"] = "LemurX-octotree" }
    local tok = util.trim(settings:get("token") or "")
    if tok ~= "" then h["Authorization"] = "Bearer " .. tok end
    return h
end

local function note_ratelimit(r)
    local hs = r and r.headers or {}
    local function hv(k)
        for kk, v in pairs(hs) do if tostring(kk):lower() == k then return tonumber(v) end end
        return nil
    end
    local rem, lim, rs = hv("x-ratelimit-remaining"), hv("x-ratelimit-limit"), hv("x-ratelimit-reset")
    if rem then stats.remaining, stats.limit, stats.reset = rem, lim, rs end
end

local function api_error(r)
    stats.errors = stats.errors + 1
    local status = r and r.status
    local msg = ""
    local o = json.decode(r and r.body or "")
    if type(o) == "table" and o.message then msg = tostring(o.message) end
    if status == 403 and msg:lower():find("rate limit") then
        return { error = "GitHub API 限额用完了（未登录 60 次/小时）", need_token = true }
    end
    if status == 401 then return { error = "Token 无效或已过期", need_token = true } end
    if status == 404 then return { error = "仓库不存在，或是私有仓库需要 Token", need_token = (util.trim(settings:get("token") or "") == "") } end
    if status == 409 then return { error = "空仓库" } end
    return { error = ("GitHub API %s：%s"):format(tostring(status or (r and r.error) or "?"), msg ~= "" and msg or "请求失败") }
end

local function gh_get(url, cb)
    stats.requests = stats.requests + 1
    lx.fetch(url, { headers = headers(), timeout = 30000 }, function(r)
        note_ratelimit(r)
        if not (r and r.ok) then return cb(nil, api_error(r)) end
        local o = json.decode(r.body or "")
        if type(o) ~= "table" then return cb(nil, { error = "GitHub 返回无法解析" }) end
        cb(o)
    end)
end

-- 拿树：ref 为空先查默认分支
function S.tree(owner, repo, ref, host, nocache, cb)
    local base = api_base(host)
    local function fetch_tree(ref_)
        local key = base .. "|" .. owner .. "/" .. repo .. "@" .. ref_
        local ttl = (tonumber(settings:get("cache_minutes")) or 30) * 60000
        local c = cache[key]
        if c and not nocache and ttl > 0 and util.now_ms() - c.at < ttl then
            stats.cache_hits = stats.cache_hits + 1
            return cb(c.data)
        end
        gh_get(("%s/repos/%s/%s/git/trees/%s?recursive=1"):format(base, owner, repo, util.url.encode(ref_)), function(o, err)
            if not o then return cb(err) end
            local list = {}
            for _, e in ipairs(o.tree or {}) do
                if e.type == "blob" or e.type == "tree" then
                    list[#list + 1] = { path = e.path, type = e.type, size = e.size }
                end
            end
            local data = { ref = ref_, tree = list, truncated = o.truncated == true, sha = o.sha }
            cache[key] = { at = util.now_ms(), data = data }
            cb(data)
        end)
    end
    if ref and ref ~= "" then return fetch_tree(ref) end
    -- 默认分支也缓存
    local dk = base .. "|" .. owner .. "/" .. repo .. "@default"
    local dc = cache[dk]
    if dc and not nocache and util.now_ms() - dc.at < 3600000 then return fetch_tree(dc.data) end
    gh_get(("%s/repos/%s/%s"):format(base, owner, repo), function(o, err)
        if not o then return cb(err) end
        local def = o.default_branch or "main"
        cache[dk] = { at = util.now_ms(), data = def }
        fetch_tree(def)
    end)
end

-- ===== 渲染进程通道 =====
local ch = lx.web.require("octotree/web")
local function push_pid(pid) lx.web.send_pid(CHANNEL, pid, "config", json.encode(config())) end
if ch then
    ch:add_signal("hello", function(_, pid) if type(pid) == "number" then push_pid(pid) end end)
    lx.web.on_process(push_pid)
    ch:add_signal("req", function(_, pid, req_id, payload)
        local p = json.decode(payload or "") or {}
        local function reply(t) lx.web.send_pid(CHANNEL, pid, "result", req_id, json.encode(t)) end
        if p.type == "tree" and type(p.owner) == "string" and type(p.repo) == "string" then
            if not settings:get("enabled") then return reply({ error = "文件树已停用" }) end
            S.tree(p.owner, p.repo, p.ref, p.host, p.nocache, reply)
        else
            reply({ error = "unknown request " .. tostring(p.type) })
        end
    end)
end
settings:on_change(function() lx.web.broadcast(CHANNEL, "config", json.encode(config())) end)

function S.cmd_current(name)
    local t = lx.tabs.current()
    if not t then return end
    if not settings:get("enabled") then lx.toast("文件树已停用，去设置里打开") return end
    local host = util.host_of(t.url or "")
    local ok = false
    for _, h in ipairs(settings:get("hosts") or {}) do if util.host_matches(host, h) then ok = true end end
    if not ok then lx.toast("这不是 GitHub 页面") return end
    lx.web.broadcast(CHANNEL, "cmd", t.id, name)
end

-- ===== API / 页面 =====
S.api.ratelimit = function(_, ctx)
    gh_get(api_base() .. "/rate_limit", function(o, err)
        if not o then return ctx.reply({ ok = false, message = err.error }) end
        local core = o.resources and o.resources.core or o.rate or {}
        stats.remaining, stats.limit, stats.reset = core.remaining, core.limit, core.reset
        local who = (util.trim(settings:get("token") or "") ~= "") and "已登录" or "未登录（按 IP）"
        ctx.reply({ ok = true, message = ("%s · 剩余 %s / %s 次，%s 重置"):format(who, tostring(core.remaining), tostring(core.limit),
            core.reset and os.date("%H:%M", core.reset) or "?") })
    end)
    return "async"
end
S.api["cache.clear"] = function() cache = {} return { ok = true, message = "已清空" } end
S.api.stats = function()
    local n = 0 for _ in pairs(cache) do n = n + 1 end
    return { requests = stats.requests, cache_hits = stats.cache_hits, errors = stats.errors, cached = n,
        remaining = stats.remaining, limit = stats.limit, reset = stats.reset and os.date("%H:%M", stats.reset) or nil }
end

S.page = function(ctx)
    local body = [[
<div class="card"><div class="row"><div class="icon">🌲</div><div class="l"><div class="t">GitHub 文件树 <span class="badge">v1.0.0</span></div><div class="d">Lua 实现，替代 Octotree。打开任意 GitHub 仓库，左侧把手 › 展开文件树；点文件直接跳、点目录展开、长按目录进目录页。过滤框按路径模糊匹配。</div></div></div>
<div class="row"><div class="l"><div class="d" id="st">–</div></div><a class="btn sec" href="https://github.com/chromium/chromium">试一下</a></div></div>
]] .. lx.html.settings(S) .. [[
<div class="actions"><button class="sec" data-api="settings.reset">恢复默认设置</button><a class="btn sec" href="lemurx://scripts/source?f=official/octotree.lua">查看源码</a></div>]]
    return lx.html.page({ title = "GitHub 文件树", icon = "🌲", body = body,
        js = "lx.api('stats').then(function(r){lx.q('#st').textContent='本次运行请求 '+r.requests+' 次，缓存命中 '+r.cache_hits+'，缓存 '+r.cached+' 棵树'+(r.remaining!=null?('；API 剩余 '+r.remaining+'/'+r.limit+'，'+r.reset+' 重置'):'')})" })
end

lx.web.broadcast(CHANNEL, "config", json.encode(config()))
return S
