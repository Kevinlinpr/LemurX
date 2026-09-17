-- 案例 40 · 自己的 URL 协议：lua://dash 是一张由 Lua 现场生成的页面
--
-- 扩展做不到的点：
--   * 扩展不能注册 scheme。chrome-extension:// 是静态打包资源，改一次要重新发版；
--     这里 lua://dash 每次访问都是 Lua 当场拼出来的 HTML，数据来自浏览器进程：
--     打开的标签、历史库、sqlite、本地文件、甚至 lemurx.http 抓来的东西。
--   * 页面里的 JS 通过 window.luaDash(...) 直接调回浏览器进程的 Lua（register_function +
--     ipc），关标签、开标签、查历史，一行 await 搞定。
--
-- 访问：地址栏输 lua://dash 。lua://tabs 是纯 JSON 接口，任何页面都可以 fetch。

local webview = require("webview")

luakit.register_scheme("lua")

-- ---------- 渲染进程那半边：给 lua:// 页面暴露 window.luaDash ----------
local WM_NAME = "lua_dash_wm"
local WM_SOURCE = [==[
local ui = ipc_channel("lua_dash_wm")
local pending, next_id = {}, 0
luakit.register_function("^lua://", "luaDash", function(page, resolve, reject, action, arg)
    next_id = next_id + 1
    pending[next_id] = { resolve = resolve, reject = reject }
    ui:emit_signal("call", page.id, next_id, tostring(action), arg)
end)
ui:add_signal("result", function(_, _, id, ok, value)
    local p = pending[id]
    pending[id] = nil
    if not p then return end
    if ok then p.resolve(value) else p.reject(tostring(value)) end
end)
]==]
do
    local path = luakit.config_dir .. "/" .. WM_NAME .. ".lua"
    local f = io.open(path, "r")
    local old = f and f:read("a")
    if f then f:close() end
    if old ~= WM_SOURCE then
        f = assert(io.open(path, "w"))
        f:write(WM_SOURCE)
        f:close()
    end
end
local wm = require_web_module(WM_NAME)

