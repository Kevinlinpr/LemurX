-- @name 网页翻译
-- @description Google 翻译 + 沉浸式翻译替代：整页双语对照 / 替换翻译，划词翻译，自动翻译站点；引擎 Google / 微软 / DeepL / OpenAI 兼容接口
-- @version 1.0.0
-- @icon 🌐
-- @category 阅读与语言
-- @replaces Google Translate · 沉浸式翻译（Immersive Translate）
-- @page lemurx://translate/
--
-- 结构：
--   浏览器进程（本文件）：翻译引擎（无 CORS 的 lemurx.http.fetch）、缓存、设置、菜单 / 悬浮按钮
--   渲染进程（official/translate/web.lua）：按需注入页面运行时、桥接请求
--   页面运行时（official/translate/runtime.lua）：段落扫描、可见优先分批、双语渲染、划词卡片
--
-- 引擎：
--   google     translate.googleapis.com（免费，无需密钥）
--   microsoft  Edge 免费令牌 + 微软翻译 API（免费，无需密钥）
--   deepl      需要 DeepL API Key（免费版 :fx 结尾自动走 api-free）
--   openai     任意 OpenAI 兼容接口（填 base_url / key / model），可用于 DeepSeek / 通义 / 本地模型

local lx = require("lx")
local util, json = lx.util, lx.json
local esc = lx.html.escape

local ID = "translate"
local CHANNEL = "lx.translate"

local S
local cache = {}          -- key -> translation
local cache_keys = {}     -- FIFO
local CACHE_MAX = 3000
local tab_state = {}      -- tab id -> state
local ms_token = { value = nil, at = 0 }
local stats = { requests = 0, chars = 0, errors = 0 }

local LANGS = {
    { "zh-CN", "简体中文" }, { "zh-TW", "繁體中文" }, { "en", "English" }, { "ja", "日本語" }, { "ko", "한국어" },
    { "fr", "Français" }, { "de", "Deutsch" }, { "es", "Español" }, { "ru", "Русский" }, { "pt", "Português" },
    { "it", "Italiano" }, { "vi", "Tiếng Việt" }, { "th", "ไทย" }, { "ar", "العربية" }, { "id", "Bahasa Indonesia" },
}

S = lx.register({
    id = ID, name = "网页翻译", version = "1.0.0", icon = "🌐",
    description = "整页双语对照翻译（沉浸式）、划词翻译、自动翻译指定站点。引擎可选 Google / 微软（免费）或 DeepL / OpenAI 兼容接口（自备密钥）。",
    replaces = "Google Translate · 沉浸式翻译",
    settings = {
        enabled = true,
        target = "zh-CN",
        engine = "google",
        display = "bilingual",
        style = "border",
        font_scale = 1,
        selection_popup = true,
        float_button = true,
        auto_sites = {},
        never_sites = {},
        exclude = "",
        deepl_key = "",
        openai_base = "https://api.openai.com/v1", openai_key = "", openai_model = "gpt-4o-mini",
        openai_prompt = "You are a professional translator. Translate each item of the JSON array into {target}. Keep numbers, code, URLs and proper nouns. Reply with a JSON array of the same length and nothing else.",
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用翻译", section = "总开关" },
        { key = "target", type = "select", label = "目标语言", options = LANGS },
        { key = "engine", type = "select", label = "翻译引擎", options = { { "google", "Google 翻译（免费）" }, { "microsoft", "微软翻译（免费）" }, { "deepl", "DeepL（需密钥）" }, { "openai", "OpenAI 兼容接口（需密钥）" } } },
        { key = "display", type = "select", label = "显示方式", options = { { "bilingual", "双语对照（原文下方显示译文）" }, { "replace", "只显示译文" } }, section = "显示" },
        { key = "style", type = "select", label = "译文样式", options = { { "border", "左侧竖线" }, { "underline", "下划线" }, { "dashed", "虚线下划线" }, { "quote", "引用块" }, { "bg", "浅色底" }, { "plain", "无装饰" } } },
        { key = "font_scale", type = "number", label = "译文字号倍率", min = 0.7, max = 1.5, step = 0.05 },
        { key = "selection_popup", type = "bool", label = "划词翻译", desc = "选中文字后出现「译」按钮", section = "行为" },
        { key = "float_button", type = "bool", label = "网页右下角悬浮翻译按钮" },
        { key = "auto_sites", type = "list", label = "自动翻译的站点", desc = "一行一个域名；打开这些站自动整页翻译", placeholder = "news.ycombinator.com\n*.reddit.com" },
        { key = "exclude", type = "string", label = "不翻译的元素（CSS 选择器）", placeholder = ".comments, #sidebar" },
        { key = "deepl_key", type = "string", label = "DeepL API Key", section = "密钥", placeholder = "xxxxxxxx-xxxx-...:fx" },
        { key = "openai_base", type = "url", label = "OpenAI 兼容 Base URL", placeholder = "https://api.openai.com/v1" },
        { key = "openai_key", type = "string", label = "API Key" },
        { key = "openai_model", type = "string", label = "模型", placeholder = "gpt-4o-mini" },
        { key = "openai_prompt", type = "text", label = "系统提示词", desc = "{target} 会替换成目标语言" },
    },
    menu = {
        { id = "page", title = "🌐 翻译此页 / 还原", page = "main", onClick = function() S.toggle_current() end },
        { id = "auto", title = "🌐 本站自动翻译开关", page = "main", onClick = function() S.toggle_auto_site() end },
    },
})
local settings = S.settings

