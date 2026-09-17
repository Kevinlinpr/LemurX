-- LemurX · luakit-compatible library · image_css
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "image_css" module API. No luakit code is used.
--
-- 单张图片页面美化（浏览器进程侧）：把样式表送到渲染进程的 image_css_wm，
-- 由其在检测到图片文档时注入。样式里的 %BG% 会替换成 image_css.background。
-- 公开接口：image_css.background（读写）image_css.stylesheet（读写）image_css.is_image(view)
-- IPC (image_css_wm)：→ stylesheet(css)   ← image(page_id, bool)

local webview = require("webview")

local _M = {}

local wm = require_web_module("image_css_wm")

local state = {
    background = "#121212",
    template = [[
html, body { margin: 0; height: 100%; }
body { background: %BG% !important; display: flex; align-items: center; justify-content: center; }
body > img { max-width: 100%; max-height: 100%; object-fit: contain; cursor: zoom-in; box-shadow: 0 0 24px rgba(0,0,0,.6); }
body.lx-natural > img { max-width: none; max-height: none; cursor: zoom-out; }
body.lx-natural { display: block; overflow: auto; }
]],
}

local image_views = setmetatable({}, { __mode = "k" })

local function render()
    return (state.template:gsub("%%BG%%", function() return state.background end))
end

local function push(view)
    if view then wm:emit_signal(view, "stylesheet", render())
    else wm:emit_signal("stylesheet", render()) end
end

function _M.is_image(view)
    return image_views[view] == true
end

wm:add_signal("image", function(_, page_id, is_image)
    for _, v in pairs(__lk and __lk.webviews or {}) do
        if v.is_alive and v.id == page_id then
            image_views[v] = is_image and true or nil
        end
    end
end)

webview.add_signal("init", function(view)
    view:add_signal("web-extension-loaded", function(v) push(v) end)
end)

setmetatable(_M, {
    __index = function(_, k)
        if k == "background" then return state.background end
        if k == "stylesheet" then return render() end
        return nil
    end,
    __newindex = function(t, k, v)
        if k == "background" then
            state.background = tostring(v)
            push()
        elseif k == "stylesheet" then
            state.template = tostring(v)
            push()
        else
            rawset(t, k, v)
        end
    end,
})

push()

return _M
