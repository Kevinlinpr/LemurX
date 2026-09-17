-- LemurX · luakit-compatible library · lousy.theme
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "lousy.theme" module API. No luakit code is used.
--
-- 主题查找。init(path) 加载一个 `return { ... }` 形式的 Lua 文件；get() 返回一个代理表，
-- 读取不存在的键时按“逐段去前缀”回退：
--   uri_sbar_fg → sbar_fg → fg        menu_selected_bg → selected_bg → bg
--   tab_font → font                   hint_border → border（再没有就 nil）
-- 代理只有一份，init() 之后所有已持有代理的模块自动看到新值。

local util = require("lousy.util")

local M = {}

-- 内置默认值：即使没有 theme.lua 也能画出一个能看的界面
local defaults = {
    font = "sans 12px",
    fg = "#e6e6e6",
    bg = "#1c1c1c",

    notif_fg = "#e6e6e6", notif_bg = "#1c1c1c",
    warning_fg = "#ffd75f", warning_bg = "#1c1c1c",
    error_fg = "#ffffff", error_bg = "#af0000",

    sbar_fg = "#e6e6e6", sbar_bg = "#1c1c1c", sbar_font = "sans 12px",
    ibar_fg = "#e6e6e6", ibar_bg = "#1c1c1c", ibar_font = "monospace 12px",
    loaded_fg = "#5faf5f",
    loading_fg = "#e6e6e6", loading_bg = "#1c1c1c",
    trust_fg = "#5faf5f", notrust_fg = "#d75f5f", success_fg = "#5faf5f",

    scroll_fg = "#e6e6e6", zoom_fg = "#e6e6e6",

    tab_fg = "#9e9e9e", tab_bg = "#262626", tab_font = "sans 12px",
    tab_ntheme = "#8a8a8a", tab_loading_fg = "#ffaf5f",
    tab_hover_bg = "#3a3a3a", tab_hover_fg = "#e6e6e6",
    tab_selected_fg = "#ffffff", tab_selected_bg = "#3f3f3f",
    tab_trust_fg = "#5faf5f", tab_notrust_fg = "#d75f5f",
    tablist_bg = "#1c1c1c", tab_list_bg = "#1c1c1c",
    selected_fg = "#ffffff", selected_bg = "#3f3f3f", selected_ntheme = "#dadada",
    private_tab_bg = "#3a2a4a", selected_private_tab_bg = "#5a3a7a",

    menu_fg = "#e6e6e6", menu_bg = "#262626", menu_font = "sans 12px",
    menu_selected_fg = "#ffffff", menu_selected_bg = "#005f87",
    menu_title_fg = "#ffffff", menu_title_bg = "#1c1c1c",
    menu_primary_title_fg = "#ffaf5f", menu_secondary_title_fg = "#87afd7",
    menu_disabled_fg = "#6c6c6c", menu_disabled_bg = "#262626",
    menu_enabled_fg = "#5faf5f", menu_enabled_bg = "#262626",
    menu_active_fg = "#ffd75f", menu_active_bg = "#262626",
    proxy_active_menu_fg = "#5faf5f", proxy_active_menu_bg = "#262626",
    proxy_inactive_menu_fg = "#9e9e9e", proxy_inactive_menu_bg = "#262626",

    hint_font = "bold 11px monospace",
    hint_fg = "#000000", hint_bg = "#ffd75f", hint_border = "1px solid #af8700",
    hint_opacity = "0.85",
    hint_overlay_bg = "rgba(255,215,95,0.25)", hint_overlay_border = "1px dotted #af8700",
    hint_overlay_selected_bg = "rgba(95,175,95,0.35)", hint_overlay_selected_border = "1px dotted #5faf5f",

    passthrough_fg = "#e6e6e6", passthrough_bg = "#1c1c1c",
    insert_fg = "#e6e6e6", insert_bg = "#1c1c1c",
}
-- w:set_prompt(text, theme.ok) 这类用法要的三元组
defaults.ok = { fg = defaults.success_fg, bg = defaults.bg }
defaults.warn = { fg = defaults.warning_fg, bg = defaults.warning_bg }
defaults.error = { fg = defaults.error_fg, bg = defaults.error_bg }

local loaded = {}      -- init() 得到的用户主题
local proxy            -- get() 返回的唯一代理

-- 在一张表里沿 “去掉最前面一段 xxx_” 的链条查找
local function chain_lookup(tbl, key)
    local k = key
    while k do
        local v = tbl[k]
        if v ~= nil then return v end
        k = k:match("^[^_]+_(.+)$")
    end
    return nil
end

-- 先在用户主题里走完整条回退链（与 luakit 的 theme.some_thing_bg → thing_bg → bg 一致），
-- 用户主题里完全没有时才查内置默认值
local function lookup(key)
    if type(key) ~= "string" then return nil end
    local v = chain_lookup(loaded, key)
    if v ~= nil then return v end
    return chain_lookup(defaults, key)
end

proxy = setmetatable({}, {
    __index = function(_, key) return lookup(key) end,
    __newindex = function(_, key, value) loaded[key] = value end,
    __pairs = function()
        local merged = util.table.join(defaults, loaded)
        return next, merged, nil
    end,
})

-- 读取 theme 文件。文件应 return 一张表；也接受把值写进全局 `theme` 表的旧写法。
function M.init(path)
    if path == nil then
        path = util.find_config("theme.lua", true)
    end
    if type(path) ~= "string" then
        return M.set({})
    end
    local env = setmetatable({ theme = {} }, { __index = _G })
    local chunk, err = loadfile(path, "t", env)
    if not chunk then
        local m = rawget(_G, "msg")
        if m and m.warn then m.warn("theme: cannot load %s: %s", path, tostring(err)) end
        return M.set({})
    end
    local ok, result = pcall(chunk)
    if not ok then
        local m = rawget(_G, "msg")
        if m and m.warn then m.warn("theme: error running %s: %s", path, tostring(result)) end
        return M.set({})
    end
    if type(result) ~= "table" then
        result = type(env.theme) == "table" and env.theme or {}
    end
    return M.set(result)
end

-- 直接设置主题表（会替换 init() 加载的内容）
function M.set(t)
    if type(t) ~= "table" then
        error("lousy.theme.set: expected a table", 2)
    end
    loaded = {}
    for k, v in pairs(t) do loaded[k] = v end
    return proxy
end

function M.get()
    return proxy
end

-- 原始表（不带回退），调试用
function M.raw()
    return loaded
end

M.defaults = defaults

return M
