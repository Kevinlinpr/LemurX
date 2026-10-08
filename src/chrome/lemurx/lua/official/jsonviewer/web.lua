-- jsonviewer · 渲染进程半边
--
-- document-loaded 后判断这是不是一份"裸 JSON"文档（Chromium 把 application/json 响应
-- 渲染成只有一个 <pre> 的页面），是就注入 viewer.lua 里的查看器。
--
-- 通道 "lx.jsonviewer"：← "config"(cfg)   → "hello"(pid)   → "shown"(page_id, bytes)

local W = require("lx.web")
local json = W.json
local util = W.util

local M = {}
local ch = W.channel("lx.jsonviewer")
local cfg = { enabled = true, theme = "auto", indent = 2, collapse_depth = 2, max_kb = 4096, wrap = false, sites_off = {} }
local viewer_js = nil

local DETECT = [[(function(){
var ct=document.contentType||'';var b=document.body;if(!b)return 0;
var pre=b.children.length===1&&b.firstElementChild.tagName==='PRE'?b.firstElementChild:null;
if(!/json/i.test(ct)&&!(pre&&!/html/i.test(ct)&&b.children.length===1))return 0;
var t=(pre?pre.textContent:b.textContent).trim();if(!t)return 0;
if(!/^[\[{"]/.test(t)&&!/^[\w$.]+\(/.test(t)&&!/^(true|false|null|-?\d)/.test(t))return 0;
return t.length})()]]

local function site_off(host)
    for _, pat in ipairs(cfg.sites_off or {}) do
        if util.host_matches(host, pat) then return true end
    end
    return false
end

local function maybe_show(page)
    if not cfg.enabled then return end
    local host = W.page_host(page)
    if site_off(host) then return end
    local uri = page.uri or ""
    if not (uri:match("^https?://") or uri:match("^file://") or uri:match("^blob:")) then return end
    local bytes = W.eval(page, DETECT)
    bytes = tonumber(bytes) or 0
    if bytes <= 0 then return end
    if bytes > (tonumber(cfg.max_kb) or 4096) * 1024 then
        W.log("jsonviewer: %s too large (%d KB), skipped", uri, bytes // 1024)
        return
    end
    if not viewer_js then viewer_js = require("jsonviewer.viewer") end
    local c = json.encode({ theme = cfg.theme, indent = tonumber(cfg.indent) or 2, collapse_depth = tonumber(cfg.collapse_depth) or 2,
        max_bytes = (tonumber(cfg.max_kb) or 4096) * 1024, wrap = cfg.wrap and true or false })
    W.eval(page, "window.__lxjv_cfg=" .. c .. ";" .. viewer_js, "lx-jsonviewer")
    local ok, id = pcall(function() return page.id end)
    if ok then ch:emit_signal("shown", id, bytes) end
end

W.on_document_loaded(maybe_show, 60)

ch:add_signal("config", function(_, _page, c)
    if type(c) == "table" then for k, v in pairs(c) do cfg[k] = v end end
end)
ch:emit_signal("hello", luakit.web_process_id)

M.cfg = cfg
M.maybe_show = maybe_show
return M
