-- LemurX · luakit-compatible library · editor
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "editor" module API. No luakit code is used.
--
-- 文本编辑器启动。Android 上没有终端编辑器可拉起：
--   * editor.editor_cmd 默认为 editor.builtin.lemurx —— 若宿主提供 lemurx.editor.open(file, line, cb)
--     就用它，否则用 lemurx.toast / w:notify 告知文件路径（诚实地"没有编辑器"）。
--   * 其余 builtin（xterm / urxvt / xdg_open / autodetect）保留字串，在有 luakit.spawn 的环境下
--     会替换 {file} {line} 后 spawn。
-- 公开接口：editor.edit(file, line, callback) editor.builtin editor.editor_cmd
-- 模块信号：no-editor(file, line)

local lousy = require("lousy")

local _M = {}
lousy.signal.setup(_M, true)

_M.builtin = {
    autodetect = "${TERMINAL:-xterm} -e ${EDITOR:-vim} {file} +{line}",
    xterm = "xterm -e vim {file} +{line}",
    urxvt = "urxvt -e vim {file} +{line}",
    xdg_open = "xdg-open {file}",
    lemurx = "lemurx://edit?file={file}&line={line}",
}

_M.editor_cmd = _M.builtin.lemurx

local function shell_quote(s)
    return "'" .. tostring(s):gsub("'", "'\\''") .. "'"
end

local function substitute(cmd, file, line)
    return (cmd:gsub("{file}", function() return shell_quote(file) end):gsub("{line}", tostring(line or 1)))
end

local function current_window()
    local ok, window = pcall(require, "window")
    if ok and window then
        if window.current then
            local okc, w = pcall(window.current)
            if okc and w then return w end
        end
        for _, w in pairs(window.bywidget or {}) do return w end
    end
end

local function announce(text)
    local w = current_window()
    if w and w.notify then
        pcall(w.notify, w, text)
    elseif lemurx and lemurx.toast then
        pcall(lemurx.toast, text)
    else
        msg.info("%s", text)
    end
end

-- editor.edit(file, line, callback)
function _M.edit(file, line, callback)
    if type(file) ~= "string" or file == "" then error("editor.edit: file path required", 2) end
    line = tonumber(line) or 1
    local cmd = _M.editor_cmd or _M.builtin.lemurx

    if cmd == _M.builtin.lemurx then
        if lemurx and lemurx.editor and lemurx.editor.open then
            local ok, err = pcall(lemurx.editor.open, file, line, function(...)
                if callback then callback(...) end
            end)
            if ok then return true end
            msg.warn("editor: lemurx.editor.open failed: %s", tostring(err))
        end
        announce(("No text editor is available here. Edit this file yourself: %s (line %d)"):format(file, line))
        _M.emit_signal("no-editor", file, line)
        if lemurx and lemurx.clipboard and lemurx.clipboard.set then pcall(lemurx.clipboard.set, file) end
        return false
    end

    local full = substitute(cmd, file, line)
    if luakit and luakit.spawn then
        local ok, err = pcall(luakit.spawn, full, function(...)
            if callback then callback(...) end
        end)
        if ok then return true end
        announce("editor: cannot run " .. full .. ": " .. tostring(err))
        return false
    end
    announce("editor: no way to run " .. full)
    return false
end

return _M
