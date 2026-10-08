-- lx.web —— 官方脚本框架 · 渲染进程半边。
--
-- 每个渲染进程一个 Lua 状态；这里把 luakit 的 page 信号整理成一条有序管线，
-- 多个官方 web 模块（adblock_web、darkreader_web…）共用，互不踩脚：
--
--   local W = require("lx.web")
--   W.on_request(function(page, url, headers, info) return false end, 10)   -- 阻止；返回 url 重定向
--   W.on_window_cleared(function(page, uri) ... end)      -- 新文档：注 CSS / 挂桥的最早时机
--   W.on_document_loaded(function(page) ... end)          -- DOMContentLoaded
--   W.on_page_created(function(page) ... end)
--   W.css(page, css, key)  / W.uncss(page, key)           -- 用户样式表（绕 CSP）
--   W.eval(page, js)                                      -- 主世界执行
--   W.expose(page, name, fn(page, args...) -> value)       -- 页面 JS 里可调 window[name](...) → Promise
--   W.channel(name) -> ipc_channel                        -- 与浏览器进程通话
--   W.host_of(uri) W.page_host(page)
--
-- 请求管线：按 priority 升序跑，任一返回 false 即拦截（后面不跑）；第一个返回字符串的重定向生效；
-- headers 表所有处理器共享可改。

local json = require("lx.json")
local util = require("lx.util")

local W = rawget(_G, "__lx_web")
if W then return W end
W = { json = json, util = util }
rawset(_G, "__lx_web", W)

local N = __luakit_web

function W.log(fmt, ...)
    local s = select("#", ...) > 0 and (pcall(string.format, fmt, ...) and string.format(fmt, ...) or tostring(fmt)) or tostring(fmt)
    if rawget(_G, "msg") then msg.info("[lx] %s", s) else N.log(2, "lx", s) end
end

local function try(what, fn, ...)
    local ok, err = xpcall(fn, debug.traceback, ...)
    if not ok then W.log("%s failed: %s", what, tostring(err)) end
    return ok, err
end
W.try = try

W.host_of = util.host_of
function W.page_host(page)
    local ok, uri = pcall(function() return page.uri end)
    if not ok or type(uri) ~= "string" then return "" end
    return util.host_of(uri)
end

