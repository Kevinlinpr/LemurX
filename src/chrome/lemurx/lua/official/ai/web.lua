-- ai/web · AI 助手 · 渲染进程侧
--
-- 职责：
--   * 在普通 http(s) 页面注入 ai/runtime（悬浮球 / 划词 / 写作助手），lemurx:// 与站点例外不注入
--   * 桥 __lx_ai(JSON)：页面 → 这里 → 浏览器进程（模型接口、密钥都在那边）→ 原路回 Promise
--   * 浏览器进程可下发 cmd（open / summarize / ask / pagetext）
local W = require("lx.web")
local json = require("lx.json")
local util = require("lx.util")

local ch = W.channel("lx.ai")
local cfg = { enabled = true, bubble = true, selection = true, writing = true, lang = "zh-CN", position = "right", sites_off = {}, bubble_sites = nil }
local pending, seq = {}, 0

local function site_off(host)
    for _, pat in ipairs(cfg.sites_off or {}) do if util.host_matches(host, pat) then return true end end
    return false
end
local function bubble_for(host)
    if not cfg.bubble then return false end
    if type(cfg.bubble_sites) == "table" and #cfg.bubble_sites > 0 then
        for _, pat in ipairs(cfg.bubble_sites) do if util.host_matches(host, pat) then return true end end
        return false
    end
    return true
end

local function page_cfg(host)
    return { bubble = bubble_for(host), selection = cfg.selection, writing = cfg.writing, lang = cfg.lang, position = cfg.position }
end

local function inject(page)
    local st = W.state(page, "ai")
    if st.injected then return true end
    local host = W.page_host(page)
    if host == "" or site_off(host) then return false end
    st.injected = true
    W.eval(page, "window.__lxai_cfg=" .. json.encode(page_cfg(host)) .. ";" .. require("ai.runtime"))
    return true
end

W.on_document_loaded(function(page)
    if not cfg.enabled then return end
    if not (cfg.bubble or cfg.selection or cfg.writing) then return end
    inject(page)
end, 60)

W.expose_async("__lx_ai", function(page, resolve, reject, arg)
    if not cfg.enabled then return reject("AI 助手已停用") end
    seq = seq + 1
    pending[seq] = { resolve = resolve, reject = reject }
    local ok, page_id = pcall(function() return page.id end)
    ch:emit_signal("req", W.pid, ok and page_id or -1, seq, tostring(arg))
end)

ch:add_signal("result", function(_, _page, req_id, result_json)
    local p = pending[tonumber(req_id)]
    if not p then return end
    pending[tonumber(req_id)] = nil
    p.resolve(result_json)
end)

local function page_by_id(page_id)
    for _, p in pairs(__lk.pages()) do
        local ok, id = pcall(function() return p.id end)
        if ok and id == page_id then return p end
    end
end

-- cmd(page_id, name, arg)：先保证注入再执行
ch:add_signal("cmd", function(_, _page, page_id, name, arg)
    local p = page_by_id(tonumber(page_id))
    if not p then return end
    if not inject(p) then return end
    local r = W.eval(p, "window.__lxai_cmd(" .. W.js_string(tostring(name)) .. "," .. (arg ~= nil and json.encode(arg) or "null") .. ")")
    if name == "pagetext" then
        ch:emit_signal("pagetext", page_id, type(r) == "string" and r or "")
    end
    return r
end)

ch:add_signal("config", function(_, _page, cfg_json)
    local c = type(cfg_json) == "string" and json.decode(cfg_json) or cfg_json
    if type(c) ~= "table" then return end
    for k, v in pairs(c) do cfg[k] = v end
    for _, p in pairs(__lk.pages()) do
        local st = W.state(p, "ai")
        if st.injected then
            pcall(W.eval, p, "window.__lxai_cmd('config'," .. json.encode(page_cfg(W.page_host(p))) .. ")")
        end
    end
end)

ch:emit_signal("hello", W.pid)
return { cfg = cfg, inject = inject }
