-- translate · 渲染进程半边
--
-- 页面里的事（扫段落、插译文、划词卡片）由 translate/runtime.lua 的 JS 做；这里负责：
--   * 按需把运行时注进页面（首次翻译 / 自动翻译站点 / 开了划词）
--   * 桥函数 __lx_tr_req：页面 → 渲染进程 → 浏览器进程翻译引擎 → 原路回
--   * 响应浏览器进程的指令（翻译此页 / 还原 / 切换 / 翻译选区）
--
-- 通道 "lx.translate"：
--   ← "config"(cfg_json)      { target, display, style, font_scale, selection_popup, auto_sites, exclude }
--   ← "cmd"(page_id, name)    translatePage | restore | toggle | translateSelection | state
--   ← "result"(req_id, json)
--   → "hello"(pid)
--   → "translate"(pid, page_id, req_id, texts_json)
--   → "state"(page_id, state_json)

local W = require("lx.web")
local RUNTIME = require("translate.runtime")
local util, json = W.util, W.json

local ch = W.channel("lx.translate")
local cfg = { target = "zh-CN", display = "bilingual", style = "border", font_scale = 1, selection_popup = true, auto_sites = {}, exclude = "" }
local pending, req_seq = {}, 0

local function page_cfg_js()
    return ("window.__lxtr.configure(%s);"):format(json.encode({
        target = cfg.target, display = cfg.display, style = cfg.style, fontScale = cfg.font_scale or 1, minLen = 2, exclude = cfg.exclude or "",
    }))
end

local function ensure_runtime(page)
    local st = W.state(page, "translate")
    if st.injected then return true end
    local _, err = W.eval(page, RUNTIME, "lx-translate")
    if err then W.log("translate: runtime inject failed: %s", tostring(err)) return false end
    W.eval(page, page_cfg_js())
    st.injected = true
    return true
end

local function cmd(page, name)
    if not ensure_runtime(page) then return nil end
    local js
    if name == "state" then js = "JSON.stringify(window.__lxtr.state())"
    elseif name == "pageLang" then js = "window.__lxtr.pageLang()"
    else js = ("window.__lxtr.%s()"):format(name) end
    local v = W.eval(page, js)
    return v
end

local function auto_site(host)
    for _, pat in ipairs(cfg.auto_sites or {}) do
        if util.host_matches(host, pat) then return true end
    end
    return false
end

W.on_window_cleared(function(page)
    W.state(page, "translate").injected = nil
end, 30)

W.on_document_loaded(function(page)
    local host = W.page_host(page)
    if host == "" then return end
    local want = auto_site(host)
    if want then
        cmd(page, "translatePage")
    end
    if cfg.selection_popup and ensure_runtime(page) then
        W.eval(page, "window.__lxtr.enableSelectionPopup(true)")
    end
end, 30)

-- 桥
W.expose_async("__lx_tr_req", function(page, resolve, reject, arg)
    req_seq = req_seq + 1
    pending[req_seq] = { resolve = resolve, reject = reject, at = os.time() }
    local ok, page_id = pcall(function() return page.id end)
    ch:emit_signal("translate", W.pid, ok and page_id or -1, req_seq, tostring(arg))
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

ch:add_signal("cmd", function(_, _page, page_id, name)
    local p = page_by_id(tonumber(page_id))
    if not p then return end
    local v = cmd(p, name)
    if name == "toggle" or name == "state" or name == "translatePage" then
        local st = cmd(p, "state")
        ch:emit_signal("state", page_id, st)
    end
    return v
end)

ch:add_signal("config", function(_, _page, cfg_json)
    local c = type(cfg_json) == "string" and json.decode(cfg_json) or cfg_json
    if type(c) ~= "table" then return end
    cfg = c
    cfg.auto_sites = cfg.auto_sites or {}
    for _, p in pairs(__lk.pages()) do
        local st = W.state(p, "translate")
        if st.injected then
            pcall(W.eval, p, page_cfg_js())
            pcall(W.eval, p, "window.__lxtr.enableSelectionPopup(" .. (cfg.selection_popup and "true" or "false") .. ")")
        end
    end
end)

ch:emit_signal("hello", W.pid)
return {}
