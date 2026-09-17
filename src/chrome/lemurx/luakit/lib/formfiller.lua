-- LemurX · luakit-compatible library · formfiller
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "formfiller" module API. No luakit code is used.
--
-- 表单填充：把页面表单的当前值存成 luakit.data_dir/forms.lua 里的 Lua DSL，
-- 之后按 URI 匹配填回去。文件格式（公开 DSL）：
--   on "example%.com" {
--     form "profile" { method = "post", action = "/login", id = "f", className = "c",
--       input { name = "user", type = "text", value = "me" },
--       input { name = "remember", type = "checkbox", checked = true },
--       submit = true,  -- 或第 n 个提交按钮的序号
--       autofill = false,
--     },
--   }
-- 绑定（normal）：za 保存当前页表单  ze 编辑 forms.lua  zl 填充（唯一匹配直接填，否则菜单）
--                 zL 总是弹菜单选择配置
-- 公开接口：formfiller.extend(fns) formfiller.load() formfiller.rules formfiller.match(uri)
--           formfiller.apply(w, spec) formfiller.serialize(pattern, forms) formfiller.file
-- 模式：formfiller-menu
-- IPC (formfiller_wm)：→ collect(view) apply_form(view, spec)  ← add(page_id, forms) applied(page_id, ok, state)
-- Android 上没有外部编辑器：ze 只提示文件路径。

local lousy = require("lousy")
local modes = require("modes")
local window = require("window")
local webview = require("webview")

local _M = {}

local wm = require_web_module("formfiller_wm")

_M.file = luakit.data_dir .. "/forms.lua"

local extensions = {}
local rules = {}
local loaded = false

-- ---------------------------------------------------------------------------
-- DSL 解析
-- ---------------------------------------------------------------------------
local LAZY = {}

local function lazy(fn, args)
    return setmetatable({}, { __index = LAZY, __call = function() return fn(table.unpack(args, 1, args.n)) end })
end

local function resolve(v)
    if type(v) == "table" and getmetatable(v) and getmetatable(v).__index == LAZY then
        local ok, r = pcall(v)
        if ok then return r end
        msg.warn("formfiller: extension call failed: %s", tostring(r))
        return nil
    end
    return v
end

