-- LemurX · luakit-compatible library · go_input
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "go_input" module API. No luakit code is used.
--
-- gi：聚焦页面上第一个可见的文本输入框并进入 insert 模式（带计数时聚焦第 n 个）。
-- 用 view:eval_js 直接在页面里找；找不到就退回 follow 模式的 focus 配置（若 follow 已加载）。
-- 公开接口：go_input.go_input(w, n) go_input.script

local modes = require("modes")

local _M = {}

_M.selector = "input:not([type=hidden]):not([type=submit]):not([type=button]):not([type=reset])"
    .. ":not([type=checkbox]):not([type=radio]):not([type=image]):not([type=file]), textarea, [contenteditable=''], [contenteditable='true']"

_M.script = [[
(function (selector, index) {
  var all = document.querySelectorAll(selector), seen = 0;
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    if (el.disabled || el.readOnly) continue;
    var r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    var cs = window.getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none') continue;
    seen++;
    if (seen === index) {
      el.focus();
      if (el.select && el.tagName !== 'SELECT') { try { el.select(); } catch (e) {} }
      el.scrollIntoView({ block: 'center', inline: 'nearest' });
      return true;
    }
  }
  return false;
})(%s, %d)
]]

local function js_string(s)
    return '"' .. tostring(s):gsub("[%c\"\\]", function(c) return string.format("\\u%04x", c:byte()) end) .. '"'
end

function _M.go_input(w, n)
    local view = w.view
    if not view then return end
    n = tonumber(n) or 1
    local js = _M.script:format(js_string(_M.selector), n)
    view:eval_js(js, { source = "go_input", callback = function(ret, err)
        if err then
            w:warning("go_input: " .. tostring(err))
            return
        end
        if ret == true then
            w:set_mode("insert")
        else
            local follow = package.loaded["follow"]
            if follow and follow.start then
                follow.start(w, "focus")
            else
                w:warning("go_input: no text field found")
            end
        end
    end })
end

modes.add_binds("normal", {
    { "^gi$", "Focus the first (or n-th) text field on the page.", function (w, m)
        _M.go_input(w, (m and m.count) or 1)
    end, { count = 1 } },
})

return _M
