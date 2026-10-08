-- page 类（对应 luakit extension/clib/page.c）
--
--   属性：uri id document
--   方法：eval_js(script, {source=}) → 值 | nil, err（结果是 JS 函数时返回可调用的 Lua 函数）
--         wrap_js(body, {argnames}) → Lua 函数
--   信号："send-request"(uri, headers, info) → 返回字符串重定向 / false 拦截；headers 表可改
--             info = {type, destination, method, initiator, main_frame}（LemurX 扩展）
--         "window-object-cleared"(uri)  新文档 window 就绪、页面脚本未跑（LemurX 扩展）
--         "document-loaded"  "destroy"
--   LemurX 扩展方法：insert_css(css[, key]) → key   remove_css(key)   （用户样式表，绕 CSP）
--
-- 一个 page 对应一个渲染进程里的主框架（routing_id）。page.id == UI 侧 webview.id。

local N = __luakit_web
local object = __lk.object
local js = __lk.js
local unpack = table.unpack

local pages = {}        -- rid -> page
local page_ids = {}     -- rid -> page_id

local function callerinfo()
    local info = debug.getinfo(3, "Sl")
    if not info then return "(lua)" end
    return (info.short_src or "?") .. ":" .. tostring(info.currentline or 0)
end

local page = object.class("page", {
    props = {
        uri = { get = function(o) return N.page_uri(object.priv(o).rid) end },
        id = { get = function(o) return page_ids[object.priv(o).rid] end },
        document = { get = function(o)
            local doc, err = __lk.document_for(object.priv(o).rid)
            if not doc then error("page.document: " .. tostring(err), 2) end
            return doc
        end },
    },
    methods = {
        eval_js = function(o, script, opts)
            if type(script) ~= "string" then error("page:eval_js expects a script string", 2) end
            local source
            if type(opts) == "table" and rawget(opts, "source") then source = tostring(opts.source) end
            source = source or callerinfo()
            local rid = object.priv(o).rid
            js.current_rid = rid
            local v, err = N.page_eval(rid, script, source)
            if v == nil and err then return nil, err end
            return __lk.wrap_value(v, rid)
        end,
        wrap_js = function(o, body, argnames)
            if type(body) ~= "string" then error("page:wrap_js expects a script string", 2) end
            local names = {}
            if type(argnames) == "table" then
                for i, n in ipairs(argnames) do names[i] = tostring(n) end
            end
            local src = "(function(" .. table.concat(names, ",") .. "){" .. body .. "})"
            local fn, err = o:eval_js(src, { source = callerinfo() })
            if fn == nil then return nil, err end
            return fn
        end,
    },
    tostring = function(o)
        local p = object.priv(o)
        return ("page: %s (id %s)"):format(tostring(p.rid), tostring(page_ids[p.rid]))
    end,
})

-- 构造/查找
function __lk.page_for(rid)
    local p = pages[rid]
    if p and object.is_alive(p) then return p end
    if not N.page_alive(rid) then return nil end
    p = object.new(page, { rid = rid })
    pages[rid] = p
    return p
end

function __lk.page_created(rid, page_id)
    page_ids[rid] = page_id
    local p = __lk.page_for(rid)
    if p then
        __lk.emit_luakit_signal("page-created", p)
    end
    return p
end

function __lk.page_destroyed(rid)
    local p = pages[rid]
    pages[rid] = nil
    __lk.dom_destroy_page(rid)
    if p and object.is_alive(p) then
        pcall(object.emit_signal, p, "destroy")
        object.destroy(p)
    end
    page_ids[rid] = nil
end

function __lk.page_document_loaded(rid)
    local p = __lk.page_for(rid)
    if p then object.emit_signal(p, "document-loaded") end
end

function __lk.page_window_cleared(rid, uri)
    -- 新文档：旧 DOM 对象全部作废（"destroy"），page 对象本身不变
    __lk.dom_destroy_page(rid)
    local p = __lk.page_for(rid)
    if not p then return end
    if __lk.on_window_cleared then __lk.on_window_cleared(p, rid, uri) end
    -- LemurX 扩展：新文档的 window 刚建好、页面脚本还没跑。这是注 CSS / 挂 JS 桥的最早时机。
    object.emit_signal(p, "window-object-cleared", uri)
end

-- 子资源请求：返回 (verdict, headers)
-- info（LemurX 扩展，luakit 原版没有）= {type, destination, method, initiator, main_frame, mode}
function __lk.page_send_request(rid, uri, headers, info)
    local p = pages[rid]
    if not (p and object.is_alive(p)) then return nil, headers end
    local ret = object.emit_signal(p, "send-request", uri, headers, info)
    -- info.opts 由处理器按需填写：{ credentials = "omit" } 等，交给 C++ 改 ResourceRequest
    return ret, headers, info and info.opts or nil
end

-- 注入用户样式表（Blink InsertStyleSheet，绕过页面 CSP，页面脚本不可见）
-- page:insert_css(css [, key]) -> key ; page:remove_css(key)
page.__methods.insert_css = function(o, css, key)
    if type(css) ~= "string" then error("page:insert_css expects a css string", 2) end
    return N.page_insert_css(object.priv(o).rid, css, key)
end
page.__methods.remove_css = function(o, key)
    if type(key) ~= "string" then error("page:remove_css expects a key", 2) end
    return N.page_remove_css(object.priv(o).rid, key)
end

function __lk.pages() return pages end
function __lk.page_id_for(rid) return page_ids[rid] end
function __lk.rid_for_page_id(id)
    for rid, pid in pairs(page_ids) do
        if pid == id then return rid end
    end
end

_G.page = page
return page
