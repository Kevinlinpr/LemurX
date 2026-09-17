-- LemurX · luakit-compatible library · select
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "select" module API. No luakit code is used.
--
-- 浏览器进程侧的 select 模块：真正的提示层引擎在渲染进程的 select_wm.lua 里，
-- 这里只负责把用户自定义的 label_maker 送过去。
--   select.label_maker = function (s) return s.trim(s.sort(s.reverse(s.charset("asdf")))) end
-- 函数会以 string.dump 的字节码（十六进制文本）传输；也可以直接赋一段 Lua 源码字符串。
-- IPC (select_wm)：→ set_label_maker({ kind = "source"|"dump", data = str })

local _M = {}

local wm = require_web_module("select_wm")

local current = nil

local function to_hex(bin)
    return (bin:gsub(".", function(c) return string.format("%02x", c:byte()) end))
end

local function push(spec)
    wm:emit_signal("set_label_maker", spec)
end

local function set_label_maker(v)
    if v == nil then
        current = nil
        push({ kind = "source", data = "return function (s) return s.trim(s.sort(s.reverse(s.numbers()))) end" })
        return
    end
    if type(v) == "function" then
        local ok, dumped = pcall(string.dump, v, true)
        if not ok then error("select.label_maker: cannot serialise function: " .. tostring(dumped), 3) end
        current = v
        push({ kind = "dump", data = to_hex(dumped) })
    elseif type(v) == "string" then
        current = v
        push({ kind = "source", data = v })
    else
        error("select.label_maker must be a function or Lua source string", 3)
    end
end

-- 新渲染进程上线时重推一次
luakit.add_signal("web-extension-created", function(view)
    if current == nil then return end
    if type(current) == "function" then
        local ok, dumped = pcall(string.dump, current, true)
        if ok then
            if view then wm:emit_signal(view, "set_label_maker", { kind = "dump", data = to_hex(dumped) })
            else push({ kind = "dump", data = to_hex(dumped) }) end
        end
    else
        if view then wm:emit_signal(view, "set_label_maker", { kind = "source", data = current })
        else push({ kind = "source", data = current }) end
    end
end)

setmetatable(_M, {
    __index = function(_, k)
        if k == "label_maker" then return current end
        return nil
    end,
    __newindex = function(t, k, v)
        if k == "label_maker" then
            set_label_maker(v)
        else
            rawset(t, k, v)
        end
    end,
})

return _M
