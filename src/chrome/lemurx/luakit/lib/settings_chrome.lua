-- LemurX · luakit-compatible library · settings_chrome
-- Copyright (c) 2026 LemurX. All rights reserved.
-- Independent implementation of the luakit "settings_chrome" module API. No luakit code is used.
--
-- luakit://settings/ ：按模块分组列出 settings.get_settings()，每项一个输入控件
-- （boolean → 开关，enum → 下拉，number → 数字框，其余 → 文本框），修改即通过导出的
-- settings_set(key, text[, domain]) 写回；?domain=example.com 查看 / 编辑某域名的覆盖值。
-- 命令 :settings

local chrome = require("chrome")
local settings = require("settings")

local M = {}

M.chrome_page = "luakit://settings/"
M.html_style = [[
.grp { margin-bottom: 14px; }
.s { display: grid; grid-template-columns: 1fr; gap: 6px; padding: 10px 0; border-bottom: 1px solid var(--lx-line); }
.s:last-child { border-bottom: 0; }
.s .k { font-family: var(--lx-mono); color: var(--lx-accent-2); }
.s .k small { color: var(--lx-muted); margin-left: 6px; font-family: var(--lx-font); }
.s .d { color: var(--lx-muted); font-size: 13px; }
.s .ctl { display: flex; gap: 8px; align-items: center; }
.s .ctl input[type=text], .s .ctl input[type=number], .s .ctl select { flex: 1; }
.s .ctl .st { flex: none; width: 18px; text-align: center; }
.s .ctl .st.ok { color: var(--lx-ok); }
.s .ctl .st.err { color: var(--lx-danger); }
.s.changed .k { color: var(--lx-accent); }
.s.hidden { display: none; }
.sw { position: relative; width: 44px; height: 24px; flex: none; }
.sw input { opacity: 0; width: 0; height: 0; }
.sw i { position: absolute; inset: 0; background: var(--lx-panel-2); border: 1px solid var(--lx-line); border-radius: 12px; transition: .2s; }
.sw i::after { content: ""; position: absolute; width: 18px; height: 18px; left: 2px; top: 2px; border-radius: 50%; background: var(--lx-muted); transition: .2s; }
.sw input:checked + i { background: var(--lx-accent); border-color: var(--lx-accent); }
.sw input:checked + i::after { transform: translateX(20px); background: #111; }
@media (min-width: 720px) { .s { grid-template-columns: 1fr 1fr; align-items: center; } .s .d { grid-column: 1; } .s .ctl { grid-column: 2; grid-row: 1 / span 2; } }
]]

local esc = chrome.escape

local function to_text(rec, v)
    if v == nil then return "" end
    local t = rec.type or "any"
    if t == "boolean" or t == "number" or t == "integer" or t == "string" or t == "enum" then return tostring(v) end
    if t:match("^array:") and type(v) == "table" then
        local parts = {}
        for _, x in ipairs(v) do parts[#parts + 1] = tostring(x) end
        return table.concat(parts, ", ")
    end
    if type(v) == "table" then return chrome.json(v) end
    return tostring(v)
end

local function control(rec, value, domain)
    local key = rec.key
    local dom = domain and (", " .. chrome.json(domain)) or ""
    local id = "in-" .. key:gsub("%W", "_")
    if rec.type == "boolean" then
        return ("<label class=\"sw\"><input id=\"%s\" type=\"checkbox\"%s onchange=\"lxSet(%s, this.checked ? 'true' : 'false', this%s)\"><i></i></label>")
            :format(id, value and " checked" or "", chrome.json(key), dom)
    end
    if rec.type == "enum" and type(rec.options) == "table" then
        local opts = {}
        local list = {}
        if #rec.options > 0 then
            for _, o in ipairs(rec.options) do list[#list + 1] = { tostring(o), "" } end
        else
            for o, d in pairs(rec.options) do list[#list + 1] = { tostring(o), type(d) == "string" and d or "" } end
            table.sort(list, function(a, b) return a[1] < b[1] end)
        end
        for _, o in ipairs(list) do
            opts[#opts + 1] = ("<option value=\"%s\"%s>%s%s</option>"):format(esc(o[1]),
                tostring(value) == o[1] and " selected" or "", esc(o[1]), o[2] ~= "" and (" — " .. esc(o[2])) or "")
        end
        return ("<select id=\"%s\" onchange=\"lxSet(%s, this.value, this%s)\">%s</select>"):format(id, chrome.json(key), dom, table.concat(opts))
    end
    local itype = (rec.type == "number" or rec.type == "integer") and "number" or "text"
    local extra = ""
    if itype == "number" then
        if rec.min then extra = extra .. (" min=\"%s\""):format(tostring(rec.min)) end
        if rec.max then extra = extra .. (" max=\"%s\""):format(tostring(rec.max)) end
        if rec.type == "number" then extra = extra .. " step=\"any\"" end
    end
    return ("<input id=\"%s\" type=\"%s\"%s value=\"%s\" onchange=\"lxSet(%s, this.value, this%s)\" onkeydown=\"if(event.key==='Enter')this.blur()\">")
        :format(id, itype, extra, esc(to_text(rec, value)), chrome.json(key), dom)
end

local function setting_html(rec, domain)
    local value = rec.value
    if domain then
        value = rec.domains and rec.domains[domain]
    end
    local status = ""
    if domain then
        status = value ~= nil and "<small>overridden</small>" or "<small>inherits global</small>"
    elseif not rec.is_default then
        status = "<small>changed</small>"
    end
    return table.concat({
        "<div class=\"s", (not domain and not rec.is_default) and " changed" or "", "\" data-key=\"", esc(rec.key), "\">",
        "<div class=\"k\">", esc(rec.name), status, "</div>",
        "<div class=\"d\">", esc(rec.desc or ""), " <span class=\"lx-muted\">(", esc(rec.type),
        rec.default ~= nil and (", default " .. esc(to_text(rec, rec.default))) or "", ")</span></div>",
        "<div class=\"ctl\">", control(rec, value == nil and rec.value or value, domain),
        "<span class=\"st\" id=\"st-", esc(rec.key:gsub("%W", "_")), "\"></span></div>",
        "</div>",
    })
end

local script = [[
var q = document.getElementById('q');
q.addEventListener('input', function () {
  var n = q.value.toLowerCase();
  document.querySelectorAll('.s').forEach(function (s) { s.classList.toggle('hidden', n && s.textContent.toLowerCase().indexOf(n) < 0); });
  document.querySelectorAll('.grp').forEach(function (g) { g.style.display = g.querySelector('.s:not(.hidden)') ? '' : 'none'; });
});
function lxSet(key, text, el, domain) {
  var st = document.getElementById('st-' + key.replace(/\W/g, '_'));
  if (typeof settings_set !== 'function') { if (st) { st.textContent = '?'; st.className = 'st err'; st.title = 'Lua bridge unavailable'; } return; }
  settings_set(key, text, domain || null).then(function () {
    if (st) { st.textContent = '\u2713'; st.className = 'st ok'; st.title = ''; }
    el.closest('.s').classList.add('changed');
  }).catch(function (e) {
    if (st) { st.textContent = '!'; st.className = 'st err'; st.title = String(e); }
  });
}
]]

local function page(_, meta)
    local domain = meta.params.domain
    if domain == "" then domain = nil end
    local all = settings.get_settings()
    local groups, order = {}, {}
    for key, rec in pairs(all) do
        local g = rec.group or key:match("^([^.]+)")
        if not groups[g] then groups[g] = {}; order[#order + 1] = g end
        table.insert(groups[g], rec)
    end
    table.sort(order)
    local out = {}
    for _, g in ipairs(order) do
        local recs = groups[g]
        table.sort(recs, function(a, b) return a.key < b.key end)
        local items = {}
        for _, rec in ipairs(recs) do items[#items + 1] = setting_html(rec, domain) end
        out[#out + 1] = "<section class=\"lx-card grp\"><h2>" .. esc(g) .. "</h2>" .. table.concat(items) .. "</section>"
    end
    local domains = settings.get_domains()
    local dom_links = { ("<a class=\"lx-tag%s\" href=\"luakit://settings/\">global</a>"):format(domain == nil and " lx-on" or "") }
    for _, d in ipairs(domains) do
        dom_links[#dom_links + 1] = ("<a class=\"lx-tag%s\" href=\"%s\">%s</a>"):format(
            d == domain and " lx-on" or "", esc(chrome.page_uri("settings", "", { domain = d })), esc(d))
    end
    local body = table.concat({
        "<div class=\"lx-toolbar\"><input id=\"q\" type=\"search\" placeholder=\"Filter settings\" autocomplete=\"off\">",
        "<form method=\"get\" action=\"luakit://settings/\" style=\"flex:0 0 auto;display:flex;gap:6px\">",
        "<input type=\"text\" name=\"domain\" placeholder=\"domain override…\" value=\"", esc(domain or ""), "\" style=\"width:180px\">",
        "<button type=\"submit\">Go</button></form></div>",
        "<div style=\"margin-bottom:12px\">", table.concat(dom_links, " "), "</div>",
        domain and ("<p class=\"lx-muted\">Editing overrides for <b>" .. esc(domain) .. "</b>. Empty a text field to remove an override.</p>") or "",
        #out > 0 and table.concat(out) or "<div class=\"lx-empty\">No settings registered.</div>",
    })
    return chrome.render({
        title = "Settings", heading = domain and ("Settings · " .. domain) or "Settings",
        style = M.html_style, body = body, script = script,
    })
end

chrome.add("settings", page, nil, {
    settings_set = function(_, key, text, domain)
        if domain == "" then domain = nil end
        local value
        if text == nil or text == "" then
            value = nil
        else
            local v, err = settings.coerce(key, tostring(text))
            if v == nil and err then error(err) end
            value = v
        end
        settings.set_setting(key, value, domain and { domain = domain } or nil)
        return true
    end,
    settings_get = function(_, key, domain)
        return settings.get_setting(key, domain ~= "" and domain or nil)
    end,
    settings_all = function() return settings.get_settings() end,
})

do
    local ok, modes = pcall(require, "modes")
    if ok and modes then
        modes.add_cmds({
            { ":settings", "Open the settings page.", function(w) w:new_tab(M.chrome_page) end },
        })
    end
end

return M
