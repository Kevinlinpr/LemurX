-- adblock · 渲染进程半边
--
-- 浏览器进程（official/adblock.lua）把编译好的过滤表推过来，这里：
--   * send-request：每个子资源同步判定 → 拦截 / 放行
--   * window-object-cleared：给新文档注入元素隐藏 CSS（用户样式表，绕 CSP、无闪烁）
--   * 把拦截计数回报给浏览器进程（每页聚合）
--
-- 通道 "lx.adblock"：
--   ← "rules"(compiled_json, generation, whitelist)   全量规则（进程启动 / 列表更新）
--   ← "whitelist"(list)                                只更新白名单
--   ← "enable"(bool)
--   → "hello"(pid)                                     模块就位，请发规则
--   → "blocked"(page_id, host, n, samples)             聚合计数

local W = require("lx.web")
local abp = require("lx.abp")
local util = W.util
local json = W.json

local M = {}
local ch = W.channel("lx.adblock")

local engine = nil
local generation = 0
local enabled = true
local cosmetic = true
local whitelist = {}          -- host 模式列表（util.host_matches）
local page_state = setmetatable({}, { __mode = "k" })  -- page -> {host, flags, blocked, samples}

local function state_for(page)
    local st = page_state[page]
    local host = W.page_host(page)
    if st and st.host == host and st.gen == generation then return st end
    st = { host = host, gen = generation, blocked = 0, samples = {}, reported = 0 }
    if engine then
        st.flags = engine:page_flags(host)
    else
        st.flags = {}
    end
    st.whitelisted = false
    for _, pat in ipairs(whitelist) do
        if util.host_matches(host, pat) then st.whitelisted = true break end
    end
    page_state[page] = st
    return st
end

local function flush(page, st, force)
    if st.blocked <= st.reported then return end
    if not force and st.blocked - st.reported < 10 then return end
    local ok, id = pcall(function() return page.id end)
    if not ok then return end
    ch:emit_signal("blocked", id, st.host, st.blocked - st.reported, st.samples)
    st.reported = st.blocked
    st.samples = {}
end

-- ===== 请求判定 =====
W.on_request(function(page, url, headers, info)
    if not (enabled and engine) then return nil end
    local st = state_for(page)
    if st.whitelisted or st.flags.document then return nil end
    local rtype = info.type or "other"
    if rtype == "document" then return nil end
    local initiator = info.initiator and util.host_of(info.initiator) or st.host
    local verdict, by = engine:match(url, { type = rtype, page = st.host, initiator = initiator })
    if verdict == "block" then
        st.blocked = st.blocked + 1
        if #st.samples < 8 then
            st.samples[#st.samples + 1] = { url = url:sub(1, 200), type = rtype, filter = engine:describe(by) }
        end
        flush(page, st, false)
        return false
    end
    return nil
end, 10)

-- ===== 元素隐藏 =====
local function inject_css(page)
    if not (enabled and cosmetic and engine) then
        W.uncss(page, "lx-adblock")
        return
    end
    local st = state_for(page)
    if st.whitelisted or st.flags.document or st.flags.elemhide then
        W.uncss(page, "lx-adblock")
        return
    end
    local css = engine:cosmetic_css(st.host)
    if css and css ~= "" then
        W.css(page, css, "lx-adblock")
    else
        W.uncss(page, "lx-adblock")
    end
end

W.on_window_cleared(function(page, uri)
    page_state[page] = nil
    inject_css(page)
end, 10)

W.on_document_loaded(function(page)
    local st = page_state[page]
    if st then flush(page, st, true) end
end)

W.on_page_destroyed(function(page)
    local st = page_state[page]
    if st then flush(page, st, true) end
    page_state[page] = nil
end)

-- 已经打开的页面（模块热加载 / 规则到达时）：补注 CSS
local function reapply_all()
    for _, page in pairs(__lk.pages()) do
        pcall(function()
            page_state[page] = nil
            inject_css(page)
        end)
    end
end

-- ===== 来自浏览器进程 =====
ch:add_signal("rules", function(_, _page, compiled_json, gen, wl)
    local t0 = os.clock()
    local compiled = type(compiled_json) == "string" and json.decode(compiled_json) or compiled_json
    if type(compiled) ~= "table" then
        W.log("adblock: bad rules payload")
        return
    end
    engine = abp.engine(compiled)
    generation = tonumber(gen) or (generation + 1)
    if type(wl) == "table" then whitelist = wl end
    collectgarbage("step", 0)
    W.log("adblock: rules gen %d loaded in %.0f ms (%d net, %d hosts, %d cosmetic)", generation,
        (os.clock() - t0) * 1000, compiled.stats and compiled.stats.network or 0,
        compiled.stats and compiled.stats.hosts or 0, compiled.stats and compiled.stats.cosmetic or 0)
    reapply_all()
end)

ch:add_signal("whitelist", function(_, _page, wl)
    if type(wl) == "table" then
        whitelist = wl
        generation = generation + 1
        reapply_all()
    end
end)

ch:add_signal("enable", function(_, _page, on)
    enabled = on ~= false
    generation = generation + 1
    reapply_all()
end)

ch:add_signal("cosmetic", function(_, _page, on)
    cosmetic = on ~= false
    reapply_all()
end)

-- 就位：要规则
ch:emit_signal("hello", W.pid)

M.engine = function() return engine end
return M
