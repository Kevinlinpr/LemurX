-- lx —— LemurX 官方脚本框架（浏览器进程）。
--
-- 官方脚本（files/lua/official/*.lua）都长这样：
--
--   local lx = require("lx")
--   local S = lx.register({
--       id = "adblock", name = "去广告", version = "1.0", icon = "🛡",
--       description = "…",
--       settings = { enabled = true, lists = {...} },          -- 默认值
--       schema = { { key = "enabled", type = "bool", label = "启用" }, ... },  -- 设置页表单
--       api = { stats = function(args) return {...} end },     -- lemurx://adblock/api/stats
--       page = function(ctx) return html end,                  -- 自定义 lemurx://adblock/ （可省）
--   })
--   S.settings:get("enabled")
--
-- 提供：
--   lx.register(spec) -> script            lx.scripts[id]  lx.settings(id, defaults)
--   lx.json  lx.util  lx.log(fmt, ...)     lx.toast(msg)  lx.notify(title, text)
--   lx.on_webview(fn(view))                所有已包装 + 以后包装的标签
--   lx.on_navigation(fn(view, uri, ev) -> false|url|nil)   主/子框架导航否决、改写
--   lx.on_load(fn(view, status, uri))      load-status
--   lx.web.require(name) -> ipc_channel    渲染进程模块（lua/official/<name>.lua）
--   lx.web.on_process(fn(pid))             渲染进程 Lua 就位
--   lx.web.send_pid(channel, pid, sig, ...) / broadcast(channel, sig, ...)
--   lx.html.page{...} lx.html.settings(script) 页面拼装；lemurx://<id>/… 自动路由
--   lx.fetch(url, opts, cb) / lx.fetch_sync   lx.after(ms, fn) / lx.every(ms, fn)
--   lx.data(id):read(name) / write(name, data) / exists / remove   files/lua/official_data/<id>/
--   lx.tabs.current() / list() / inject(id, js, opts) / eval(id, js)
--
-- 约定：一切失败都 pcall + lx.log，一个官方脚本出错不能把别的拖下水。

local json = require("lx.json")
local util = require("lx.util")

local lx = rawget(_G, "__lx")
if lx then return lx end
lx = { VERSION = "1.0.0", json = json, util = util, scripts = {}, _order = {} }
rawset(_G, "__lx", lx)

local N = rawget(_G, "__luakit")
local object = __lk and __lk.object

-- ===== 日志 =====
function lx.log(fmt, ...)
    local s
    if select("#", ...) > 0 then
        local ok, r = pcall(string.format, tostring(fmt), ...)
        s = ok and r or (tostring(fmt) .. " " .. table.concat({ ... }, " "))
    else
        s = tostring(fmt)
    end
    lemurx.log("[lx] " .. s)
end

local function try(what, fn, ...)
    local ok, err = xpcall(fn, debug.traceback, ...)
    if not ok then lx.log("%s failed: %s", what, tostring(err)) end
    return ok, err
end
lx.try = try

function lx.toast(msg) pcall(lemurx.toast, tostring(msg)) end

-- 通知：有原生 lemurx.notify 就用（Java 侧补齐后），否则 toast 兜底
function lx.notify(title, text, opts)
    local n = lemurx.notify
    if type(n) == "table" and n.show then
        local ok = pcall(n.show, { title = title, text = text, url = opts and opts.url, id = opts and opts.id })
        if ok then return true end
    end
    lx.toast(text and (title .. "：" .. text) or title)
    return false
end

-- ===== 定时器 / 网络 =====
function lx.after(ms, fn) return lemurx.timer.after(ms, function() try("timer", fn) end) end
function lx.every(ms, fn) return lemurx.timer.every(ms, function() try("timer", fn) end) end
function lx.cancel(id) pcall(lemurx.timer.cancel, id) end

-- 异步 fetch：cb(r)；r = {ok, status, headers, body, url, bytes}
function lx.fetch(url, opts, cb)
    if type(opts) == "function" then cb, opts = opts, nil end
    opts = opts or {}
    if opts.timeout == nil then opts.timeout = 30000 end
    local ok, id = pcall(lemurx.http.fetch, url, opts, function(r)
        if cb then try("fetch callback", cb, r or { ok = false, error = "no response" }) end
    end)
    if not ok then
        lx.log("fetch %s: %s", url, tostring(id))
        if cb then cb({ ok = false, error = tostring(id) }) end
    end
    return ok and id or nil
end

function lx.fetch_sync(url, opts)
    local ok, r = pcall(lemurx.http.fetch, url, opts or { timeout = 20000 })
    if not ok then return { ok = false, error = tostring(r) } end
    return r
end

-- ===== 数据目录：files/lua/official_data/<id>/ =====
local Data = {}
Data.__index = Data
function Data:path(name) return self.dir .. "/" .. name end
function Data:read(name, opts)
    local ok, r = pcall(lemurx.fs.read, self:path(name), opts)
    if ok and type(r) == "table" and r.ok then return r.data end
    return nil
end
function Data:write(name, data, opts)
    pcall(lemurx.fs.mkdir, self.dir)
    local ok, r = pcall(lemurx.fs.write, self:path(name), data, opts)
    return ok and (type(r) ~= "table" or r.ok ~= false)
end
function Data:exists(name)
    local ok, r = pcall(lemurx.fs.exists, self:path(name))
    return ok and (r == true or (type(r) == "table" and r.ok))
end
function Data:remove(name) return pcall(lemurx.fs.remove, self:path(name)) end
function Data:list()
    local ok, r = pcall(lemurx.fs.list, self.dir)
    if ok and type(r) == "table" then return r.files or r end
    return {}
end
function Data:read_json(name)
    local s = self:read(name)
    if not s then return nil end
    return json.decode(s)
end
function Data:write_json(name, v) return self:write(name, json.encode(v)) end

local data_cache = {}
function lx.data(id)
    local d = data_cache[id]
    if d then return d end
    d = setmetatable({ id = id, dir = "official_data/" .. id }, Data)
    data_cache[id] = d
    return d
end

-- ===== 设置：lemurx.storage["lx.<id>"] = JSON =====
local Settings = {}
Settings.__index = Settings

local function load_settings(id)
    local ok, s = pcall(lemurx.storage.get, "lx." .. id, "")
    if ok and type(s) == "string" and s ~= "" then
        local t = json.decode(s)
        if type(t) == "table" then return t end
    end
    return {}
end

function Settings:get(key, default)
    local v = self.values[key]
    if v == nil then v = self.defaults[key] end
    if v == nil then v = default end
    if type(v) == "table" then return util.deep_copy(v) end
    return v
end

function Settings:set(key, value, silent)
    local old = self.values[key]
    if old == nil then old = self.defaults[key] end
    self.values[key] = value
    self:save()
    if not silent then
        for _, fn in ipairs(self.listeners) do try("settings listener", fn, key, value, old) end
    end
    return value
end

function Settings:update(tbl)
    for k, v in pairs(tbl) do self:set(k, v) end
end

function Settings:all()
    local out = util.deep_copy(self.defaults)
    for k, v in pairs(self.values) do out[k] = util.deep_copy(v) end
    return out
end

function Settings:reset()
    self.values = {}
    self:save()
    for _, fn in ipairs(self.listeners) do try("settings listener", fn, nil, nil, nil) end
end

function Settings:save()
    pcall(lemurx.storage.set, "lx." .. self.id, json.encode(self.values))
end

function Settings:on_change(fn) self.listeners[#self.listeners + 1] = fn end

local settings_cache = {}
function lx.settings(id, defaults)
    local s = settings_cache[id]
    if s then
        if defaults then for k, v in pairs(defaults) do if s.defaults[k] == nil then s.defaults[k] = v end end end
        return s
    end
    s = setmetatable({ id = id, defaults = defaults or {}, values = load_settings(id), listeners = {} }, Settings)
    settings_cache[id] = s
    return s
end

-- ===== 标签 / webview =====
lx.tabs = {}
function lx.tabs.current()
    local ok, t = pcall(lemurx.tabs.current)
    if ok and type(t) == "table" then return t end
    return nil
end
function lx.tabs.list()
    local ok, t = pcall(lemurx.tabs.list)
    if ok and type(t) == "table" then return t end
    return {}
end
function lx.tabs.inject(id, js, opts)
    return pcall(lemurx.tabs.inject, id, js, opts or { world = "isolated", frames = "main" })
end
function lx.tabs.eval(id, js)
    local ok, r = pcall(lemurx.tabs.eval, id, js)
    if ok then return r end
    return nil, r
end
function lx.tabs.open(url, opts)
    local ok, id = pcall(lemurx.tabs.open, url, opts)
    if ok then return id end
    return nil
end

-- 把已有标签都包成 webview（自动包装只管"以后新建"的；恢复会话的标签在这里补）
function lx.wrap_all_tabs()
    if not (__lk and __lk.wrap_tab) then return 0 end
    local n = 0
    for _, t in ipairs(lx.tabs.list()) do
        if t.id and not (__lk.webviews and __lk.webviews[t.id]) then
            local ok = pcall(__lk.wrap_tab, t.id, t.incognito)
            if ok then n = n + 1 end
        end
    end
    return n
end

local webview_hooks = {}
function lx.on_webview(fn)
    webview_hooks[#webview_hooks + 1] = fn
    if __lk and __lk.webviews then
        for _, v in pairs(__lk.webviews) do
            if object.is_alive(v) then try("on_webview", fn, v) end
        end
    end
end
if __lk and __lk.webview_created_hooks then
    table.insert(__lk.webview_created_hooks, function(view)
        for _, fn in ipairs(webview_hooks) do try("on_webview", fn, view) end
    end)
end

-- 全局导航钩子：fn(view, uri, ev) -> false 阻止 / "url" 改写（暂只能阻止；改写=阻止+navigate）/ nil 放行
-- ev = {main_frame, redirect, renderer_initiated, user_gesture, reason, tab}
local nav_hooks = {}
function lx.on_navigation(fn, priority)
    nav_hooks[#nav_hooks + 1] = { fn = fn, priority = priority or 100 }
    table.sort(nav_hooks, function(a, b) return a.priority < b.priority end)
end
local load_hooks = {}
function lx.on_load(fn) load_hooks[#load_hooks + 1] = fn end

lx.on_webview(function(view)
    view:add_signal("navigation-request", function(v, uri, reason, ev)
        ev = ev or {}
        ev.reason = ev.reason or reason
        for _, h in ipairs(nav_hooks) do
            local ok, ret = xpcall(h.fn, debug.traceback, v, uri, ev)
            if not ok then
                lx.log("navigation hook error: %s", tostring(ret))
            elseif ret == false then
                return false
            elseif type(ret) == "string" and ret ~= uri then
                -- 改写：否决当前导航，另起一次
                if ev.main_frame ~= false then
                    local tab = v.id
                    lx.after(0, function() pcall(lemurx.tabs.navigate, tab, ret) end)
                end
                return false
            end
        end
        return nil
    end)
    view:add_signal("load-status", function(v, status, uri, err)
        for _, fn in ipairs(load_hooks) do try("on_load", fn, v, status, uri, err) end
    end)
end)

-- ===== 渲染进程模块 =====
lx.web = {}
local web_modules = {}
-- lx.web.require("adblock.web" | "adblock/web"[, channel])
-- 让每个渲染进程加载该模块，返回浏览器侧的 ipc_channel。
-- 通道名必须和渲染端 W.channel(...) 用的一致，渲染进程发回来的信号才会投递到这里的
-- add_signal 处理器（__lk.ipc_deliver 按名字找通道）。约定：模块 "<id>.web" / "<id>/web" 对应
-- 通道 "lx.<id>"，也可以显式传第二个参数。
function lx.web.require(name, channel)
    if not rawget(_G, "require_web_module") then
        lx.log("require_web_module missing; luakit kernel not loaded?")
        return nil
    end
    require_web_module(name)
    web_modules[#web_modules + 1] = name
    channel = channel or ("lx." .. (name:match("^([%w_%-]+)[%./]") or name))
    return ipc_channel(channel)
end
function lx.web.channel(name) return ipc_channel(name) end
local process_hooks = {}
function lx.web.on_process(fn) process_hooks[#process_hooks + 1] = fn end
if __lk and __lk.web_process_hooks then
    table.insert(__lk.web_process_hooks, function(pid)
        for _, fn in ipairs(process_hooks) do try("web process hook", fn, pid) end
    end)
end
function lx.web.send_pid(channel, pid, signame, ...)
    if __lk and __lk.ipc_send_pid then
        return __lk.ipc_send_pid(channel, pid, signame, { ... })
    end
end
function lx.web.broadcast(channel, signame, ...)
    local ok, ch = pcall(ipc_channel, channel)
    if ok then ch:emit_signal(signame, ...) end
end
function lx.web.processes()
    if N and N.web_processes then
        local ok, t = pcall(N.web_processes)
        if ok and type(t) == "table" then return t end
    end
    return {}
end
-- 在某标签的渲染进程 Lua 状态里跑 JS（主世界，能看到 register_function 暴露的东西）
function lx.web.eval(view_or_tab, js, cb)
    if __lk and __lk.web_eval then return __lk.web_eval(view_or_tab, js, "(lx)", cb) end
end

-- ===== HTML 拼装 =====
lx.html = {}
local esc = util.escape_html
lx.html.escape = esc

lx.html.CSS = [[
:root{color-scheme:light dark;--bg:#fafafa;--fg:#1d1d1f;--card:#fff;--muted:#6e6e73;--line:#e5e5ea;--accent:#0a84ff;--danger:#ff3b30;--ok:#34c759}
@media(prefers-color-scheme:dark){:root{--bg:#000;--fg:#f5f5f7;--card:#1c1c1e;--muted:#8e8e93;--line:#2c2c2e}}
*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 -apple-system,Roboto,"PingFang SC","Noto Sans CJK SC",sans-serif}
header{position:sticky;top:0;background:var(--bg);padding:14px 16px 8px;display:flex;align-items:center;gap:10px;border-bottom:1px solid var(--line);z-index:2}
header h1{font-size:20px;margin:0;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
header a{color:var(--accent);text-decoration:none;font-size:15px}
main{padding:12px 12px 40px;max-width:720px;margin:0 auto}
.card{background:var(--card);border-radius:14px;padding:4px 16px;margin:12px 0;box-shadow:0 1px 2px rgba(0,0,0,.05)}
.row{display:flex;align-items:center;gap:12px;padding:12px 0;border-bottom:1px solid var(--line);min-height:52px}
.row:last-child{border-bottom:0}.row .l{flex:1;min-width:0}.row .t{font-size:16px}.row .d{font-size:13px;color:var(--muted);margin-top:2px;word-break:break-all}
.muted{color:var(--muted);font-size:13px}.sec{font-size:13px;color:var(--muted);margin:18px 4px 4px;text-transform:uppercase;letter-spacing:.04em}
input[type=text],input[type=number],input[type=url],select,textarea{width:100%;font:inherit;color:inherit;background:var(--bg);border:1px solid var(--line);border-radius:10px;padding:10px 12px;outline:none}
textarea{min-height:120px;font-family:ui-monospace,Menlo,monospace;font-size:13px;line-height:1.4}
input[type=text]:focus,textarea:focus,select:focus{border-color:var(--accent)}
.sw{position:relative;width:51px;height:31px;flex:none}.sw input{opacity:0;width:0;height:0}
.sw span{position:absolute;inset:0;background:#c7c7cc;border-radius:31px;transition:.2s}.sw span:before{content:"";position:absolute;width:27px;height:27px;left:2px;top:2px;background:#fff;border-radius:50%;transition:.2s;box-shadow:0 2px 4px rgba(0,0,0,.2)}
.sw input:checked+span{background:var(--ok)}.sw input:checked+span:before{transform:translateX(20px)}
button,.btn{font:inherit;font-size:15px;border:0;border-radius:10px;padding:10px 16px;background:var(--accent);color:#fff;cursor:pointer}
button.sec,.btn.sec{background:var(--line);color:var(--fg)}button.danger{background:var(--danger)}button:disabled{opacity:.5}
.actions{display:flex;gap:10px;flex-wrap:wrap;padding:12px 0}
.stat{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;padding:12px 0}
.stat div{background:var(--bg);border-radius:10px;padding:10px 12px}.stat b{display:block;font-size:22px}.stat small{color:var(--muted)}
pre,code{font-family:ui-monospace,Menlo,monospace;font-size:13px}pre{background:var(--card);padding:12px;border-radius:10px;overflow:auto;white-space:pre-wrap;word-break:break-all}
.toast{position:fixed;left:50%;bottom:32px;transform:translateX(-50%);background:#333;color:#fff;padding:10px 18px;border-radius:20px;font-size:14px;opacity:0;transition:.25s;pointer-events:none;z-index:9}
.toast.on{opacity:.95}
a{color:var(--accent)}
.badge{font-size:12px;padding:2px 8px;border-radius:10px;background:var(--line);color:var(--muted)}
.badge.on{background:rgba(52,199,89,.15);color:var(--ok)}
.list a.row{text-decoration:none;color:inherit}
.icon{font-size:26px;width:40px;text-align:center;flex:none}
]]

-- 页面内 JS 小工具：lx.api(name, args) → Promise<table>；lx.toast(msg)
lx.html.JS = [[
window.lx={
 api:function(n,a){var s=JSON.stringify(a||{});var req=s.length>1500?fetch('/api/'+n,{method:'POST',body:s,cache:'no-store',headers:{'Content-Type':'application/json'}}):fetch('/api/'+n+'?a='+encodeURIComponent(s),{cache:'no-store'});return req.then(function(r){return r.json()}).then(function(j){if(j&&j.error)throw new Error(j.error);return j})},
 toast:function(m){var t=document.getElementById('lxtoast');if(!t){t=document.createElement('div');t.id='lxtoast';t.className='toast';document.body.appendChild(t)}t.textContent=m;t.classList.add('on');clearTimeout(t._h);t._h=setTimeout(function(){t.classList.remove('on')},1800)},
 set:function(k,v){return lx.api('settings.set',{key:k,value:v})},
 get:function(){return lx.api('settings.get',{})},
 q:function(s){return document.querySelector(s)},
 qa:function(s){return Array.prototype.slice.call(document.querySelectorAll(s))},
 fmtBytes:function(n){if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KB';return (n/1048576).toFixed(1)+' MB'},
 fmtTime:function(ms){if(!ms)return '从未';var d=Date.now()-ms;if(d<60e3)return '刚刚';if(d<3600e3)return Math.floor(d/60e3)+' 分钟前';if(d<86400e3)return Math.floor(d/3600e3)+' 小时前';return new Date(ms).toLocaleDateString()}
};
document.addEventListener('change',function(e){var el=e.target;if(!el.dataset||!el.dataset.key)return;var v;
 if(el.type==='checkbox')v=el.checked;else if(el.type==='number')v=parseFloat(el.value);else if(el.tagName==='TEXTAREA'&&el.dataset.list)v=el.value.split(/\r?\n/).map(function(s){return s.trim()}).filter(Boolean);else v=el.value;
 lx.set(el.dataset.key,v).then(function(){lx.toast('已保存')}).catch(function(e){lx.toast('保存失败: '+e.message)})});
document.addEventListener('click',function(e){var b=e.target.closest('button[data-api]');if(!b)return;if(b.dataset.confirm&&!confirm(b.dataset.confirm))return;b.disabled=true;
 lx.api(b.dataset.api,b.dataset.args?JSON.parse(b.dataset.args):{}).then(function(r){lx.toast(r.message||'完成');if(r.reload)setTimeout(function(){location.reload()},600)}).catch(function(e){lx.toast(e.message)}).then(function(){b.disabled=false})});
]]

function lx.html.page(o)
    local parts = {
        "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">",
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">",
        "<title>", esc(o.title or "LemurX"), "</title>",
        "<style>", lx.html.CSS, o.css or "", "</style></head><body>",
    }
    if o.header ~= false then
        parts[#parts + 1] = "<header>"
        if o.back ~= false then
            parts[#parts + 1] = ("<a href=\"%s\">‹ %s</a>"):format(esc(o.back_url or "lemurx://scripts/"), esc(o.back_label or "脚本"))
        end
        parts[#parts + 1] = "<h1>" .. (o.icon and (esc(o.icon) .. " ") or "") .. esc(o.title or "") .. "</h1>"
        if o.header_extra then parts[#parts + 1] = o.header_extra end
        parts[#parts + 1] = "</header>"
    end
    parts[#parts + 1] = "<main>"
    parts[#parts + 1] = o.body or ""
    parts[#parts + 1] = "</main><script>" .. lx.html.JS .. (o.js or "") .. "</script></body></html>"
    return table.concat(parts)
end

-- 表单控件：schema 项 → HTML
local function control(item, value)
    local key = esc(item.key)
    local t = item.type or "bool"
    if t == "bool" then
        return ("<label class=\"sw\"><input type=\"checkbox\" data-key=\"%s\"%s><span></span></label>")
            :format(key, value and " checked" or "")
    elseif t == "number" then
        return ("<input type=\"number\" style=\"width:110px\" data-key=\"%s\" value=\"%s\"%s%s%s>")
            :format(key, esc(tostring(value or 0)),
                item.min and (" min=\"" .. item.min .. "\"") or "",
                item.max and (" max=\"" .. item.max .. "\"") or "",
                item.step and (" step=\"" .. item.step .. "\"") or "")
    elseif t == "select" then
        local opts = {}
        for _, o in ipairs(item.options or {}) do
            local ov, ol = o, o
            if type(o) == "table" then ov, ol = o[1] or o.value, o[2] or o.label end
            opts[#opts + 1] = ("<option value=\"%s\"%s>%s</option>"):format(esc(tostring(ov)),
                tostring(ov) == tostring(value) and " selected" or "", esc(tostring(ol)))
        end
        return ("<select data-key=\"%s\">%s</select>"):format(key, table.concat(opts))
    elseif t == "text" or t == "list" then
        local v = value
        if t == "list" and type(v) == "table" then v = table.concat(v, "\n") end
        return ("<textarea data-key=\"%s\"%s placeholder=\"%s\">%s</textarea>")
            :format(key, t == "list" and " data-list=\"1\"" or "", esc(item.placeholder or ""), esc(tostring(v or "")))
    elseif t == "action" then
        return ("<button data-api=\"%s\" data-args='%s' class=\"%s\"%s>%s</button>")
            :format(esc(item.api or ""), esc(json.encode(item.args or {})), esc(item.style or ""),
                item.confirm and (" data-confirm=\"" .. esc(item.confirm) .. "\"") or "", esc(item.label or "执行"))
    else -- string / url
        return ("<input type=\"%s\" data-key=\"%s\" value=\"%s\" placeholder=\"%s\">")
            :format(t == "url" and "url" or "text", key, esc(tostring(value or "")), esc(item.placeholder or ""))
    end
end

-- 由 schema 生成设置卡片。schema 项：{key, type, label, desc, section, options, min, max, placeholder}
function lx.html.settings(script, values)
    values = values or script.settings:all()
    local out, section = {}, nil
    local open = false
    for _, item in ipairs(script.schema or {}) do
        if item.section and item.section ~= section then
            if open then out[#out + 1] = "</div>" end
            section = item.section
            out[#out + 1] = "<div class=\"sec\">" .. esc(section) .. "</div><div class=\"card\">"
            open = true
        elseif not open then
            out[#out + 1] = "<div class=\"card\">"
            open = true
        end
        local t = item.type or "bool"
        local wide = (t == "text" or t == "list")
        if item.type == "info" then
            out[#out + 1] = "<div class=\"row\"><div class=\"l\"><div class=\"d\">" .. (item.html or esc(item.desc or "")) .. "</div></div></div>"
        elseif wide then
            out[#out + 1] = ("<div class=\"row\" style=\"display:block\"><div class=\"t\">%s</div>%s<div style=\"margin:8px 0\">%s</div></div>")
                :format(esc(item.label or item.key), item.desc and ("<div class=\"d\">" .. esc(item.desc) .. "</div>") or "",
                    control(item, values[item.key]))
        else
            out[#out + 1] = ("<div class=\"row\"><div class=\"l\"><div class=\"t\">%s</div>%s</div>%s</div>")
                :format(esc(item.label or item.key), item.desc and ("<div class=\"d\">" .. esc(item.desc) .. "</div>") or "",
                    control(item, values[item.key]))
        end
    end
    if open then out[#out + 1] = "</div>" end
    return table.concat(out)
end

-- ===== lemurx:// 路由 =====
-- lemurx://<id>/                 脚本主页（spec.page 或自动设置页）
-- lemurx://<id>/api/<name>?a=…   spec.api[name](args, ctx) → JSON
-- lemurx://<id>/<path>           spec.routes[path](ctx) → html | data, mime
-- lemurx://scripts/              官方脚本目录
local function json_reply(request, v, status)
    request:finish(json.encode(v), "application/json", status)
end

local function route(uri, request, ev)
    local u = util.url.parse(uri)
    local host = (u.host or ""):lower()
    local path = u.path or "/"
    local query = util.url.query(u.query)
    local ctx = { uri = uri, url = u, path = path, query = query, request = request, ev = ev, host = host }
    -- POST 正文（C++ SchemeLoader 以 base64 带过来）：页面往路由传大块数据用
    ctx.method = ev and ev.method or "GET"
    if ev and type(ev.body_b64) == "string" then
        ctx.body = util.base64_decode(ev.body_b64)
    end

    if host == "" or host == "scripts" then
        return lx._scripts_page(ctx)
    end
    local script = lx.scripts[host]
    if not script then
        return request:finish(lx.html.page({ title = "没有这个脚本", body = "<div class=\"card\"><div class=\"row\">lemurx://" .. esc(host) .. " 未注册</div></div>" }), "text/html", 404)
    end
    ctx.script = script

    -- API
    local api_name = path:match("^/api/([%w%._%-]+)$")
    if api_name then
        local args = {}
        if query.a and query.a ~= "" then args = json.decode(query.a) or {} end
        if ctx.body and ctx.body ~= "" then
            -- POST：正文就是 JSON 参数（lx.api 参数过大时前端自动改 POST）
            local b = json.decode(ctx.body)
            if type(b) == "table" then for k, v in pairs(b) do args[k] = v end end
        end
        local fn = script.api[api_name]
        if not fn then return json_reply(request, { error = "no api " .. api_name }, 404) end
        -- 异步 API：处理器返回 "async"，之后自己调 ctx.reply(v) / ctx.fail(err)
        ctx.reply = function(v) if not request.finished then json_reply(request, v or { ok = true }) end end
        ctx.fail = function(err) if not request.finished then json_reply(request, { error = tostring(err) }, 500) end end
        local ok, r, r2 = xpcall(fn, debug.traceback, args, ctx)
        if not ok then
            lx.log("api %s/%s: %s", host, api_name, tostring(r))
            return json_reply(request, { error = tostring(r):match("^[^\n]*") }, 500)
        end
        if r == "async" then return end
        if r == nil and r2 then return json_reply(request, { error = tostring(r2) }, 400) end
        if r == nil then r = { ok = true } end
        if type(r) ~= "table" then r = { value = r } end
        return json_reply(request, r)
    end

    -- 自定义路由
    local handler = script.routes[path]
    if not handler and path ~= "/" then
        -- 前缀路由 /foo/*
        for pat, h in pairs(script.routes) do
            if pat:sub(-1) == "*" and path:sub(1, #pat - 1) == pat:sub(1, -2) then handler = h break end
        end
    end
    if handler then
        local ok, body, mime, status = xpcall(handler, debug.traceback, ctx)
        if not ok then
            return request:finish(lx.html.page({ title = "出错了", body = "<pre>" .. esc(tostring(body)) .. "</pre>" }), "text/html", 500)
        end
        if body == nil then return end -- handler 自己 finish
        return request:finish(body, mime or "text/html", status)
    end
    if path == "/" then
        local ok, body = xpcall(script.page or lx._default_page, debug.traceback, ctx)
        if not ok then
            return request:finish(lx.html.page({ title = "出错了", body = "<pre>" .. esc(tostring(body)) .. "</pre>" }), "text/html", 500)
        end
        return request:finish(body, "text/html")
    end
    return request:finish(lx.html.page({ title = "404", body = "<div class=\"card\"><div class=\"row\">" .. esc(path) .. "</div></div>", back_url = "lemurx://" .. host .. "/" }), "text/html", 404)
end

function lx._default_page(ctx)
    local s = ctx.script
    local body = {}
    body[#body + 1] = ("<div class=\"card\"><div class=\"row\"><div class=\"icon\">%s</div><div class=\"l\"><div class=\"t\">%s <span class=\"badge\">v%s</span></div><div class=\"d\">%s</div></div></div></div>")
        :format(esc(s.icon or "📜"), esc(s.name), esc(s.version or "1.0"), esc(s.description or ""))
    if s.summary then
        local ok, html = pcall(s.summary, ctx)
        if ok and html then body[#body + 1] = html end
    end
    body[#body + 1] = lx.html.settings(s)
    body[#body + 1] = ("<div class=\"actions\"><button class=\"sec\" data-api=\"settings.reset\">恢复默认</button><a class=\"btn sec\" href=\"lemurx://scripts/source?f=%s\">查看源码</a></div>")
        :format(esc(s.file or ("official/" .. s.id .. ".lua")))
    return lx.html.page({ title = s.name, icon = s.icon, body = table.concat(body), js = s.page_js, css = s.page_css })
end

function lx._scripts_page(ctx)
    local path = ctx.path
    if path == "/source" then
        local f = ctx.query.f or ""
        if f:find("%.%.") then return ctx.request:finish("bad path", "text/plain", 400) end
        local ok, r = pcall(lemurx.fs.read, f)
        local src = (ok and type(r) == "table" and r.ok) and r.data or ("-- 读不到 " .. f)
        return ctx.request:finish(lx.html.page({
            title = f, back_url = "javascript:history.back()", back_label = "返回",
            body = "<pre>" .. esc(src) .. "</pre>",
        }), "text/html")
    end
    local items = {}
    for _, id in ipairs(lx._order) do
        local s = lx.scripts[id]
        local enabled = s.settings:get("enabled", true)
        items[#items + 1] = ("<a class=\"row\" href=\"lemurx://%s/\"><div class=\"icon\">%s</div><div class=\"l\"><div class=\"t\">%s</div><div class=\"d\">%s</div></div><span class=\"badge%s\">%s</span></a>")
            :format(esc(id), esc(s.icon or "📜"), esc(s.name), esc(s.description or ""), enabled and " on" or "", enabled and "已启用" or "已停用")
    end
    return ctx.request:finish(lx.html.page({
        title = "官方 Lua 脚本", back = false,
        body = "<div class=\"card list\">" .. table.concat(items) .. "</div>"
            .. "<p class=\"muted\">这些脚本随 LemurX 内置，全部用 Lua 实现，源码可看可改（复制到本地目录后改）。开关与重载在三点菜单「Lua 脚本」。</p>",
    }), "text/html")
end

local scheme_ready = false
local function ensure_scheme()
    if scheme_ready then return end
    scheme_ready = true
    if not (__lk and __lk.scheme_handlers) then
        lx.log("kernel scheme_handlers missing; lemurx:// pages unavailable")
        return
    end
    __lk.scheme_handlers["lemurx"] = function(uri, request, ev)
        local ok, err = xpcall(route, debug.traceback, uri, request, ev)
        if not ok then
            lx.log("route %s: %s", uri, tostring(err))
            if not request.finished then
                pcall(request.finish, request, lx.html.page({ title = "出错了", body = "<pre>" .. esc(tostring(err)) .. "</pre>" }), "text/html", 500)
            end
        end
    end
    if rawget(_G, "luakit") and luakit.register_scheme then
        pcall(luakit.register_scheme, "lemurx")
    end
end

-- ===== 注册 =====
function lx.register(spec)
    assert(type(spec) == "table" and type(spec.id) == "string", "lx.register: spec.id required")
    local id = spec.id
    local existing = lx.scripts[id]
    local script = existing or { id = id }
    for k, v in pairs(spec) do script[k] = v end
    script.name = script.name or id
    script.api = script.api or {}
    script.routes = script.routes or {}
    script.schema = script.schema or {}
    local defaults = script.settings_defaults or spec.settings or {}
    if defaults.enabled == nil then defaults.enabled = true end
    script.settings_defaults = defaults
    script.settings = lx.settings(id, defaults)

    -- 内置 API
    script.api["settings.get"] = script.api["settings.get"] or function() return script.settings:all() end
    script.api["settings.set"] = script.api["settings.set"] or function(args)
        if type(args.key) ~= "string" then return nil, "key required" end
        script.settings:set(args.key, args.value)
        return { ok = true }
    end
    script.api["settings.reset"] = script.api["settings.reset"] or function()
        script.settings:reset()
        return { ok = true, message = "已恢复默认", reload = true }
    end
    script.api["info"] = script.api["info"] or function()
        return { id = id, name = script.name, version = script.version, description = script.description }
    end

    if not existing then
        lx._order[#lx._order + 1] = id
        lx.scripts[id] = script
    end
    ensure_scheme()

    -- 菜单项
    if script.menu then
        for _, m in ipairs(script.menu) do
            pcall(lemurx.menu.add, {
                id = "lx." .. id .. "." .. (m.id or m.title), title = m.title, page = m.page or "main",
                onClick = function(ev)
                    if m.onClick then
                        try("menu " .. m.title, m.onClick, ev)
                    elseif m.url then
                        lx.tabs.open(m.url)
                    else
                        lx.tabs.open("lemurx://" .. id .. "/")
                    end
                end,
            })
        end
    end
    lx.log("registered %s v%s", id, tostring(script.version or "?"))
    return script
end

-- 打开脚本设置页
function lx.open(id) return lx.tabs.open("lemurx://" .. (id or "scripts") .. "/") end

-- ===== 启动收尾 =====
-- 官方脚本在内核之后加载，此时恢复会话的标签已存在但未包装：补上
lx.after(0, function() lx.wrap_all_tabs() end)
pcall(lemurx.tabs.on, "created", function() end) -- 确保 tabs 事件通道已激活

return lx
