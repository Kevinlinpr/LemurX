-- LemurX · luakit-compatible library · lousy.bind
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.bind" module API. No luakit code is used.
--
-- 按键 / 缓冲序列 / 命令 / 鼠标按钮 绑定的解析与匹配。
--
-- 绑定描述串（trigger）的形态：
--   "<Control-x>" "<Mod1-Return>" "<Escape>" "<Shift-Tab>"   → 按键绑定
--   "x" "G" ":" "/"（单个字符）                                → 无修饰键的按键绑定（大写字母自带 Shift 语义）
--   "gg" "ZZ" "gt"                                           → 缓冲序列绑定（字面量）
--   "^%d*gg$"（以 ^ 开头）                                    → 缓冲序列绑定（Lua 模式）
--   ":open, :o"                                              → 命令绑定（可多个别名）
--   "<Mouse1>" "<Control-Mouse2>"                            → 鼠标按钮绑定
--   "<any>"                                                  → 任意键绑定
--
-- 绑定表里每一项可以是：
--   { trigger, desc, fn, opts }           modes.add_binds 的四元组（desc 可省略）
--   { bind = trigger, action = fn|{func=,desc=}, opts = {} }
--   parse_bind()/key()/buf()/cmd()/but()/any() 产出的内部表
--
-- 回调签名统一为 fn(object, opts, args)：opts 是绑定时选项与调用时参数的合并
-- （再叠加 count / buffer / arg / bang / cmd），args 是调用时参数原表。
-- 回调返回 false 表示“当我没绑定”，继续匹配下一条；其他返回值终止匹配。

local util = require("lousy.util")

local M = {}

local BUFFER_LIMIT = 10

-- =====================================================================
-- 修饰键
-- =====================================================================

-- 别名 → 规范名
M.mod_map = {
    Control = "Control", C = "Control", Ctrl = "Control", ctrl = "Control", control = "Control",
    Shift = "Shift", S = "Shift", shift = "Shift",
    Mod1 = "Mod1", M = "Mod1", A = "Mod1", Alt = "Mod1", alt = "Mod1",
    Mod2 = "Mod2", Mod3 = "Mod3", Mod4 = "Mod4", Mod5 = "Mod5",
    Super = "Super", Win = "Super", super = "Super",
    Hyper = "Hyper", Meta = "Meta", Lock = "Lock",
}

-- 拼接顺序
local mod_rank = {
    Control = 1, Shift = 2, Mod1 = 3, Mod2 = 4, Mod3 = 5, Mod4 = 6, Mod5 = 7,
    Super = 8, Hyper = 9, Meta = 10, Lock = 11,
}

-- 匹配时忽略的修饰键（NumLock / CapsLock）。列表与集合两种写法都支持。
M.ignore_modifiers = { "Mod2", "Lock" }
M.ignore_mask = { Mod2 = true, Lock = true }

-- 键名别名
M.map = {
    ISO_Left_Tab = "Tab",
    KP_Enter = "Return",
    KP_Tab = "Tab",
}

-- 按下修饰键本身产生的键名：不该影响缓冲
local modifier_key_names = {
    Shift_L = true, Shift_R = true, Control_L = true, Control_R = true,
    Alt_L = true, Alt_R = true, Meta_L = true, Meta_R = true, Super_L = true, Super_R = true,
    Hyper_L = true, Hyper_R = true, Caps_Lock = true, Num_Lock = true, Scroll_Lock = true,
    ISO_Level3_Shift = true, ISO_Level5_Shift = true, Mode_switch = true,
}

local function is_ignored(m)
    if M.ignore_mask and M.ignore_mask[m] then return true end
    if M.ignore_modifiers then
        for _, x in ipairs(M.ignore_modifiers) do
            if x == m then return true end
        end
    end
    return false
end

local function canon_mod(m, strict)
    local c = M.mod_map[m]
    if c then return c end
    if mod_rank[m] then return m end
    if strict then error(("lousy.bind: unknown modifier %q"):format(tostring(m)), 3) end
    return m
end

