-- darkmode · 渲染进程半边
--
-- 三种引擎：
--   native  Blink 强制暗色（浏览器进程用 lemurx.chrome.setForceDark 打开），这里只负责亮度/对比度等
--           附加滤镜和站点自定义 CSS
--   filter  CSS 反色 + 色相旋转（Dark Reader 的 Filter 模式），图片/视频再反回来
--   static  一套通用暗色样式表（Dark Reader 的 Static 模式）
-- 都通过 Blink 用户样式表注入（绕 CSP，文档一建立就生效，不闪白）。
--
-- 通道 "lx.darkmode"：
--   ← "config"(cfg_json)   { engine, active, exceptions, list_mode, brightness, contrast, sepia, grayscale,
--                            detect_dark, site_css = { host = css } }
--   → "hello"(pid)
--   → "detected"(page_id, host, is_dark)   自动检测结果（浏览器端记录到"本来就是暗色的站"）

local W = require("lx.web")
local util = W.util
local json = W.json

local ch = W.channel("lx.darkmode")
local cfg = { engine = "native", active = false, exceptions = {}, list_mode = false, brightness = 100, contrast = 100, sepia = 0, grayscale = 0, detect_dark = true, site_css = {} }
local KEY, KEY_ADJ, KEY_SITE = "lx-dark", "lx-dark-adj", "lx-dark-site"

