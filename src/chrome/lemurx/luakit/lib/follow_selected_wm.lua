-- LemurX · luakit-compatible library · follow_selected_wm
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "follow_selected_wm" module API. No luakit code is used.
--
-- 在渲染进程里找出当前文字选区所在（或所含）的链接，把它的绝对地址回传给
-- 浏览器进程的 follow_selected.lua。
-- IPC (follow_selected_wm)：← query(page, action)   → result(page_id, action, uri|nil)

local ui = ipc_channel("follow_selected_wm")

local M = {}

-- 选区锚点向上找 <a href>；找不到就看选区范围里第一个链接
M.script = [[
(function () {
  var sel = window.getSelection();
  if (!sel || sel.rangeCount === 0) return null;
  function up(node) {
    while (node && node.nodeType !== 1) node = node.parentNode;
    while (node) {
      if (node.tagName === 'A' && node.href) return node.href;
      node = node.parentElement;
    }
    return null;
  }
  var found = up(sel.anchorNode) || up(sel.focusNode);
  if (found) return found;
  var range = sel.getRangeAt(0);
  var root = range.commonAncestorContainer;
  if (root && root.nodeType !== 1) root = root.parentElement;
  if (!root) return null;
  var links = root.querySelectorAll('a[href]');
  for (var i = 0; i < links.length; i++) {
    if (range.intersectsNode(links[i])) return links[i].href;
  }
  return null;
})()
]]

function M.selected_uri(page)
    local ok, uri = pcall(page.eval_js, page, M.script, { source = "follow_selected_wm" })
    if ok and type(uri) == "string" and uri ~= "" then return uri end
    return nil
end

ui:add_signal("query", function(_, page, action)
    if not page then return end
    ui:emit_signal("result", page, action, M.selected_uri(page))
end)

return M
