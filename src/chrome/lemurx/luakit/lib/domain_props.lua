-- LemurX · luakit-compatible library · domain_props
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "domain_props" module API. No luakit code is used.
--
-- 兼容层：luakit 早已用 settings.on[domain] 取代 domain_props。这里保留旧写法
--   domain_props["example.com"] = { enable_javascript = false, zoom_level = 1.2 }
-- 并把每个键镜像成域名级 webview 设置：settings.set_setting("webview.<key>", value, domain)。
-- 键名若已带分组（含 "."），原样使用。读取 domain_props[domain] 返回已镜像的表。

local settings = require("settings")

local _M = {}
local store = {}

local function mirror(domain, props)
    for key, value in pairs(props) do
        local name = key
        if not tostring(key):find(".", 1, true) then name = "webview." .. key end
        local ok, err = pcall(settings.set_setting, name, value, domain)
        if not ok then
            -- 退回 settings.on[domain] 代理写法
            local ok2 = pcall(function()
                local group, leaf = name:match("^([^.]+)%.(.+)$")
                settings.on[domain][group][leaf] = value
            end)
            if not ok2 then
                msg.warn("domain_props: cannot apply %s for %s: %s", name, domain, tostring(err))
            end
        end
    end
end

setmetatable(_M, {
    __index = function(_, domain) return store[domain] end,
    __newindex = function(_, domain, props)
        if type(domain) ~= "string" then error("domain_props: domain must be a string", 2) end
        if props == nil then
            store[domain] = nil
            return
        end
        if type(props) ~= "table" then error("domain_props[domain] must be a table of settings", 2) end
        msg.verbose("domain_props is deprecated; use settings.on[%q] instead", domain)
        store[domain] = props
        mirror(domain, props)
    end,
})

return _M
