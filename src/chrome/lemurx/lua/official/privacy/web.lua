-- privacy · 渲染进程半边
--
-- 每个子资源请求同步过一遍：
--   1. ClearURLs：去掉追踪参数 / 拆跳转（改写 URL）
--   2. 追踪器判定（第三方请求）：内置名单 + 浏览器进程学习到的 → 拦截 / 去 Cookie 放行
--   3. GPC / DNT 请求头
-- 文档级：
--   * window-object-cleared：navigator.globalPrivacyControl / doNotTrack
--   * document-loaded / destroy：把本页看到的第三方域（含被拦的）汇报给浏览器进程做启发式学习
--
-- 通道 "lx.privacy"：
--   ← "config"(cfg)             { enabled, clearurls, custom_params, trackers, learn, gpc, dnt,
--                                  disabled_sites, learned = { host = "block"|"cookieblock"|"allow" },
--                                  user = { host = "block"|"cookieblock"|"allow" } }
--   → "hello"(pid)
--   → "seen"(page_id, page_host, third = { host = { n, blocked, cookieblocked, company } }, cleaned)

local W = require("lx.web")
local util = W.util
local json = W.json
local clearurls = require("privacy.clearurls")
local trackers = require("privacy.trackers")

local M = {}
local ch = W.channel("lx.privacy")

local cfg = {
    enabled = true, clearurls = true, custom_params = {}, trackers = true, learn = true,
    gpc = true, dnt = true, disabled_sites = {}, learned = {}, user = {},
}
local page_state = setmetatable({}, { __mode = "k" })

local function site_disabled(host)
    for _, pat in ipairs(cfg.disabled_sites or {}) do
        if util.host_matches(host, pat) then return true end
    end
    return false
end

local function state_for(page)
    local st = page_state[page]
    local host = W.page_host(page)
    if st and st.host == host then return st end
    st = { host = host, base = util.base_domain(host), third = {}, cleaned = 0, blocked = 0, cookieblocked = 0, dirty = false, off = site_disabled(host) }
    page_state[page] = st
    return st
end

-- 沿后缀链查用户/学习表
local function lookup_map(map, host)
    local h = host
    while h and h ~= "" do
        local v = map[h]
        if v then return v, h end
        local dot = h:find(".", 1, true)
        if not dot then break end
        h = h:sub(dot + 1)
    end
    return nil
end

-- 决定第三方 host 的处置："block" | "cookieblock" | nil
local function decide(host)
    local u = lookup_map(cfg.user or {}, host)
    if u then
        if u == "allow" then return nil end
        return u
    end
    local builtin, entry = trackers.classify(host)
    local learned = lookup_map(cfg.learned or {}, host)
    if learned == "allow" then return nil end
    if builtin then return builtin, entry end
    if learned then
        if learned == "block" and trackers.yellow(host) then return "cookieblock" end
        return learned
    end
    return nil
end
M.decide = decide

local function report(page, st, force)
    if not st.dirty then return end
    if not force then return end
    local ok, id = pcall(function() return page.id end)
    if not ok then return end
    ch:emit_signal("seen", id, st.host, st.third, st.cleaned, st.blocked, st.cookieblocked)
    st.dirty = false
end

W.on_request(function(page, url, headers, info)
    if not cfg.enabled then return nil end
    local st = state_for(page)
    if st.off then return nil end
    local rtype = info.type or "other"
    local redirect
    -- 1. 链接去追踪（导航请求由浏览器进程处理；这里只管子资源，主要是 xhr/fetch/iframe/img）
    if cfg.clearurls and rtype ~= "document" then
        local nu = clearurls.clean(url, cfg.custom_params)
        if nu then
            redirect = nu
            url = nu
            st.cleaned = st.cleaned + 1
            st.dirty = true
        end
    end
    -- 2. 第三方追踪器
    local host = util.host_of(url)
    if host ~= "" and rtype ~= "document" then
        local base = util.base_domain(host)
        if base ~= st.base and st.base ~= "" then
            local rec = st.third[host]
            if not rec then
                rec = { n = 0, blocked = 0, cookieblocked = 0, company = trackers.company_of(host) }
                st.third[host] = rec
            end
            rec.n = rec.n + 1
            st.dirty = true
            if cfg.trackers then
                local action = decide(host)
                if action == "block" then
                    rec.blocked = rec.blocked + 1
                    st.blocked = st.blocked + 1
                    return false
                elseif action == "cookieblock" then
                    rec.cookieblocked = rec.cookieblocked + 1
                    st.cookieblocked = st.cookieblocked + 1
                    info.opts = info.opts or {}
                    info.opts.credentials = "omit"
                    headers.Referer = nil
                end
            end
        end
    end
    -- 3. GPC / DNT
    if cfg.gpc then headers["Sec-GPC"] = "1" end
    if cfg.dnt then headers["DNT"] = "1" end
    return redirect
end, 5)

W.on_window_cleared(function(page, uri)
    page_state[page] = nil
    if not cfg.enabled then return end
    local st = state_for(page)
    if st.off then return end
    local js = {}
    if cfg.gpc then
        js[#js + 1] = "try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:function(){return true},configurable:true})}catch(e){}"
    end
    if cfg.dnt then
        js[#js + 1] = "try{Object.defineProperty(Navigator.prototype,'doNotTrack',{get:function(){return '1'},configurable:true})}catch(e){}"
    end
    if #js > 0 then W.eval(page, "(function(){" .. table.concat(js) .. "})()") end
end, 5)

W.on_document_loaded(function(page)
    local st = page_state[page]
    if st then report(page, st, true) end
end)
W.on_page_destroyed(function(page)
    local st = page_state[page]
    if st then report(page, st, true) end
    page_state[page] = nil
end)

ch:add_signal("config", function(_, _page, c)
    if type(c) ~= "table" then return end
    for k, v in pairs(c) do cfg[k] = v end
    for _, p in pairs(__lk.pages()) do
        local st = page_state[p]
        if st then st.off = site_disabled(st.host) end
    end
end)

ch:emit_signal("hello", luakit.web_process_id)
M.cfg = cfg
return M