-- 修饰键列表/字串 → 集合（已做别名映射与忽略过滤）
local function mods_to_set(mods, strict)
    local set = {}
    local function add(m)
        if type(m) ~= "string" or m == "" then return end
        m = canon_mod(m, strict)
        if not is_ignored(m) then set[m] = true end
    end
    if type(mods) == "string" then
        for m in mods:gmatch("[^%-%s,]+") do add(m) end
    elseif type(mods) == "table" then
        for k, v in pairs(mods) do
            if type(k) == "number" then add(v) elseif v then add(k) end
        end
    end
    return set
end

local function set_to_string(set)
    local list = {}
    for m in pairs(set) do list[#list + 1] = m end
    table.sort(list, function(a, b)
        local ra, rb = mod_rank[a] or 99, mod_rank[b] or 99
        if ra ~= rb then return ra < rb end
        return a < b
    end)
    return table.concat(list, "-")
end

-- 单字符键：大小写本身就表达了 Shift，所以去掉 Shift 并把小写字母提升为大写
local function canon_key(key, set)
    if type(key) ~= "string" then key = tostring(key) end
    key = M.map[key] or key
    if utf8.len(key) == 1 and set.Shift then
        set.Shift = nil
        if key:match("^%l$") then key = key:upper() end
    end
    return key
end

function M.parse_mods(mods, remove_shift)
    local set = mods_to_set(mods, false)
    if remove_shift then set.Shift = nil end
    return set_to_string(set)
end

-- =====================================================================
-- 解析 trigger
-- =====================================================================

local function split_bracket(inner)
    -- 用 "-" 切分，但允许键本身就是 "-"（"<Control-->"）
    local parts = {}
    local rest = inner
    while true do
        local head, tail = rest:match("^([^%-]+)%-(.+)$")
        if head and tail ~= "" then
            parts[#parts + 1] = head
            rest = tail
        else
            break
        end
    end
    return parts, rest
end

function M.parse_bind(str)
    if type(str) ~= "string" or str == "" then
        error("lousy.bind.parse_bind: trigger must be a non-empty string", 2)
    end
    local b = { __bind = true, str = str }
    local inner = str:match("^<(.+)>$")
    if inner then
        if inner:lower() == "any" then
            b.type = "any"
            return b
        end
        local modlist, key = split_bracket(inner)
        local set = mods_to_set(modlist, true)
        local button = key:match("^Mouse(%d+)$")
        if button then
            b.type = "but"
            b.button = tonumber(button)
            b.mods = set_to_string(set)
            return b
        end
        b.type = "key"
        b.key = canon_key(key, set)
        b.mods = set_to_string(set)
        return b
    end
    if utf8.len(str) == 1 then
        b.type = "key"
        b.key = str
        b.mods = ""
        return b
    end
    if str:sub(1, 1) == ":" then
        local cmds = {}
        for tok in str:gmatch("[^,]+") do
            tok = util.string.strip(tok)
            if tok:sub(1, 1) ~= ":" or #tok < 2 then
                error(("lousy.bind.parse_bind: bad command trigger %q"):format(str), 2)
            end
            cmds[#cmds + 1] = tok:sub(2)
        end
        b.type = "cmd"
        b.cmds = cmds
        return b
    end
    b.type = "buf"
    if str:sub(1, 1) == "^" then
        b.pattern = str
    else
        b.literal = str
        b.pattern = "^" .. util.lua_escape(str) .. "$"
    end
    return b
end

-- =====================================================================
-- 绑定项规范化
-- =====================================================================

local raw_cache = setmetatable({}, { __mode = "k" })

local function attach_action(b, action, desc, opts)
    if type(action) == "function" then
        b.func = action
    elseif type(action) == "table" then
        b.func = action.func or action[1]
        desc = desc or action.desc or action[2]
    end
    b.action = action
    b.desc = desc
    b.opts = type(opts) == "table" and opts or {}
    return b
end

local function norm(b)
    if type(b) ~= "table" then return nil end
    if b.__bind then return b end
    local cached = raw_cache[b]
    if type(b[1]) == "string" then
        local desc, fn, opts = b[2], b[3], b[4]
        if type(desc) == "function" then
            desc, fn, opts = nil, desc, fn
        end
        if cached and cached.str == b[1] and cached.func == fn and cached.opts == (opts or cached.opts) then
            return cached
        end
        local ok, p = pcall(M.parse_bind, b[1])
        if not ok then return nil end
        attach_action(p, fn, desc, opts)
        raw_cache[b] = p
        return p
    end
    if type(b.bind) == "string" then
        if cached and cached.str == b.bind and cached.action == b.action then return cached end
        local ok, p = pcall(M.parse_bind, b.bind)
        if not ok then return nil end
        attach_action(p, b.action, b.desc, b.opts)
        raw_cache[b] = p
        return p
    end
    if type(b.type) == "string" and type(b.func) == "function" then
        b.__bind = true
        b.opts = b.opts or {}
        return b
    end
    return nil
end
M.normalize = norm

local function each_bind(binds)
    local i = 0
    return function()
        while true do
            i = i + 1
            local raw = binds[i]
            if raw == nil then return nil end
            local b = norm(raw)
            if b and type(b.func) == "function" then return b, raw, i end
        end
    end
end

-- 调用回调：opts = 绑定时选项 ⊕ 调用时参数 ⊕ extra
local function invoke(b, object, args, extra)
    local merged = util.table.join(b.opts, args)
    if extra then
        for k, v in pairs(extra) do merged[k] = v end
    end
    local ret = b.func(object, merged, args or {})
    return ret ~= false
end

-- =====================================================================
-- 匹配
-- =====================================================================

function M.match_any(object, binds, args)
    for b in each_bind(binds) do
        if b.type == "any" and invoke(b, object, args) then return true end
    end
    return false
end

function M.match_key(object, binds, mods, key, args)
    local set = mods_to_set(mods, false)
    key = canon_key(key, set)
    local modstr = set_to_string(set)
    for b in each_bind(binds) do
        if b.type == "key" and b.key == key and b.mods == modstr then
            if invoke(b, object, args) then return true end
        end
    end
    return false
end

function M.match_but(object, binds, mods, button, args)
    local set = mods_to_set(mods, false)
    local modstr = set_to_string(set)
    if type(button) == "string" then button = tonumber(button:match("(%d+)$")) end
    for b in each_bind(binds) do
        if b.type == "but" and b.button == button and b.mods == modstr then
            if invoke(b, object, args) then return true end
        end
    end
    return false
end

-- ---- 缓冲模式的“前缀可能匹配”判定 ----

-- 把 Lua 模式切成最小单元（每个单元带自己的量词），括号单独成项
local function pattern_items(pat)
    local body = pat
    if body:sub(1, 1) == "^" then body = body:sub(2) end
    if body:sub(-1) == "$" and body:sub(-2, -2) ~= "%" then body = body:sub(1, -2) end
    local items = {}
    local i, n = 1, #body
    while i <= n do
        local c = body:sub(i, i)
        local text
        if c == "(" or c == ")" then
            items[#items + 1] = { text = c, paren = c }
            i = i + 1
        else
            if c == "%" then
                local nxt = body:sub(i + 1, i + 1)
                if nxt == "b" then
                    text = body:sub(i, i + 3)
                    i = i + 4
                elseif nxt == "f" then
                    local close = body:find("]", i + 3, true) or n
                    text = body:sub(i, close)
                    i = close + 1
                else
                    text = body:sub(i, i + 1)
                    i = i + 2
                end
            elseif c == "[" then
                local j = i + 1
                if body:sub(j, j) == "^" then j = j + 1 end
                if body:sub(j, j) == "]" then j = j + 1 end
                while j <= n and body:sub(j, j) ~= "]" do
                    if body:sub(j, j) == "%" then j = j + 1 end
                    j = j + 1
                end
                text = body:sub(i, j)
                i = j + 1
            else
                text = c
                i = i + 1
            end
            local q = body:sub(i, i)
            if q == "*" or q == "+" or q == "-" or q == "?" then
                text = text .. q
                i = i + 1
            end
            items[#items + 1] = { text = text }
        end
    end
    return items
end

local function is_partial(b, buffer)
    if b.literal then
        return #buffer < #b.literal and b.literal:sub(1, #buffer) == buffer
    end
    local items = b._items
    if not items then
        items = pattern_items(b.pattern)
        b._items = items
    end
    -- 逐个前缀试匹配：任意一个前缀能整体匹配当前缓冲，就说明还可能继续
    local prefix = { "^" }
    local depth = 0
    for k = 1, #items - 1 do
        local it = items[k]
        prefix[#prefix + 1] = it.text
        if it.paren == "(" then depth = depth + 1 elseif it.paren == ")" then depth = depth - 1 end
        local pat = table.concat(prefix) .. string.rep(")", math.max(depth, 0)) .. "$"
        local ok, hit = pcall(string.match, buffer, pat)
        if ok and hit ~= nil then return true end
    end
    return false
end

function M.match_buf(object, binds, buffer, args)
    local partial = false
    for b in each_bind(binds) do
        if b.type == "buf" then
            local ok, caps = pcall(function() return table.pack(buffer:match(b.pattern)) end)
            if ok and caps.n > 0 and caps[1] ~= nil then
                if invoke(b, object, args, { buffer = buffer }) then return true, false end
            elseif ok and is_partial(b, buffer) then
                partial = true
            end
        end
    end
    return false, partial
end

function M.match_cmd(object, binds, buffer, args)
    if type(buffer) ~= "string" then return false end
    local text = buffer
    if text:sub(1, 1) == ":" then text = text:sub(2) end
    text = text:gsub("^%s+", "")
    local name, bang, arg = text:match("^([^%s!]+)(!?)%s*(.-)%s*$")
    if name then
        for b in each_bind(binds) do
            if b.type == "cmd" and util.table.hasitem(b.cmds, name) then
                local extra = {
                    cmd = name,
                    bang = bang == "!",
                    arg = arg ~= "" and arg or nil,
                    buffer = buffer,
                }
                if invoke(b, object, args, extra) then return true end
            end
        end
    end
    -- 命令行里也允许缓冲模式（例如 "^%d+$" 跳到第 n 个标签）
    local matched = M.match_buf(object, binds, text, args)
    return matched == true
end

-- =====================================================================
-- hit：按键分发 + 缓冲维护
-- =====================================================================

function M.hit(object, binds, mods, key, args)
    args = args or {}
    local set = mods_to_set(mods, false)
    key = canon_key(key, set)
    local modstr = set_to_string(set)
    local buffer = type(args.buffer) == "string" and args.buffer or ""

    -- 纯修饰键按下：什么都不做，缓冲原样保留
    if modifier_key_names[key] then
        return false, buffer
    end

    -- 缓冲里的数字前缀作为 count
    local count = tonumber(buffer:match("^(%d+)"))
    local a = util.table.clone(args)
    if count then a.count = count end

    if M.match_any(object, binds, a) then return true, "" end
    if M.match_key(object, binds, modstr, key, a) then return true, "" end

    if args.enable_buffer and modstr == "" and utf8.len(key) == 1 then
        local newbuf = buffer .. key
        if #newbuf > BUFFER_LIMIT then return false, "" end
        local matched, partial = M.match_buf(object, binds, newbuf, a)
        if matched then return true, "" end
        local digits, rest = newbuf:match("^(%d+)(.*)$")
        if digits then
            if rest == "" then
                -- 只有数字：等待后续按键
                partial = true
            else
                local a2 = util.table.clone(args)
                a2.count = tonumber(digits)
                local m2, p2 = M.match_buf(object, binds, rest, a2)
                if m2 then return true, "" end
                partial = partial or p2
            end
        end
        if partial then return true, newbuf end
        return false, ""
    end

    return false, ""
end

-- =====================================================================
-- 描述 / 增删改
-- =====================================================================

function M.bind_to_string(b)
    local p = norm(b)
    if not p then
        if type(b) == "table" and type(b[1]) == "string" then return b[1] end
        if type(b) == "string" then return b end
        return nil
    end
    if p.type == "key" then
        if p.mods == "" then
            if utf8.len(p.key) == 1 then return p.key end
            return "<" .. p.key .. ">"
        end
        return "<" .. p.mods .. "-" .. p.key .. ">"
    elseif p.type == "buf" then
        return p.literal or p.pattern
    elseif p.type == "cmd" then
        return ":" .. table.concat(p.cmds, ", :")
    elseif p.type == "but" then
        local mods = p.mods ~= "" and (p.mods .. "-") or ""
        return "<" .. mods .. "Mouse" .. tostring(p.button) .. ">"
    elseif p.type == "any" then
        return "<any>"
    end
    return p.str
end

local function same_trigger(a, b)
    return M.bind_to_string(a) == M.bind_to_string(b)
end

function M.remove_bind(binds, bind)
    local target = type(bind) == "string" and M.parse_bind(bind) or norm(bind)
    if not target then return nil end
    local action, opts
    local i = 1
    while i <= #binds do
        local p = norm(binds[i])
        if p and same_trigger(p, target) then
            if action == nil then
                action = p.action or p.func
                opts = p.opts
            end
            table.remove(binds, i)
        else
            i = i + 1
        end
    end
    return action, opts
end

function M.add_bind(binds, bind, action, opts)
    if type(binds) ~= "table" then
        error("lousy.bind.add_bind: binds must be a table", 2)
    end
    local p = M.parse_bind(bind)
    attach_action(p, action, nil, opts)
    if type(p.func) ~= "function" then
        error(("lousy.bind.add_bind: no action function for %q"):format(bind), 2)
    end
    M.remove_bind(binds, p)
    binds[#binds + 1] = p
    return p
end

function M.remap_bind(binds, new, old, keep)
    local target = M.parse_bind(old)
    local found
    for b in each_bind(binds) do
        if same_trigger(b, target) then
            found = b
            break
        end
    end
    if not found then return nil end
    local p = M.add_bind(binds, new, found.action or found.func, found.opts)
    p.desc = p.desc or found.desc
    if not keep then M.remove_bind(binds, old) end
    return p
end

-- 找到某个 trigger 对应的绑定（规范化后的表）
function M.find_bind(binds, bind)
    local target = type(bind) == "string" and M.parse_bind(bind) or norm(bind)
    if not target then return nil end
    for b, raw, i in each_bind(binds) do
        if same_trigger(b, target) then return b, raw, i end
    end
    return nil
end

-- =====================================================================
-- 构造器（旧式 API）
-- =====================================================================

local function shift_desc(desc, func, opts)
    if type(desc) == "function" then return nil, desc, func end
    return desc, func, opts
end

function M.key(mods, key, desc, func, opts)
    desc, func, opts = shift_desc(desc, func, opts)
    local set = mods_to_set(mods, true)
    local b = { __bind = true, type = "key" }
    b.key = canon_key(key, set)
    b.mods = set_to_string(set)
    attach_action(b, func, desc, opts)
    b.str = M.bind_to_string(b)
    return b
end

function M.buf(pattern, desc, func, opts)
    desc, func, opts = shift_desc(desc, func, opts)
    local b = { __bind = true, type = "buf" }
    if pattern:sub(1, 1) == "^" then
        b.pattern = pattern
    else
        b.literal = pattern
        b.pattern = "^" .. util.lua_escape(pattern) .. "$"
    end
    attach_action(b, func, desc, opts)
    b.str = pattern
    return b
end

function M.cmd(cmds, desc, func, opts)
    desc, func, opts = shift_desc(desc, func, opts)
    local list = {}
    if type(cmds) == "string" then
        for tok in cmds:gmatch("[^,%s]+") do
            list[#list + 1] = (tok:gsub("^:", ""))
        end
    elseif type(cmds) == "table" then
        for _, c in ipairs(cmds) do list[#list + 1] = (tostring(c):gsub("^:", "")) end
    end
    if #list == 0 then error("lousy.bind.cmd: no command names given", 2) end
    local b = { __bind = true, type = "cmd", cmds = list }
    attach_action(b, func, desc, opts)
    b.str = M.bind_to_string(b)
    return b
end

function M.but(mods, button, desc, func, opts)
    desc, func, opts = shift_desc(desc, func, opts)
    local set = mods_to_set(mods, true)
    if type(button) == "string" then button = tonumber(button:match("(%d+)$")) end
    local b = { __bind = true, type = "but", button = button, mods = set_to_string(set) }
    attach_action(b, func, desc, opts)
    b.str = M.bind_to_string(b)
    return b
end

function M.any(desc, func, opts)
    desc, func, opts = shift_desc(desc, func, opts)
    local b = { __bind = true, type = "any", str = "<any>" }
    attach_action(b, func, desc, opts)
    return b
end

return M
