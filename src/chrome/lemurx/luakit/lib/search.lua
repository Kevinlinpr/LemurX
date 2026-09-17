-- LemurX · luakit-compatible library · search
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "search" module API. No luakit code is used.
--
-- 页内查找。normal 模式 "/" 向前、"?" 向后进入 search 模式，边敲边搜
--（view:search(text, case_sensitive, forward, wrap)），<Return> 确认，
-- 之后 n / N 沿查找方向前后跳，:noh[lsearch] 清除高亮。
-- 大小写：settings.search.case_sensitive = "smart"（含大写字母才区分）| "always" | "never"；
-- settings.search.wrap 控制到末尾后是否绕回。状态放在 w.search_state。

local lousy = require("lousy")
local modes = require("modes")
local window = require("window")

local _M = {}

local settings = package.loaded["settings"]
if not settings then
    local ok, mod = pcall(require, "settings")
    if ok and type(mod) == "table" then settings = mod end
end
local function get_setting(key, default)
    if settings and settings.get_setting then
        local ok, v = pcall(settings.get_setting, key)
        if ok and v ~= nil then return v end
    end
    return default
end
if settings and settings.register_settings then
    pcall(settings.register_settings, {
        ["search.wrap"] = {
            type = "boolean", default = true,
            desc = "Continue from the other end of the page when a search reaches the last match.",
        },
        ["search.case_sensitive"] = {
            type = "enum", options = { smart = {}, always = {}, never = {} }, default = "smart",
            desc = "smart: case matters only when the query has capitals; always / never force it.",
        },
    })
end

local function wrap() return get_setting("search.wrap", true) ~= false end

function _M.is_case_sensitive(text)
    local policy = get_setting("search.case_sensitive", "smart")
    if policy == "always" then return true end
    if policy == "never" then return false end
    return text:find("%u") ~= nil
end

local function state(w)
    w.search_state = w.search_state or {}
    return w.search_state
end

-- ---------------------------------------------------------------------------
-- 窗口方法
-- ---------------------------------------------------------------------------
window.methods.search = function(w, text, forward, live)
    local view = w.view
    if not (view and view.is_alive) then return end
    local s = state(w)
    if forward == nil then forward = s.forward ~= false end
    text = text or s.last or ""
    s.last = text
    s.forward = forward
    s.live = live and true or false
    s.cleared = false
    if text == "" then
        view:clear_search()
        return
    end
    view:search(text, _M.is_case_sensitive(text), forward, wrap())
end

window.methods.search_next = function(w, n)
    local s = state(w)
    local view = w.view
    if not (view and view.is_alive) then return end
    if not s.last or s.last == "" then
        w:warning("nothing to search for")
        return
    end
    if s.cleared then
        w:search(s.last, s.forward)
        return
    end
    for _ = 1, (tonumber(n) or 1) do
        if s.forward ~= false then view:search_next() else view:search_previous() end
    end
end

window.methods.search_previous = function(w, n)
    local s = state(w)
    local view = w.view
    if not (view and view.is_alive) then return end
    if not s.last or s.last == "" then
        w:warning("nothing to search for")
        return
    end
    if s.cleared then
        w:search(s.last, s.forward == false)
        s.forward = not s.forward
        return
    end
    for _ = 1, (tonumber(n) or 1) do
        if s.forward ~= false then view:search_previous() else view:search_next() end
    end
end

window.methods.clear_search = function(w, forget)
    local s = state(w)
    local view = w.view
    if view and view.is_alive then pcall(view.clear_search, view) end
    s.cleared = true
    if forget then s.last = nil end
end

-- ---------------------------------------------------------------------------
-- 模式
-- ---------------------------------------------------------------------------
modes.new_mode("search", "Type text to find in the page.", {
    has_input = true,
    enter = function(w, forward)
        local s = state(w)
        s.forward = forward ~= false
        s.pending = ""
        w:set_prompt()
        w:set_input(s.forward and "/" or "?")
        w:set_ibar_theme("search")
    end,
    changed = function(w, text)
        local s = state(w)
        if not w:is_mode("search") then return end
        local prefix = text:sub(1, 1)
        if prefix ~= "/" and prefix ~= "?" then
            w:set_mode()
            return
        end
        s.forward = (prefix == "/")
        local query = text:sub(2)
        s.pending = query
        w:search(query, s.forward, true)
    end,
    activate = function(w, text)
        local s = state(w)
        local query = text:sub(2)
        s.live = false          -- 确认：保留高亮
        if query ~= "" then s.last = query end
        w:set_mode()
        if query == "" and s.last and s.last ~= "" then w:search(s.last, s.forward) end
    end,
    leave = function(w)
        local s = state(w)
        w:set_input()
        -- 边敲边搜时 Escape 退出：撤掉临时高亮但记住上一次确认过的词
        if s.live then
            local view = w.view
            if view and view.is_alive then pcall(view.clear_search, view) end
            s.live = false
            if s.pending and s.pending ~= "" and s.last == s.pending then s.cleared = true end
        end
    end,
})

modes.add_binds("normal", {
    { "/", "Search forwards.",            function(w) w:set_mode("search", true) end },
    { "?", "Search backwards.",           function(w) w:set_mode("search", false) end },
    { "n", "Jump to the next match.",     function(w, m) w:search_next(m and m.count) end },
    { "N", "Jump to the previous match.", function(w, m) w:search_previous(m and m.count) end },
})

modes.add_binds("search", {
    { "<Control-j>", "Next match while typing.",     function(w) w:search_next() end },
    { "<Control-k>", "Previous match while typing.", function(w) w:search_previous() end },
})

return _M
