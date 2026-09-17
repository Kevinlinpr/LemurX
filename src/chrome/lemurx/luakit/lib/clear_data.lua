-- LemurX · luakit-compatible library · clear_data
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "clear_data" module API. No luakit code is used.
--
-- 清除网站数据。命令：
--   :clear-data [类型...] [时间段]   直接清；不带参数则打开 luakit://clear-data/ 选择页
--       类型：cache cookies site_data history all     时间段：15m hour day week all（默认 all）
--   :clear-favicon-db                清图标缓存
-- 后端优先 lemurx.data.clear(types, span)（LemurX原生），否则 luakit.website_data.clear。
-- 也可在 Lua 里直接调用 clear_data.clear({"cache","cookies"}, "day")。

local chrome = require("chrome")

local M = {}

M.chrome_page = "luakit://clear-data/"
M.types = {
    { id = "cache", label = "Cached files", desc = "Images, scripts and other files kept to speed up loading." },
    { id = "cookies", label = "Cookies", desc = "Signs you out of most sites." },
    { id = "site_data", label = "Site storage", desc = "Local storage, IndexedDB, service workers and similar." },
    { id = "history", label = "Browsing history", desc = "The list of pages you visited (history.db)." },
}
M.spans = {
    { id = "15m", label = "Last 15 minutes" },
    { id = "hour", label = "Last hour" },
    { id = "day", label = "Last 24 hours" },
    { id = "week", label = "Last 7 days" },
    { id = "all", label = "Everything" },
}

local VALID_TYPE = { cache = true, cookies = true, site_data = true, history = true }
local VALID_SPAN = { ["15m"] = true, hour = true, day = true, week = true, all = true }

local function span_to_us(span)
    return ({ ["15m"] = 15 * 60 * 1e6, hour = 3600 * 1e6, day = 86400 * 1e6, week = 7 * 86400 * 1e6, all = 0 })[span] or 0
end

-- 清除；返回 ok, err
function M.clear(types, span)
    span = VALID_SPAN[span] and span or "all"
    local list, seen = {}, {}
    for _, t in ipairs(types or {}) do
        if t == "all" then
            list = { "cache", "cookies", "site_data", "history" }
            break
        end
        if VALID_TYPE[t] and not seen[t] then seen[t] = true; list[#list + 1] = t end
    end
    if #list == 0 then return false, "nothing selected" end

    -- 我们自己的历史库
    local want_history = false
    for i = #list, 1, -1 do
        if list[i] == "history" then want_history = true end
    end
    if want_history then
        local ok, history = pcall(require, "history")
        if ok and history and history.db then
            local cutoff = span == "all" and 0 or (os.time() - span_to_us(span) / 1e6)
            pcall(function()
                if cutoff > 0 then
                    history.db:exec("DELETE FROM history WHERE last_visit >= :c", { [":c"] = math.floor(cutoff) })
                else
                    history.clear()
                end
            end)
        end
    end

    local ok, err
    if lemurx and lemurx.data and lemurx.data.clear then
        ok, err = pcall(lemurx.data.clear, list, span)
    else
        local wd_types = {}
        for _, t in ipairs(list) do
            if t == "cache" then wd_types[#wd_types + 1] = "disk_cache"; wd_types[#wd_types + 1] = "memory_cache"
            elseif t == "cookies" then wd_types[#wd_types + 1] = "cookies"
            elseif t == "site_data" then wd_types[#wd_types + 1] = "local_storage"; wd_types[#wd_types + 1] = "indexeddb_databases" end
        end
        if #wd_types > 0 then
            ok, err = pcall(luakit.website_data.clear, wd_types, span_to_us(span))
        else
            ok = true
        end
    end
    if not ok then return false, tostring(err) end
    M.emit_signal("cleared", list, span)
    return true
end

-- ====================================================================
-- luakit://clear-data/
-- ====================================================================
local esc = chrome.escape

chrome.add("clear-data", function()
    local boxes = {}
    for _, t in ipairs(M.types) do
        boxes[#boxes + 1] = ("<label class=\"lx-row\"><input type=\"checkbox\" name=\"t\" value=\"%s\"%s>"
            .. "<div class=\"lx-grow\"><span class=\"lx-title\">%s</span><span class=\"lx-sub\">%s</span></div></label>")
            :format(esc(t.id), t.id ~= "history" and " checked" or "", esc(t.label), esc(t.desc))
    end
    local spans = {}
    for _, s in ipairs(M.spans) do
        spans[#spans + 1] = ("<option value=\"%s\"%s>%s</option>"):format(esc(s.id), s.id == "all" and " selected" or "", esc(s.label))
    end
    local body = table.concat({
        "<div class=\"lx-card\"><h2>What to clear</h2>", table.concat(boxes), "</div>",
        "<div class=\"lx-card\"><h2>Time range</h2><select id=\"span\">", table.concat(spans), "</select></div>",
        "<div class=\"lx-toolbar\"><span id=\"msg\" class=\"lx-muted\"></span>",
        "<button class=\"lx-primary lx-danger\" onclick=\"go()\">Clear now</button></div>",
    })
    return chrome.render({
        title = "Clear data", heading = "Clear browsing data", body = body,
        script = [[
function go() {
  var types = Array.prototype.map.call(document.querySelectorAll('input[name=t]:checked'), function (i) { return i.value; });
  var span = document.getElementById('span').value;
  var m = document.getElementById('msg');
  if (!types.length) { m.textContent = 'Pick at least one item.'; return; }
  if (typeof clear_data_run !== 'function') { m.textContent = 'Lua bridge unavailable.'; return; }
  m.textContent = 'Clearing…';
  clear_data_run(types, span).then(function () { m.textContent = 'Done.'; }, function (e) { m.textContent = 'Failed: ' + e; });
}
]],
    })
end, nil, {
    clear_data_run = function(_, types, span)
        local ok, err = M.clear(types, span)
        if not ok then error(err) end
        return true
    end,
})

do
    local ok, modes = pcall(require, "modes")
    if ok and modes then
        modes.add_cmds({
            { ":clear-data", "Clear site data: :clear-data [cache|cookies|site_data|history|all ...] [15m|hour|day|week|all]",
                function(w, o)
                    local arg = (o and o.arg) or ""
                    local types, span = {}, "all"
                    for word in arg:gmatch("%S+") do
                        if VALID_SPAN[word] then span = word
                        elseif VALID_TYPE[word] or word == "all" then types[#types + 1] = word
                        else w:error("Unknown argument: " .. word) return end
                    end
                    if #types == 0 then
                        w:new_tab(M.chrome_page)
                        return
                    end
                    local ok2, err = M.clear(types, span)
                    if ok2 then w:notify("Cleared " .. table.concat(types, ", ") .. " (" .. span .. ")")
                    else w:error("Clear failed: " .. tostring(err)) end
                end },
            { ":clear-favicon-db", "Clear the favicon cache.", function(w)
                luakit.clear_favicon_database()
                w:notify("Favicon cache cleared")
            end },
        })
    end
end

do
    local ok, lousy = pcall(require, "lousy")
    if ok and lousy and lousy.signal then lousy.signal.setup(M, true)
    else
        local list = {}
        M.add_signal = function(n, fn) list[n] = list[n] or {}; table.insert(list[n], fn) end
        M.emit_signal = function(n, ...) for _, fn in ipairs(list[n] or {}) do fn(...) end end
        M.remove_signal = function() end
        M.remove_signals = function(n) list[n] = nil end
    end
end

return M
