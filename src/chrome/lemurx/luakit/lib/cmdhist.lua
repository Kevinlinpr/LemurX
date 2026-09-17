-- LemurX · luakit-compatible library · cmdhist
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "cmdhist" module API. No luakit code is used.
--
-- ":" 命令历史。窗口每执行一条命令（window 的 run-cmd 信号）就记一条，
-- 持久化为 luakit.data_dir/cmdhist（每行一条，最新在末尾）。
-- command 模式里 <Up>/<Down> 在历史中前后翻，翻到底部时恢复原先敲的内容。
-- 只保留与当前输入前缀匹配的条目（前缀过滤）。

local lousy = require("lousy")
local modes = require("modes")
local window = require("window")

local _M = {}

_M.file = luakit.data_dir .. "/cmdhist"
_M.max_items = 200
_M.items = {}

local function load()
    local f = io.open(_M.file, "r")
    if not f then return end
    local items = {}
    for line in f:lines() do
        line = line:gsub("\r$", "")
        if line ~= "" then items[#items + 1] = line end
    end
    f:close()
    _M.items = items
end

local function save()
    local f, err = io.open(_M.file, "w")
    if not f then
        msg.verbose("cmdhist: cannot write %s: %s", _M.file, tostring(err))
        return false
    end
    f:write(table.concat(_M.items, "\n"))
    if #_M.items > 0 then f:write("\n") end
    f:close()
    return true
end

function _M.add(cmd)
    cmd = tostring(cmd or ""):gsub("^%s*:", ""):gsub("^%s+", ""):gsub("%s+$", "")
    if cmd == "" then return end
    -- 去掉重复项，最新的放末尾
    for i = #_M.items, 1, -1 do
        if _M.items[i] == cmd then table.remove(_M.items, i) end
    end
    _M.items[#_M.items + 1] = cmd
    while #_M.items > _M.max_items do table.remove(_M.items, 1) end
    save()
end

function _M.get()
    local out = {}
    for i, v in ipairs(_M.items) do out[i] = v end
    return out
end

function _M.clear()
    _M.items = {}
    save()
end

-- ---------------------------------------------------------------------------
-- 翻历史
-- ---------------------------------------------------------------------------
local function state(w)
    w.cmdhist_state = w.cmdhist_state or {}
    return w.cmdhist_state
end

local function matches(prefix)
    local out = {}
    for _, c in ipairs(_M.items) do
        if c:sub(1, #prefix) == prefix then out[#out + 1] = c end
    end
    return out
end

local function begin(w)
    local s = state(w)
    if s.active then return s end
    local text = (w.ibar.input.text or ""):gsub("^:", "")
    s.active = true
    s.original = text
    s.list = matches(text)
    s.index = #s.list + 1      -- 指向“原始输入”
    return s
end

local function show(w, s)
    if s.index > #s.list then
        w:set_input(":" .. s.original)
    else
        w:set_input(":" .. s.list[s.index])
    end
end

function _M.history_prev(w)
    local s = begin(w)
    if s.index <= 1 then return end
    s.index = s.index - 1
    show(w, s)
end

function _M.history_next(w)
    local s = begin(w)
    if s.index > #s.list then return end
    s.index = s.index + 1
    show(w, s)
end

_M.history_prev_func = _M.history_prev
_M.history_next_func = _M.history_next

local function reset(w)
    local s = state(w)
    s.active = false
    s.list = nil
end

window.add_signal("init", function(w)
    w:add_signal("run-cmd", function(_, text) _M.add(text) end)
    w:add_signal("mode-changed", function() reset(w) end)
    -- 用户手动改了输入内容 → 下一次 Up/Down 重新按新前缀过滤
    w.ibar.input:add_signal("changed", function()
        local s = state(w)
        if not s.active or not w:is_mode("command") then return end
        local cur = (w.ibar.input.text or ""):gsub("^:", "")
        local expected = s.index > #s.list and s.original or s.list[s.index]
        if cur ~= expected then reset(w) end
    end)
end)

modes.add_binds("command", {
    { "<Up>",   "Previous command from the history.", function(w) _M.history_prev(w) end },
    { "<Down>", "Next command from the history.",     function(w) _M.history_next(w) end },
})

load()

return _M