-- 浏览器进程里可被页面调用的动作表
local actions = {
    close_tab = function(id) lemurx.tabs.close(tonumber(id)) return true end,
    select_tab = function(id) lemurx.tabs.select(tonumber(id)) return true end,
    open = function(url) return lemurx.tabs.open(url) end,
    history = function(q)
        local rows = lemurx.history.query(q or "") or {}
        local out = {}
        for i = 1, math.min(#rows, 30) do out[i] = { url = rows[i].url, title = rows[i].title } end
        return out
    end,
    screenshot = function(id)
        local r = lemurx.tabs.screenshot(tonumber(id), { base64 = true })
        return r and r.data or nil
    end,
}

wm:add_signal("call", function(_, page_id, req_id, action, arg)
    local fn = actions[action]
    local ok, value
    if fn then ok, value = pcall(fn, arg) else ok, value = false, "unknown action " .. action end
    local view = __lk.webview_for_tab(page_id)
    if view then wm:emit_signal(view, "result", req_id, ok, value)
    else wm:emit_signal("result", req_id, ok, value) end
end)

-- ---------- 页面生成 ----------
local function esc(s)
    return (tostring(s or ""):gsub("[<>&\"]", { ["<"] = "&lt;", [">"] = "&gt;", ["&"] = "&amp;", ['"'] = "&quot;" }))
end

local function dash_html()
    local tabs = lemurx.tabs.list() or {}
    local rows = {}
    for _, t in ipairs(tabs) do
        rows[#rows + 1] = ([[
          <li data-id="%d">
            <b>%s</b><br><small>%s</small><br>
            <button onclick="go(%d)">切到</button>
            <button onclick="closeTab(%d)">关闭</button>
            <button onclick="shot(%d)">截图</button>
          </li>]]):format(t.id, esc(t.title ~= "" and t.title or "(无标题)"), esc(t.url), t.id, t.id, t.id)
    end
    local info = lemurx.browser.info() or {}
    return ([[
<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Lua Dash</title>
<style>
 body{font:14px sans-serif;margin:0;background:#0b1f33;color:#d9e2ec}
 header{padding:14px;background:#102a43;font-size:18px}
 ul{list-style:none;padding:0;margin:0} li{padding:10px 14px;border-bottom:1px solid #243b53}
 button{background:#3e8ed0;color:#fff;border:0;border-radius:6px;padding:4px 10px;margin:4px 4px 0 0}
 input{width:100%%;box-sizing:border-box;padding:8px;border-radius:6px;border:0;margin:10px 0}
 img{max-width:100%%;border-radius:8px;margin-top:8px}
 #hist li{padding:6px 14px}
</style>
<header>Lua Dash · %s %s · %d 个标签 · 由 Lua 在 %s 生成</header>
<ul id="tabs">%s</ul>
<div style="padding:14px">
  <input id="q" placeholder="搜历史（回车）—— 这是在调浏览器进程里的 Lua">
  <ul id="hist"></ul>
  <div id="shot"></div>
</div>
<script>
 async function go(id){ await luaDash('select_tab', id); }
 async function closeTab(id){ await luaDash('close_tab', id); location.reload(); }
 async function shot(id){ const b64 = await luaDash('screenshot', id);
   document.getElementById('shot').innerHTML = b64 ? '<img src="data:image/jpeg;base64,'+b64+'">' : '截图失败'; }
 document.getElementById('q').addEventListener('keydown', async e => {
   if (e.key !== 'Enter') return;
   const rows = await luaDash('history', e.target.value);
   document.getElementById('hist').innerHTML = rows.map(r =>
     '<li><a style="color:#9fb3c8" href="'+r.url+'">'+(r.title||r.url)+'</a></li>').join('');
 });
</script>]]):format(esc("lemurx"), esc(info.versionName or ""), #tabs, os.date("%H:%M:%S"), table.concat(rows))
end

-- 每个 webview（含原生开的标签）都能响应 lua://
local function hook(view)
    view:add_signal("scheme-request::lua", function(v, uri, request)
        local page = uri:match("^lua://([%w%-]+)")
        if page == "dash" or page == nil or page == "" then
            request:finish(dash_html(), "text/html")
        elseif page == "tabs" then
            -- 纯数据接口：任何网页都可以 fetch("lua://tabs")
            request:finish(__luakit.json_encode(lemurx.tabs.list() or {}), "application/json")
        elseif page == "file" then
            -- lua://file/<相对 files/lua 的路径>：把本地文件当网页资源发出去
            local rel = uri:match("^lua://file/(.*)")
            local r = rel and lemurx.fs.read(rel)
            if r and r.ok then
                local mime = rel:match("%.png$") and "image/png" or rel:match("%.css$") and "text/css" or "text/plain"
                request:finish(r.data, mime)
            else
                request:finish("<h1>404</h1>" .. esc(rel), "text/html")
            end
        end
        -- 其它 lua://xxx 不在这里 finish：留给别的脚本接（例如案例 60 的 lua://gate）；
        -- 谁都不接的话原生 30 秒后报错，不会永久 loading。
    end)
end
-- 之后新开的标签走 luakit 的 init 钩子；脚本加载前就存在的标签补挂一遍
webview.add_signal("init", hook)
for _, t in ipairs(lemurx.tabs.list() or {}) do
    local v = __lk.webview_for_tab(t.id)          -- 已包装过的：init 已错过，直接挂
    if v then hook(v) else __lk.webview_for_tab(t.id, true) end   -- 未包装：现在包，init 会跑 hook
end

-- 顶栏放个入口
lemurx.ui.render("toolbar.end", lemurx.ui.h("icon", {
    id = "dash_btn", text = "☷", size = 18, desc = "Lua Dash",
    onClick = function() lemurx.tabs.open("lua://dash") end,
}))

lemurx.log("[案例40] lua:// 已注册：lua://dash · lua://tabs · lua://file/<path>")
