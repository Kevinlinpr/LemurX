-- LemurX · luakit-compatible library · select_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "select_wm" module API. No luakit code is used.
--
-- 渲染进程侧的"元素提示"（hint）引擎：枚举可交互元素、生成标签、在 document.body
-- 上绘制绝对定位的提示层、按输入过滤、移动焦点、清理。follow_wm 等模块在其上
-- 构建自己的 IPC 协议。本文件不依赖浏览器侧模块。
--
-- 标签生成器（label composer）：charset(str) numbers() interleave(l, r)
--   reverse(f) sort(f) trim(f)；默认 label_maker = trim(sort(reverse(numbers())))
-- IPC (select_wm)：← set_label_maker({ kind = "source"|"dump", data = str })

local M = {}

-- ---------------------------------------------------------------------------
-- 标签生成
-- ---------------------------------------------------------------------------
local function chars_of(str)
    local out = {}
    for _, cp in utf8.codes(str) do out[#out + 1] = utf8.char(cp) end
    return out
end

local function utf8_reverse(s)
    local cs = chars_of(s)
    local out = {}
    for i = #cs, 1, -1 do out[#out + 1] = cs[i] end
    return table.concat(out)
end

-- 用给定字母表（每个位置可用不同字母表）生成 n 个等长标签
local function enumerate(alphabets, n)
    if n <= 0 then return {} end
    -- 最短长度 L：∏ |alphabet_i| ≥ n
    local len, total = 0, 1
    while total < n do
        len = len + 1
        total = total * #alphabets[((len - 1) % #alphabets) + 1]
        if len > 32 then break end
    end
    if len == 0 then len = 1 end
    local labels = {}
    local digits = {}
    for i = 1, len do digits[i] = 1 end
    for _ = 1, n do
        local parts = {}
        for i = 1, len do
            local set = alphabets[((i - 1) % #alphabets) + 1]
            parts[i] = set[digits[i]]
        end
        labels[#labels + 1] = table.concat(parts)
        -- 进位（最右位最快变化）
        local i = len
        while i >= 1 do
            local set = alphabets[((i - 1) % #alphabets) + 1]
            digits[i] = digits[i] + 1
            if digits[i] <= #set then break end
            digits[i] = 1
            i = i - 1
        end
    end
    return labels
end

local composers = {}

function composers.charset(str)
    local set = chars_of(str)
    if #set < 2 then error("charset needs at least two characters", 2) end
    return function(n) return enumerate({ set }, n) end
end

-- 数字标签从 1 开始，宽度按 n 补零
function composers.numbers()
    return function(n)
        if n <= 0 then return {} end
        local width = #tostring(n)
        if width < 2 then width = 2 end
        local out = {}
        for i = 1, n do out[i] = string.format("%0" .. width .. "d", i) end
        return out
    end
end

function composers.interleave(left, right)
    local l, r = chars_of(left), chars_of(right)
    if #l == 0 or #r == 0 then error("interleave needs two non-empty strings", 2) end
    return function(n) return enumerate({ l, r }, n) end
end

function composers.reverse(f)
    return function(n)
        local labels = f(n)
        for i, s in ipairs(labels) do labels[i] = utf8_reverse(s) end
        return labels
    end
end

function composers.sort(f)
    return function(n)
        local labels = f(n)
        table.sort(labels)
        return labels
    end
end

-- 把每个标签缩短到在集合内唯一的最短前缀
function composers.trim(f)
    return function(n)
        local labels = f(n)
        local count = {}
        local split = {}
        for i, s in ipairs(labels) do
            local cs = chars_of(s)
            split[i] = cs
            local prefix = ""
            for _, c in ipairs(cs) do
                prefix = prefix .. c
                count[prefix] = (count[prefix] or 0) + 1
            end
        end
        for i, cs in ipairs(labels) do
            local prefix = ""
            for _, c in ipairs(split[i]) do
                prefix = prefix .. c
                if count[prefix] == 1 then break end
            end
            labels[i] = prefix
        end
        return labels
    end
end

M.composers = composers

local function default_label_maker(s)
    return s.trim(s.sort(s.reverse(s.numbers())))
end

M.label_maker = default_label_maker

local maker_env = setmetatable({}, { __index = function(_, k)
    local c = composers[k]
    if c ~= nil then return c end
    local allowed = { string = string, table = table, math = math, utf8 = utf8, ipairs = ipairs, pairs = pairs,
                      tostring = tostring, tonumber = tonumber, select = select, type = type, error = error }
    return allowed[k]
end })

-- 接受函数、Lua 源码字符串或 {kind=, data=}
function M.set_label_maker(spec)
    if type(spec) == "function" then
        M.label_maker = spec
        return true
    end
    if type(spec) == "string" then spec = { kind = "source", data = spec } end
    if type(spec) ~= "table" or type(spec.data) ~= "string" then return false, "bad label maker spec" end
    local chunk, err
    if spec.kind == "dump" then
        local bin = spec.data:gsub("%x%x", function(h) return string.char(tonumber(h, 16)) end)
        chunk, err = load(bin, "=label_maker", "b", maker_env)
    else
        local src = spec.data
        if not src:match("^%s*return") then src = "return " .. src end
        chunk, err = load(src, "=label_maker", "t", maker_env)
    end
    if not chunk then return false, err end
    local ok, fn = pcall(chunk)
    if not ok then return false, fn end
    if type(fn) ~= "function" then return false, "label maker did not produce a function" end
    M.label_maker = fn
    return true
end

function M.make_labels(n)
    local gen = M.label_maker(composers)
    local labels = gen(n)
    if #labels < n then
        -- 生成器给得不够：用序号补足
        for i = #labels + 1, n do labels[i] = tostring(i) end
    end
    return labels
end

-- ---------------------------------------------------------------------------
-- 提示层
-- ---------------------------------------------------------------------------
local states = {}   -- page -> state
local OVERLAY_ID = "luakit_select_overlay"

local DEFAULT_THEME = {
    hint_font = "11px monospace",
    hint_fg = "#111",
    hint_bg = "#ffd54a",
    hint_border = "1px solid #a67c00",
    hint_opacity = "0.92",
    hint_overlay_bg = "rgba(255, 213, 74, 0.25)",
    hint_overlay_border = "1px dotted #a67c00",
    hint_overlay_selected_bg = "rgba(60, 170, 255, 0.35)",
    hint_overlay_selected_border = "1px solid #1e6fd9",
}

local function theme_get(theme, k)
    local v = theme and theme[k]
    if v == nil or v == "" then return DEFAULT_THEME[k] end
    return tostring(v)
end

local function trim(s)
    return (tostring(s or ""):gsub("^%s+", ""):gsub("%s+$", ""):gsub("%s+", " "))
end

local function element_text(el)
    local ok, v
    ok, v = pcall(function() return el.value end)
    if ok and type(v) == "string" and v ~= "" then return trim(v) end
    ok, v = pcall(function() return el.text_content end)
    if ok and type(v) == "string" and trim(v) ~= "" then return trim(v) end
    for _, a in ipairs({ "aria-label", "title", "alt", "placeholder", "href", "src" }) do
        ok, v = pcall(function() return el.attr[a] end)
        if ok and type(v) == "string" and v ~= "" then return trim(v) end
    end
    return ""
end

local function element_rect(el)
    local ok, r = pcall(function() return el.rect end)
    if ok and type(r) == "table" then return r end
    return { left = 0, top = 0, width = 0, height = 0 }
end

local function in_viewport(rect, win)
    if not win then return true end
    local sx, sy = win.scroll_x or 0, win.scroll_y or 0
    local iw, ih = win.inner_width, win.inner_height
    if not (iw and ih) then return true end
    if rect.left + rect.width < sx or rect.left > sx + iw then return false end
    if rect.top + rect.height < sy or rect.top > sy + ih then return false end
    return true
end

local function collect_elements(doc, selector)
    local seen, out = {}, {}
    local list = {}
    -- 逗号分隔的选择器分开查询：结果可控且能去重
    for part in tostring(selector):gmatch("[^,]+") do
        part = trim(part)
        if part ~= "" then
            local ok, found = pcall(doc.query, doc, part)
            if ok and type(found) == "table" then
                for _, el in ipairs(found) do list[#list + 1] = el end
            end
        end
    end
    for _, el in ipairs(list) do
        if not seen[el] then
            seen[el] = true
            out[#out + 1] = el
        end
    end
    return out
end

local function style_string(t)
    local parts = {}
    for _, kv in ipairs(t) do parts[#parts + 1] = kv end
    return table.concat(parts, ";")
end

local function label_style(h, theme)
    return style_string({
        "position:absolute",
        ("left:%dpx"):format(math.floor(math.max(0, h.rect.left - 2))),
        ("top:%dpx"):format(math.floor(math.max(0, h.rect.top - 2))),
        "font:" .. theme_get(theme, "hint_font"),
        "color:" .. theme_get(theme, "hint_fg"),
        "background:" .. theme_get(theme, "hint_bg"),
        "border:" .. theme_get(theme, "hint_border"),
        "opacity:" .. theme_get(theme, "hint_opacity"),
        "padding:0 3px", "border-radius:3px", "line-height:1.3", "white-space:nowrap",
        "z-index:2147483647", "pointer-events:none",
    })
end

local function overlay_style(h, theme, selected)
    return style_string({
        "position:absolute",
        ("left:%dpx"):format(math.floor(h.rect.left)),
        ("top:%dpx"):format(math.floor(h.rect.top)),
        ("width:%dpx"):format(math.floor(h.rect.width)),
        ("height:%dpx"):format(math.floor(h.rect.height)),
        "background:" .. theme_get(theme, selected and "hint_overlay_selected_bg" or "hint_overlay_bg"),
        "border:" .. theme_get(theme, selected and "hint_overlay_selected_border" or "hint_overlay_border"),
        "box-sizing:border-box", "z-index:2147483646", "pointer-events:none",
    })
end

local function set_hint_visual(st, h, selected)
    local shown = h.visible
    local ls = label_style(h, st.theme) .. (shown and "" or ";display:none")
    local os = overlay_style(h, st.theme, selected) .. (shown and "" or ";display:none")
    pcall(function() h.label_el.attr.style = ls end)
    pcall(function() h.overlay_el.attr.style = os end)
    if selected then
        pcall(function() h.label_el.attr.class = "hint_label hint_selected" end)
    else
        pcall(function() h.label_el.attr.class = "hint_label" end)
    end
end

local function refresh_visuals(st)
    for i, h in ipairs(st.hints) do
        set_hint_visual(st, h, i == st.focused)
    end
end

function M.leave(page)
    local st = states[page]
    if not st then return end
    states[page] = nil
    pcall(function() if st.overlay then st.overlay:remove() end end)
    pcall(function() if st.style_el then st.style_el:remove() end end)
end

-- 进入提示模式。opts = { selector=, ignore_case=, theme=, stylesheet= }
-- 返回 可见提示数
function M.enter(page, opts)
    opts = opts or {}
    M.leave(page)
    local doc = page.document
    local body = doc.body
    if not body then return 0 end
    local win = doc.window
    local st = {
        page = page,
        hints = {},
        focused = nil,
        ignore_case = opts.ignore_case ~= false,
        theme = opts.theme or {},
        text = "",
    }
    local elements = collect_elements(doc, opts.selector or "a[href]")
    local candidates = {}
    for _, el in ipairs(elements) do
        local rect = element_rect(el)
        if (rect.width > 0 or rect.height > 0) and in_viewport(rect, win) then
            candidates[#candidates + 1] = { elem = el, rect = rect, text = element_text(el), visible = true }
        end
    end
    local labels = M.make_labels(#candidates)
    local overlay = doc:create_element("div", { id = OVERLAY_ID })
    pcall(function()
        overlay.attr.style = "position:absolute;left:0;top:0;width:0;height:0;margin:0;padding:0;pointer-events:none;z-index:2147483647"
    end)
    if type(opts.stylesheet) == "string" and opts.stylesheet ~= "" then
        local style_el = doc:create_element("style", { id = OVERLAY_ID .. "_style" }, opts.stylesheet)
        body:append(style_el)
        st.style_el = style_el
    end
    for i, h in ipairs(candidates) do
        h.label = labels[i]
        h.overlay_el = doc:create_element("div", { class = "hint_overlay" })
        h.label_el = doc:create_element("span", { class = "hint_label" }, h.label)
        overlay:append(h.overlay_el)
        overlay:append(h.label_el)
        st.hints[i] = h
    end
    body:append(overlay)
    st.overlay = overlay
    if #st.hints > 0 then st.focused = 1 end
    states[page] = st
    pcall(function()
        doc:add_signal("destroy", function() M.leave(page) end)
    end)
    pcall(function()
        page:add_signal("destroy", function() M.leave(page) end)
    end)
    refresh_visuals(st)
    return #st.hints
end

local function visible_count(st)
    local n, single = 0, nil
    for _, h in ipairs(st.hints) do
        if h.visible then
            n = n + 1
            single = h
        end
    end
    if n ~= 1 then single = nil end
    return n, single
end

-- 过滤：label_pat / text_pat 为 Lua pattern（可为 nil）。返回 可见数, 唯一命中的提示
function M.changed(page, label_pat, text_pat)
    local st = states[page]
    if not st then return 0, nil end
    if label_pat == "" then label_pat = nil end
    if text_pat == "" then text_pat = nil end
    local first_visible
    for _, h in ipairs(st.hints) do
        local ok = false
        if not label_pat and not text_pat then
            ok = true
        else
            if label_pat and pcall(string.find, h.label, label_pat) and h.label:find(label_pat) then ok = true end
            if not ok and text_pat then
                local text = h.text
                local pat = text_pat
                if st.ignore_case then
                    text = text:lower()
                    pat = pat:lower()
                end
                local found, res = pcall(string.find, text, pat)
                if found and res then ok = true end
            end
        end
        h.visible = ok
        if ok and not first_visible then first_visible = h end
    end
    -- 焦点落到第一个可见提示（若当前焦点仍可见则保留）
    local cur = st.focused and st.hints[st.focused]
    if not (cur and cur.visible) then
        st.focused = nil
        for i, h in ipairs(st.hints) do
            if h.visible then st.focused = i break end
        end
    end
    refresh_visuals(st)
    return visible_count(st)
end

-- 焦点前后移动 step（+1/-1），只在可见提示间循环
function M.focus(page, step)
    local st = states[page]
    if not st or #st.hints == 0 then return nil end
    step = step or 1
    local n = #st.hints
    local i = st.focused or 0
    for _ = 1, n do
        i = ((i - 1 + step) % n) + 1
        if st.hints[i].visible then
            st.focused = i
            refresh_visuals(st)
            return st.hints[i]
        end
    end
    return nil
end

function M.focused(page)
    local st = states[page]
    if not st or not st.focused then return nil end
    return st.hints[st.focused]
end

function M.hints(page)
    local st = states[page]
    return st and st.hints or {}
end

function M.visible_hints(page)
    local out = {}
    for _, h in ipairs(M.hints(page)) do
        if h.visible then out[#out + 1] = h end
    end
    return out
end

function M.active(page)
    return states[page] ~= nil
end

-- ---------------------------------------------------------------------------
-- IPC：浏览器侧 select.lua 推送自定义 label_maker
-- ---------------------------------------------------------------------------
local ui = ipc_channel("select_wm")
ui:add_signal("set_label_maker", function(_, _, spec)
    local ok, err = M.set_label_maker(spec)
    if not ok then msg.warn("select_wm: label maker rejected: %s", tostring(err)) end
end)

return M
