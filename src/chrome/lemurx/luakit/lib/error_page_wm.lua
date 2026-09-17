-- LemurX · luakit-compatible library · error_page_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "error_page_wm" module API. No luakit code is used.
--
-- 渲染进程侧：错误页上的按钮回调。浏览器进程在 show_error_page 后发
--   "arm"(token, base_uri)
-- 这里对该 URI 暴露 window.__lemurErrorPageAction(token, index) → Promise，
-- 调用时把 (token, index) 发回浏览器 "action"，由 error_page.lua 执行对应按钮的 callback。

local ui = ipc_channel("error_page_wm")

local armed = {}      -- 已暴露过的 URI 模式 -> true
local pending = {}    -- call id -> { resolve=, reject= }
local next_id = 0
local FN = "__lemurErrorPageAction"

local function literal_pattern(s)
    return "^" .. s:gsub("[%^%$%(%)%%%.%[%]%*%+%-%?]", "%%%0")
end

local function expose(pattern)
    if armed[pattern] then return end
    armed[pattern] = true
    luakit.register_function(pattern, FN, function(pg, resolve, reject, token, index)
        next_id = next_id + 1
        local id = next_id
        pending[id] = { resolve = resolve, reject = reject }
        ui:emit_signal("action", pg, id, token, index)
    end)
end

ui:add_signal("arm", function(_, _pg, _token, base_uri)
    if type(base_uri) ~= "string" or base_uri == "" then return end
    expose(literal_pattern(base_uri))
end)

ui:add_signal("action-result", function(_, _pg, id, ok, err)
    local entry = pending[id]
    if not entry then return end
    pending[id] = nil
    if ok == false then entry.reject(err or "error page action failed") else entry.resolve(true) end
end)

return ui
