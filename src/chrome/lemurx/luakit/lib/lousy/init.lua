-- LemurX · luakit-compatible library · lousy
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy" module API. No luakit code is used.
--
-- lousy 包入口：把各子模块聚合到一张表里。
--   lousy.util  lousy.signal  lousy.mode  lousy.bind  lousy.theme
--   lousy.uri   lousy.pickle  lousy.load  lousy.widget
-- 全部立即加载；这些模块彼此只依赖 util/signal/theme，不依赖 window/webview 等上层模块。

local lousy = {
    util   = require("lousy.util"),
    signal = require("lousy.signal"),
    mode   = require("lousy.mode"),
    bind   = require("lousy.bind"),
    theme  = require("lousy.theme"),
    uri    = require("lousy.uri"),
    pickle = require("lousy.pickle"),
    load   = require("lousy.load"),
    widget = require("lousy.widget"),
}

-- 版本号，方便第三方模块做能力探测
lousy.version = "lemurx-1"

return lousy
