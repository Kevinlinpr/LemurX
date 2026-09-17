-- LemurX · luakit-compatible library · formfiller_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "formfiller_wm" module API. No luakit code is used.
--
-- 表单填充器的渲染进程半边：
--   collect    枚举页面上的 form 及其 input/select/textarea 当前值，回传给浏览器进程
--   apply_form 按浏览器进程送来的配置找到匹配的表单，写入值 / 勾选 / 聚焦 / 提交
-- IPC (formfiller_wm)：
--   ← collect(page)                 → add(page_id, forms)
--   ← apply_form(page, spec)        → applied(page_id, ok, state_or_error)
--   spec = { method=, action=, id=, className=, inputs = { {name=,type=,id=,className=,value=,checked=,focus=,select=} },
--            submit = true|number }

local ui = ipc_channel("formfiller_wm")

local M = {}

local function attr(el, name)
    local ok, v = pcall(function() return el.attr[name] end)
    if ok and v ~= nil then return v end
    return nil
end

local function lower(s) return type(s) == "string" and s:lower() or "" end

local function query_all(root, selectors)
    local out, seen = {}, {}
    for part in selectors:gmatch("[^,]+") do
        part = part:gsub("^%s+", ""):gsub("%s+$", "")
        local ok, list = pcall(root.query, root, part)
        if ok and type(list) == "table" then
            for _, el in ipairs(list) do
                if not seen[el] then
                    seen[el] = true
                    out[#out + 1] = el
                end
            end
        end
    end
    return out
end

local SKIP_INPUT_TYPES = { submit = true, button = true, reset = true, image = true, file = true }

-- ---------------------------------------------------------------------------
-- 采集
-- ---------------------------------------------------------------------------
local function describe_input(el)
    local tag = lower(el.tag_name)
    local ty = lower(el.type)
    if tag == "input" and SKIP_INPUT_TYPES[ty] then return nil end
    local name = attr(el, "name")
    local id = attr(el, "id")
    if not name and not id then return nil end
    local d = { name = name, id = id, className = attr(el, "class") }
    if tag == "input" then d.type = ty ~= "" and ty or "text" end
    if tag == "select" then d.type = "select" end
    if tag == "textarea" then d.type = "textarea" end
    if ty == "checkbox" or ty == "radio" then
        local ok, c = pcall(function() return el.checked end)
        d.checked = ok and c and true or false
    else
        local ok, v = pcall(function() return el.value end)
        if ok and type(v) == "string" then d.value = v end
    end
    return d
end

function M.collect(page)
    local doc = page.document
    local forms = {}
    for _, f in ipairs(query_all(doc, "form")) do
        local spec = {
            method = attr(f, "method"),
            action = attr(f, "action"),
            id = attr(f, "id"),
            className = attr(f, "class"),
            inputs = {},
        }
        for _, el in ipairs(query_all(f, "input, select, textarea")) do
            local d = describe_input(el)
            if d then spec.inputs[#spec.inputs + 1] = d end
        end
        if #spec.inputs > 0 then forms[#forms + 1] = spec end
    end
    return forms
end

-- ---------------------------------------------------------------------------
-- 填充
-- ---------------------------------------------------------------------------
local FORM_MATCH_ATTRS = { method = "method", action = "action", id = "id", className = "class" }
local INPUT_MATCH_ATTRS = { name = "name", type = "type", id = "id", className = "class" }

local function form_matches(f, spec)
    for key, aname in pairs(FORM_MATCH_ATTRS) do
        local want = spec[key]
        if want ~= nil then
            local have = attr(f, aname)
            if key == "method" then
                if lower(have or "get") ~= lower(want) then return false end
            elseif have ~= tostring(want) then
                return false
            end
        end
    end
    return true
end

local function input_matches(el, ispec)
    for key, aname in pairs(INPUT_MATCH_ATTRS) do
        local want = ispec[key]
        if want ~= nil then
            local have = attr(el, aname)
            if key == "type" then
                local tag = lower(el.tag_name)
                if tag == "select" or tag == "textarea" then have = tag
                elseif have == nil or have == "" then have = "text" end
                if lower(have) ~= lower(want) then return false end
            elseif have ~= tostring(want) then
                return false
            end
        end
    end
    return true
end

local function click_submit(f, index)
    local buttons = query_all(f, "input[type=submit], button[type=submit], button:not([type])")
    local b = buttons[index]
    if b then
        b:click()
        return true
    end
    return false
end

local function apply_to_form(f, spec)
    local state = "done"
    local fields = query_all(f, "input, select, textarea")
    local filled = 0
    for _, ispec in ipairs(spec.inputs or {}) do
        for _, el in ipairs(fields) do
            if input_matches(el, ispec) then
                filled = filled + 1
                if ispec.checked ~= nil then
                    pcall(function() el.checked = ispec.checked and true or false end)
                end
                if ispec.value ~= nil then
                    pcall(function() el.value = tostring(ispec.value) end)
                end
                if ispec.focus then
                    pcall(el.focus, el)
                    state = "form-active"
                end
                if ispec.select then
                    pcall(el.focus, el)
                    pcall(function() el.value = el.value end)
                    state = "form-active"
                end
            end
        end
    end
    if filled == 0 and #(spec.inputs or {}) > 0 then return false, "no matching fields" end
    local submit = spec.submit
    if submit then
        local clicked = false
        if type(submit) == "number" then clicked = click_submit(f, submit) end
        if not clicked then pcall(f.submit, f) end
        state = "submitted"
    end
    return true, state
end

function M.apply(page, spec)
    local doc = page.document
    local forms = query_all(doc, "form")
    for _, f in ipairs(forms) do
        if form_matches(f, spec) then
            local ok, state = apply_to_form(f, spec)
            if ok then return true, state end
        end
    end
    return false, "no matching form"
end

ui:add_signal("collect", function(_, page)
    if not page then return end
    local ok, forms = pcall(M.collect, page)
    if not ok then
        msg.warn("formfiller_wm: collect failed: %s", tostring(forms))
        forms = {}
    end
    ui:emit_signal("add", page, forms)
end)

ui:add_signal("apply_form", function(_, page, spec)
    if not page then return end
    local ok, res, state = pcall(M.apply, page, spec or {})
    if not ok then
        ui:emit_signal("applied", page, false, tostring(res))
        return
    end
    ui:emit_signal("applied", page, res, state)
end)

return M
