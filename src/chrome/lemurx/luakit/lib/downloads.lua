-- LemurX · luakit-compatible library · downloads
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "downloads" module API. No luakit code is used.
--
-- 下载管理（数据层，页面见 downloads_chrome）：
--   downloads.add(uri | download, { window=, filename=, dir= }) -> download
--   downloads.get_all() -> {download}     downloads.to_download(id) -> d, data
--   downloads.get(id) -> data { id, download, created, dir, opening, w }
--   downloads.open(id, w)  do_open(d, w)  cancel(id)  remove(id)  restart(id)  clear()
--   downloads.default_dir  （读写代理到设置 downloads.default_dir，默认 xdg.download_dir）
-- 信号：download-location(uri, filename) -> 路径      open-file(file, mime, w) -> true 已处理
--       download::status(d, data)  status-tick  removed-download(id)  cleared-downloads
-- 接入：luakit "download-start"(d, view) 与 webview "download-request"(view, d)。

local lousy = require("lousy")
local settings = require("settings")

local M = {}
lousy.signal.setup(M, true)

settings.register_settings({
    ["downloads.default_dir"] = {
        type = "string",
        default = (xdg and xdg.download_dir) or (luakit.data_dir .. "/downloads"),
        desc = "Directory that finished downloads are written to.",
    },
})

local records = {}     -- id -> data
local by_download = setmetatable({}, { __mode = "k" })
local next_id = 0
local tick = nil

local RUNNING = { created = true, started = true }

local function is_running(d)
    local ok, st = pcall(function() return d.status end)
    return ok and RUNNING[st] == true
end

local function any_running()
    for _, rec in pairs(records) do
        if is_running(rec.download) then return true end
    end
    return false
end

local function update_timer()
    if any_running() then
        if not tick then
            tick = timer({ interval = 1000 })
            tick:add_signal("timeout", function()
                M.emit_signal("status-tick")
                if not any_running() then update_timer() end
            end)
        end
        if not tick.started then tick:start() end
    elseif tick and tick.started then
        tick:stop()
    end
end