local FILTER_CSS = [[
@media screen {
html { -webkit-filter: invert(100%%) hue-rotate(180deg) %s !important; filter: invert(100%%) hue-rotate(180deg) %s !important; background: #fff !important; }
html img, html video, html canvas, html picture, html svg image, html object, html embed, html iframe,
html [style*="background-image"]:not(html):not(body), html .lx-noinvert {
  -webkit-filter: invert(100%%) hue-rotate(180deg) !important; filter: invert(100%%) hue-rotate(180deg) !important;
}
html [style*="background-image"] img, html [style*="background-image"] video, html [style*="background-image"] canvas,
html iframe img, html picture img, html .lx-noinvert img { -webkit-filter: none !important; filter: none !important; }
html ::selection { background: #3390ff !important; color: #000 !important; }
}
]]

local STATIC_CSS = [[
@media screen {
:root { color-scheme: dark !important; }
html, body { background-color: #181a1b !important; color: #e8e6e3 !important; }
html, body, div, section, article, main, aside, header, footer, nav, menu, ul, ol, li, dl, dt, dd, table, thead, tbody, tfoot, tr, td, th,
form, fieldset, legend, p, blockquote, pre, code, kbd, samp, span, label, figure, figcaption, details, summary, dialog, hr,
h1, h2, h3, h4, h5, h6, small, strong, em, b, i, u, s, sub, sup, time, address, cite, abbr, mark, ins, del, output, caption {
  background-color: #181a1b !important; color: #e8e6e3 !important; border-color: #3a3f42 !important; box-shadow: none !important; text-shadow: none !important;
}
mark { background-color: #6b5b00 !important; color: #fff !important; }
a, a * { color: #8ab4f8 !important; }
a:visited, a:visited * { color: #c58af9 !important; }
input, textarea, select, button, [role="button"], [contenteditable] {
  background-color: #26292b !important; color: #e8e6e3 !important; border-color: #4a5053 !important;
}
input::placeholder, textarea::placeholder { color: #9aa0a6 !important; }
button, [type="button"], [type="submit"], [role="button"] { background-color: #303436 !important; }
::selection { background: #3390ff !important; color: #fff !important; }
::-webkit-scrollbar { background: #202324 !important; } ::-webkit-scrollbar-thumb { background: #454a4d !important; }
img, video, canvas, picture, svg { filter: brightness(.88) contrast(1.05) !important; }
[style*="background-image"] { filter: brightness(.85) !important; }
iframe { color-scheme: dark; }
}
]]

local function adjustments()
    local parts = {}
    if cfg.brightness and cfg.brightness ~= 100 then parts[#parts + 1] = ("brightness(%d%%)"):format(cfg.brightness) end
    if cfg.contrast and cfg.contrast ~= 100 then parts[#parts + 1] = ("contrast(%d%%)"):format(cfg.contrast) end
    if cfg.sepia and cfg.sepia ~= 0 then parts[#parts + 1] = ("sepia(%d%%)"):format(cfg.sepia) end
    if cfg.grayscale and cfg.grayscale ~= 0 then parts[#parts + 1] = ("grayscale(%d%%)"):format(cfg.grayscale) end
    return table.concat(parts, " ")
end

local function host_listed(host)
    for _, pat in ipairs(cfg.exceptions or {}) do
        if util.host_matches(host, pat) then return true end
    end
    return false
end

-- 这个站现在该不该暗
local function active_for(host)
    if not cfg.active then return false end
    if cfg.list_mode then return host_listed(host) end
    return not host_listed(host)
end

local function site_css_for(host)
    local css = cfg.site_css and cfg.site_css[host]
    if css and css ~= "" then return css end
    -- 允许 *.example.com 形式
    for pat, c in pairs(cfg.site_css or {}) do
        if pat ~= host and util.host_matches(host, pat) and c ~= "" then return c end
    end
    return nil
end

local function apply(page, uri)
    local host = util.host_of(uri or "")
    local st = W.state(page, "darkmode")
    st.host = host
    st.applied = nil
    if host == "" then return end
    local on = active_for(host)
    st.on = on
    if not on then return end
    local adj = adjustments()
    if cfg.engine == "filter" then
        W.css(page, FILTER_CSS:format(adj, adj), KEY)
        st.applied = KEY
    elseif cfg.engine == "static" then
        W.css(page, STATIC_CSS, KEY)
        if adj ~= "" then W.css(page, "html{-webkit-filter:" .. adj .. " !important;filter:" .. adj .. " !important}", KEY_ADJ) end
        st.applied = KEY
    else -- native：Blink 自己做；这里只挂附加滤镜
        if adj ~= "" then W.css(page, "html{-webkit-filter:" .. adj .. " !important;filter:" .. adj .. " !important}", KEY_ADJ) end
    end
    local sc = site_css_for(host)
    if sc then W.css(page, sc, KEY_SITE) end
end

local function clear(page)
    W.uncss(page, KEY)
    W.uncss(page, KEY_ADJ)
    W.uncss(page, KEY_SITE)
end

W.on_window_cleared(function(page, uri) apply(page, uri) end, 20)

-- 已经是暗色的站：filter / static 引擎下撤掉（native 引擎 Blink 自己会判断）
local DETECT_JS = [[(function(){try{
 function lum(c){var m=/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(c||'');if(!m)return null;var a=m[4]==null?1:parseFloat(m[4]);if(a<0.5)return null;
  return (0.2126*m[1]+0.7152*m[2]+0.0722*m[3])/255}
 var cs=getComputedStyle(document.documentElement).colorScheme||'';
 var meta=document.querySelector('meta[name=color-scheme]');var declared=/dark/.test(cs)||(meta&&/dark/.test(meta.content||''));
 var b=lum(getComputedStyle(document.body).backgroundColor),h=lum(getComputedStyle(document.documentElement).backgroundColor);
 var l=b!=null?b:h;
 if(l==null){var el=document.elementFromPoint(innerWidth/2,innerHeight/3);while(el&&l==null){l=lum(getComputedStyle(el).backgroundColor);el=el.parentElement}}
 var t=lum(getComputedStyle(document.body).color);
 return JSON.stringify({dark:(l!=null&&l<0.35)||(l==null&&t!=null&&t>0.6),declared:declared,l:l});
}catch(e){return null}})()]]

W.on_document_loaded(function(page)
    local st = W.state(page, "darkmode")
    if not st.on or not cfg.detect_dark then return end
    if cfg.engine ~= "filter" and cfg.engine ~= "static" then return end
    -- 检测要在我们的样式之外看：先摘掉再量，量完按结果决定
    W.uncss(page, KEY)
    W.uncss(page, KEY_ADJ)
    local raw = W.eval(page, DETECT_JS)
    local r = type(raw) == "string" and json.decode(raw) or nil
    local is_dark = r and r.dark or false
    if not is_dark then
        apply(page, (function() local ok, u = pcall(function() return page.uri end) return ok and u or "" end)())
    else
        W.uncss(page, KEY_SITE)
    end
    local ok, id = pcall(function() return page.id end)
    if ok then ch:emit_signal("detected", id, st.host, is_dark and true or false) end
end, 20)

local function reapply_all()
    for _, page in pairs(__lk.pages()) do
        pcall(function()
            clear(page)
            local ok, uri = pcall(function() return page.uri end)
            apply(page, ok and uri or "")
        end)
    end
end

ch:add_signal("config", function(_, _page, cfg_json)
    local c = type(cfg_json) == "string" and json.decode(cfg_json) or cfg_json
    if type(c) ~= "table" then return end
    cfg = c
    cfg.exceptions = cfg.exceptions or {}
    cfg.site_css = cfg.site_css or {}
    reapply_all()
end)

ch:emit_signal("hello", W.pid)
return {}
