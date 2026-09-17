-- LemurX · luakit-compatible library · follow_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "follow_wm" module API. No luakit code is used.
--
-- follow 模式的渲染进程半边：在 select_wm 之上包一层 IPC 协议，并提供
-- 对被选元素的"求值器"（click / focus / uri / desc / src）。
--
-- IPC (follow_wm)：
--   ← enter(page, opts)      opts = { selector = css, evaluator = name, ignore_case = bool,
--                                     theme = {hint_*}, stylesheet = css }
--   ← changed(page, label_pat, text_pat)   两个 Lua pattern（可空）
--   ← focus(page, step)
--   ← follow(page)
--   ← leave(page)
--   → matches(page_id, count)
--   → follow_func(page_id, evaluator, result)

local select_wm = require("select_wm")

local ui = ipc_channel("follow_wm")

local M = {}

local sessions = {}   -- page -> { evaluator = name }

-- ---------------------------------------------------------------------------
-- URI 解析（相对 → 绝对）
-- ---------------------------------------------------------------------------
local function resolve_uri(base, rel)
    if type(rel) ~= "string" or rel == "" then return rel end
    if rel:match("^%a[%w+.-]*:") then return rel end
    base = tostring(base or "")
    local scheme, authority, path = base:match("^(%a[%w+.-]*)://([^/?#]*)([^?#]*)")
    if not scheme then return rel end
    if rel:sub(1, 2) == "//" then return scheme .. ":" .. rel end
    if rel:sub(1, 1) == "/" then return scheme .. "://" .. authority .. rel end
    if rel:sub(1, 1) == "?" then return scheme .. "://" .. authority .. (path ~= "" and path or "/") .. rel end
    if rel:sub(1, 1) == "#" then
        local nofrag = base:match("^([^#]*)")
        return nofrag .. rel
    end
    local dir = path:match("^(.*/)") or "/"
    local joined = dir .. rel
    -- 归一化 ./ 与 ../
    local segs = {}
    for seg in joined:gmatch("[^/]+") do
        if seg == ".." then
            if #segs > 0 then table.remove(segs) end
        elseif seg ~= "." then
            segs[#segs + 1] = seg
        end
    end
    local trailing = joined:sub(-1) == "/" and "/" or ""
    return scheme .. "://" .. authority .. "/" .. table.concat(segs, "/") .. trailing
end
M.resolve_uri = resolve_uri

-- ---------------------------------------------------------------------------
-- 求值器
-- ---------------------------------------------------------------------------
local NON_TEXT_INPUT = {
    button = true, submit = true, reset = true, checkbox = true, radio = true, image = true,
    file = true, hidden = true, color = true, range = true,
}

local function lower(s) return type(s) == "string" and s:lower() or "" end

local function is_text_input(el)
    local tag = lower(el.tag_name)
    if tag == "textarea" then return true end
    if tag == "input" then
        local ty = lower(el.type)
        return not NON_TEXT_INPUT[ty]
    end
    local ok, ce = pcall(function() return el.attr.contenteditable end)
    if ok and ce and ce ~= "false" then return true end
    return false
end

local function first_attr(el, names)
    for _, n in ipairs(names) do
        local ok, v = pcall(function() return el.attr[n] end)
        if ok and type(v) == "string" and v ~= "" then return v end
    end
    return nil
end

local evaluators = {}

function evaluators.click(el, page)
    if is_text_input(el) then
        el:focus()
        return "form-active"
    end
    local tag = lower(el.tag_name)
    if tag == "select" then
        el:focus()
        return "form-active"
    end
    el:click()
    return "root-active"
end

function evaluators.focus(el)
    el:focus()
    if is_text_input(el) then return "form-active" end
    return "root-active"
end

function evaluators.uri(el, page)
    local href = first_attr(el, { "href", "src", "data-href", "action" })
    if not href then
        -- 元素本身没有链接：向上找最近的 a
        local cur = el
        for _ = 1, 8 do
            local ok, parent = pcall(function() return cur.parent end)
            if not ok or not parent then break end
            local h = first_attr(parent, { "href" })
            if h then href = h break end
            cur = parent
        end
    end
    if not href then return nil end
    return resolve_uri(page.uri, href)
end

function evaluators.desc(el)
    local d = first_attr(el, { "title", "alt", "aria-label" })
    if d then return d end
    local ok, t = pcall(function() return el.text_content end)
    if ok and type(t) == "string" then
        t = t:gsub("^%s+", ""):gsub("%s+$", ""):gsub("%s+", " ")
        if t ~= "" then return t end
    end
    return first_attr(el, { "href", "src" })
end

function evaluators.src(el, page)
    local src = first_attr(el, { "src", "data-src", "href" })
    if not src then
        -- a > img 的情形：从子元素找
        local ok, imgs = pcall(function() return el:query("img") end)
        if ok and imgs and imgs[1] then src = first_attr(imgs[1], { "src" }) end
    end
    if not src then return nil end
    return resolve_uri(page.uri, src)
end

M.evaluators = evaluators

-- ---------------------------------------------------------------------------
-- 协议
-- ---------------------------------------------------------------------------
local function report_matches(page, count)
    ui:emit_signal("matches", page, count)
end

local function do_follow(page)
    local s = sessions[page]
    local hint = select_wm.focused(page)
    if not hint then
        report_matches(page, 0)
        return false
    end
    local name = (s and s.evaluator) or "click"
    local ev = evaluators[name] or evaluators.click
    local ok, result = pcall(ev, hint.elem, page)
    if not ok then
        msg.warn("follow_wm: evaluator %s failed: %s", name, tostring(result))
        result = nil
    end
    ui:emit_signal("follow_func", page, name, result)
    return true
end

function M.enter(page, opts)
    opts = opts or {}
    sessions[page] = { evaluator = opts.evaluator or "click" }
    local count = select_wm.enter(page, {
        selector = opts.selector,
        ignore_case = opts.ignore_case,
        theme = opts.theme,
        stylesheet = opts.stylesheet,
    })
    report_matches(page, count)
    return count
end

function M.changed(page, label_pat, text_pat)
    if not select_wm.active(page) then return 0 end
    local count, single = select_wm.changed(page, label_pat, text_pat)
    report_matches(page, count)
    local typed = (label_pat and label_pat ~= "") or (text_pat and text_pat ~= "")
    if count == 1 and single and typed then
        -- 唯一命中：直接跟随，不必再按回车
        do_follow(page)
    end
    return count
end

function M.focus(page, step)
    select_wm.focus(page, step or 1)
end

function M.follow(page)
    return do_follow(page)
end

function M.leave(page)
    sessions[page] = nil
    select_wm.leave(page)
end

ui:add_signal("enter", function(_, page, opts) if page then M.enter(page, opts) end end)
ui:add_signal("changed", function(_, page, label_pat, text_pat) if page then M.changed(page, label_pat, text_pat) end end)
ui:add_signal("focus", function(_, page, step) if page then M.focus(page, step) end end)
ui:add_signal("follow", function(_, page) if page then M.follow(page) end end)
ui:add_signal("leave", function(_, page) if page then M.leave(page) end end)

luakit.add_signal("page-created", function(p)
    p:add_signal("destroy", function() sessions[p] = nil end)
end)

return M
