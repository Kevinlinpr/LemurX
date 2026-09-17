-- LemurX · luakit-compatible library · taborder
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "taborder" module API. No luakit code is used.
--
-- 新标签插到哪：每个函数接受 (w, newview) 返回 1 起的插入位置。
--   taborder.first / last / after_current / before_current / by_origin
--   taborder.default     前台打开时用（默认 after_current）
--   taborder.default_bg  后台打开时用（默认 by_origin：跟在同一父标签的一串子标签之后）
--   taborder.kidsof      弱表：父 view → { 子 view … }，by_origin 用它记谱系
-- w:new_tab(arg, { order = fn }) 可指定单次的顺序函数。

local _M = {}

_M.kidsof = setmetatable({}, { __mode = "k" })

local function count(w) return w.tabs:count() end
local function current(w)
    local c = w.tabs:current()
    if not c or c < 1 then c = count(w) end
    return c
end

function _M.first() return 1 end
function _M.last(w) return count(w) + 1 end
function _M.after_current(w)
    if count(w) == 0 then return 1 end
    return current(w) + 1
end
function _M.before_current(w)
    if count(w) == 0 then return 1 end
    return current(w)
end

local function alive(v) return v and v.is_alive end

-- 递归找 view 及其后代里最靠后的标签位置
local function last_descendant_index(w, view, depth)
    local best = w.tabs:indexof(view) or 0
    if depth > 32 then return best end
    for _, kid in ipairs(_M.kidsof[view] or {}) do
        if alive(kid) then
            local i = last_descendant_index(w, kid, depth + 1)
            if i > best then best = i end
        end
    end
    return best
end

function _M.by_origin(w, newview)
    local parent = w.view
    if not alive(parent) or count(w) == 0 then return _M.last(w) end
    local kids = _M.kidsof[parent]
    if not kids then
        kids = {}
        _M.kidsof[parent] = kids
    end
    -- 清掉已死的孩子
    for i = #kids, 1, -1 do
        if not alive(kids[i]) then table.remove(kids, i) end
    end
    local pos = last_descendant_index(w, parent, 0) + 1
    if newview then kids[#kids + 1] = newview end
    return pos
end

_M.default = _M.after_current
_M.default_bg = _M.by_origin
_M.bgdefault = _M.by_origin

return _M
