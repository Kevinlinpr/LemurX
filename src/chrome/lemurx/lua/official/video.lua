-- @name 视频增强
-- @description Picture-in-Picture + Global Speed + Volume Master 替代：全站视频倍速（记忆每站）、画中画、音量增强到 600%、循环、截帧、双击快进/长按加速手势
-- @version 1.0.0
-- @icon 🎬
-- @category 媒体
-- @replaces Picture-in-Picture Extension · Global Speed · Volume Master · Video Speed Controller
-- @page lemurx://video/
--
-- 浏览器进程半边：设置、每站倍速持久化、菜单里的"画中画 / 倍速"快捷入口。
-- 页面里的一切（工具条、手势、WebAudio）在 video/runtime.lua，由 video/web.lua 注入。

local lx = require("lx")
local util = lx.util
local json = lx.json

local ID = "video"
local CHANNEL = "lx.video"
local data = lx.data(ID)

local S
S = lx.register({
    id = ID, name = "视频增强", version = "1.0.0", icon = "🎬",
    description = "任何网页视频：倍速（每站记忆）、画中画、音量增强、循环、截帧，双击快进 / 长按加速手势。",
    replaces = "Picture-in-Picture Extension · Global Speed · Volume Master · Video Speed Controller",
    settings = {
        enabled = true, default_speed = 1, remember = true, toolbar = true, gestures = true, keys = true,
        boost_max = 6, step = 0.25, autohide = 3000, sites_off = {},
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用视频增强", section = "总开关" },
        { key = "toolbar", type = "bool", label = "浮动工具条", desc = "视频播放时右下角出现，几秒不动自动隐藏，点视频再出来", section = "界面" },
        { key = "autohide", type = "select", label = "工具条自动隐藏", options = { { 0, "不隐藏" }, { 3000, "3 秒" }, { 6000, "6 秒" } } },
        { key = "gestures", type = "bool", label = "手势", desc = "双击视频左/右 1/3 快退/快进 10 秒；长按 2 倍速" },
        { key = "keys", type = "bool", label = "键盘快捷键", desc = "S/D 减/加速，R 复位，P 画中画，[ ] 快退快进（外接键盘 / 桌面模式）" },
        { key = "default_speed", type = "select", label = "默认倍速", section = "倍速", options = { { 1, "1x" }, { 1.25, "1.25x" }, { 1.5, "1.5x" }, { 2, "2x" } } },
        { key = "step", type = "select", label = "每次调节", options = { { 0.1, "0.1" }, { 0.25, "0.25" }, { 0.5, "0.5" } } },
        { key = "remember", type = "bool", label = "记住每个站点的倍速", desc = "在某站调过的倍速，下次打开该站自动套用" },
        { key = "boost_max", type = "select", label = "音量增强上限", section = "音量", options = { { 2, "200%" }, { 4, "400%" }, { 6, "600%" } }, desc = "WebAudio 实现；跨域且无 CORS 的视频不能增强（会静音，运行时会自动跳过）" },
        { key = "sites_off", type = "list", label = "不生效的站点", section = "例外", placeholder = "example.com" },
    },
    menu = {
        { id = "pip", title = "视频画中画", onClick = function() S.cmd("pip") end },
        { id = "speed", title = "视频倍速…", onClick = function() S.speed_dialog() end },
    },
    api = {},
})
local settings = S.settings
local speeds = data:read_json("speeds.json") or {}
local last_state = {}

local function payload()
    local c = settings:all()
    c.default_speed, c.boost_max, c.step, c.autohide = tonumber(c.default_speed) or 1, tonumber(c.boost_max) or 6, tonumber(c.step) or 0.25, tonumber(c.autohide) or 3000
    c.speeds = speeds
    return c
