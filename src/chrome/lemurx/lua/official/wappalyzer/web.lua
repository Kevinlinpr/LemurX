-- wappalyzer/web · 技术栈探测 · 渲染进程侧
--
-- 页面加载完（以及 3 秒后再补一次，等 SPA 水合）从页面取一把信号：全局变量、<script src>、
-- <meta>、DOM 选择器命中、Cookie 名、HTML 片段，用 wappalyzer/detect 匹配后把结果发给浏览器进程。
-- 响应头这里拿不到，由浏览器进程按需 HEAD 一次补上。
local W = require("lx.web")
local json = require("lx.json")
local detect = require("wappalyzer.detect")

local ch = W.channel("lx.wappalyzer")
local cfg = { enabled = true, auto = true }

local probe_js
local function build_probe_js()
    if probe_js then return probe_js end
    probe_js = ([==[
(function(){
  var out = { url: location.href, scripts: [], meta: {}, js: {}, dom: {}, cookies: [], attrs: {} };
  try { out.html = document.documentElement.outerHTML.slice(0, 200*1024); } catch (e) { out.html = ''; }
  var ss = document.scripts; for (var i = 0; i < ss.length; i++) if (ss[i].src) out.scripts.push(ss[i].src);
  var ms = document.getElementsByTagName('meta'); for (var i = 0; i < ms.length; i++) { var n = (ms[i].getAttribute('name') || ms[i].getAttribute('property') || '').toLowerCase(); if (n) out.meta[n] = ms[i].getAttribute('content') || ''; }
  var probes = %s;
  for (var i = 0; i < probes.length; i++) { var p = probes[i]; try { var parts = p.split('.'); var v = window; for (var j = 0; j < parts.length; j++) { if (v == null) break; v = v[parts[j]]; } if (v !== undefined && v !== null) { out.js[p] = (typeof v === 'string' || typeof v === 'number') ? String(v).slice(0, 40) : true; } } catch (e) {} }
  var sels = %s;
  for (var i = 0; i < sels.length; i++) { try { if (document.querySelector(sels[i])) out.dom[sels[i]] = true; } catch (e) {} }
  try { out.cookies = document.cookie.split(';').map(function (c) { return c.split('=')[0].trim(); }).filter(Boolean); } catch (e) {}
  var attrs = %s; for (var i = 0; i < attrs.length; i++) { var el = document.querySelector('[' + attrs[i] + ']'); if (el) out.attrs[attrs[i]] = el.getAttribute(attrs[i]); }
  return JSON.stringify(out);
})()]==]):format(json.encode(detect.js_probes()), json.encode(detect.dom_probes()), json.encode(detect.attr_probes()))
    return probe_js
end

local function scan(page)
    local host = W.page_host(page)
    if host == "" then return nil end
    local raw = W.eval(page, build_probe_js())
    local sig = type(raw) == "string" and json.decode(raw) or nil
    if type(sig) ~= "table" then return nil end
    local results = detect.detect(sig)
    local slim = {}
    for _, r in ipairs(results) do
        slim[#slim + 1] = { name = r.name, cat = r.cat, version = r.version, confidence = r.confidence, via = r.via[1] }
    end
    local st = W.state(page, "wappalyzer")
    st.results = slim
    local ok, page_id = pcall(function() return page.id end)
    ch:emit_signal("result", ok and page_id or -1, sig.url or "", json.encode(slim))
    return slim
end

-- 渲染进程 Lua 没有定时器：借页面的 setTimeout 回调到暴露函数，等 SPA 水合后再扫一次
W.expose("__lx_wapp_rescan", function(page) pcall(scan, page) return true end)

W.on_document_loaded(function(page)
    if not (cfg.enabled and cfg.auto) then return end
    scan(page)
    W.eval(page, "setTimeout(function(){try{window.__lx_wapp_rescan&&window.__lx_wapp_rescan()}catch(e){}},3000)")
end, 90)

local function page_by_id(page_id)
    for _, p in pairs(__lk.pages()) do
        local ok, id = pcall(function() return p.id end)
        if ok and id == page_id then return p end
    end
end
ch:add_signal("scan", function(_, _page, page_id)
    local p = page_by_id(tonumber(page_id))
    if p then scan(p) end
end)
ch:add_signal("config", function(_, _page, cfg_json)
    local c = type(cfg_json) == "string" and json.decode(cfg_json) or cfg_json
    if type(c) == "table" then for k, v in pairs(c) do cfg[k] = v end end
end)
ch:emit_signal("hello", W.pid)
return { scan = scan, cfg = cfg }
