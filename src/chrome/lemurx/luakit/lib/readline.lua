-- LemurX · luakit-compatible library · readline
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "readline" module API. No luakit code is used.
--
-- 输入栏的 Emacs 风格编辑键：作用于 w.ibar.input 的 text / position。
-- 装进 command / search / lua / completion 模式（以及任何 has_input = true 的模式）。
-- readline.bindings 是绑定表本体，rc.lua 可以在 require 之后改它再
-- 调 readline.install(modes_list) 重新装载。

local lousy = require("lousy")
local modes = require("modes")
local window = require("window")

local _M = {}

-- 光标位置按 UTF-8 字符计
local function state(w)
    local i = w.ibar.input
    local text = i.text or ""
    local len = utf8.len(text) or #text
    local pos = tonumber(i.position)
    if pos == nil or pos < 0 or pos > len then pos = len end
    return i, text, pos, len
end

local function byte_at(text, cpos)
    -- 第 cpos 个字符之后的字节偏移（cpos=0 → 1）
    local len = utf8.len(text) or #text
    if cpos >= len then return #text + 1 end
    return utf8.offset(text, cpos + 1)
end

local function set(w, text, pos)
    local i = w.ibar.input
    i.text = text
    local len = utf8.len(text) or #text
    if pos < 0 then pos = 0 end
    if pos > len then pos = len end
    i.position = pos
end

local function left(text, pos) return text:sub(1, byte_at(text, pos) - 1) end
local function right(text, pos) return text:sub(byte_at(text, pos)) end

_M.kill_ring = nil

local function kill(w, keep_left, keep_right, pos)
    local i, text = state(w)
    local removed_len = (utf8.len(text) or #text) - (utf8.len(keep_left .. keep_right) or #(keep_left .. keep_right))
    _M.kill_ring = text:sub(#keep_left + 1, #text - #keep_right)
    set(w, keep_left .. keep_right, pos)
    return removed_len
end

-- 词边界：向左跳过空白再跳过非空白
local function word_start(text, pos)
    local l = left(text, pos)
    local stripped = l:gsub("%s+$", "")
    local head = stripped:gsub("%S+$", "")
    return utf8.len(head) or #head
end
local function word_end(text, pos)
    local r = right(text, pos)
    local skipped = r:match("^%s*%S*")
    return pos + (utf8.len(skipped) or #skipped)
end

local B = {}

function B.beginning_of_line(w) local _, text = state(w) set(w, text, 0) end
function B.end_of_line(w) local _, text, _, len = state(w) set(w, text, len) end
function B.backward_char(w) local _, text, pos = state(w) set(w, text, pos - 1) end
function B.forward_char(w) local _, text, pos, len = state(w) set(w, text, math.min(len, pos + 1)) end
function B.backward_word(w) local _, text, pos = state(w) set(w, text, word_start(text, pos)) end
function B.forward_word(w) local _, text, pos = state(w) set(w, text, word_end(text, pos)) end

function B.delete_char(w)
    local _, text, pos, len = state(w)
    if pos >= len then return end
    set(w, left(text, pos) .. right(text, pos + 1), pos)
end
function B.backward_delete_char(w)
    local _, text, pos = state(w)
    if pos <= 0 then return end
    set(w, left(text, pos - 1) .. right(text, pos), pos - 1)
end
function B.kill_line(w)
    local _, text, pos = state(w)
    kill(w, left(text, pos), "", pos)
end
function B.unix_line_discard(w)
    local _, text, pos = state(w)
    -- 保留提示前缀（":" "/" "?"）
    local prefix = text:match("^[:/?]") or ""
    if pos <= #prefix then return end
    kill(w, prefix, right(text, pos), utf8.len(prefix) or #prefix)
end
function B.unix_word_rubout(w)
    local _, text, pos = state(w)
    local ws = word_start(text, pos)
    local prefix = text:match("^[:/?]") or ""
    if ws < (utf8.len(prefix) or #prefix) then ws = utf8.len(prefix) or #prefix end
    if ws >= pos then return end
    kill(w, left(text, ws), right(text, pos), ws)
end
function B.kill_word(w)
    local _, text, pos = state(w)
    local we = word_end(text, pos)
    if we <= pos then return end
    kill(w, left(text, pos), right(text, we), pos)
end
function B.yank(w)
    if not _M.kill_ring or _M.kill_ring == "" then return end
    local _, text, pos = state(w)
    local ins = _M.kill_ring
    set(w, left(text, pos) .. ins .. right(text, pos), pos + (utf8.len(ins) or #ins))
end
function B.paste_primary(w)
    local ok, sel = pcall(function() return luakit.selection.primary end)
    if not ok or type(sel) ~= "string" or sel == "" then return end
    sel = sel:gsub("[\r\n]+", " ")
    local _, text, pos = state(w)
    set(w, left(text, pos) .. sel .. right(text, pos), pos + (utf8.len(sel) or #sel))
end
function B.transpose_chars(w)
    local _, text, pos, len = state(w)
    if len < 2 then return end
    if pos >= len then pos = len - 1 end
    if pos < 1 then return end
    local a = text:sub(byte_at(text, pos - 1), byte_at(text, pos) - 1)
    local b = text:sub(byte_at(text, pos), byte_at(text, pos + 1) - 1)
    set(w, left(text, pos - 1) .. b .. a .. right(text, pos + 1), pos + 1)
end

_M.actions = B

_M.bindings = {
    { "<Control-a>",     "Move to the start of the line.",          B.beginning_of_line },
    { "<Control-e>",     "Move to the end of the line.",            B.end_of_line },
    { "<Control-b>",     "Move one character left.",                B.backward_char },
    { "<Control-f>",     "Move one character right.",               B.forward_char },
    { "<Mod1-b>",        "Move one word left.",                     B.backward_word },
    { "<Mod1-f>",        "Move one word right.",                    B.forward_word },
    { "<Control-d>",     "Delete the character under the cursor.",  B.delete_char },
    { "<Control-h>",     "Delete the character before the cursor.", B.backward_delete_char },
    { "<Control-k>",     "Delete to the end of the line.",          B.kill_line },
    { "<Control-u>",     "Delete to the start of the line.",        B.unix_line_discard },
    { "<Control-w>",     "Delete the word before the cursor.",      B.unix_word_rubout },
    { "<Mod1-BackSpace>", "Delete the word before the cursor.",     B.unix_word_rubout },
    { "<Mod1-d>",        "Delete the word after the cursor.",       B.kill_word },
    { "<Control-y>",     "Insert the last deleted text.",           B.yank },
    { "<Control-t>",     "Swap the two characters around the cursor.", B.transpose_chars },
    { "<Shift-Insert>",  "Insert the primary selection.",           B.paste_primary },
}

_M.modes = { "command", "search", "lua", "completion" }

function _M.install(mode_list)
    mode_list = mode_list or _M.modes
    for _, name in ipairs(mode_list) do
        if not modes.get_mode(name) then modes.new_mode(name, nil, { has_input = true }) end
    end
    modes.add_binds(mode_list, _M.bindings)
end

_M.install()

-- 新增的带输入栏模式自动获得这些绑定
modes.add_signal("mode-added", function(name, m)
    if m.has_input then
        local known = false
        for _, n in ipairs(_M.modes) do if n == name then known = true end end
        if not known then
            _M.modes[#_M.modes + 1] = name
            modes.add_binds(name, _M.bindings)
        end
    end
end)

return _M
