-- LemurX · luakit-compatible library · unique_instance
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "unique_instance" module API. No luakit code is used.
--
-- 单实例：unique.new("org.lemurx.luakit")，收到 "message" 信号时解析其中的 URI 并在当前窗口
-- 打开。Android 上 unique.is_running() 恒为 false，所以本模块实际上只处理宿主转发进来的外部
-- Intent 链接（内核 __lk.unique_deliver → unique "message" 信号）。
-- 消息格式：可选首行动词 "tabopen" | "winopen"（默认 tabopen），其后每行/每个空白分隔项是一个 URI。
-- 公开接口：unique_instance.parse_message(msg) unique_instance.open(uris, verb) unique_instance.name

local window = require("window")

local _M = {}

_M.name = "org.lemurx.luakit"

function _M.parse_message(message)
    local verb = "tabopen"
    local uris = {}
    for token in tostring(message or ""):gmatch("%S+") do
        if #uris == 0 and (token == "tabopen" or token == "winopen") then
            verb = token
        else
            uris[#uris + 1] = token
        end
    end
    return verb, uris
end

local function current_window()
    if window.current then
        local ok, w = pcall(window.current)
        if ok and w then return w end
    end
    for _, w in pairs(window.bywidget or {}) do return w end
end

function _M.open(uris, verb)
    if #uris == 0 then return end
    if verb == "winopen" and window.new then
        window.new(uris)
        return
    end
    local w = current_window()
    if not w then
        if window.new then window.new(uris) end
        return
    end
    for i, uri in ipairs(uris) do
        w:new_tab(uri, { switch = (i == 1) })
    end
end

if rawget(_G, "unique") then
    unique.new(_M.name)
    -- 内核的模块级信号不传对象（handler(message, screen)），luakit 文档写的是 (unique, message, screen)：两种都接
    unique.add_signal("message", function(a, b, c)
        local message = a
        if type(a) ~= "string" then message = b end
        local verb, uris = _M.parse_message(message)
        _M.open(uris, verb)
    end)
    if unique.is_running() then
        -- 桌面语义：把启动参数交给已运行的实例后退出
        local list = rawget(_G, "uris") or {}
        if #list > 0 then unique.send_message("tabopen " .. table.concat(list, " ")) end
        luakit.quit()
    end
end

return _M
