-- LemurX · luakit-compatible library · chrome_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "chrome_wm" module API. No luakit code is used.
--
-- 渲染进程侧：把浏览器进程 chrome.add(name, fn, first_visual, exports) 里导出的 Lua
-- 函数暴露到 luakit://<name>/ 页面的 window 上。流程：
--   1. 浏览器进程通过 ipc_channel("chrome_wm") 发 "exports"(name, {fname,...})；
--   2. 这里对每个函数名 luakit.register_function("^luakit://name/", fname, ...)；
--   3. 页面 JS 调 window.fname(...) → 记下 resolve/reject，把调用发回浏览器 "call"；
--   4. 浏览器执行后回 "result"(call_id, ok, value)，这里 resolve/reject 对应的 Promise。
-- 只依赖渲染进程可用的全局：luakit / ipc_channel / msg / page。

local ui = ipc_channel("chrome_wm")

local registered = {}     -- "name\0fname" -> true
local pending = {}        -- call_id -> { resolve=, reject= }
local next_call = 0

local function pattern_for(name)
    -- 页面名里只有 [%w_-]，"-" 需要转义
    return "^luakit://" .. name:gsub("%-", "%%-") .. "/"
end

local function expose(name, fname)
    local key = name .. "\0" .. fname
    if registered[key] then return end
    registered[key] = true
    luakit.register_function(pattern_for(name), fname, function(pg, resolve, reject, ...)
        next_call = next_call + 1
        local id = next_call
        pending[id] = { resolve = resolve, reject = reject }
        local argc = select("#", ...)
        local args = { ... }
        ui:emit_signal("call", pg, name, fname, id, argc, args)
    end)
end

ui:add_signal("exports", function(_, _, name, names)
    if type(name) ~= "string" or type(names) ~= "table" then return end
    for _, fname in ipairs(names) do
        if type(fname) == "string" then expose(name, fname) end
    end
end)

ui:add_signal("result", function(_, _, id, ok, value)
    local entry = pending[id]
    if not entry then return end
    pending[id] = nil
    if ok then
        entry.resolve(value)
    else
        entry.reject(value == nil and "chrome export failed" or value)
    end
end)

-- 新页面：若是 luakit:// 页面，主动问一次导出表（覆盖渲染进程晚于 chrome.add 启动的情况）
luakit.add_signal("page-created", function(pg)
    local uri = pg and pg.uri or ""
    local name = uri:match("^luakit://([%w_%-]+)")
    if name then ui:emit_signal("query", pg, name) end
end)

-- 模块加载完毕：要一份全部导出表
ui:emit_signal("hello")

return ui
