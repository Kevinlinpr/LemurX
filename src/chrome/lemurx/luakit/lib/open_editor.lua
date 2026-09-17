-- LemurX · luakit-compatible library · open_editor
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "open_editor" module API. No luakit code is used.
--
-- insert 模式 <Control-e>：把当前聚焦的 textarea/input 内容写到缓存目录的临时文件，
-- 交给 editor.edit；编辑器退出后把文件内容写回该输入框。
-- Android 上 editor.edit 通常只会提示文件路径；此时输入框保持可编辑。
-- 公开接口：open_editor.edit_focused(w) open_editor.tmp_dir

local modes = require("modes")
local editor = require("editor")

local _M = {}

_M.tmp_dir = luakit.cache_dir

local GET_JS = [[
(function () {
  var el = document.activeElement;
  if (!el) return null;
  var tag = el.tagName;
  if (tag === 'TEXTAREA' || (tag === 'INPUT' && /^(text|search|url|email|tel|password|)$/i.test(el.type || '')))
    { el.setAttribute('data-lx-editing', '1'); return el.value; }
  if (el.isContentEditable) { el.setAttribute('data-lx-editing', '1'); return el.innerText; }
  return null;
})()
]]

local SET_JS = [[
(function (text) {
  var el = document.querySelector('[data-lx-editing]') || document.activeElement;
  if (!el) return false;
  el.removeAttribute('data-lx-editing');
  if (el.isContentEditable && el.tagName !== 'TEXTAREA' && el.tagName !== 'INPUT') el.innerText = text;
  else el.value = text;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return true;
})(%s)
]]

local function js_string(s)
    return '"' .. tostring(s):gsub("[%c\"\\]", function(c) return string.format("\\u%04x", c:byte()) end) .. '"'
end

local counter = 0

function _M.edit_focused(w)
    local view = w.view
    if not view then return end
    view:eval_js(GET_JS, { source = "open_editor", callback = function(text, err)
        if err then w:warning("open_editor: " .. tostring(err)) return end
        if type(text) ~= "string" then
            w:warning("open_editor: no editable text field is focused")
            return
        end
        counter = counter + 1
        pcall(lfs.mkdir, _M.tmp_dir)
        local path = ("%s/luakit-edit-%d-%d.txt"):format(_M.tmp_dir, math.floor(luakit.time() * 1000) % 1000000, counter)
        local f, ferr = io.open(path, "wb")
        if not f then
            w:error("open_editor: cannot write " .. path .. ": " .. tostring(ferr))
            return
        end
        f:write(text)
        f:close()
        editor.edit(path, 1, function()
            local rf = io.open(path, "rb")
            if not rf then return end
            local new = rf:read("a")
            rf:close()
            os.remove(path)
            if view.is_alive then
                view:eval_js(SET_JS:format(js_string(new)), { source = "open_editor" })
            end
        end)
    end })
end

modes.add_binds("insert", {
    { "<Control-e>", "Edit the focused text field in an external editor.", function (w) _M.edit_focused(w) end },
})

return _M
