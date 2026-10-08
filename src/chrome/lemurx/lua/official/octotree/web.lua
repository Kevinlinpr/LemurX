-- octotree/web · GitHub 文件树 · 渲染进程侧
--
--   * 只在 github.com（以及设置里加的 GitHub Enterprise 域名）注入 runtime
--   * 桥 window.__lx_octotree(JSON) → 浏览器进程（带 token 请求 API / 缓存）→ Promise 回页面
--   * 浏览器进程可下发 config / cmd(toggle|reload)
local W = require("lx.web")
local json = require("lx.json")
local util = require("lx.util")

local ch = W.channel("lx.octotree")
local cfg = { enabled = true, hosts = { "github.com" }, width = 280, auto_open = true, push = true, show_on_tabs = false }
local pending, seq = {}, 0

local function is_gh(host)
    for _, h in ipairs(cfg.hosts or {}) do if util.host_matches(host, h) then return true end end
    return false
end

local function page_cfg()
    return { width = cfg.width, auto_open = cfg.auto_open, push = cfg.push, show_on_tabs = cfg.show_on_tabs }
end

local function inject(page)
    local st = W.state(page, "octotree")
    if st.injected then return true end
    local host = W.page_host(page)
    if not is_gh(host) then return false end
    st.injected = true
    W.eval(page, "window.__lxot_cfg=" .. json.encode(page_cfg()) .. ";" .. require("octotree.runtime"))
    return true
end

W.on_document_loaded(function(page)
    if not cfg.enabled then return end
    inject(page)
end, 70)

W.expose_async("__lx_octotree", function(page, resolve, reject, arg)
    if not cfg.enabled then return reject("octotree 已停用") end
    if not is_gh(W.page_host(page)) then return reject("not github") end
    seq = seq + 1
    pending[seq] = { resolve = resolve, reject = reject }
    ch:emit_signal("req", W.pid, seq, tostring(arg))
end)

ch:add_signal("result", function(_, _page, req_id, result_json)
    local p = pending[tonumber(req_id)]
    if not p then return end
    pending[tonumber(req_id)] = nil
    p.resolve(result_json)
end)

ch:add_signal("cmd", function(_, _page, page_id, name)
    for _, p in pairs(__lk.pages()) do
        local ok, id = pcall(function() return p.id end)
        if ok and id == tonumber(page_id) then
            if not inject(p) then return end
            return W.eval(p, "window.__lxot&&window.__lxot." .. (name == "reload" and "reload" or "toggle") .. "()")
        end
    end
end)

ch:add_signal("config", function(_, _page, cfg_json)
    local c = type(cfg_json) == "string" and json.decode(cfg_json) or cfg_json
    if type(c) ~= "table" then return end
    for k, v in pairs(c) do cfg[k] = v end
    for _, p in pairs(__lk.pages()) do
        local st = W.state(p, "octotree")
        if st.injected then
            if cfg.enabled then
                pcall(W.eval, p, "window.__lxot&&window.__lxot.config(" .. json.encode(page_cfg()) .. ")")
            else
                pcall(W.eval, p, "window.__lxot&&window.__lxot.off()")
            end
        end
    end
end)

ch:emit_signal("hello", W.pid)
return { cfg = cfg, inject = inject }
