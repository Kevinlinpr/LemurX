-- LemurX · luakit-compatible library · referer_control_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "referer_control_wm" module API. No luakit code is used.
--
-- 渲染进程侧的 Referer 控制：在 send-request 里检查 Referer 头，跨站请求时按策略处理。
--   策略 policy："same-origin"（默认，跨可注册域时删除）、"strip"（总是删除）、
--               "origin"（跨站时只保留 scheme://host/）、"none"（不动）
--   exceptions：不处理的请求目标域列表
-- 单独 require_web_module("referer_control_wm") 即可工作；浏览器侧 referer_control.lua
-- 可通过 IPC 改策略。
-- IPC (referer_control_wm)：← policy(name) exceptions({domains}) rules({{host=,policy=}})

local ui = ipc_channel("referer_control_wm")

local M = {
    policy = "same-origin",
    exceptions = {},
    rules = {},    -- { host = "a.com", policy = "none" }：对该目标域使用特定策略
}

local KNOWN_SLD = { co = true, com = true, net = true, org = true, gov = true, edu = true, ac = true, ["or"] = true, ne = true, go = true }

local function host_of(uri)
    local rest = tostring(uri or ""):match("^%a[%w+.-]*://([^/?#]*)")
    if not rest then return nil end
    rest = rest:gsub("^[^@]*@", "")
    return (rest:match("^([^:]*)") or rest):lower()
end

local function base_domain(host)
    if not host or host == "" then return "" end
    if host:match("^[%d%.]+$") then return host end
    local labels = {}
    for l in host:gmatch("[^%.]+") do labels[#labels + 1] = l end
    local n = #labels
    if n <= 2 then return host end
    if KNOWN_SLD[labels[n - 1]] and #labels[n] == 2 then
        return labels[n - 2] .. "." .. labels[n - 1] .. "." .. labels[n]
    end
    return labels[n - 1] .. "." .. labels[n]
end

local function in_list(host, list)
    for _, d in ipairs(list) do
        if host == d or host:sub(-(#d + 1)) == "." .. d then return true end
    end
    return false
end

local function find_header(headers)
    for k in pairs(headers) do
        if type(k) == "string" and k:lower() == "referer" then return k end
    end
end

local function policy_for(host)
    for _, r in ipairs(M.rules) do
        if r.host and (host == r.host or host:sub(-(#r.host + 1)) == "." .. r.host) then
            return r.policy or M.policy
        end
    end
    return M.policy
end

-- 返回处理后的 Referer 值（nil 表示删除）；供测试直接调用
function M.filter(referer, request_uri)
    if type(referer) ~= "string" or referer == "" then return referer end
    local target = host_of(request_uri)
    if not target then return referer end
    if in_list(target, M.exceptions) then return referer end
    local policy = policy_for(target)
    if policy == "none" then return referer end
    if policy == "strip" then return nil end
    local from = host_of(referer)
    local same = from and base_domain(from) == base_domain(target)
    if same then return referer end
    if policy == "origin" then
        local scheme, authority = referer:match("^(%a[%w+.-]*)://([^/?#]*)")
        if scheme then return scheme .. "://" .. authority .. "/" end
        return nil
    end
    -- same-origin
    return nil
end

local function on_send_request(p, uri, headers)
    if type(headers) ~= "table" then return end
    local key = find_header(headers)
    if not key then return end
    local new = M.filter(headers[key], uri)
    if new ~= headers[key] then headers[key] = new end
end

luakit.add_signal("page-created", function(p)
    p:add_signal("send-request", on_send_request)
end)

ui:add_signal("policy", function(_, _, name)
    if type(name) == "string" then M.policy = name end
end)
ui:add_signal("exceptions", function(_, _, list)
    M.exceptions = {}
    for _, d in ipairs(list or {}) do
        if type(d) == "string" then M.exceptions[#M.exceptions + 1] = d:lower() end
    end
end)
ui:add_signal("rules", function(_, _, list)
    M.rules = {}
    for _, r in ipairs(list or {}) do
        if type(r) == "table" and type(r.host) == "string" then
            M.rules[#M.rules + 1] = { host = r.host:lower(), policy = r.policy }
        end
    end
end)

return M
