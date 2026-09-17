-- LemurX · luakit-compatible library · go_next_prev
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "go_next_prev" module API. No luakit code is used.
--
-- ]] / [[ ：找到页面的"下一页 / 上一页"链接并点击。查找顺序：
--   <link rel=next|prev>、<a rel=next|prev>、再按链接文字 / title / aria-label 匹配关键词。
-- 公开接口：go_next_prev.next_patterns / prev_patterns（JS 正则源码数组）go_next_prev.go(w, "next"|"prev")

local modes = require("modes")

local _M = {}

_M.next_patterns = { "^\\s*(next|newer|more|older posts)\\b", "^\\s*(>|»|›|→|>>)\\s*$", "下一[页頁章]", "次へ|次のページ", "^\\s*siguiente|suivant|weiter|далее" }
_M.prev_patterns = { "^\\s*(prev|previous|newer posts|back)\\b", "^\\s*(<|«|‹|←|<<)\\s*$", "上一[页頁章]", "前へ|前のページ", "^\\s*anterior|précédent|zurück|назад" }

_M.script = [[
(function (rel, patterns) {
  function go(el) {
    if (!el) return false;
    if (el.tagName === 'LINK' && el.href) { location.href = el.href; return true; }
    el.click();
    return true;
  }
  var byRel = document.querySelector('link[rel~="' + rel + '"]') || document.querySelector('a[rel~="' + rel + '"]');
  if (byRel) return go(byRel);
  var res = patterns.map(function (p) { return new RegExp(p, 'i'); });
  var links = document.querySelectorAll('a[href], button, [role=button], [role=link]');
  for (var i = 0; i < res.length; i++) {
    for (var j = 0; j < links.length; j++) {
      var el = links[j];
      var text = (el.textContent || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.getAttribute('aria-label') || '');
      if (res[i].test(text.trim())) return go(el);
    }
  }
  return false;
})(%s, %s)
]]

local function js_string(s)
    return '"' .. tostring(s):gsub("[%c\"\\]", function(c) return string.format("\\u%04x", c:byte()) end) .. '"'
end

local function js_array(list)
    local parts = {}
    for i, s in ipairs(list) do parts[i] = js_string(s) end
    return "[" .. table.concat(parts, ",") .. "]"
end

function _M.go(w, which)
    local view = w.view
    if not view then return end
    local patterns = which == "prev" and _M.prev_patterns or _M.next_patterns
    local js = _M.script:format(js_string(which), js_array(patterns))
    view:eval_js(js, { source = "go_next_prev", callback = function(ret, err)
        if err then w:warning("go_next_prev: " .. tostring(err)) return end
        if ret ~= true then w:warning(("No %s-page link found"):format(which)) end
    end })
end

modes.add_binds("normal", {
    { "^%]%]$", "Follow the page's next-page link.", function (w) _M.go(w, "next") end },
    { "^%[%[$", "Follow the page's previous-page link.", function (w) _M.go(w, "prev") end },
})

return _M
