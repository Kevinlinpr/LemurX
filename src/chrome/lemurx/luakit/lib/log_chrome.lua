-- LemurX · luakit-compatible library · log_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "log_chrome" module API. No luakit code is used.
--
-- luakit://log/ 日志页：把 msg 的 "log" 信号收进环形缓冲（大小 = 设置 log_chrome.buffer_size），
-- 页面按级别过滤（?level=warn 或页面上的按钮，纯客户端过滤）。
--   log_chrome.widget() 返回一个状态栏控件：有 warn/error 时显示计数，点一下隐藏。
--   log_chrome.buffer_size / html_page_title / html_style / widget_format /
--   widget_error_format / widget_warning_format 可改。
-- 命令 :log

local settings = require("settings")

local M = {}

settings.register_settings({
    ["log_chrome.buffer_size"] = {
        type = "number", default = 500, min = 10, max = 100000,
        desc = "How many recent log lines luakit://log/ keeps.",
    },
})

M.html_page_title = "Log"
M.html_style = [[
.filters { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
.filters button.lx-on { border-color: var(--lx-accent); color: var(--lx-accent-2); }
.line { display: grid; grid-template-columns: 62px 70px 1fr; gap: 10px; padding: 6px 0; border-bottom: 1px solid var(--lx-line); font-family: var(--lx-mono); font-size: 12.5px; }
.line .t { color: var(--lx-muted); }
.line .g { color: var(--lx-accent-2); overflow: hidden; text-overflow: ellipsis; }
.line .m { white-space: pre-wrap; word-break: break-word; }
.line.warn .m { color: #ffd166; }
.line.error .m, .line.fatal .m { color: var(--lx-danger); }
.line.verbose .m, .line.debug .m { color: var(--lx-muted); }
.line.hidden { display: none; }
]]
M.widget_format = "{errors}{warnings}"
M.widget_error_format = "<span foreground=\"#ff5d5d\">%d✗</span> "
M.widget_warning_format = "<span foreground=\"#ffd166\">%d⚠</span>"

local buffer = {}     -- 最老的在前；超过容量丢最老的
local errors, warnings = 0, 0
local widgets = setmetatable({}, { __mode = "k" })
local LEVEL_RANK = { fatal = 0, error = 1, warn = 2, info = 3, verbose = 4, debug = 5 }

local function capacity()
    local n = settings.get_setting("log_chrome.buffer_size")
    return math.max(10, math.floor(tonumber(n) or 500))
end

local function push(entry)
    buffer[#buffer + 1] = entry
    local cap = capacity()
    while #buffer > cap do table.remove(buffer, 1) end
end

-- 按时间顺序返回条目（拷贝）
function M.entries()
    return { table.unpack(buffer) }
end

function M.clear()
    buffer = {}
    errors, warnings = 0, 0
    M.update_widgets()
end

local function on_log(...)
    local a1, a2, a3, a4, a5 = ...
    local time, level, group, text
    if type(a1) == "table" then time, level, group, text = a2, a3, a4, a5
    else time, level, group, text = a1, a2, a3, a4 end
    if type(text) ~= "string" then text = tostring(text) end
    level = tostring(level or "info")
    push({ time = tonumber(time) or 0, wall = os.time(), level = level, group = tostring(group or "?"), text = text })
    if level == "error" or level == "fatal" then errors = errors + 1
    elseif level == "warn" then warnings = warnings + 1 end
    if errors + warnings > 0 then M.update_widgets() end
end

-- ====================================================================
-- 状态栏控件
-- ====================================================================
local function widget_text()
    local e = errors > 0 and M.widget_error_format:format(errors) or ""
    local w = warnings > 0 and M.widget_warning_format:format(warnings) or ""
    return (M.widget_format:gsub("{errors}", function() return e end):gsub("{warnings}", function() return w end))
end

function M.update_widgets()
    local text = widget_text()
    for ebox, label in pairs(widgets) do
        if ebox.is_alive and label.is_alive then
            label.text = text
            if errors + warnings > 0 then ebox:show() else ebox:hide() end
        end
    end
end

function M.widget()
    local ebox = widget({ type = "eventbox" })
    local label = widget({ type = "label" })
    label.font = "monospace 10"
    label.padding = 4
    ebox.child = label
    ebox:add_signal("button-release", function()
        -- 点击：隐藏所有此类控件并清零计数
        errors, warnings = 0, 0
        for box in pairs(widgets) do if box.is_alive then box:hide() end end
        return true
    end)
    widgets[ebox] = label
    label.text = widget_text()
    if errors + warnings > 0 then ebox:show() else ebox:hide() end
    return ebox
end

msg.add_signal("log", on_log)

-- ====================================================================
-- luakit://log/
-- ====================================================================
do
    local ok, chrome = pcall(require, "chrome")
    if ok and chrome and chrome.add then
        local esc = chrome.escape
        local function page(_, meta)
            local want = meta.params.level or "info"
            local rank = LEVEL_RANK[want] or LEVEL_RANK.info
            local lines = {}
            local entries = M.entries()
            for i = #entries, 1, -1 do
                local e = entries[i]
                local hidden = (LEVEL_RANK[e.level] or 3) > rank
                lines[#lines + 1] = table.concat({
                    "<div class=\"line ", esc(e.level), hidden and " hidden" or "", "\" data-rank=\"", tostring(LEVEL_RANK[e.level] or 3), "\">",
                    "<span class=\"t\">", os.date("%H:%M:%S", e.wall), "</span>",
                    "<span class=\"g\">", esc(e.group), "</span>",
                    "<span class=\"m\">", esc(e.text), "</span></div>",
                })
            end
            local filters = {}
            for _, lv in ipairs({ "error", "warn", "info", "verbose", "debug" }) do
                filters[#filters + 1] = ("<button class=\"lx-small%s\" data-level=\"%d\" onclick=\"setLevel(%d, this)\">%s</button>")
                    :format(LEVEL_RANK[lv] == rank and " lx-on" or "", LEVEL_RANK[lv], LEVEL_RANK[lv], lv)
            end
            local body = table.concat({
                "<div class=\"filters\">", table.concat(filters),
                "<span class=\"lx-spacer\" style=\"flex:1\"></span>",
                "<button class=\"lx-small lx-danger\" onclick=\"clearLog()\">Clear</button></div>",
                "<div class=\"lx-card\">",
                #lines > 0 and table.concat(lines) or "<div class=\"lx-empty\">Log is empty.</div>",
                "</div>",
            })
            return chrome.render({
                title = M.html_page_title, heading = M.html_page_title, style = M.html_style, body = body,
                header_extra = ("<span class=\"lx-muted\">%d lines</span>"):format(#entries),
                script = [[
function setLevel(rank, btn) {
  document.querySelectorAll('.filters button[data-level]').forEach(function (b) { b.classList.toggle('lx-on', b === btn); });
  document.querySelectorAll('.line').forEach(function (l) { l.classList.toggle('hidden', +l.dataset.rank > rank); });
}
function clearLog() { if (typeof log_clear === 'function') log_clear().then(function () { location.reload(); }); }
]],
            })
        end
        chrome.add("log", page, nil, {
            log_clear = function() M.clear() return true end,
            log_entries = function() return M.entries() end,
        })
    end
end

do
    local ok, modes = pcall(require, "modes")
    if ok and modes then
        modes.add_cmds({
            { ":log", "Open the log page.", function(w) w:new_tab("luakit://log/") end },
        })
    end
end

return M
