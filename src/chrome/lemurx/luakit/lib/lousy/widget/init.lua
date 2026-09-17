-- LemurX · luakit-compatible library · lousy.widget
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.widget" module API. No luakit code is used.
--
-- 小部件工厂集合。每个工厂都是可调用的模块表：
--   lousy.widget.uri(w) hist(w) buf(w) scroll(w) ssl(w) progress(w) zoom(w) tabi(w) tgname(w)
--       → 已挂好信号、会自己刷新的 label
--   lousy.widget.menu([w])                → 菜单表 { widget, build, ... }
--   lousy.widget.tablist(w, args) / (notebook, orientation) → 标签栏表 { widget, update, ... }
--   lousy.widget.tab(view, index)         → 单个标签头表
--   lousy.widget.common                   → 共用帮助函数

local M = {
    common   = require("lousy.widget.common"),
    uri      = require("lousy.widget.uri"),
    hist     = require("lousy.widget.hist"),
    buf      = require("lousy.widget.buf"),
    scroll   = require("lousy.widget.scroll"),
    ssl      = require("lousy.widget.ssl"),
    progress = require("lousy.widget.progress"),
    zoom     = require("lousy.widget.zoom"),
    tabi     = require("lousy.widget.tabi"),
    tab      = require("lousy.widget.tab"),
    tablist  = require("lousy.widget.tablist"),
    tgname   = require("lousy.widget.tgname"),
    menu     = require("lousy.widget.menu"),
}

return M
