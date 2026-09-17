-- msg：luakit 日志模块（对应 luakit clib/msg.c + log.c）
--   msg.fatal / warn / info / verbose / debug (fmt, ...)
--   信号 "log" (time, level, group, msg)，msg.add_signal("log", fn) 可接（log_chrome 用）
-- 输出走 lemurx.log → logcat tag LemurX。

local env = ...
local object = __lk.object

local M = {}

local levels = { fatal = 0, error = 1, warn = 2, info = 3, verbose = 4, debug = 5 }

local function threshold()
    if lemurx.storage.get("luakit_verbose", "0") == "1" then return levels.debug end
    if env and env.verbose then return levels.debug end
    return levels.info
end

-- 调用方所在模块名：从 source 里取文件名去掉 .lua
local function caller_group(level)
    local info = debug.getinfo(level, "S")
    local src = info and info.source or "?"
    src = src:gsub("^@", ""):gsub("^=", "")
    local base = src:match("([^/\\]+)%.lua$") or src:match("([^/\\]+)$") or src
    return base
end

local function do_log(level, fmt, ...)
    local text
    if select("#", ...) > 0 then
        local ok, s = pcall(string.format, tostring(fmt), ...)
        text = ok and s or (tostring(fmt) .. " " .. table.concat({ ... }, " "))
    else
        text = tostring(fmt)
    end
    local group = caller_group(4)
    if levels[level] <= threshold() then
        lemurx.log(("[luakit] %s %s: %s"):format(level, group, text))
    end
    -- luakit log.c：信号参数 (time, level, group, msg)，time 为进程启动以来秒数
    M.emit_signal("log", __luakit.time(), level, group, text)
    return text
end

object.setup_module_signals(M)

function M.fatal(fmt, ...)
    local text = do_log("fatal", fmt, ...)
    error("luakit fatal: " .. text, 2)
end
function M.error(fmt, ...) return do_log("error", fmt, ...) end
function M.warn(fmt, ...) return do_log("warn", fmt, ...) end
function M.info(fmt, ...) return do_log("info", fmt, ...) end
function M.verbose(fmt, ...) return do_log("verbose", fmt, ...) end
function M.debug(fmt, ...) return do_log("debug", fmt, ...) end

_G.msg = M
return M
