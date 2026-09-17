-- LemurX · luakit-compatible library · view_source
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "view_source" module API. No luakit code is used.
--
-- 查看页面源码。Chromium 原生支持 view-source:<uri>，所以只需在新标签导航过去。
--   :view-source / :vs [uri]     <Control-Shift-U>
--   view_source.tab_size         兼容字段（源码由 Chromium 渲染，此值仅保留）
--   view_source.open(w, uri)     供其他模块调用

local modes = require("modes")

local M = {}

M.tab_size = 4

local function strip(uri)
    return (uri or ""):gsub("^view%-source:", "")
end

function M.open(w, uri)
    uri = strip(uri or (w.view and w.view.uri))
    if not uri or uri == "" or uri == "about:blank" then
        w:error("No page to view source of")
        return
    end
    if uri:match("^luakit://") then
        -- 内置页由 Lua 生成，Chromium 拿不到其源码；退到把 HTML 塞进新标签
        local view = w:new_tab("about:blank")
        pcall(function()
            local src = w.view.source or ""
            local esc = src:gsub("&", "&amp;"):gsub("<", "&lt;")
            view:load_string("<!DOCTYPE html><meta charset=utf-8><style>body{background:#0f1115;color:#e6e8ee;font:13px/1.45 monospace;padding:12px;white-space:pre-wrap;word-break:break-all}</style><body>" .. esc, "view-source:" .. uri)
        end)
        return view
    end
    return w:new_tab("view-source:" .. uri)
end

modes.add_cmds({
    { ":view-source, :vs", "View the source of the current page (or the given URL) in a new tab.",
        function(w, o)
            local arg = ((o and o.arg) or ""):match("^%s*(%S+)")
            M.open(w, arg)
        end },
})

modes.add_binds("normal", {
    { "<Shift-Control-U>", "View the source of the current page.", function(w) M.open(w) end },
})

return M