end
local ch = lx.web.require("video.web")
lx.web.on_process(function(pid) lx.web.send_pid(CHANNEL, pid, "config", payload()) end)
ch:add_signal("hello", function(_, pid) if type(pid) == "number" then lx.web.send_pid(CHANNEL, pid, "config", payload()) end end)
ch:add_signal("speed", function(_, host, rate)
    if type(host) == "string" and tonumber(rate) then
        if math.abs(tonumber(rate) - 1) < 0.001 then speeds[host] = nil else speeds[host] = tonumber(rate) end
        data:write_json("speeds.json", speeds)
        -- 其他进程同站页面也同步
        lx.web.broadcast(CHANNEL, "config", { speeds = speeds })
    end
end)
ch:add_signal("state", function(_, page_id, s)
    local t = type(s) == "string" and json.decode(s) or s
    if type(t) == "table" then last_state[page_id] = t end
end)
settings:on_change(function() lx.web.broadcast(CHANNEL, "config", payload()) end)

function S.cmd(cmd, arg)
    local cur = lx.tabs.current()
    if not cur then return end
    lx.web.broadcast(CHANNEL, "cmd", cur.id, cmd, arg)
end

function S.speed_dialog()
    local opts = { "0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x", "3x" }
    local ok = pcall(lemurx.ui.dialog, {
        title = "视频倍速", message = "选择后立即应用到当前页所有视频", items = opts,
        onSelect = function(ev)
            local label = opts[(ev.index or 0) + 1] or "1x"
            local r = tonumber((label:gsub("x", "")))
            if r then S.cmd("speed", r) end
        end,
    })
    if not ok then
        -- 没有列表对话框：用 prompt
        pcall(lemurx.ui.prompt, { title = "视频倍速", hint = "例如 1.5", value = "1.5", onOk = function(ev) local r = tonumber(ev.text) if r then S.cmd("speed", r) end end })
    end
end

S.api["speeds"] = function() return { speeds = speeds } end
S.api["speed.remove"] = function(args)
    speeds[tostring(args.host)] = nil
    data:write_json("speeds.json", speeds)
    lx.web.broadcast(CHANNEL, "config", { speeds = speeds })
    return { ok = true, message = "已清除", reload = true }
end
S.api["speed.clear"] = function()
    speeds = {}
    data:write_json("speeds.json", speeds)
    lx.web.broadcast(CHANNEL, "config", { speeds = speeds })
    return { ok = true, message = "已全部清除", reload = true }
end
S.api["cmd"] = function(args)
    S.cmd(tostring(args.cmd), args.arg)
    return { ok = true }
end

S.page = function(ctx)
    local body = {}
    body[#body + 1] = [[
<div class="card"><div class="row"><div class="icon">🎬</div><div class="l"><div class="t">视频增强 <span class="badge">v1.0.0</span></div><div class="d">Lua 实现，替代 Picture-in-Picture / Global Speed / Volume Master。视频开始播放时右下角出现工具条：− 倍速 + · 🔊 音量增强 · ⧉ 画中画 · ↻ 循环 · 📷 截帧。点倍速数字循环常用档，长按复位 1x。</div></div></div></div>
<div class="sec">记住的站点倍速</div><div class="card" id="speeds"></div>
]]
    body[#body + 1] = lx.html.settings(S)
    body[#body + 1] = [[<div class="actions"><button class="sec" data-api="speed.clear">清除全部站点倍速</button><button class="sec" data-api="settings.reset">恢复默认设置</button><a class="btn sec" href="lemurx://scripts/source?f=official/video.lua">查看源码</a></div>]]
    local js = [[
function load(){lx.api('speeds').then(function(r){var ks=Object.keys(r.speeds).sort();var h='';if(!ks.length)h='<div class="row"><div class="d">还没有。在任何站点用工具条调过倍速就会记在这里。</div></div>';
 ks.forEach(function(k){h+='<div class="row"><div class="l"><div class="t">'+k+'</div></div><span class="badge">'+r.speeds[k]+'x</span><button class="sec" data-h="'+k+'">清除</button></div>'});lx.q('#speeds').innerHTML=h;
 document.querySelectorAll('button[data-h]').forEach(function(b){b.onclick=function(){lx.api('speed.remove',{host:b.dataset.h}).then(load)}})})}
load();
]]
    return lx.html.page({ title = "视频增强", icon = "🎬", body = table.concat(body), js = js })
end

lx.web.broadcast(CHANNEL, "config", payload())
return S
