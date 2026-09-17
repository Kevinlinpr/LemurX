-- LemurX · luakit-compatible library · lousy.widget.buf
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget.buf" module API. No luakit code is used.
--
-- 状态栏按键缓冲显示（w.buffer）。缓冲变化的通知来源：
--   * w 上的 key-press / mode-changed / buffer-changed / update-buf 信号
--   * w:update_buf()（若窗口没有这个方法，这里补一个；若 window 模块已加载且
--     window.methods 缺 update_buf，也补进去，方便 window.lua 在 hit() 之后调用）
--   * label.update()

local util = require("lousy.util")
local common = require("lousy.widget.common")

local M = {}

-- w -> { label, ... }
local labels = setmetatable({}, { __mode = "k" })

local function paint_w(w)
    local list = labels[w]
    if not list then return end
    local buf = rawget(w, "buffer")
    if buf == nil then buf = "" end
    for i = #list, 1, -1 do
        local label = list[i]
        if not label.is_alive then
            table.remove(list, i)
        else
            if buf == "" then
                label.text = ""
                label:hide()
            else
                label.text = util.escape(tostring(buf))
                label:show()
            end
        end
    end
end

-- 供 window.methods.update_buf 使用
function M.update(w)
    paint_w(w)
end

local function ensure_method(w)
    if type(w) == "table" and rawget(w, "update_buf") == nil then
        local ok = pcall(function()
            if w.update_buf == nil then w.update_buf = paint_w end
        end)
        if not ok then rawset(w, "update_buf", paint_w) end
    end
    local win = package.loaded["window"]
    if type(win) == "table" and type(win.methods) == "table" and win.methods.update_buf == nil then
        win.methods.update_buf = paint_w
    end
end

local function new(w)
    local label = common.make_label("buf")
    labels[w] = labels[w] or {}
    table.insert(labels[w], label)

    local function paint() paint_w(w) end
    common.on_w(w, "key-press", paint)
    common.on_w(w, "mode-changed", paint)
    common.on_w(w, "buffer-changed", paint)
    common.on_w(w, "update-buf", paint)
    common.on_w(w, "init", paint)
    ensure_method(w)
    common.attach_update(label, paint)
    paint()
    return label
end

return common.callable(M, new)
