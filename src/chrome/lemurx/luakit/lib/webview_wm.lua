-- LemurX · luakit-compatible library · webview_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "webview_wm" module API. No luakit code is used.
--
-- 渲染进程侧的 webview 辅助模块（require_web_module("webview_wm")）。
-- 在 Chromium 上滚动由内核的 view.scroll 直接完成，所以这里只做一件事：
-- 监听页面里可编辑元素的聚焦/失焦，并通过 IPC 通知 UI 进程
--   ui:emit_signal("form-active", page_id)  /  ui:emit_signal("root-active", page_id)
-- 让 webview.lua 在用户点进输入框时切到 insert 模式、离开时回到 normal。
-- 只用渲染进程里存在的全局：luakit page dom_document dom_element ipc_channel msg。

local ui = ipc_channel("webview_wm")

local editable_tags = { INPUT = true, TEXTAREA = true, SELECT = true }

local function is_editable(el)
    if not el then return false end
    local ok, tag = pcall(function() return el.tag_name end)
    if ok and type(tag) == "string" and editable_tags[tag:upper()] then
        local okt, itype = pcall(function() return el.type end)
        if okt and type(itype) == "string" then
            local t = itype:lower()
            if t == "button" or t == "submit" or t == "checkbox" or t == "radio" or t == "reset" or t == "image" then
                return false
            end
        end
        return true
    end
    local oke, ce = pcall(function() return el.attr.contenteditable end)
    if oke and ce ~= nil and tostring(ce) ~= "false" then return true end
    return false
end

local function hook(page)
    local ok, doc = pcall(function() return page.document end)
    if not ok or not doc then return end
    local okb, body = pcall(function() return doc.body end)
    if not okb or not body then return end
    local pid = page.id
    pcall(body.add_event_listener, body, "focusin", true, function(_, ev)
        local target = type(ev) == "table" and ev.target or nil
        if is_editable(target) then ui:emit_signal("form-active", pid) end
    end)
    pcall(body.add_event_listener, body, "focusout", true, function(_, ev)
        local target = type(ev) == "table" and ev.target or nil
        if is_editable(target) then ui:emit_signal("root-active", pid) end
    end)
end

luakit.add_signal("page-created", function(page)
    page:add_signal("document-loaded", function(p) hook(p) end)
end)

-- UI 侧可主动询问当前是否有可编辑元素聚焦：ui:emit_signal(view, "query-focus")
ui:add_signal("query-focus", function(_, page)
    if type(page) ~= "page" then return end
    local ok, active = pcall(page.eval_js, page, [[(function(){var a=document.activeElement;
        if(!a)return false;var t=a.tagName;if(t==='INPUT'||t==='TEXTAREA'||t==='SELECT')return true;
        return a.isContentEditable===true;})()]], { source = "webview_wm" })
    ui:emit_signal(ok and active == true and "form-active" or "root-active", page.id)
end)

return ui
