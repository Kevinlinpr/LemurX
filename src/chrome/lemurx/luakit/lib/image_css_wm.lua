-- LemurX · luakit-compatible library · image_css_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "image_css_wm" module API. No luakit code is used.
--
-- 渲染进程侧：在 document-loaded 时判断当前文档是否是"单张图片"页
-- （document.contentType 以 image/ 开头，或 body 只含一个 img 且没有文字），
-- 是则注入浏览器进程送来的样式，并让点击图片在"适应窗口 / 原始尺寸"间切换。
-- IPC (image_css_wm)：← stylesheet(css)   → image(page_id, is_image)

local ui = ipc_channel("image_css_wm")

local M = {}

M.stylesheet = [[
html, body { margin: 0; height: 100%; }
body { background: #121212 !important; display: flex; align-items: center; justify-content: center; }
body > img { max-width: 100%; max-height: 100%; object-fit: contain; cursor: zoom-in; box-shadow: 0 0 24px rgba(0,0,0,.6); }
body.lx-natural > img { max-width: none; max-height: none; cursor: zoom-out; }
body.lx-natural { display: block; }
]]

local STYLE_ID = "luakit_image_css"

local function trim(s) return (tostring(s or ""):gsub("^%s+", ""):gsub("%s+$", "")) end

function M.is_image_document(page)
    local ok, ct = pcall(page.eval_js, page, "document.contentType", { source = "image_css_wm" })
    if ok and type(ct) == "string" and ct:sub(1, 6) == "image/" then return true end
    local okd, doc = pcall(function() return page.document end)
    if not okd or not doc then return false end
    local body = doc.body
    if not body then return false end
    local okc, count = pcall(function() return body.child_count end)
    if not okc or count ~= 1 then return false end
    local first = body.first_child
    if not first or tostring(first.tag_name or ""):lower() ~= "img" then return false end
    local okt, text = pcall(function() return body.text_content end)
    if okt and trim(text) ~= "" then return false end
    return true
end

function M.apply(page)
    local doc = page.document
    local body = doc.body
    if not body then return end
    local style = doc:create_element("style", { id = STYLE_ID }, M.stylesheet)
    body:append(style)
    local img = body.first_child
    if img and tostring(img.tag_name or ""):lower() == "img" then
        img:add_event_listener("click", false, function()
            local cls = body.attr.class or ""
            if cls:find("lx%-natural") then
                body.attr.class = trim(cls:gsub("lx%-natural", ""))
            else
                body.attr.class = trim(cls .. " lx-natural")
            end
        end)
    end
end

luakit.add_signal("page-created", function(page)
    page:add_signal("document-loaded", function(p)
        local is_image = M.is_image_document(p)
        if is_image then
            local ok, err = pcall(M.apply, p)
            if not ok then msg.warn("image_css_wm: %s", tostring(err)) end
        end
        ui:emit_signal("image", p, is_image)
    end)
end)

ui:add_signal("stylesheet", function(_, _, css)
    if type(css) == "string" and css ~= "" then M.stylesheet = css end
end)

return M
