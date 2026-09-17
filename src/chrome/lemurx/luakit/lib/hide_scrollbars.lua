-- LemurX · luakit-compatible library · hide_scrollbars
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "hide_scrollbars" module API. No luakit code is used.
--
-- 给每个 webview 挂一张隐藏滚动条的用户样式表。
-- 公开接口：hide_scrollbars.stylesheet（内核 stylesheet 对象）hide_scrollbars.enabled（读写）

local webview = require("webview")

local _M = {}

local ss = stylesheet{ source = [[
::-webkit-scrollbar { width: 0 !important; height: 0 !important; display: none !important; }
html, body { scrollbar-width: none !important; -ms-overflow-style: none !important; }
]] }

local enabled = true
local views = setmetatable({}, { __mode = "k" })

local function apply(view)
    pcall(function() view.stylesheets[ss] = enabled end)
end

webview.add_signal("init", function(view)
    views[view] = true
    apply(view)
end)

setmetatable(_M, {
    __index = function(_, k)
        if k == "stylesheet" then return ss end
        if k == "enabled" then return enabled end
    end,
    __newindex = function(t, k, v)
        if k == "enabled" then
            enabled = v and true or false
            for view in pairs(views) do
                if view.is_alive then apply(view) end
            end
        elseif k == "stylesheet" then
            error("hide_scrollbars.stylesheet is read-only; change .source instead", 2)
        else
            rawset(t, k, v)
        end
    end,
})

return _M
