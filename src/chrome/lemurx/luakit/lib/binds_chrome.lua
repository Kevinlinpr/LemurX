-- LemurX · luakit-compatible library · binds_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "binds_chrome" module API. No luakit code is used.
--
-- luakit://binds/ ：把 modes.get_modes() 里每个模式的绑定列成表（触发键、说明），
-- 页面上有即时过滤框。binds_chrome.collect() 返回结构化数据，help_chrome /
-- introspector_chrome 复用。命令 :binds

local lousy = require("lousy")
local chrome = require("chrome")
local modes = require("modes")

local M = {}

M.chrome_page = "luakit://binds/"
M.stylesheet = [[
.mode { margin-bottom: 16px; }
.mode h2 span { color: var(--lx-text); text-transform: none; letter-spacing: 0; font-weight: 500; margin-left: 8px; }
.b { display: grid; grid-template-columns: minmax(110px, 30%) 1fr; gap: 12px; padding: 7px 0; border-bottom: 1px solid var(--lx-line); }
.b:last-child { border-bottom: 0; }
.b .k { font-family: var(--lx-mono); color: var(--lx-accent-2); word-break: break-all; }
.b .d { color: var(--lx-text); }
.b.hidden, .mode.hidden { display: none; }
.nodesc { color: var(--lx-muted); font-style: italic; }
]]

local esc = chrome.escape

-- 单条绑定 → 触发键字符串、说明
function M.describe(b)
    if type(b) ~= "table" then return tostring(b), "" end
    local trigger
    if lousy.bind and lousy.bind.bind_to_string then
        local ok, s = pcall(lousy.bind.bind_to_string, b)
        if ok and type(s) == "string" and s ~= "" then trigger = s end
    end
    if not trigger then
        if type(b.cmds) == "table" then
            local parts = {}
            for _, c in ipairs(b.cmds) do parts[#parts + 1] = ":" .. tostring(c) end
            trigger = table.concat(parts, ", ")
        else
            trigger = b.trigger or b.key or b.pattern or b.name or (type(b[1]) == "string" and b[1]) or "?"
        end
    end
    local desc = b.desc or b.description or (type(b[2]) == "string" and b[2]) or ""
    return trigger, desc
end

local MODE_ORDER = { all = 0, normal = 1, insert = 2, command = 3, passthrough = 4, completion = 5, follow = 6 }

-- 结构化：{ { name=, desc=, binds = { { trigger=, desc= }, ... } }, ... }
function M.collect()
    local all = modes.get_modes() or {}
    local list = {}
    for name, mode in pairs(all) do
        if type(mode) == "table" then
            local binds = {}
            for _, b in ipairs(mode.binds or {}) do
                local trigger, desc = M.describe(b)
                binds[#binds + 1] = { trigger = trigger, desc = desc }
            end
            list[#list + 1] = {
                name = tostring(mode.name or name),
                desc = tostring(mode.desc or ""),
                order = tonumber(mode.order) or MODE_ORDER[name] or 100,
                binds = binds,
            }
        end
    end
    table.sort(list, function(a, b)
        if a.order ~= b.order then return a.order < b.order end
        return a.name < b.name
    end)
    return list
end

function M.mode_html(mode)
    local rows = {}
    for _, b in ipairs(mode.binds) do
        rows[#rows + 1] = table.concat({
            "<div class=\"b\"><span class=\"k\">", esc(b.trigger), "</span>",
            "<span class=\"d\">", b.desc ~= "" and esc(b.desc) or "<span class=\"nodesc\">no description</span>", "</span></div>",
        })
    end
    if #rows == 0 then rows[1] = "<div class=\"lx-muted\">No bindings.</div>" end
    return table.concat({
        "<section class=\"lx-card mode\" id=\"mode-", esc(mode.name), "\"><h2>", esc(mode.name),
        mode.desc ~= "" and ("<span>" .. esc(mode.desc) .. "</span>") or "", "</h2>",
        table.concat(rows), "</section>",
    })
end

local script = [[
var q = document.getElementById('q');
function apply() {
  var needle = (q.value || '').toLowerCase();
  document.querySelectorAll('.mode').forEach(function (m) {
    var any = false;
    m.querySelectorAll('.b').forEach(function (b) {
      var hit = !needle || b.textContent.toLowerCase().indexOf(needle) >= 0;
      b.classList.toggle('hidden', !hit); if (hit) any = true;
    });
    m.classList.toggle('hidden', !any);
  });
}
q.addEventListener('input', apply);
]]

function M.render(opts)
    opts = opts or {}
    local sections = {}
    for _, mode in ipairs(M.collect()) do sections[#sections + 1] = M.mode_html(mode) end
    local body = "<div class=\"lx-toolbar\"><input id=\"q\" type=\"search\" placeholder=\"Filter bindings\" autofocus></div>"
        .. table.concat(sections)
    return chrome.render({
        title = opts.title or "Key bindings", heading = opts.title or "Key bindings",
        style = M.stylesheet, body = body, script = script,
    })
end

chrome.add("binds", function() return M.render() end)

modes.add_cmds({
    { ":binds", "Show all key bindings and commands.", function(w) w:new_tab(M.chrome_page) end },
})

return M