local function normalize_form(raw, profile)
    local spec = { profile = profile, inputs = {} }
    for k, v in pairs(raw) do
        if type(k) == "number" then
            if type(v) == "table" then
                local inp = {}
                for ik, iv in pairs(v) do inp[ik] = iv end
                inp.__input = nil
                spec.inputs[#spec.inputs + 1] = inp
            end
        elseif k ~= "inputs" then
            spec[k] = v
        end
    end
    if type(raw.inputs) == "table" then
        for _, inp in ipairs(raw.inputs) do spec.inputs[#spec.inputs + 1] = inp end
    end
    return spec
end

function _M.parse(text, chunkname)
    local out = {}
    local env = {}
    env.on = function(pattern)
        if type(pattern) ~= "string" then error("on() expects a pattern string", 2) end
        return function(forms)
            local entry = { pattern = pattern, forms = {} }
            for _, f in ipairs(forms or {}) do
                if type(f) == "table" then
                    entry.forms[#entry.forms + 1] = normalize_form(f, f.profile)
                end
            end
            out[#out + 1] = entry
        end
    end
    env.form = function(a)
        if type(a) == "string" then
            return function(spec)
                spec.profile = a
                return spec
            end
        end
        return a
    end
    env.input = function(spec)
        spec.__input = true
        return spec
    end
    for name, fn in pairs(extensions) do
        env[name] = function(...) return lazy(fn, table.pack(...)) end
    end
    setmetatable(env, { __index = function(_, k)
        local safe = { tostring = tostring, tonumber = tonumber, string = string, table = table, math = math, os = { getenv = os.getenv } }
        return safe[k]
    end })
    local chunk, err = load(text, chunkname or "=forms.lua", "t", env)
    if not chunk then return nil, err end
    local ok, rerr = pcall(chunk)
    if not ok then return nil, rerr end
    return out
end

-- 读取 forms.lua
function _M.load()
    rules = {}
    loaded = true
    local f = io.open(_M.file, "rb")
    if not f then return rules end
    local text = f:read("a")
    f:close()
    local parsed, err = _M.parse(text)
    if not parsed then
        msg.warn("formfiller: %s: %s", _M.file, tostring(err))
        return rules
    end
    rules = parsed
    return rules
end

function _M.extend(fns)
    if type(fns) ~= "table" then error("formfiller.extend expects a table of functions", 2) end
    for name, fn in pairs(fns) do
        if type(fn) == "function" then extensions[name] = fn end
    end
    if loaded then _M.load() end
end

setmetatable(_M, {
    __index = function(_, k)
        if k == "rules" then
            if not loaded then _M.load() end
            return rules
        end
    end,
})

-- 找到匹配 uri 的所有表单配置
function _M.match(uri, autofill_only)
    if not loaded then _M.load() end
    local host = tostring(uri or ""):match("^%a[%w+.-]*://([^/?#:]+)") or ""
    local out = {}
    for _, entry in ipairs(rules) do
        local ok, found = pcall(string.find, uri, entry.pattern)
        if ok and found then
            for _, form in ipairs(entry.forms) do
                if not autofill_only or (form.autofill and entry.pattern:gsub("%%", ""):find(host, 1, true)) then
                    out[#out + 1] = { pattern = entry.pattern, form = form }
                end
            end
        end
    end
    return out
end

-- ---------------------------------------------------------------------------
-- 序列化（za 采集到的表单 → DSL 文本）
-- ---------------------------------------------------------------------------
local function q(v)
    if type(v) == "string" then return string.format("%q", v) end
    return tostring(v)
end

local function escape_pattern(s)
    return (s:gsub("[%^%$%(%)%%%.%[%]%*%+%-%?]", "%%%0"))
end

function _M.serialize(pattern, forms)
    local lines = { ("on %s {"):format(q(pattern)) }
    for _, f in ipairs(forms) do
        lines[#lines + 1] = f.profile and ("    form %s {"):format(q(f.profile)) or "    form {"
        for _, key in ipairs({ "method", "action", "id", "className" }) do
            if f[key] ~= nil and f[key] ~= "" then
                lines[#lines + 1] = ("        %s = %s,"):format(key, q(f[key]))
            end
        end
        for _, inp in ipairs(f.inputs or {}) do
            lines[#lines + 1] = "        input {"
            for _, key in ipairs({ "name", "id", "type", "className" }) do
                if inp[key] ~= nil and inp[key] ~= "" then
                    lines[#lines + 1] = ("            %s = %s,"):format(key, q(inp[key]))
                end
            end
            if inp.checked ~= nil then
                lines[#lines + 1] = ("            checked = %s,"):format(tostring(inp.checked and true or false))
            elseif inp.value ~= nil then
                lines[#lines + 1] = ("            value = %s,"):format(q(inp.value))
            end
            lines[#lines + 1] = "        },"
        end
        lines[#lines + 1] = "        submit = false,"
        lines[#lines + 1] = "        autofill = false,"
        lines[#lines + 1] = "    },"
    end
    lines[#lines + 1] = "}"
    return table.concat(lines, "\n") .. "\n"
end

local function append_to_file(text)
    pcall(lfs.mkdir, luakit.data_dir)
    local f, err = io.open(_M.file, "ab")
    if not f then return false, err end
    f:write("\n", text)
    f:close()
    return true
end

-- ---------------------------------------------------------------------------
-- 应用
-- ---------------------------------------------------------------------------
local function wire_spec(form)
    local spec = { method = form.method, action = form.action, id = form.id, className = form.className,
                   submit = form.submit, inputs = {} }
    for k, v in pairs(spec) do spec[k] = resolve(v) end
    for _, inp in ipairs(form.inputs or {}) do
        local w = {}
        for k, v in pairs(inp) do w[k] = resolve(v) end
        spec.inputs[#spec.inputs + 1] = w
    end
    return spec
end

function _M.apply(w, form)
    local view = w.view
    if not view then return end
    wm:emit_signal(view, "apply_form", wire_spec(form))
end

local function window_for_view_id(id)
    for _, w in pairs(window.bywidget or {}) do
        local v = w.view
        if v and v.is_alive and v.id == id then return w end
    end
end

wm:add_signal("applied", function(_, page_id, ok, state)
    local w = window_for_view_id(page_id)
    if not w then return end
    if not ok then
        w:warning("formfiller: " .. tostring(state))
        return
    end
    if state == "form-active" then
        w:set_mode("insert")
    elseif state == "submitted" then
        w:notify("formfiller: form submitted")
    else
        w:notify("formfiller: form filled")
    end
end)

wm:add_signal("add", function(_, page_id, forms)
    local w = window_for_view_id(page_id)
    if not w then return end
    if type(forms) ~= "table" or #forms == 0 then
        w:warning("formfiller: no forms with named fields on this page")
        return
    end
    local host = tostring(w.view.uri or ""):match("^%a[%w+.-]*://([^/?#:]+)") or ""
    local text = _M.serialize(escape_pattern(host), forms)
    local ok, err = append_to_file(text)
    if not ok then
        w:error("formfiller: cannot write " .. _M.file .. ": " .. tostring(err))
        return
    end
    _M.load()
    w:notify(("formfiller: %d form(s) saved to %s"):format(#forms, _M.file))
end)

local function fill(w, force_menu)
    local uri = w.view and w.view.uri or ""
    local matches = _M.match(uri)
    if #matches == 0 then
        w:warning("formfiller: nothing saved for " .. uri)
        return
    end
    if #matches == 1 and not force_menu then
        _M.apply(w, matches[1].form)
        return
    end
    w:set_mode("formfiller-menu", matches)
end

-- 自动填充
webview.add_signal("init", function(view)
    view:add_signal("load-status", function(v, status)
        if status ~= "finished" then return end
        if not loaded then _M.load() end
        local matches = _M.match(v.uri or "", true)
        if #matches == 0 then return end
        local w = webview.window and webview.window(v)
        if not w then return end
        _M.apply(w, matches[1].form)
    end)
end)

-- ---------------------------------------------------------------------------
-- 菜单模式
-- ---------------------------------------------------------------------------
modes.new_mode("formfiller-menu", "Choose which saved form profile to apply.", {
    enter = function(w, matches)
        local rows = { { "Profile", "Form", title = true } }
        for _, m in ipairs(matches or {}) do
            local f = m.form
            local label = f.profile or ("(unnamed for " .. m.pattern .. ")")
            local desc = (f.action or f.id or f.className or f.method or "form") .. (f.submit and " → submit" or "")
            rows[#rows + 1] = { label, desc, form = f }
        end
        w.menu:build(rows)
        w.menu:show()
        w:set_prompt("Fill which form? (Return applies)")
    end,
    leave = function(w)
        w.menu:hide()
    end,
})

modes.add_binds("formfiller-menu", {
    { "<Return>", "Apply the selected form profile.", function (w)
        local row = w.menu:get()
        w:set_mode()
        if row and row.form then _M.apply(w, row.form) end
    end },
    { "<Tab>", "Select the next profile.", function (w) w.menu:move_down() end },
    { "<Shift-Tab>", "Select the previous profile.", function (w) w.menu:move_up() end },
})

modes.add_binds("normal", {
    { "za", "Save this page's form values to forms.lua.", function (w)
        if w.view then wm:emit_signal(w.view, "collect") end
    end },
    { "ze", "Edit forms.lua.", function (w)
        local ok, editor = pcall(require, "editor")
        if ok and editor and editor.edit then
            editor.edit(_M.file, 1, function() _M.load() end)
        else
            w:notify("formfiller: edit " .. _M.file)
        end
    end },
    { "zl", "Fill the form matching this page (menu if ambiguous).", function (w) fill(w, false) end },
    { "zL", "Choose a saved form profile for this page from a menu.", function (w) fill(w, true) end },
})

return _M