function M.get_all()
    local ids = {}
    for id in pairs(records) do ids[#ids + 1] = id end
    table.sort(ids)
    local out = {}
    for _, id in ipairs(ids) do out[#out + 1] = records[id].download end
    return out
end

function M.get(id)
    if type(id) == "number" then return records[id] end
    return by_download[id]
end

function M.to_download(id)
    local rec = M.get(id)
    if not rec then return nil end
    return rec.download, rec
end

local function basename_for(d, rec)
    local name = rec.filename
    if not name or name == "" then
        local ok, suggested = pcall(function() return d.suggested_filename end)
        name = ok and suggested or nil
    end
    if not name or name == "" then name = "download" end
    return (name:gsub("[/\\]", "_"))
end

local function decide_destination(d, rec)
    local filename = basename_for(d, rec)
    local uri = d.uri
    local path = M.emit_signal("download-location", uri, filename)
    if type(path) ~= "string" or path == "" then
        local dir = rec.dir or M.default_dir
        path = dir .. "/" .. filename
    end
    pcall(function() d.destination = path end)
    return path
end

function M.do_open(d, w)
    local file = d.destination
    local mime = d.mime_type
    local handled = M.emit_signal("open-file", file, mime, w)
    if handled then return true end
    if lemurx and lemurx.downloads and lemurx.downloads.open then
        local rec = by_download[d]
        local ok = pcall(lemurx.downloads.open, rec and rec.native_id or file)
        if ok then return true end
    end
    local text = ("Nothing registered to open %s"):format(tostring(file))
    if w and w.warning then w:warning(text) else msg.warn("%s", text) end
    return false
end

function M.add(what, opts)
    opts = opts or {}
    local d
    if type(what) == "string" then
        if what == "" then error("downloads.add: empty uri", 2) end
        d = download({ uri = what })
    elseif type(what) == "download" then
        d = what
    else
        error("downloads.add: expected a uri or download object", 2)
    end
    local existing = by_download[d]
    if existing then return d end

    next_id = next_id + 1
    local rec = {
        id = next_id,
        download = d,
        created = os.time(),
        started_at = luakit.time(),
        w = opts.window,
        dir = opts.dir,
        filename = opts.filename,
        opening = false,
    }
    records[rec.id] = rec
    by_download[d] = rec

    d:add_signal("decide-destination", function(dd) decide_destination(dd, rec) end)
    d:add_signal("property::status", function(dd)
        M.emit_signal("download::status", dd, rec)
        if dd.status == "finished" and rec.opening then
            rec.opening = false
            M.do_open(dd, rec.w)
        end
        update_timer()
    end)
    d:add_signal("error", function(dd, err)
        local text = ("Download failed: %s (%s)"):format(tostring(err), tostring(dd.uri))
        if rec.w and rec.w.error then rec.w.error(rec.w, text) else msg.warn("%s", text) end
    end)

    if d.status == "created" then
        if not d.destination then decide_destination(d, rec) end
        d:start()
    elseif not d.destination then
        decide_destination(d, rec)
    end
    M.emit_signal("download::status", d, rec)
    update_timer()
    return d
end

function M.open(id, w)
    local d, rec = M.to_download(id)
    if not d then return false end
    if d.status == "finished" then return M.do_open(d, w or rec.w) end
    rec.opening = true
    if w then rec.w = w end
    return true
end

function M.cancel(id)
    local d = M.to_download(id)
    if not d then return false end
    if is_running(d) then d:cancel() end
    update_timer()
    return true
end

function M.remove(id)
    local d, rec = M.to_download(id)
    if not d then return false end
    if is_running(d) then pcall(d.cancel, d) end
    records[rec.id] = nil
    by_download[d] = nil
    M.emit_signal("removed-download", rec.id)
    update_timer()
    return true
end

function M.restart(id)
    local d, rec = M.to_download(id)
    if not d then return nil end
    local uri = d.uri
    local w, dir, filename = rec.w, rec.dir, rec.filename
    M.remove(rec.id)
    return M.add(uri, { window = w, dir = dir, filename = filename })
end

function M.clear()
    for id, rec in pairs(records) do
        if not is_running(rec.download) then
            records[id] = nil
            by_download[rec.download] = nil
        end
    end
    M.emit_signal("cleared-downloads")
    update_timer()
end

-- 给页面用的快照
function M.snapshot(d, rec)
    rec = rec or by_download[d]
    local function safe(k) local ok, v = pcall(function() return d[k] end) return ok and v or nil end
    local elapsed = safe("elapsed_time") or 0
    local cur = safe("current_size") or 0
    return {
        id = rec and rec.id,
        uri = safe("uri"),
        destination = safe("destination"),
        status = safe("status"),
        progress = safe("progress") or 0,
        current_size = cur,
        total_size = safe("total_size") or 0,
        mime_type = safe("mime_type"),
        error = safe("error"),
        elapsed = elapsed,
        speed = elapsed > 0 and (cur / elapsed) or 0,
        created = rec and rec.created,
        filename = basename_for(d, rec or {}),
    }
end

-- ====================================================================
-- 接入浏览器触发的下载
-- ====================================================================
local function window_of(view)
    if not view then return nil end
    local ok, webview = pcall(require, "webview")
    if ok and webview and webview.window then
        local ok2, w = pcall(webview.window, view)
        if ok2 then return w end
    end
    return nil
end

luakit.add_signal("download-start", function(d, view)
    M.add(d, { window = window_of(view) })
    return true
end)

local hooked = setmetatable({}, { __mode = "k" })
local function hook(view)
    if hooked[view] then return end
    hooked[view] = true
    view:add_signal("download-request", function(v, d)
        if type(d) ~= "download" then return end
        M.add(d, { window = window_of(v) })
        return true
    end)
end
do
    local ok, webview = pcall(require, "webview")
    if ok and type(webview) == "table" and webview.add_signal then
        webview.add_signal("init", function(view) hook(view) end)
    end
    widget.add_signal("create", function(w) if w.type == "webview" then hook(w) end end)
end

-- ====================================================================
-- 按键 / 命令
-- ====================================================================
do
    local ok, modes = pcall(require, "modes")
    if ok and modes then
        modes.add_binds("normal", {
            { "<Control-D>", "Download a URL (opens the :download prompt).", function(w) w:enter_cmd(":download ") end },
        })
        modes.add_cmds({
            { ":down[load]", "Download the given URL: :download <uri>", function(w, o)
                local uri = ((o and o.arg) or ""):match("^%s*(%S+)")
                if not uri then w:error("Usage: :download <uri>") return end
                M.add(uri, { window = w })
                w:notify("Downloading " .. uri)
            end },
        })
    end
end

-- downloads.default_dir ↔ settings（保留 lousy.signal.setup 可能装上的元表）
do
    local old = getmetatable(M)
    local old_index = old and old.__index
    local old_newindex = old and old.__newindex
    local mt = {}
    for k, v in pairs(old or {}) do mt[k] = v end
    mt.__index = function(t, k)
        if k == "default_dir" then return settings.get_setting("downloads.default_dir") end
        if type(old_index) == "function" then return old_index(t, k) end
        if type(old_index) == "table" then return old_index[k] end
        return nil
    end
    mt.__newindex = function(t, k, v)
        if k == "default_dir" then settings.set_setting("downloads.default_dir", v) return end
        if type(old_newindex) == "function" then return old_newindex(t, k, v) end
        rawset(t, k, v)
    end
    setmetatable(M, mt)
end

return M
