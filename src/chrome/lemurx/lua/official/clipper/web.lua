-- clipper/web · 网页剪藏 · 渲染进程侧
--
-- 按需注入 clipper/runtime（不常驻），浏览器进程下发 extract(page_id, mode, req) 时执行提取，
-- 结果通过 "extracted"(req, json) 回传。页面里也可以通过 __lx_clip("article") 主动剪藏（给书签脚本用）。
local W = require("lx.web")
local json = require("lx.json")

local ch = W.channel("lx.clipper")

local function ensure(page)
    local st = W.state(page, "clipper")
    if not st.injected then
        st.injected = true
        W.eval(page, (require("clipper.runtime")))
    end
end

local function extract(page, mode)
    ensure(page)
    local r = W.eval(page, "window.__lxclip_extract(" .. W.js_string(tostring(mode or "article")) .. ")")
    return type(r) == "string" and r or nil
end

local function page_by_id(page_id)
    for _, p in pairs(__lk.pages()) do
        local ok, id = pcall(function() return p.id end)
        if ok and id == page_id then return p end
    end
end

ch:add_signal("extract", function(_, _page, page_id, mode, req)
    local p = page_by_id(tonumber(page_id))
    if not p then return end
    local r = extract(p, mode)
    ch:emit_signal("extracted", req, r or "")
end)

-- 页面主动剪藏（例如用户脚本里调 __lx_clip("selection")）
W.expose("__lx_clip", function(page, mode)
    local r = extract(page, mode)
    if r then ch:emit_signal("clip", W.pid, r) end
    return r and "ok" or "fail"
end)

return { extract = extract }
