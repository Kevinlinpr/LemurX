-- LemurX · luakit-compatible library · keysym
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "keysym" module API. No luakit code is used.
--
-- 合成按键：keysym.send(w, "<Control-a>Hello<Return>")
--   把字符串拆成 (mods, key) 序列；insert / passthrough 模式下送进页面
--   （优先 view:send_key，Chromium 上再用 JS 注入文字/按键事件兜底），
--   其他模式交给 w:hit 走按键绑定。keysym.parse(str) 返回解析结果供测试与其他模块复用。

local _M = {}

-- 单字符 → GDK 键名（只列非字母数字的常见键；字母数字键名就是字符本身）
local char_names = {
    [" "] = "space",
    ["\n"] = "Return",
    ["\t"] = "Tab",
}

local special_names = {
    ret = "Return", enter = "Return", cr = "Return",
    esc = "Escape", bs = "BackSpace", del = "Delete", ins = "Insert",
    space = "space", tab = "Tab", up = "Up", down = "Down", left = "Left", right = "Right",
    home = "Home", ["end"] = "End", pageup = "Page_Up", pagedown = "Page_Down",
    lt = "less", gt = "greater",
}

local mod_names = {
    control = "Control", ctrl = "Control", c = "Control",
    shift = "Shift", s = "Shift",
    mod1 = "Mod1", alt = "Mod1", meta = "Mod1", a = "Mod1", m = "Mod1",
    mod4 = "Mod4", super = "Mod4", win = "Mod4",
}

local function normalize_key(name)
    local lower = name:lower()
    if special_names[lower] then return special_names[lower] end
    return name
end

--- 解析 "<Control-a>Hi<Return>" → { {mods={"Control"}, key="a"}, {mods={}, key="H"}, … }
function _M.parse(str)
    local out = {}
    local i, n = 1, #str
    while i <= n do
        local c = str:sub(i, i)
        if c == "<" then
            local close = str:find(">", i + 1, true)
            local inner = close and str:sub(i + 1, close - 1) or nil
            if inner and inner ~= "" and not inner:find("%s") then
                local parts = {}
                for p in inner:gmatch("[^%-]+") do parts[#parts + 1] = p end
                -- "<Control-->" 之类以 '-' 结尾的：最后一个键是 minus
                if inner:sub(-1) == "-" then parts[#parts + 1] = "minus" end
                local mods, key = {}, nil
                for idx, p in ipairs(parts) do
                    local mod = mod_names[p:lower()]
                    if idx < #parts and mod then
                        mods[#mods + 1] = mod
                    else
                        key = (key and (key .. "-" .. p)) or p
                    end
                end
                if key then
                    out[#out + 1] = { mods = mods, key = normalize_key(key) }
                    i = close + 1
                else
                    out[#out + 1] = { mods = {}, key = "less" }
                    i = i + 1
                end
            else
                out[#out + 1] = { mods = {}, key = "less" }
                i = i + 1
            end
        else
            -- 按 UTF-8 字符切
            local cp_end = i
            local b = str:byte(i)
            if b >= 0xF0 then cp_end = i + 3 elseif b >= 0xE0 then cp_end = i + 2 elseif b >= 0xC0 then cp_end = i + 1 end
            local ch = str:sub(i, cp_end)
            local mods = {}
            if ch:match("^%u$") then mods[1] = "Shift" end
            out[#out + 1] = { mods = mods, key = char_names[ch] or ch, char = ch }
            i = cp_end + 1
        end
    end
    return out
end

local js_key_names = {
    Return = "Enter", Escape = "Escape", BackSpace = "Backspace", Tab = "Tab", space = " ",
    Up = "ArrowUp", Down = "ArrowDown", Left = "ArrowLeft", Right = "ArrowRight",
    Home = "Home", End = "End", Page_Up = "PageUp", Page_Down = "PageDown", Delete = "Delete",
}

local function has_mod(mods, name)
    for _, m in ipairs(mods) do if m == name then return true end end
    return false
end

-- Chromium 上没有 GDK 事件注入：用 JS 模拟输入
local function inject_js(view, ev)
    local key = ev.key
    local mods = ev.mods
    local printable = ev.char or (key == "space" and " ") or nil
    if printable and not has_mod(mods, "Control") and not has_mod(mods, "Mod1") then
        local js = ([[(function(t){var a=document.activeElement;
            if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA'||a.isContentEditable)){
                if(!document.execCommand('insertText',false,t)){
                    var s=a.selectionStart==null?a.value.length:a.selectionStart;
                    a.value=a.value.slice(0,s)+t+a.value.slice(a.selectionEnd==null?s:a.selectionEnd);
                }
                a.dispatchEvent(new Event('input',{bubbles:true}));return true;}
            return false;})(%s)]]):format(("%q"):format(printable))
        view:eval_js(js, { source = "keysym" })
        return
    end
    if key == "BackSpace" and #mods == 0 then
        view:eval_js("document.execCommand('delete',false,null)", { source = "keysym" })
        return
    end
    local jsname = js_key_names[key] or key
    local js = ([[(function(){var a=document.activeElement||document.body;
        var o={key:%q,bubbles:true,cancelable:true,ctrlKey:%s,shiftKey:%s,altKey:%s,metaKey:%s};
        var ok=a.dispatchEvent(new KeyboardEvent('keydown',o));
        a.dispatchEvent(new KeyboardEvent('keyup',o));
        if(ok&&o.key==='Enter'&&a.form&&a.tagName!=='TEXTAREA'){a.form.requestSubmit?a.form.requestSubmit():a.form.submit();}
        return ok;})()]]):format(jsname,
        tostring(has_mod(mods, "Control")), tostring(has_mod(mods, "Shift")),
        tostring(has_mod(mods, "Mod1")), tostring(has_mod(mods, "Mod4")))
    view:eval_js(js, { source = "keysym" })
end

--- 发送一串按键。opts.target = "page" | "window" 可强制目标。
function _M.send(w, str, opts)
    opts = opts or {}
    local events = _M.parse(tostring(str or ""))
    local to_page = opts.target == "page"
    if opts.target == nil then
        to_page = w:is_mode("insert") or w:is_mode("passthrough")
    end
    local view = w.view
    for _, ev in ipairs(events) do
        if to_page and view and view.is_alive then
            local sent = false
            if type(view.send_key) == "function" then
                local ok = pcall(view.send_key, view, ev.key, ev.mods)
                sent = ok and (opts.trust_send_key == true)
            end
            if not sent then inject_js(view, ev) end
        else
            w:hit(ev.mods, ev.key, { synthetic = true })
        end
    end
    return #events
end

return _M
