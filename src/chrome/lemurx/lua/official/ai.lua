-- @name AI 助手
-- @description 页面总结、对页面提问、划词解释/翻译/润色、输入框写作助手（润色/语法/续写）。接任意 OpenAI 兼容接口，自备密钥，也能连本机 Ollama。
-- @version 1.0.0
-- @icon ✦
-- @category AI
-- @page lemurx://ai/
-- @replaces Sider · Monica · HARPA AI · Grammarly · QuillBot · Wordtune · LanguageTool
--
-- 结构：
--   ai.lua          浏览器进程：模型接口（密钥不进页面）、对话历史、菜单、设置页 / 独立对话页
--   ai/web.lua      渲染进程：注入运行时、桥接页面请求
--   ai/runtime.lua  页面内 JS：悬浮球 + 侧边面板、划词工具条、写作助手
--
-- 不做的：Grammarly 那种逐词下划线实时批改（需要持续把用户输入送模型，费钱且慢）。
-- 这里是"写完点一下 ✨ 改整段"，效果等价、可控。
local lx = require("lx")
local json = require("lx.json")
local util = require("lx.util")

local ID, CHANNEL = "ai", "lx.ai"
local S

-- 预设：选一个就自动填 base_url / model；custom 用手填的
local PROVIDERS = {
    { "openai",   "OpenAI",             "https://api.openai.com/v1",                              "gpt-4o-mini" },
    { "deepseek", "DeepSeek",           "https://api.deepseek.com/v1",                            "deepseek-chat" },
    { "moonshot", "Moonshot / Kimi",    "https://api.moonshot.cn/v1",                             "moonshot-v1-8k" },
    { "zhipu",    "智谱 GLM",            "https://open.bigmodel.cn/api/paas/v4",                   "glm-4-flash" },
    { "qwen",     "通义千问",             "https://dashscope.aliyuncs.com/compatible-mode/v1",      "qwen-turbo" },
    { "doubao",   "火山方舟 / 豆包",       "https://ark.cn-beijing.volces.com/api/v3",               "" },
    { "groq",     "Groq",               "https://api.groq.com/openai/v1",                         "llama-3.1-8b-instant" },
    { "openrouter", "OpenRouter",       "https://openrouter.ai/api/v1",                           "openai/gpt-4o-mini" },
    { "ollama",   "Ollama（本机，免密钥）", "http://127.0.0.1:11434/v1",                             "qwen2.5" },
    { "custom",   "自定义 OpenAI 兼容接口", "",                                                     "" },
}
local function provider(id) for _, p in ipairs(PROVIDERS) do if p[1] == id then return p end end return PROVIDERS[#PROVIDERS] end

S = lx.register({
    id = ID, name = "AI 助手", version = "1.0.0", icon = "✦",
    description = "页面总结、对页面提问、划词解释/翻译/润色、输入框写作助手（润色/语法/续写/改语气）。接任意 OpenAI 兼容接口（OpenAI / DeepSeek / Kimi / 智谱 / 通义 / Groq / 本机 Ollama…），密钥只留在浏览器进程。",
    replaces = "Sider · Monica · HARPA AI · Grammarly · QuillBot · Wordtune · LanguageTool",
    settings = {
        enabled = true,
        provider = "deepseek",
        base_url = "", api_key = "", model = "",
        temperature = 0.4,
        max_tokens = 2048,
        lang = "zh-CN",
        system_prompt = "你是浏览器里的 AI 助手，回答简洁、准确、有条理，默认使用{lang}。给到网页内容时以它为依据，不知道就说不知道。",
        bubble = true, bubble_position = "right", bubble_sites = {},
        selection = true,
        writing = true,
        sites_off = {},
        history_keep = 50,
        context_chars = 12000,
    },
    schema = {
        { key = "enabled", type = "bool", label = "启用 AI 助手", section = "模型" },
        { key = "provider", type = "select", label = "服务商", options = (function() local o = {} for _, p in ipairs(PROVIDERS) do o[#o + 1] = { p[1], p[2] } end return o end)() },
        { key = "api_key", type = "string", label = "API Key", desc = "只存在本机浏览器进程，不会注入网页" },
        { key = "model", type = "string", label = "模型", placeholder = "留空用服务商默认" },
        { key = "base_url", type = "url", label = "Base URL", placeholder = "留空用服务商默认；自定义接口在这里填" },
        { key = "temperature", type = "number", label = "温度", min = 0, max = 2, step = 0.1 },
        { key = "max_tokens", type = "number", label = "最大输出 tokens", min = 256, max = 32000, step = 256 },
        { key = "test", type = "action", label = "测试连接", api = "test" },
        { key = "lang", type = "select", label = "回答语言", section = "行为", options = { { "zh-CN", "简体中文" }, { "zh-TW", "繁體中文" }, { "en", "English" }, { "ja", "日本語" }, { "ko", "한국어" } } },
        { key = "system_prompt", type = "text", label = "系统提示词", desc = "{lang} 会替换成回答语言" },
        { key = "context_chars", type = "number", label = "带入的页面正文上限（字符）", min = 2000, max = 60000, step = 1000 },
        { key = "bubble", type = "bool", label = "页面右下角悬浮球", section = "页面内入口" },
        { key = "bubble_position", type = "select", label = "悬浮球位置", options = { { "right", "右侧" }, { "left", "左侧" } } },
        { key = "bubble_sites", type = "list", label = "只在这些站点显示悬浮球", desc = "留空 = 所有站点；每行一个，支持 *.example.com" },
        { key = "selection", type = "bool", label = "划词工具条（解释 / 翻译 / 润色 / 改写）" },
        { key = "writing", type = "bool", label = "输入框写作助手 ✨" },
        { key = "sites_off", type = "list", label = "这些站点完全不注入", desc = "每行一个 host，支持 *.example.com" },
        { key = "history_keep", type = "number", label = "保留对话条数", section = "历史", min = 0, max = 500 },
        { key = "clear_history", type = "action", label = "清空历史", api = "history.clear", style = "danger" },
    },
    menu = {
        { id = "open", title = "AI 助手", onClick = function() S.cmd_current("open") end },
        { id = "summarize", title = "总结本页", onClick = function() S.cmd_current("summarize") end },
        { id = "chat", title = "AI 对话页", page = "main" },
    },
    api = {},
})
local settings = S.settings
local data = lx.data(ID)

-- ===== 历史 / 统计 =====
local history = data:read_json("history.json") or {}
local stats = data:read_json("stats.json") or { requests = 0, errors = 0, in_chars = 0, out_chars = 0 }
local save_timer
local function save_later()
    if save_timer then return end
    save_timer = lx.after(2000, function()
        save_timer = nil
        data:write_json("history.json", history)
        data:write_json("stats.json", stats)
    end)
end
local function record(entry)
    local keep = tonumber(settings:get("history_keep")) or 50
    if keep <= 0 then return end
    table.insert(history, 1, entry)
    while #history > keep do table.remove(history) end
    save_later()
end

-- ===== 模型调用 =====
local function endpoint()
    local p = provider(settings:get("provider"))
    local base = (settings:get("base_url") or ""):gsub("%s", "")
    if base == "" then base = p[3] end
    base = base:gsub("/+$", "")
    local model = (settings:get("model") or ""):gsub("%s", "")
    if model == "" then model = p[4] end
    return base, model, settings:get("api_key") or "", p
end

-- chat(messages, opts, cb)：opts = {context=正文, page={title,url}, writing=bool}
-- cb(content | nil, err)
function S.chat(messages, opts, cb)
    opts = opts or {}
    if not settings:get("enabled") then return cb(nil, "AI 助手已停用") end
    local base, model, key, p = endpoint()
    if base == "" then return cb(nil, "没有填 Base URL") end
    if model == "" then return cb(nil, "没有填模型名") end
    if key == "" and p[1] ~= "ollama" and p[1] ~= "custom" then return cb(nil, "没有填 API 密钥（lemurx://ai/ 设置）") end
    local lang = settings:get("lang") or "zh-CN"
    local lang_name = ({ ["zh-CN"] = "简体中文", ["zh-TW"] = "繁體中文", en = "English", ja = "日本語", ko = "한국어" })[lang] or lang
    local sys = (settings:get("system_prompt") or ""):gsub("{lang}", lang_name)
    if opts.writing then
        sys = "你是写作助手。严格按用户指令处理文字，只输出处理结果本身，不要任何解释、前言、引号或 markdown 包裹。"
    end
    local full = { { role = "system", content = sys } }
    if type(opts.context) == "string" and opts.context ~= "" then
        local limit = tonumber(settings:get("context_chars")) or 12000
        local ctx = opts.context
        if #ctx > limit then ctx = ctx:sub(1, limit) .. "\n…（已截断）" end
        local pg = opts.page or {}
        full[#full + 1] = { role = "system", content = ("以下是用户正在浏览的网页内容。\n标题：%s\n地址：%s\n\n%s"):format(pg.title or "", pg.url or "", ctx) }
    end
    local in_chars = 0
    for _, m in ipairs(messages) do
        if type(m) == "table" and (m.role == "user" or m.role == "assistant") and type(m.content) == "string" then
            full[#full + 1] = { role = m.role, content = m.content }
            in_chars = in_chars + #m.content
        end
    end
    stats.requests = stats.requests + 1
    stats.in_chars = stats.in_chars + in_chars + #(opts.context or "")
    local headers = { ["Content-Type"] = "application/json" }
    if key ~= "" then headers["Authorization"] = "Bearer " .. key end
    if p[1] == "openrouter" then headers["HTTP-Referer"] = "https://lemurx.app" headers["X-Title"] = "LemurX" end
    lx.fetch(base .. "/chat/completions", {
        method = "POST", headers = headers,
        body = json.encode({ model = model, temperature = tonumber(settings:get("temperature")) or 0.4,
            max_tokens = tonumber(settings:get("max_tokens")) or 2048, messages = full, stream = false }),
        timeout = 120000,
    }, function(r)
        if not (r and r.ok) then
            stats.errors = stats.errors + 1
            save_later()
            local detail = r and r.body and tostring(r.body):sub(1, 300) or ""
            local o = json.decode(detail)
            if type(o) == "table" and type(o.error) == "table" and o.error.message then detail = o.error.message end
            return cb(nil, ("模型接口请求失败（%s）：%s"):format(tostring(r and (r.status or r.error) or "?"), detail))
        end
        local o = json.decode(r.body or "")
        local content = o and o.choices and o.choices[1] and o.choices[1].message and o.choices[1].message.content
        if type(content) ~= "string" then
            stats.errors = stats.errors + 1
            save_later()
            return cb(nil, "模型返回无法解析：" .. tostring(r.body or ""):sub(1, 200))
        end
        stats.out_chars = stats.out_chars + #content
        if not opts.writing then
            local last_user
            for i = #messages, 1, -1 do if messages[i].role == "user" then last_user = messages[i].content break end end
            record({ at = util.now_ms(), url = opts.page and opts.page.url, title = opts.page and opts.page.title,
                q = (last_user or ""):sub(1, 400), a = content:sub(1, 4000), model = model })
        else
            save_later()
        end
        cb(content)
    end)
end

-- ===== 渲染进程 =====
local function config()
    return {
        enabled = settings:get("enabled") and true or false,
        bubble = settings:get("bubble") and true or false, position = settings:get("bubble_position") or "right",
        bubble_sites = settings:get("bubble_sites") or {},
        selection = settings:get("selection") and true or false, writing = settings:get("writing") and true or false,
        lang = settings:get("lang") or "zh-CN", sites_off = settings:get("sites_off") or {},
    }
end
local function push_pid(pid) lx.web.send_pid(CHANNEL, pid, "config", json.encode(config())) end
local ch = lx.web.require("ai/web")
if ch then
    ch:add_signal("hello", function(_, pid) if type(pid) == "number" then push_pid(pid) end end)
    lx.web.on_process(push_pid)
    ch:add_signal("req", function(_, pid, page_id, req_id, payload)
        local p = json.decode(payload or "") or {}
        local function reply(t) lx.web.send_pid(CHANNEL, pid, "result", req_id, json.encode(t)) end
        if p.type == "chat" then
            S.chat(p.messages or {}, { context = p.context, page = p.page, writing = p.writing }, function(content, err)
                if content then reply({ content = content }) else reply({ error = err }) end
            end)
        else
            reply({ error = "unknown request " .. tostring(p.type) })
        end
    end)
end
settings:on_change(function() lx.web.broadcast(CHANNEL, "config", json.encode(config())) end)

function S.cmd_current(name, arg)
    local t = lx.tabs.current()
    if not t then return end
    if not settings:get("enabled") then lx.toast("AI 助手已停用，去设置里打开") return end
    if not (t.url or ""):match("^https?://") then lx.tabs.open("lemurx://ai/") return end
    lx.web.broadcast(CHANNEL, "cmd", t.id, name, arg)
end

-- ===== API / 页面 =====
S.api.chat = function(args, ctx)
    local msgs = args.messages or (args.text and { { role = "user", content = args.text } })
    if type(msgs) ~= "table" or #msgs == 0 then return nil, "messages required" end
    S.chat(msgs, { context = args.context, page = args.page, writing = args.writing }, function(content, err)
        if content then ctx.reply({ content = content }) else ctx.fail(err) end
    end)
    return "async"
end
S.api.test = function(_, ctx)
    local base, model = endpoint()
    S.chat({ { role = "user", content = "Reply with the single word OK." } }, {}, function(content, err)
        if content then ctx.reply({ ok = true, message = ("✅ %s @ %s → %s"):format(model, base, content:sub(1, 60)) }) else ctx.fail(err) end
    end)
    return "async"
end
S.api["history.list"] = function() return { items = history } end
S.api["history.clear"] = function() history = {} data:write_json("history.json", history) return { ok = true, message = "已清空", reload = true } end
S.api.stats = function() return stats end
S.api.providers = function() local o = {} for _, p in ipairs(PROVIDERS) do o[#o + 1] = { id = p[1], name = p[2], base = p[3], model = p[4] } end return { providers = o } end

S.page_css = [[
#chat{display:flex;flex-direction:column;gap:8px;min-height:120px;max-height:50vh;overflow:auto;padding:4px 0}
.m{max-width:90%;padding:8px 12px;border-radius:14px;white-space:pre-wrap;word-break:break-word;line-height:1.5}.m.u{align-self:flex-end;background:var(--accent,#0a84ff);color:#fff}.m.a{align-self:flex-start;background:var(--bg2,#f2f2f7)}
#ask{display:flex;gap:8px;margin-top:8px}#ask textarea{flex:1;min-height:44px}#ask button{width:64px}
.h .t{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.h .d{white-space:pre-wrap;max-height:4.5em;overflow:hidden}
]]
S.summary = function()
    local base, model, key, p = endpoint()
    local items = {}
    for i = 1, math.min(#history, 20) do
        local h = history[i]
        items[#items + 1] = ("<div class=\"row h\"><div class=\"l\"><div class=\"t\">%s</div><div class=\"d\">%s</div><div class=\"d\" style=\"opacity:.6\">%s%s</div></div></div>")
            :format(lx.html.escape(h.q or ""), lx.html.escape(h.a or ""), os.date("%m-%d %H:%M", math.floor((h.at or 0) / 1000)),
                h.title and (" · " .. lx.html.escape(h.title)) or "")
    end
    return ([[
<div class="card"><div class="row" style="display:block"><div class="t">对话 <span class="badge">%s · %s</span></div>
 <div id="chat"><div class="m a">这里是不带网页上下文的对话。要问某个页面的事，在那个页面点右下角 ✦。</div></div>
 <div id="ask"><textarea id="q" placeholder="问点什么…"></textarea><button id="go">发送</button></div>
 %s</div>
 <div class="stat"><div><b>%d</b><small>请求</small></div><div><b>%d</b><small>失败</small></div><div><b>%s</b><small>输入字符</small></div><div><b>%s</b><small>输出字符</small></div></div>
</div>
<div class="card list"><div class="row"><div class="l"><div class="t">最近对话</div></div></div>%s</div>]]):format(
        lx.html.escape(p[2]), lx.html.escape(model ~= "" and model or "未设模型"),
        (key == "" and p[1] ~= "ollama" and p[1] ~= "custom") and "<p class=\"muted\">还没有填 API 密钥，在下面设置里填好后再用。</p>" or "",
        stats.requests, stats.errors,
        stats.in_chars >= 1000 and ("%.1fk"):format(stats.in_chars / 1000) or tostring(stats.in_chars),
        stats.out_chars >= 1000 and ("%.1fk"):format(stats.out_chars / 1000) or tostring(stats.out_chars),
        #items > 0 and table.concat(items) or "<div class=\"row\"><div class=\"d\">还没有对话</div></div>")
end
S.page_js = [[
var msgs=[];var box=lx.q('#chat');
function add(role,t){var d=document.createElement('div');d.className='m '+(role==='user'?'u':'a');d.textContent=t;box.appendChild(d);box.scrollTop=box.scrollHeight;return d}
lx.q('#go').onclick=function(){var q=lx.q('#q').value.trim();if(!q)return;lx.q('#q').value='';msgs.push({role:'user',content:q});add('user',q);var a=add('assistant','…');lx.q('#go').disabled=true;
 lx.api('chat',{messages:msgs.slice(-12)}).then(function(r){a.textContent=r.content;msgs.push({role:'assistant',content:r.content})}).catch(function(e){a.textContent='出错了：'+e.message;msgs.pop()}).then(function(){lx.q('#go').disabled=false})};
lx.q('#q').addEventListener('keydown',function(e){if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();lx.q('#go').click()}});
document.addEventListener('change',function(e){if(e.target.dataset&&e.target.dataset.key==='provider'){lx.api('providers').then(function(r){var p=r.providers.filter(function(x){return x.id===e.target.value})[0];if(!p)return;var b=document.querySelector('[data-key=base_url]'),m=document.querySelector('[data-key=model]');if(b)b.placeholder=p.base||'自定义接口地址';if(m)m.placeholder=p.model||'模型名'})}});
]]

return S