-- ===== 钩子表 =====
local req_hooks, wc_hooks, dl_hooks, pc_hooks, pd_hooks = {}, {}, {}, {}, {}
local function add_sorted(list, fn, prio)
    list[#list + 1] = { fn = fn, p = prio or 100 }
    table.sort(list, function(a, b) return a.p < b.p end)
end
function W.on_request(fn, prio) add_sorted(req_hooks, fn, prio) end
function W.on_window_cleared(fn, prio) add_sorted(wc_hooks, fn, prio) end
function W.on_document_loaded(fn, prio) add_sorted(dl_hooks, fn, prio) end
function W.on_page_destroyed(fn, prio) add_sorted(pd_hooks, fn, prio) end
function W.on_page_created(fn, prio)
    add_sorted(pc_hooks, fn, prio)
    for _, p in pairs(__lk.pages()) do try("on_page_created", fn, p) end
end

-- 钩子 fn(page, uri, headers, info)：
--   return false      -> 拦截
--   return "new-url"  -> 改写（第一个改写者胜出，后续钩子看到的是新 URL）
--   改 headers 表     -> 改请求头（Referer 置 nil 即去掉 referrer）
--   info.opts.credentials = "omit" -> 不带 Cookie 发出（第三方追踪防护用）
local function run_request(page, uri, headers, info)
    info = info or {}
    local redirect
    for i = 1, #req_hooks do
        local ok, ret = xpcall(req_hooks[i].fn, debug.traceback, page, uri, headers, info)
        if not ok then
            W.log("request hook error: %s", tostring(ret))
        elseif ret == false then
            return false
        elseif type(ret) == "string" and not redirect and ret ~= uri then
            redirect = ret
            uri = ret
        end
    end
    return redirect
end

local attached = setmetatable({}, { __mode = "k" })
local function attach(page)
    if attached[page] then return end
    attached[page] = true
    page:add_signal("send-request", function(p, uri, headers, info)
        if #req_hooks == 0 then return nil end
        return run_request(p, uri, headers, info)
    end)
    page:add_signal("window-object-cleared", function(p, uri)
        for _, h in ipairs(wc_hooks) do try("window-object-cleared", h.fn, p, uri) end
    end)
    page:add_signal("document-loaded", function(p)
        for _, h in ipairs(dl_hooks) do try("document-loaded", h.fn, p) end
    end)
    page:add_signal("destroy", function(p)
        for _, h in ipairs(pd_hooks) do try("destroy", h.fn, p) end
        attached[p] = nil
    end)
    for _, h in ipairs(pc_hooks) do try("page-created", h.fn, page) end
end

luakit.add_signal("page-created", function(page) attach(page) end)
for _, p in pairs(__lk.pages()) do attach(p) end

-- ===== CSS / JS =====
-- 用户样式表：Blink InsertStyleSheet，不受页面 CSP 限制，页面脚本看不到；文档换掉后需重注。
function W.css(page, css, key)
    if not css or css == "" then return nil end
    local ok, k, err = pcall(page.insert_css, page, css, key)
    if not ok then W.log("insert_css: %s", tostring(k)) return nil end
    if k == nil and err then W.log("insert_css: %s", tostring(err)) end
    return k
end
function W.uncss(page, key)
    if not key then return end
    pcall(page.remove_css, page, key)
end

-- 主世界执行；返回值经 JSON 化处理（句柄类值退化为字符串）
function W.eval(page, js, source)
    local ok, v, err = pcall(page.eval_js, page, js, { source = source or "lx" })
    if not ok then return nil, tostring(v) end
    return v, err
end

-- 把 Lua 函数暴露给页面：window[name](...args) → Promise
-- fn(page, ...) 返回值直接 resolve；抛错 reject。pattern 是 Lua 模式，匹配 page.uri（默认全部）
local exposed = {}
function W.expose(name, fn, pattern)
    if exposed[name] then
        exposed[name] = fn
        return
    end
    exposed[name] = fn
    luakit.register_function(pattern or ".", name, function(page, resolve, reject, ...)
        local f = exposed[name]
        if type(f) == "table" then f = f.async return f(page, resolve, reject, ...) end
        local ok, ret = xpcall(f, debug.traceback, page, ...)
        if ok then
            local ok2, err = pcall(resolve, ret)
            if not ok2 then W.log("expose %s resolve: %s", name, tostring(err)) end
        else
            W.log("expose %s: %s", name, tostring(ret))
            pcall(reject, tostring(ret))
        end
    end)
end

-- 异步版：fn(page, resolve, reject, ...)，自己决定何时 resolve（IPC 往返 / 定时器之后）
function W.expose_async(name, fn, pattern)
    if exposed[name] then
        exposed[name] = { async = fn }
        return
    end
    exposed[name] = { async = fn }
    luakit.register_function(pattern or ".", name, function(page, resolve, reject, ...)
        local f = exposed[name]
        f = type(f) == "table" and f.async or f
        local ok, err = xpcall(f, debug.traceback, page, function(v) pcall(resolve, v) end, function(e) pcall(reject, tostring(e)) end, ...)
        if not ok then
            W.log("expose_async %s: %s", name, tostring(err))
            pcall(reject, tostring(err))
        end
    end)
end

-- 页面级弱表状态：W.state(page) 取/建一个跟 page 生命周期绑定的表
local page_states = setmetatable({}, { __mode = "k" })
function W.state(page, ns)
    local s = page_states[page]
    if not s then s = {} page_states[page] = s end
    if ns then
        local n = s[ns]
        if not n then n = {} s[ns] = n end
        return n
    end
    return s
end
W.on_page_destroyed(function(page) page_states[page] = nil end, 1000)

-- 挂到 window-object-cleared 之后、documentElement 存在时执行 JS（比 document-loaded 早，比 wc 稳）
function W.on_document_start(fn, prio)
    W.on_window_cleared(function(page, uri) fn(page, uri) end, prio)
end

-- ===== IPC =====
local channels = {}
function W.channel(name)
    local ch = channels[name]
    if ch then return ch end
    ch = ipc_channel(name)
    channels[name] = ch
    return ch
end
W.pid = luakit.web_process_id

-- ===== 小工具 =====
-- 简单的 JS 字面量编码（放进 eval 的脚本里）
function W.js_string(s) return json.encode(tostring(s)) end

-- 在文档里跑一段脚本，等 documentElement 存在（window-object-cleared 时它可能还没建）
function W.eval_when_ready(page, js)
    local wrapped = "(function(){var f=function(){" .. js .. "};if(document.documentElement){f()}else{new MutationObserver(function(m,o){if(document.documentElement){o.disconnect();f()}}).observe(document,{childList:true})}})()"
    return W.eval(page, wrapped)
end

return W