-- ===== 缓存 =====
local function cache_get(text, target) return cache[target .. "\1" .. text] end
local function cache_put(text, target, tr)
    local k = target .. "\1" .. text
    if cache[k] == nil then
        cache_keys[#cache_keys + 1] = k
        if #cache_keys > CACHE_MAX then
            local old = table.remove(cache_keys, 1)
            cache[old] = nil
        end
    end
    cache[k] = tr
end

-- ===== 引擎 =====
local engines = {}

-- Google：POST translate_a/t，多个 q，返回 ["译文", ...] 或 [["译文","src"], ...]
engines.google = function(texts, target, cb)
    local body = {}
    for _, t in ipairs(texts) do body[#body + 1] = "q=" .. util.url.encode(t) end
    local url = "https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=" .. util.url.encode(target) .. "&dt=t&format=text"
    lx.fetch(url, { method = "POST", headers = { ["Content-Type"] = "application/x-www-form-urlencoded;charset=utf-8", ["User-Agent"] = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36" }, body = table.concat(body, "&"), timeout = 20000 }, function(r)
        if not (r and r.ok) then return cb(nil, "Google 翻译请求失败：" .. tostring(r and (r.error or r.status))) end
        local arr = json.decode(r.body or "")
        if type(arr) ~= "table" then return cb(nil, "Google 翻译返回无法解析") end
        local out = {}
        if #texts == 1 and type(arr[1]) ~= "table" and type(arr[1]) == "string" then
            out[1] = arr[1]
        else
            for i = 1, #texts do
                local v = arr[i]
                if type(v) == "table" then v = v[1] end
                out[i] = type(v) == "string" and v or texts[i]
            end
        end
        cb(out)
    end)
end

-- 微软：Edge 匿名令牌 + translator API
local function ms_get_token(cb)
    if ms_token.value and (util.now_ms() - ms_token.at) < 8 * 60 * 1000 then return cb(ms_token.value) end
    lx.fetch("https://edge.microsoft.com/translate/auth", { timeout = 15000, headers = { ["User-Agent"] = "Mozilla/5.0" } }, function(r)
        if r and r.ok and type(r.body) == "string" and #r.body > 20 then
            ms_token.value, ms_token.at = r.body, util.now_ms()
            cb(r.body)
        else
            cb(nil, "获取微软翻译令牌失败")
        end
    end)
end
local function ms_lang(target)
    local map = { ["zh-CN"] = "zh-Hans", ["zh-TW"] = "zh-Hant", ["zh"] = "zh-Hans" }
    return map[target] or target
end
engines.microsoft = function(texts, target, cb)
    ms_get_token(function(token, err)
        if not token then return cb(nil, err) end
        local items = {}
        for _, t in ipairs(texts) do items[#items + 1] = { Text = t } end
        lx.fetch("https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=" .. util.url.encode(ms_lang(target)) .. "&textType=plain", {
            method = "POST", headers = { ["Content-Type"] = "application/json", ["Authorization"] = "Bearer " .. token }, body = json.encode(items), timeout = 20000,
        }, function(r)
            if not (r and r.ok) then
                if r and r.status == 401 then ms_token.value = nil end
                return cb(nil, "微软翻译请求失败：" .. tostring(r and (r.error or r.status)))
            end
            local arr = json.decode(r.body or "")
            if type(arr) ~= "table" then return cb(nil, "微软翻译返回无法解析") end
            local out = {}
            for i = 1, #texts do
                local v = arr[i] and arr[i].translations and arr[i].translations[1]
                out[i] = v and v.text or texts[i]
            end
            cb(out)
        end)
    end)
end

engines.deepl = function(texts, target, cb)
    local key = settings:get("deepl_key") or ""
    if key == "" then return cb(nil, "没有填 DeepL API Key") end
    local host = key:sub(-3) == ":fx" and "api-free.deepl.com" or "api.deepl.com"
    local map = { ["zh-CN"] = "ZH-HANS", ["zh-TW"] = "ZH-HANT", en = "EN-US", pt = "PT-BR" }
    lx.fetch("https://" .. host .. "/v2/translate", {
        method = "POST", headers = { ["Content-Type"] = "application/json", ["Authorization"] = "DeepL-Auth-Key " .. key },
        body = json.encode({ text = texts, target_lang = map[target] or target:upper() }), timeout = 30000,
    }, function(r)
        if not (r and r.ok) then return cb(nil, "DeepL 请求失败：" .. tostring(r and (r.error or r.status))) end
        local o = json.decode(r.body or "")
        if type(o) ~= "table" or type(o.translations) ~= "table" then return cb(nil, "DeepL 返回无法解析") end
        local out = {}
        for i = 1, #texts do out[i] = o.translations[i] and o.translations[i].text or texts[i] end
        cb(out)
    end)
end

engines.openai = function(texts, target, cb)
    local key = settings:get("openai_key") or ""
    local base = (settings:get("openai_base") or ""):gsub("/+$", "")
    if key == "" or base == "" then return cb(nil, "没有填 OpenAI 兼容接口的地址或密钥") end
    local lang_name = target
    for _, l in ipairs(LANGS) do if l[1] == target then lang_name = l[2] end end
    local sys = (settings:get("openai_prompt") or ""):gsub("{target}", lang_name .. " (" .. target .. ")")
    lx.fetch(base .. "/chat/completions", {
        method = "POST", headers = { ["Content-Type"] = "application/json", ["Authorization"] = "Bearer " .. key },
        body = json.encode({ model = settings:get("openai_model") or "gpt-4o-mini", temperature = 0.2,
            messages = { { role = "system", content = sys }, { role = "user", content = json.encode(texts) } } }),
        timeout = 90000,
    }, function(r)
        if not (r and r.ok) then return cb(nil, "模型接口请求失败：" .. tostring(r and (r.error or r.status)) .. (r and r.body and (" " .. tostring(r.body):sub(1, 200)) or "")) end
        local o = json.decode(r.body or "")
        local content = o and o.choices and o.choices[1] and o.choices[1].message and o.choices[1].message.content
        if type(content) ~= "string" then return cb(nil, "模型返回无法解析") end
        content = content:gsub("^%s*```%w*%s*", ""):gsub("%s*```%s*$", "")
        local arr = json.decode(content)
        if type(arr) ~= "table" then
            -- 模型没按要求：整段当一条
            if #texts == 1 then return cb({ content }) end
            return cb(nil, "模型没有返回 JSON 数组")
        end
        local out = {}
        for i = 1, #texts do out[i] = type(arr[i]) == "string" and arr[i] or texts[i] end
        cb(out)
    end)
end

-- 统一入口：缓存 + 分引擎；cb(results | nil, err)
local function translate(texts, target, cb)
    target = target or settings:get("target")
    local engine = engines[settings:get("engine")] or engines.google
    local out, missing, missing_idx = {}, {}, {}
    for i, t in ipairs(texts) do
        local c = cache_get(t, target)
        if c then out[i] = c else missing[#missing + 1] = t missing_idx[#missing_idx + 1] = i end
    end
    if #missing == 0 then return cb(out) end
    stats.requests = stats.requests + 1
    for _, t in ipairs(missing) do stats.chars = stats.chars + #t end
    engine(missing, target, function(res, err)
        if not res then
            stats.errors = stats.errors + 1
            return cb(nil, err)
        end
        for j, i in ipairs(missing_idx) do
            out[i] = res[j] or texts[i]
            cache_put(texts[i], target, out[i])
        end
        cb(out)
    end)
end
S.translate = translate

-- ===== 渲染进程 =====
local function config()
    return {
        target = settings:get("target"), display = settings:get("display"), style = settings:get("style"),
        font_scale = tonumber(settings:get("font_scale")) or 1, selection_popup = settings:get("enabled") and settings:get("selection_popup") and true or false,
        auto_sites = settings:get("enabled") and settings:get("auto_sites") or {}, exclude = settings:get("exclude") or "",
    }
end
local function push_pid(pid) lx.web.send_pid(CHANNEL, pid, "config", json.encode(config())) end
local ch = lx.web.require("translate/web")
if ch then
    ch:add_signal("hello", function(_, pid) if type(pid) == "number" then push_pid(pid) end end)
    lx.web.on_process(push_pid)
    ch:add_signal("translate", function(_, pid, page_id, req_id, payload)
        local p = json.decode(payload or "") or {}
        if not settings:get("enabled") then
            return lx.web.send_pid(CHANNEL, pid, "result", req_id, json.encode({ error = "翻译已停用" }))
        end
        translate(p.texts or {}, p.target, function(res, err)
            lx.web.send_pid(CHANNEL, pid, "result", req_id, json.encode(res and { results = res } or { error = err }))
        end)
    end)
    ch:add_signal("state", function(_, page_id, st_json)
        local st = type(st_json) == "string" and json.decode(st_json) or st_json
        if type(page_id) == "number" and type(st) == "table" then
            tab_state[page_id] = st
            S.update_button(page_id)
        end
    end)
end

settings:on_change(function(key)
    lx.web.broadcast(CHANNEL, "config", json.encode(config()))
    if key == "float_button" or key == "enabled" then S.update_button() end
end)

-- ===== 当前标签操作 =====
local function current_host()
    local t = lx.tabs.current()
    return t and util.host_of(t.url or "") or "", t
end
function S.toggle_current()
    local t = lx.tabs.current()
    if not t then return end
    if not settings:get("enabled") then lx.toast("翻译已停用，去设置里打开") return end
    lx.web.broadcast(CHANNEL, "cmd", t.id, "toggle")
end
function S.toggle_auto_site()
    local host = current_host()
    if host == "" then return end
    local list = settings:get("auto_sites") or {}
    local found
    for i, pat in ipairs(list) do if util.host_matches(host, pat) then found = i break end end
    if found then table.remove(list, found) lx.toast(host .. "：不再自动翻译") else list[#list + 1] = host lx.toast(host .. "：以后自动翻译") end
    settings:set("auto_sites", list)
    if not found then S.toggle_current() end
end

-- 悬浮按钮
function S.update_button(tab_id)
    if not (settings:get("enabled") and settings:get("float_button")) then pcall(lemurx.ui.unmount, "lx_tr_btn") return end
    local cur = lx.tabs.current()
    if not cur or (tab_id and tab_id ~= cur.id) then return end
    if (cur.url or ""):find("^lemurx://") or (cur.url or ""):find("^chrome") then pcall(lemurx.ui.unmount, "lx_tr_btn") return end
    local st = tab_state[cur.id]
    local on = st and st.on
    local h = lemurx.ui.h
    pcall(lemurx.ui.render, "page.float", h("button", {
        id = "lx_tr_btn", text = on and "原" or "译", size = 14, bold = true, width = 40, height = 40, gravity = "end|bottom", x = 12, y = 88,
        background = { color = on and "#E63367D6" or "#E61A73E8", radius = 20 }, color = "#FFFFFFFF", elevation = 4,
        onClick = function() S.toggle_current() end,
        onLongClick = function() lx.open(ID) end,
    }))
end
pcall(lemurx.tabs.on, "selected", function() S.update_button() end)
pcall(lemurx.tabs.on, "started", function(t) if t and t.id then tab_state[t.id] = nil S.update_button(t.id) end end)
pcall(lemurx.tabs.on, "closed", function(t) if t and t.id then tab_state[t.id] = nil end end)

-- ===== API / 页面 =====
S.api.translate = function(args, ctx)
    local texts = args.texts or (args.text and { args.text })
    if type(texts) ~= "table" or #texts == 0 then return nil, "texts required" end
    translate(texts, args.target, function(res, err)
        if res then ctx.reply({ results = res }) else ctx.fail(err) end
    end)
    return "async"
end
S.api.stats = function() return { requests = stats.requests, chars = stats.chars, errors = stats.errors, cache = #cache_keys } end
S.api.toggle_current = function() S.toggle_current() return { ok = true } end
S.api.test_engine = function(args, ctx)
    local engine = engines[args.engine or settings:get("engine")]
    if not engine then return nil, "unknown engine" end
    engine({ "Hello, world. This is a translation test." }, settings:get("target"), function(res, err)
        if res then ctx.reply({ ok = true, message = "✅ " .. tostring(res[1]) }) else ctx.fail(err) end
    end)
    return "async"
end

S.summary = function()
    local st = S.api.stats()
    return ([[
<div class="card">
 <div class="row" style="display:block"><div class="t">试一下</div><div style="margin:8px 0"><textarea id="src" placeholder="输入要翻译的文字…" style="min-height:80px"></textarea></div>
  <div class="actions" style="padding:0"><button id="go">翻译</button><button class="sec" data-api="test_engine">测试当前引擎</button></div><div id="out" class="d" style="margin-top:8px;white-space:pre-wrap"></div></div>
 <div class="stat"><div><b>%d</b><small>请求次数</small></div><div><b>%s</b><small>已翻译字符</small></div><div><b>%d</b><small>失败</small></div><div><b>%d</b><small>缓存条数</small></div></div>
</div>]]):format(st.requests, util.human_bytes and (st.chars >= 1000 and ("%.1fk"):format(st.chars / 1000) or tostring(st.chars)) or st.chars, st.errors, st.cache)
end
S.page_js = [[
lx.q('#go').onclick=function(){var t=lx.q('#src').value.trim();if(!t)return;lx.q('#out').textContent='翻译中…';lx.api('translate',{text:t}).then(function(r){lx.q('#out').textContent=r.results[0]}).catch(function(e){lx.q('#out').textContent='失败：'+e.message})};
]]

lx.after(1500, function() S.update_button() end)
return S
