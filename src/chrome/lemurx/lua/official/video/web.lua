-- video · 渲染进程半边
--
-- document-loaded 后把 runtime.lua 注入每个 http(s) 页面（运行时很轻，没视频时只挂一个
-- MutationObserver）。暴露 window.__lx_video_bridge 给页面保存本站倍速。
--
-- 通道 "lx.video"：
--   ← "config"(cfg)                { enabled, default_speed, remember, toolbar, gestures, keys, boost_max, step, autohide, sites_off, speeds = {host=rate} }
--   ← "cmd"(page_id, cmd, arg)     转给页面 __lxvid_cmd
--   → "hello"(pid)
--   → "speed"(host, rate)          用户改了本站倍速
--   → "state"(page_id, json)

local W = require("lx.web")
local util = W.util
local json = W.json

local M = {}
local ch = W.channel("lx.video")
local cfg = { enabled = true, default_speed = 1, remember = true, toolbar = true, gestures = true, keys = true, boost_max = 6, step = 0.25, autohide = 3000, sites_off = {}, speeds = {} }
local runtime = nil
local injected = setmetatable({}, { __mode = "k" })

local function site_off(host)
    for _, pat in ipairs(cfg.sites_off or {}) do
        if util.host_matches(host, pat) then return true end
    end
    return false
end

local function site_speed(host)
    local sp = cfg.speeds or {}
    local h = host
    while h and h ~= "" do
        if sp[h] then return tonumber(sp[h]) end
        local dot = h:find(".", 1, true)
        if not dot then break end
        h = h:sub(dot + 1)
    end
    return nil
end

local function inject(page)
    if not cfg.enabled or injected[page] then return end
    local uri = page.uri or ""
    if not uri:match("^https?://") then return end
    local host = W.page_host(page)
    if site_off(host) then return end
    injected[page] = true
    if not runtime then runtime = require("video.runtime") end
    local c = json.encode({
        default_speed = tonumber(cfg.default_speed) or 1, remember = cfg.remember and true or false, site_speed = cfg.remember and site_speed(host) or nil,
        toolbar = cfg.toolbar and true or false, gestures = cfg.gestures and true or false, keys = cfg.keys and true or false,
        boost_max = tonumber(cfg.boost_max) or 6, step = tonumber(cfg.step) or 0.25, autohide = tonumber(cfg.autohide) or 3000,
    })
    W.eval(page, "window.__lxvid_cfg=" .. c .. ";" .. runtime, "lx-video")
end

W.on_document_loaded(inject, 70)
W.on_window_cleared(function(page) injected[page] = nil end)

W.expose("__lx_video_bridge", function(page, payload)
    local m = type(payload) == "string" and json.decode(payload) or payload
    if type(m) ~= "table" then return false end
    if m.type == "speed" and tonumber(m.speed) then
        local host = util.base_domain(W.page_host(page))
        if host ~= "" then
            cfg.speeds = cfg.speeds or {}
            cfg.speeds[host] = tonumber(m.speed)
            ch:emit_signal("speed", host, tonumber(m.speed))
        end
    end
    return true
end)

ch:add_signal("config", function(_, _page, c)
    if type(c) == "table" then for k, v in pairs(c) do cfg[k] = v end end
    -- 工具条开关即时生效
    for _, p in pairs(__lk.pages()) do
        if injected[p] then pcall(W.eval, p, "window.__lxvid_cmd&&__lxvid_cmd('toolbar'," .. tostring(cfg.toolbar and true or false) .. ")") end
    end
end)

ch:add_signal("cmd", function(_, _page, page_id, cmd, arg)
    for _, p in pairs(__lk.pages()) do
        local ok, id = pcall(function() return p.id end)
        if ok and id == page_id then
            if not injected[p] then inject(p) end
            local r = W.eval(p, "window.__lxvid_cmd?__lxvid_cmd(" .. W.js_string(cmd) .. "," .. json.encode(arg) .. "):null")
            if cmd == "state" then ch:emit_signal("state", page_id, r) end
            return
        end
    end
end)

ch:emit_signal("hello", luakit.web_process_id)
M.cfg = cfg
return M
