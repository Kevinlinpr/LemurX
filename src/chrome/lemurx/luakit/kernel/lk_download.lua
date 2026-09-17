-- download 类（对应 luakit clib/download.c）
--   local d = download{ uri = "https://..." } ; d:start() ; d:cancel()
--   属性：uri destination(rw) allow_overwrite(rw) status progress current_size total_size
--         content_length mime_type suggested_filename elapsed_time error
--   status ∈ "created" | "started" | "cancelled" | "failed" | "finished"
--   信号：decide-destination(suggested) created-destination(path) error(msg) finished
--         property::destination property::allow-overwrite …
--   luakit.add_signal("download-start", fn(d, view))：浏览器自己触发的下载
--
-- 后端：lemurx.downloads.enqueue 起下载；进度/完成由 P1 的下载事件回投 __lk.download_update。

local object = __lk.object

local download
local by_native_id = {} -- lemurx 下载 id -> 对象

local function set_status(obj, status)
    local p = object.priv(obj)
    if p.status == status then return end
    p.status = status
    object.property_signal(obj, "status")
end

download = object.class("download", {
    props = {
        uri = { get = function(obj) return object.priv(obj).uri end },
        destination = {
            get = function(obj) return object.priv(obj).destination end,
            set = function(obj, v)
                if v ~= nil and type(v) ~= "string" then error("destination must be a string", 3) end
                object.priv(obj).destination = v
            end,
        },
        allow_overwrite = {
            get = function(obj) return object.priv(obj).allow_overwrite == true end,
            set = function(obj, v) object.priv(obj).allow_overwrite = v and true or false end,
        },
        status = { get = function(obj) return object.priv(obj).status end },
        progress = {
            get = function(obj)
                local p = object.priv(obj)
                if p.total_size and p.total_size > 0 then
                    return math.min(1, (p.current_size or 0) / p.total_size)
                end
                return p.status == "finished" and 1 or 0
            end,
        },
        current_size = { get = function(obj) return object.priv(obj).current_size or 0 end },
        total_size = { get = function(obj) return object.priv(obj).total_size or 0 end },
        content_length = { get = function(obj) return object.priv(obj).total_size or 0 end },
        mime_type = { get = function(obj) return object.priv(obj).mime_type end },
        suggested_filename = {
            get = function(obj)
                local p = object.priv(obj)
                if p.suggested_filename then return p.suggested_filename end
                local name = (p.uri or ""):match("([^/?#]+)[?#]?[^/]*$")
                return name and name ~= "" and name or "download"
            end,
        },
        elapsed_time = {
            get = function(obj)
                local p = object.priv(obj)
                if not p.started_at then return 0 end
                return (p.finished_at or __luakit.time()) - p.started_at
            end,
        },
        error = { get = function(obj) return object.priv(obj).error end },
    },
    methods = {
        start = function(obj)
            local p = object.priv(obj)
            if p.status ~= "created" then
                error("download:start(): download already " .. tostring(p.status), 2)
            end
            if not p.destination then
                -- 让 lib（downloads.lua）先给目的地
                local suggested = obj.suggested_filename
                object.emit_ignore(obj, "decide-destination", suggested)
            end
            p.started_at = __luakit.time()
            set_status(obj, "started")
            local ok, id = pcall(lemurx.downloads.enqueue, p.uri, p.tab_id)
            if not ok or not id then
                p.error = tostring(id or "enqueue failed")
                set_status(obj, "failed")
                object.emit_ignore(obj, "error", p.error)
                return
            end
            p.native_id = id
            by_native_id[tostring(id)] = obj
            if p.destination then
                object.emit_ignore(obj, "created-destination", p.destination)
            end
        end,
        cancel = function(obj)
            local p = object.priv(obj)
            if p.status == "finished" or p.status == "cancelled" or p.status == "failed" then return end
            if p.native_id and lemurx.downloads.cancel then
                pcall(lemurx.downloads.cancel, p.native_id)
            end
            p.finished_at = __luakit.time()
            set_status(obj, "cancelled")
        end,
    },
    new = function(props)
        if type(props) ~= "table" or type(props.uri) ~= "string" then
            error("download{} requires a 'uri' string", 3)
        end
        local obj = object.new(download, {
            uri = props.uri,
            status = "created",
            current_size = 0,
            total_size = 0,
            allow_overwrite = false,
        })
        if props.destination then obj.destination = props.destination end
        return obj
    end,
})

-- 原生/P1 回投：{ id, state, bytes, total, path, mime, error, url }
__lk.download_update = function(ev)
    if type(ev) ~= "table" then return end
    local obj = by_native_id[tostring(ev.id)]
    if not obj then
        -- 浏览器自己触发（点链接下载）：造一个对象并发 download-start
        if ev.state == "started" or ev.state == "created" or ev.state == "in_progress" then
            obj = object.new(download, {
                uri = ev.url or "",
                status = "created",
                current_size = ev.bytes or 0,
                total_size = ev.total or 0,
                allow_overwrite = false,
                native_id = ev.id,
                started_at = __luakit.time(),
                mime_type = ev.mime,
                suggested_filename = ev.filename,
                tab_id = ev.tabId,
            })
            by_native_id[tostring(ev.id)] = obj
            local view = __lk.webview_for_tab and __lk.webview_for_tab(ev.tabId) or nil
            luakit.emit_signal("download-start", obj, view)
            set_status(obj, "started")
        else
            return
        end
    end
    local p = object.priv(obj)
    if ev.bytes then p.current_size = ev.bytes; object.property_signal(obj, "current_size") end
    if ev.total then p.total_size = ev.total; object.property_signal(obj, "total_size") end
    if ev.mime then p.mime_type = ev.mime end
    if ev.path and ev.path ~= p.destination then
        p.destination = ev.path
        object.property_signal(obj, "destination")
        object.emit_ignore(obj, "created-destination", ev.path)
    end
    local s = ev.state
    if s == "complete" or s == "finished" then
        p.finished_at = __luakit.time()
        set_status(obj, "finished")
        object.emit_ignore(obj, "finished")
    elseif s == "cancelled" then
        p.finished_at = __luakit.time()
        set_status(obj, "cancelled")
    elseif s == "interrupted" or s == "failed" then
        p.error = ev.error or "download failed"
        p.finished_at = __luakit.time()
        set_status(obj, "failed")
        object.emit_ignore(obj, "error", p.error)
    elseif s == "in_progress" or s == "started" then
        set_status(obj, "started")
    end
end

-- 下载事件源：lemurx.tabs.on("download") 一类事件由 P1 补；有则接上
if lemurx.downloads and lemurx.downloads.on then
    pcall(lemurx.downloads.on, "update", __lk.download_update)
end

_G.download = download
return download
