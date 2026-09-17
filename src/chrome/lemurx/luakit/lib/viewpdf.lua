-- LemurX · luakit-compatible library · viewpdf
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "viewpdf" module API. No luakit code is used.
--
-- PDF 下载改为在新标签里打开（Chromium 自带 PDF 渲染）。
-- 接法（按可用性）：
--   * downloads 模块（C 组）已加载：它把 luakit "download-start" 吞掉了（返回 true），所以改听它的
--     "download::status"(d, rec)——首个事件在 downloads.add 里同步触发；是 PDF 就在 rec.w 开标签，
--     然后 downloads.remove(id) 取消这次下载。另外接 "open-file"(file, mime, w)：已下载完的 PDF 用
--     file:// 在新标签打开。
--   * 否则直接监听 luakit "download-start"(d, view)：是 PDF 就取消下载并 w:new_tab(uri)。
-- 公开接口：viewpdf.is_pdf(download|{mime_type=,uri=}) viewpdf.open(download, view_or_window)
--   viewpdf.enabled（读写）

local window = require("window")

local _M = {}

local enabled = true
local handled = setmetatable({}, { __mode = "k" })  -- download -> true

function _M.is_pdf(d)
    local mime = tostring(d.mime_type or ""):lower()
    if mime == "application/pdf" or mime == "application/x-pdf" then return true end
    local uri = tostring(d.uri or "")
    local path = uri:match("^[^?#]*") or uri
    return path:lower():match("%.pdf$") ~= nil
end

local function is_window(x)
    return type(x) == "table" and type(x.new_tab) == "function" and x.tabs ~= nil
end

local function find_window(hint)
    if is_window(hint) then return hint end
    local ok, webview = pcall(require, "webview")
    if ok and webview and webview.window and hint then
        local okw, w = pcall(webview.window, hint)
        if okw and w then return w end
    end
    if window.current then
        local okc, w = pcall(window.current)
        if okc and w then return w end
    end
    for _, w in pairs(window.bywidget or {}) do return w end
end

function _M.open(d, hint)
    local uri = d.uri
    if d.status == "finished" and d.destination then uri = "file://" .. d.destination end
    if type(uri) ~= "string" or uri == "" then return false end
    local w = find_window(hint)
    if not w then return false end
    w:new_tab(uri, { switch = true })
    return true
end

local function on_download_start(d, view)
    if not enabled or handled[d] then return end
    if not _M.is_pdf(d) then return end
    if _M.open(d, view) then
        handled[d] = true
        pcall(d.cancel, d)
        return true
    end
end

local downloads = package.loaded["downloads"]
if downloads and type(downloads.add_signal) == "function" then
    downloads.add_signal("download::status", function(d, rec)
        if not enabled or handled[d] or type(d) ~= "download" then return end
        if d.status == "finished" or d.status == "cancelled" or d.status == "error" then return end
        if not _M.is_pdf(d) then return end
        if _M.open(d, rec and rec.w) then
            handled[d] = true
            if rec and rec.id and downloads.remove then
                pcall(downloads.remove, rec.id)
            else
                pcall(d.cancel, d)
            end
        end
    end)
    downloads.add_signal("open-file", function(file, mime, w)
        if not enabled then return end
        if not _M.is_pdf({ mime_type = mime, uri = file }) then return end
        local ww = find_window(w)
        if not ww then return end
        ww:new_tab("file://" .. tostring(file), { switch = true })
        return true
    end)
else
    luakit.add_signal("download-start", on_download_start)
end

setmetatable(_M, {
    __index = function(_, k) if k == "enabled" then return enabled end end,
    __newindex = function(t, k, v)
        if k == "enabled" then enabled = v and true or false else rawset(t, k, v) end
    end,
})

return _M
