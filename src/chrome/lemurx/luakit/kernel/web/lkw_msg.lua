-- msg（渲染进程版，对应 luakit extension/clib/msg.c）
-- 日志经 LuakitWebHost.Log 回浏览器，由 UI 进程的 msg 统一发 "log" 信号。

local N = __luakit_web

local M = {}
local levels = { fatal = 0, error = 1, warn = 1, info = 2, verbose = 3, debug = 4 }

local function caller_group(level)
    local info = debug.getinfo(level, "S")
    local src = info and info.source or "?"
    src = src:gsub("^@", ""):gsub("^=", "")
    return src:match("([^/\\]+)%.lua$") or src:match("([^/\\]+)$") or src
end

local function do_log(level, fmt, ...)
    local text
    if select("#", ...) > 0 then
        local ok, s = pcall(string.format, tostring(fmt), ...)
        text = ok and s or (tostring(fmt) .. " " .. table.concat({ ... }, " "))
    else
        text = tostring(fmt)
    end
    N.log(levels[level] or 2, caller_group(4), text)
    if level == "fatal" then error(text, 3) end
end

for name in pairs(levels) do
    M[name] = function(fmt, ...) do_log(name, fmt, ...) end
end

_G.msg = M
return M
