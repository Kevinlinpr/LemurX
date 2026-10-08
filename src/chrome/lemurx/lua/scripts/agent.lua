-- Copyright 2026 The LemurX Authors
-- Use of this source code is governed by a BSD-style license that can be
-- found in the LICENSE file.
--
-- lemurx.agent —— 给 AI Agent 用的自动化底座（Lua 层）。
--
-- 一个 GUI Agent 的循环是「观察 → 决策 → 执行」。决策是 LLM 的事（脚本自己接模型），
-- 这里把另外两步做成两个调用：
--
--   local obs = lemurx.agent.observe()          -- 截图 + 网页元素 + 原生控件 (+ 其他 App)
--   lemurx.agent.describe(obs)                  -- 压成一段给模型看的文本，每个元素一个 ref
--   lemurx.agent.act{ type = "tap", ref = "w3" }   -- 按 ref 执行，坐标换算在这里完成
--
-- 三套坐标系被统一到「屏幕像素」：
--   网页元素  CSS px（visualViewport 相对）→ lemurx.tabs.viewport() 给的映射 → 屏幕 px
--   原生控件  lemurx.ui.dump/find 本来就是屏幕 px（getLocationOnScreen）
--   其他 App  lemurx.system.tree 的 boundsInScreen 也是屏幕 px
-- ref 前缀区分来源：w = web、n = native、s = system。
--
-- 这个文件只用 init.lua 之后已经存在的 lemurx.* 能力，不注册任何监听，不改浏览器行为；
-- Lua 总开关关掉后整个 lemurx.agent 随运行时消失。

lemurx.agent = lemurx.agent or {}
lemurx.system = lemurx.system or {}

local agent = lemurx.agent
local system = lemurx.system

agent.version = "1.0.0"

-- ------------------------------------------------------------------ 小工具

local function trunc(s, n)
    s = tostring(s or "")
    s = s:gsub("%s+", " ")
    if #s > n then
        return s:sub(1, n - 1) .. "…"
    end
    return s
end

local function isEmpty(s)
    return s == nil or s == "" or s == false
end

local function round(v)
    return math.floor((tonumber(v) or 0) + 0.5)
end

local function currentTabId(tab)
    if type(tab) == "number" and tab > 0 then
        return tab
    end
    local t = lemurx.tabs.current()
    return t and t.id or 0
end

local function inCoroutine()
    local co, isMain = coroutine.running()
    return co ~= nil and not isMain
end

--- 等待。只在 lemurx.async 里真正睡（不卡 Lua 线程）；在外面直接返回。
function agent.wait(ms)
    if inCoroutine() and lemurx.sleep then
        lemurx.sleep(ms or 300)
        return true
    end
    return false
end

-- ------------------------------------------------------------------ 网页：set-of-marks

-- 在页面里跑：找出可交互 / 有意义的元素，打上 data-lemurx-mark，返回几何与语义。
-- 坐标是 visualViewport 相对的 CSS px（被放大/平移过的可视区域左上角为原点），
-- 这样和 lemurx.tabs.viewport() 的物理像素一一对应。
local MARK_JS = [[
(function(opts){
  var max = opts.max || 120;
  var withText = !!opts.text;
  var vv = window.visualViewport || {offsetLeft:0, offsetTop:0, scale:1,
      width: window.innerWidth, height: window.innerHeight};
  var W = vv.width, H = vv.height;
  var sel = 'a[href],button,input,select,textarea,summary,label,[role],[onclick],' +
            '[contenteditable=""],[contenteditable="true"],[tabindex]:not([tabindex="-1"])';
  var all = document.querySelectorAll(sel);
  var out = [], seen = new Set(), i = 0;
  function role(el){
    var r = el.getAttribute('role'); if (r) return r;
    var t = el.tagName.toLowerCase();
    if (t === 'a') return 'link';
    if (t === 'button' || t === 'summary') return 'button';
    if (t === 'select') return 'select';
    if (t === 'textarea') return 'textbox';
    if (t === 'input') {
      var ty = (el.type || 'text').toLowerCase();
      if (ty === 'submit' || ty === 'button' || ty === 'reset' || ty === 'image') return 'button';
      if (ty === 'checkbox' || ty === 'radio') return ty;
      return 'textbox';
    }
    if (el.isContentEditable) return 'textbox';
    return 'clickable';
  }
  function name(el){
    return el.getAttribute('aria-label') || el.getAttribute('placeholder') ||
           el.getAttribute('title') || el.getAttribute('alt') ||
           (el.labels && el.labels[0] && el.labels[0].innerText) || '';
  }
  function visible(el, r){
    if (r.width < 2 || r.height < 2) return false;
    if (r.bottom < 0 || r.right < 0) return false;
    if (r.top > window.innerHeight || r.left > window.innerWidth) return false;
    var cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || cs.opacity === '0') return false;
    return true;
  }
  function covered(el, r){
    var cx = Math.min(Math.max(r.left + r.width/2, 0), window.innerWidth - 1);
    var cy = Math.min(Math.max(r.top + r.height/2, 0), window.innerHeight - 1);
    var top = document.elementFromPoint(cx, cy);
    if (!top) return true;
    return !(top === el || el.contains(top) || top.contains(el));
  }
  document.querySelectorAll('[data-lemurx-mark]').forEach(function(e){ e.removeAttribute('data-lemurx-mark'); });
  for (var k = 0; k < all.length && out.length < max; k++) {
    var el = all[k];
    if (seen.has(el)) continue;
    var r = el.getBoundingClientRect();
    if (!visible(el, r)) continue;
    if (covered(el, r)) continue;
    // 嵌套的可点击元素只留最外层有文本的那个，减少重复
    var p = el.parentElement, dup = false;
    while (p && p !== document.body) { if (seen.has(p) && p.innerText === el.innerText) { dup = true; break; } p = p.parentElement; }
    if (dup) continue;
    seen.add(el);
    i++;
    el.setAttribute('data-lemurx-mark', String(i));
    var text = (el.innerText || el.value || '').trim();
    if (!text && el.tagName === 'INPUT') text = el.value || '';
    var item = {
      i: i, tag: el.tagName.toLowerCase(), role: role(el),
      text: text.slice(0, 120), name: (name(el) || '').slice(0, 80),
      x: r.left - vv.offsetLeft, y: r.top - vv.offsetTop, w: r.width, h: r.height,
      editable: !!(el.isContentEditable || (el.tagName === 'INPUT' && !/^(button|submit|checkbox|radio|reset|image|file)$/i.test(el.type)) || el.tagName === 'TEXTAREA'),
    };
    if (el.href) item.href = String(el.href).slice(0, 200);
    if (el.type) item.type = el.type;
    if (el.checked !== undefined && (el.type === 'checkbox' || el.type === 'radio')) item.checked = !!el.checked;
    if (el.disabled) item.disabled = true;
    out.push(item);
  }
  var res = {
    url: location.href, title: document.title,
    dpr: window.devicePixelRatio,
    vv: {x: vv.offsetLeft, y: vv.offsetTop, scale: vv.scale, w: W, h: H},
    inner: {w: window.innerWidth, h: window.innerHeight},
    scroll: {x: window.scrollX, y: window.scrollY,
             maxY: Math.max(0, document.documentElement.scrollHeight - window.innerHeight)},
    elements: out
  };
  if (withText) res.text = (document.body && document.body.innerText || '').replace(/\n{3,}/g, '\n\n').slice(0, opts.textMax || 3000);
  return res;
})(%s)
]]

--- 给网页元素打标（set-of-marks），返回 {url,title,vv,elements=[...]}，坐标为 CSS px。
function agent.mark(tab, opts)
    opts = opts or {}
    local id = currentTabId(tab)
    if id <= 0 then
        return nil, "no tab"
    end
    local optJson = string.format(
        '{"max":%d,"text":%s,"textMax":%d}',
        opts.max or 120,
        opts.text and "true" or "false",
        opts.textMax or 3000
    )
    local ok, r = pcall(lemurx.tabs.eval, id, string.format(MARK_JS, optJson))
    if not ok or type(r) ~= "table" then
        return nil, tostring(r)
    end
    return r
end

-- CSS(visualViewport 相对) → 屏幕像素；依赖 tabs.viewport() 报出的物理宽高。
local function webToScreen(vp, mark, cx, cy, cw, ch)
    local vvw = (mark.vv and mark.vv.w) or (mark.inner and mark.inner.w) or 0
    local vvh = (mark.vv and mark.vv.h) or (mark.inner and mark.inner.h) or 0
    local viewportW = vp.viewportWidth or vp.w
    local viewportH = vp.viewportHeight or vp.h
    local sx = vvw > 0 and viewportW / vvw or (mark.dpr or 1) * ((mark.vv and mark.vv.scale) or 1)
    local sy = vvh > 0 and viewportH / vvh or sx
    -- 横竖比例理论上相同，取横向的，避免底栏遮挡造成的高度误差
    local s = sx
    if sx <= 0 then s = sy end
    return vp.x + cx * s, vp.y + (vp.contentOffsetY or 0) + cy * s, cw * s, ch * s, s
end

-- ------------------------------------------------------------------ 观察

--- 一次性观察：截图 + 网页元素 + 原生控件 (+ 其他 App 的 UI 树)。
--- opts: {tab=, screenshot=true, scale=0.5, quality=60, base64=false,
---        web=true, maxWeb=120, text=false,
---        native=true, maxNative=200, nativeInteractiveOnly=true,
---        system=nil (自动：无障碍服务已连且前台不是 LemurX 时才看) | true | false}
function agent.observe(opts)
    opts = opts or {}
    local obs = { ts = (os and os.time) and os.time() or 0, elements = {}, errors = {} }
    local tabId = currentTabId(opts.tab)
    local t = lemurx.tabs.current()
    if t then
        obs.tab = { id = t.id, url = t.url, title = t.title, loading = t.loading }
    end

    -- 视口映射：所有网页坐标都靠它换算
    local vp = lemurx.tabs.viewport(tabId)
    if type(vp) == "table" and vp.ok then
        obs.viewport = vp
        obs.screen = { w = vp.screenWidth, h = vp.screenHeight, density = vp.density }
    end

    -- 其他 App：无障碍服务连着、且前台不是我们自己，才去看系统树
    local sysStatus
    if opts.system ~= false then
        local ok, st = pcall(system.status)
        if ok and type(st) == "table" and st.connected then
            sysStatus = st
            local fg = system.foreground()
            obs.foreground = fg
            local wantSystem = opts.system == true or (type(fg) == "table" and fg.self == false)
            if wantSystem then
                local tree = system.tree({
                    flat = true,
                    interactiveOnly = opts.systemInteractiveOnly ~= false,
                    maxNodes = opts.maxSystem or 300,
                })
                if type(tree) == "table" and tree.ok and tree.list then
                    for _, n in ipairs(tree.list) do
                        n.kind = "system"
                        n.ref = "s" .. tostring(n.ref)
                        obs.elements[#obs.elements + 1] = n
                    end
                    obs.systemNodes = tree.nodes
                elseif type(tree) == "table" then
                    obs.errors[#obs.errors + 1] = "system.tree: " .. tostring(tree.error)
                end
                obs.mode = "system"
            end
        end
    end
    obs.system = sysStatus and { connected = true } or { connected = false }

    if obs.mode ~= "system" then
        obs.mode = "browser"
        -- 网页元素
        if opts.web ~= false and tabId > 0 and vp and vp.ok then
            local mark, err = agent.mark(tabId, { max = opts.maxWeb or 120, text = opts.text, textMax = opts.textMax })
            if mark then
                obs.page = {
                    url = mark.url, title = mark.title, scroll = mark.scroll,
                    vv = mark.vv, dpr = mark.dpr, text = mark.text,
                }
                for _, e in ipairs(mark.elements or {}) do
                    local sx, sy, sw, sh, scale = webToScreen(vp, mark, e.x, e.y, e.w, e.h)
                    obs.elements[#obs.elements + 1] = {
                        kind = "web", ref = "w" .. tostring(e.i), mark = e.i,
                        role = e.role, tag = e.tag, text = e.text, name = e.name,
                        href = e.href, type = e.type, checked = e.checked,
                        editable = e.editable, disabled = e.disabled,
                        x = round(sx), y = round(sy), w = round(sw), h = round(sh),
                        css = { x = e.x, y = e.y, w = e.w, h = e.h },
                        scale = scale,
                    }
                end
            elseif err then
                obs.errors[#obs.errors + 1] = "mark: " .. tostring(err)
            end
        end
        -- 原生控件：工具栏、菜单、对话框、Lua 自己渲染的控件
        if opts.native ~= false then
            local dump = lemurx.ui.dump({ maxNodes = opts.maxNative or 400, gone = false })
            if type(dump) == "table" and dump.ok and dump.roots then
                local interactiveOnly = opts.nativeInteractiveOnly ~= false
                local count = 0
                local function walk(node, depth)
                    if count >= (opts.maxNative or 200) then
                        return
                    end
                    local inWeb = vp and vp.ok and node.x >= vp.x and node.y >= vp.y + (vp.contentOffsetY or 0)
                        and node.x + node.w <= vp.x + vp.w and node.y + node.h <= vp.y + vp.h
                        and node.class ~= "ContentView" and node.class ~= "CompositorView"
                    local interesting = node.visible and node.w > 0 and node.h > 0
                        and (node.clickable or not isEmpty(node.text) or not isEmpty(node.desc))
                        and not (node.class or ""):match("Compositor")
                        and not (node.class or ""):match("^ContentView")
                    -- 网页区域内的原生节点一般是 ContentView 本身，跳过，避免和网页元素重复
                    if interesting and not (inWeb and isEmpty(node.text) and isEmpty(node.desc)) then
                        if not interactiveOnly or node.clickable or not isEmpty(node.text) or not isEmpty(node.desc) then
                            count = count + 1
                            obs.elements[#obs.elements + 1] = {
                                kind = "native", ref = "n" .. tostring(node.ref), nref = node.ref,
                                class = node.class, id = node.id, text = node.text, desc = node.desc,
                                clickable = node.clickable, enabled = node.enabled, focused = node.focused,
                                x = node.x, y = node.y, w = node.w, h = node.h, depth = depth,
                            }
                        end
                    end
                    for _, c in ipairs(node.children or {}) do
                        walk(c, depth + 1)
                    end
                end
                for _, root in ipairs(dump.roots) do
                    walk(root, 0)
                end
            end
        end
    end

    -- 截图最后拍，保证和上面的几何是同一帧附近
    if opts.screenshot ~= false then
        local shot
        if obs.mode == "system" then
            shot = system.screenshot({ scale = opts.scale or 0.5, quality = opts.quality or 60, base64 = opts.base64 })
        else
            shot = lemurx.ui.screenshot({ scale = opts.scale or 0.5, quality = opts.quality or 60, base64 = opts.base64 })
        end
        if type(shot) == "table" and shot.ok then
            obs.screenshot = shot
        elseif type(shot) == "table" then
            obs.errors[#obs.errors + 1] = "screenshot: " .. tostring(shot.error)
        end
    end
    return obs
end

--- 把观察结果压成给模型看的文本。每行一个元素：
---   [w3] link "Sign in" @(120,340 200x48)
--- opts: {max=80, coords=true, header=true}
function agent.describe(obs, opts)
    opts = opts or {}
    local lines = {}
    if opts.header ~= false then
        if obs.mode == "system" and obs.foreground then
            lines[#lines + 1] = string.format("APP %s %s", obs.foreground.package or "?", obs.foreground.title or "")
        elseif obs.tab then
            lines[#lines + 1] = string.format("PAGE %s — %s", trunc(obs.tab.title, 80), trunc(obs.tab.url, 120))
        end
        if obs.page and obs.page.scroll then
            lines[#lines + 1] = string.format("SCROLL y=%d/%d", round(obs.page.scroll.y), round(obs.page.scroll.maxY or 0))
        end
        if obs.screenshot then
            lines[#lines + 1] = string.format("SCREENSHOT %s (%dx%d, scale %.2f)", obs.screenshot.abs or obs.screenshot.path,
                obs.screenshot.width or 0, obs.screenshot.height or 0, obs.screenshot.scale or 1)
        end
    end
    local n = 0
    for _, e in ipairs(obs.elements or {}) do
        if n >= (opts.max or 80) then
            lines[#lines + 1] = "…"
            break
        end
        local label = e.text
        if isEmpty(label) then
            label = e.name or e.desc
        end
        if isEmpty(label) and e.kind == "native" then
            label = e.id
        end
        local role = e.role or (e.kind == "native" and e.class) or e.class or "node"
        local flags = {}
        if e.editable then flags[#flags + 1] = "editable" end
        if e.checked ~= nil and e.checked ~= false and e.checked ~= "" then flags[#flags + 1] = "checked" end
        if e.disabled or e.enabled == false then flags[#flags + 1] = "disabled" end
        if e.focused then flags[#flags + 1] = "focused" end
        if e.kind == "native" and e.clickable then flags[#flags + 1] = "clickable" end
        local line = string.format("[%s] %s \"%s\"", e.ref, role, trunc(label, 60))
        if e.kind == "native" and not isEmpty(e.id) and label ~= e.id then
            line = line .. " #" .. e.id
        end
        if #flags > 0 then
            line = line .. " (" .. table.concat(flags, ",") .. ")"
        end
        if opts.coords ~= false then
            line = line .. string.format(" @(%d,%d %dx%d)", e.x or 0, e.y or 0, e.w or 0, e.h or 0)
        end
        lines[#lines + 1] = line
        n = n + 1
    end
    if obs.page and obs.page.text and opts.text ~= false then
        lines[#lines + 1] = "TEXT:"
        lines[#lines + 1] = obs.page.text
    end
    return table.concat(lines, "\n")
end

--- 在观察结果里按 ref / 文本找元素。
function agent.find(obs, query)
    if not obs or not obs.elements then
        return nil
    end
    if type(query) == "string" then
        for _, e in ipairs(obs.elements) do
            if e.ref == query then
                return e
            end
        end
        local q = query:lower()
        for _, e in ipairs(obs.elements) do
            local hay = ((e.text or "") .. " " .. (e.name or "") .. " " .. (e.desc or "") .. " " .. (e.id or "")):lower()
            if hay:find(q, 1, true) then
                return e
            end
        end
        return nil
    end
    if type(query) == "table" then
        for _, e in ipairs(obs.elements) do
            local ok = true
            for k, v in pairs(query) do
                if e[k] ~= v then
                    ok = false
                    break
                end
            end
            if ok then
                return e
            end
        end
    end
    return nil
end

-- ------------------------------------------------------------------ 执行

local lastObs

local function resolveRef(ref, obs)
    obs = obs or lastObs
    if type(ref) == "table" then
        return ref
    end
    if type(ref) ~= "string" then
        return nil
    end
    local e = obs and agent.find(obs, ref)
    if e then
        return e
    end
    -- 没有观察结果也能解析：n12 / s5 直接当原生 / 系统句柄
    local kind, num = ref:match("^(%a)(%d+)$")
    if kind == "n" then
        return { kind = "native", ref = ref, nref = tonumber(num) }
    elseif kind == "s" then
        return { kind = "system", ref = ref, sref = tonumber(num) }
    elseif kind == "w" then
        return { kind = "web", ref = ref, mark = tonumber(num) }
    end
    return nil
end

local function centerOf(e)
    return (e.x or 0) + (e.w or 0) / 2, (e.y or 0) + (e.h or 0) / 2
end

local function systemForeground()
    local ok, fg = pcall(system.foreground)
    if ok and type(fg) == "table" and fg.ok then
        return fg
    end
    return nil
end

-- 屏幕坐标点按：优先网页 View（lemurx.input.tap 相对网页），否则窗口，前台是别的 App 走系统。
local function tapScreen(x, y, long, obs)
    obs = obs or lastObs
    local fg = systemForeground()
    if fg and fg.self == false then
        return long and system.longPress({ x = x, y = y }) or system.tap({ x = x, y = y })
    end
    local vp = obs and obs.viewport or lemurx.tabs.viewport()
    if type(vp) == "table" and vp.ok and x >= vp.x and x < vp.x + vp.w
        and y >= vp.y + (vp.contentOffsetY or 0) and y < vp.y + vp.h and not long then
        -- 在网页区域：直接投给网页 View，坐标相对该 View
        local ok = lemurx.input.tap(x - vp.x, y - vp.y, vp.tab, "px")
        return { ok = ok and true or false, via = "tab", x = x, y = y }
    end
    if long then
        return lemurx.ui.longPress(x, y)
    end
    return lemurx.ui.tap(x, y)
end

local function actTap(a, e, obs)
    if e then
        if e.kind == "system" then
            local ref = e.sref or tonumber((e.ref or ""):match("%d+"))
            return a.long and system.longClick({ ref = ref }) or system.click({ ref = ref })
        end
        if e.kind == "native" and not a.coords then
            local q = { ref = e.nref or tonumber((e.ref or ""):match("%d+")) }
            local r = a.long and lemurx.ui.longClick(q) or lemurx.ui.click(q)
            if type(r) == "table" and r.ok then
                return r
            end
            -- 句柄失效就退回坐标
        end
        if e.kind == "web" and a.via == "js" then
            local tabId = currentTabId(a.tab)
            local ok = lemurx.tabs.eval(tabId, string.format(
                "(function(){var e=document.querySelector('[data-lemurx-mark=\"%d\"]');if(!e)return false;e.scrollIntoView({block:'center'});e.click();return true;})()",
                e.mark or 0))
            return { ok = ok == true, via = "js" }
        end
        local cx, cy = centerOf(e)
        if a.dx then cx = cx + a.dx end
        if a.dy then cy = cy + a.dy end
        return tapScreen(cx, cy, a.long, obs)
    end
    if a.x and a.y then
        return tapScreen(a.x, a.y, a.long, obs)
    end
    return { ok = false, error = "tap needs ref or x,y" }
end

local function actType(a, e, obs)
    local text = a.text or a.value or ""
    if e and e.kind == "system" then
        return system.setText({ ref = e.sref or tonumber((e.ref or ""):match("%d+")), text = text, append = a.append })
    end
    if e and e.kind == "native" then
        return lemurx.ui.setText({ ref = e.nref or tonumber((e.ref or ""):match("%d+")) }, text)
    end
    local fg = systemForeground()
    if fg and fg.self == false then
        return system.setText({ text = text, append = a.append })
    end
    local tabId = currentTabId(a.tab)
    if e and e.kind == "web" then
        -- 先聚焦到目标，再走 input.type（走的是 activeElement）
        lemurx.tabs.eval(tabId, string.format(
            "(function(){var e=document.querySelector('[data-lemurx-mark=\"%d\"]');if(!e)return false;e.scrollIntoView({block:'center'});e.focus();%s;return true;})()",
            e.mark or 0, a.clear and "if('value' in e){e.value='';e.dispatchEvent(new Event('input',{bubbles:true}));}" or ""))
    elseif a.clear then
        lemurx.tabs.eval(tabId, "(function(){var e=document.activeElement;if(e&&'value' in e){e.value='';e.dispatchEvent(new Event('input',{bubbles:true}));}})()")
    end
    local ok = lemurx.input.type(text, tabId)
    if a.enter or a.submit then
        lemurx.input.key("enter", tabId)
    end
    return { ok = ok and true or false, via = "web" }
end

local function actSwipe(a, e, obs)
    obs = obs or lastObs
    local dir = (a.dir or a.direction or "down"):lower()
    local x1, y1, x2, y2
    if a.x1 then
        x1, y1, x2, y2 = a.x1, a.y1, a.x2, a.y2
    else
        local cx, cy, w, h
        if e then
            cx, cy = centerOf(e)
            w, h = e.w or 200, e.h or 200
        else
            local vp = obs and obs.viewport or lemurx.tabs.viewport()
            if type(vp) == "table" and vp.ok then
                cx = vp.x + vp.w / 2
                cy = vp.y + (vp.contentOffsetY or 0) + (vp.h - (vp.contentOffsetY or 0)) / 2
                w, h = vp.w, vp.h - (vp.contentOffsetY or 0)
            else
                local s = obs and obs.screen or { w = 1080, h = 2200 }
                cx, cy, w, h = s.w / 2, s.h / 2, s.w, s.h
            end
        end
        local dist = a.distance or (h * 0.6)
        -- "down" = 看下面的内容 = 手指向上滑
        if dir == "down" then
            x1, y1, x2, y2 = cx, cy + dist / 2, cx, cy - dist / 2
        elseif dir == "up" then
            x1, y1, x2, y2 = cx, cy - dist / 2, cx, cy + dist / 2
        elseif dir == "left" then
            dist = a.distance or (w * 0.6)
            x1, y1, x2, y2 = cx + dist / 2, cy, cx - dist / 2, cy
        else -- right
            dist = a.distance or (w * 0.6)
            x1, y1, x2, y2 = cx - dist / 2, cy, cx + dist / 2, cy
        end
    end
    local duration = a.duration or 320
    local fg = systemForeground()
    if fg and fg.self == false then
        return system.swipe({ x1 = x1, y1 = y1, x2 = x2, y2 = y2, duration = duration })
    end
    if e and e.kind == "system" then
        return system.scroll({ ref = e.sref, dir = (dir == "down" or dir == "right") and "forward" or "backward" })
    end
    local vp = obs and obs.viewport or lemurx.tabs.viewport()
    if type(vp) == "table" and vp.ok and x1 >= vp.x and x1 < vp.x + vp.w
        and y1 >= vp.y + (vp.contentOffsetY or 0) and y1 < vp.y + vp.h then
        local ok = lemurx.input.swipe({ x1 = x1 - vp.x, y1 = y1 - vp.y, x2 = x2 - vp.x, y2 = y2 - vp.y,
            duration = duration, tab = vp.tab, unit = "px" })
        return { ok = ok and true or false, via = "tab" }
    end
    return lemurx.ui.swipe({ x1 = x1, y1 = y1, x2 = x2, y2 = y2, duration = duration })
end

local function actBack(a)
    local fg = systemForeground()
    if fg and fg.self == false then
        return system.global("back")
    end
    if lemurx.chrome and lemurx.chrome.back then
        local ok = pcall(lemurx.chrome.back)
        return { ok = ok, via = "chrome" }
    end
    local t = lemurx.tabs.current()
    if t then
        return { ok = lemurx.tabs.back(t.id) and true or false, via = "tabs" }
    end
    return { ok = false, error = "no tab" }
end

--- 执行一个动作。action.type：
---   tap / click {ref | x,y, long=, via="js", dx=, dy=}
---   longPress   {ref | x,y}
---   type / text {text, ref=, clear=, enter=, append=}
---   swipe / scroll {dir="down"|"up"|"left"|"right", ref=, distance=, duration= | x1,y1,x2,y2}
---   key         {key="enter"|"back"|"tab"|...}
---   back / home / recents / notifications
---   navigate    {url, tab=}         open {url}（新标签）
---   eval        {js, tab=}
---   launch      {package | url}
---   wait        {ms}                只在 lemurx.async 里真正等待
---   global      {action=...}        透传 lemurx.system.global
--- 第二个参数是 observe() 的结果（缺省用上一次的）。返回 {ok, ...}。
function agent.act(a, obs)
    if type(a) == "string" then
        a = { type = a }
    end
    if type(a) ~= "table" then
        return { ok = false, error = "action must be a table" }
    end
    obs = obs or lastObs
    local kind = (a.type or a.action or "tap"):lower()
    local e = a.ref and resolveRef(a.ref, obs) or nil
    if a.ref and not e then
        return { ok = false, error = "unknown ref " .. tostring(a.ref) }
    end
    local ok, r = pcall(function()
        if kind == "tap" or kind == "click" then
            return actTap(a, e, obs)
        elseif kind == "longpress" or kind == "long_press" or kind == "longclick" then
            a.long = true
            return actTap(a, e, obs)
        elseif kind == "type" or kind == "text" or kind == "input" then
            return actType(a, e, obs)
        elseif kind == "swipe" or kind == "scroll" then
            return actSwipe(a, e, obs)
        elseif kind == "key" then
            local fg = systemForeground()
            if fg and fg.self == false and (a.key == "back" or a.key == "home") then
                return system.global(a.key)
            end
            return { ok = lemurx.input.key(a.key or "enter", currentTabId(a.tab)) and true or false }
        elseif kind == "back" then
            return actBack(a)
        elseif kind == "home" or kind == "recents" or kind == "notifications" then
            return system.global(kind)
        elseif kind == "global" then
            return system.global(a.action or a.name)
        elseif kind == "navigate" or kind == "goto" then
            local id = currentTabId(a.tab)
            return { ok = lemurx.tabs.navigate(id, a.url) and true or false, tab = id }
        elseif kind == "open" then
            local id = lemurx.tabs.open(a.url, a.opts)
            return { ok = id ~= nil, tab = id }
        elseif kind == "eval" or kind == "js" then
            return { ok = true, result = lemurx.tabs.eval(currentTabId(a.tab), a.js or a.code or "") }
        elseif kind == "launch" then
            return system.launch(a.package and { package = a.package, activity = a.activity } or { url = a.url })
        elseif kind == "wait" or kind == "sleep" then
            return { ok = true, waited = agent.wait(a.ms or 500) }
        elseif kind == "screenshot" then
            return lemurx.ui.screenshot(a)
        elseif kind == "done" or kind == "finish" then
            return { ok = true, done = true, result = a.result }
        end
        return { ok = false, error = "unknown action " .. kind }
    end)
    if not ok then
        return { ok = false, error = tostring(r) }
    end
    if type(r) ~= "table" then
        r = { ok = r and true or false }
    end
    r.action = kind
    return r
end

--- 观察并记住结果，之后 act 不传 obs 也能解析 ref。
function agent.look(opts)
    lastObs = agent.observe(opts)
    return lastObs
end

--- 观察-决策-执行循环。policy(obs, describeText, step) 返回一个 action（或 nil 停止）；
--- action.type == "done" 也停止。在 lemurx.async 里跑，动作之间会 sleep(opts.settle)。
--- opts: {maxSteps=20, settle=600, observe={...}, onStep=function(step, obs, action, result) end}
--- 返回 {steps, done, result}。
function agent.run(policy, opts)
    opts = opts or {}
    local maxSteps = opts.maxSteps or 20
    local function loop()
        local out = { steps = 0, done = false }
        for step = 1, maxSteps do
            local obs = agent.look(opts.observe)
            local text = agent.describe(obs, opts.describe)
            local action = policy(obs, text, step)
            if not action then
                break
            end
            local result = agent.act(action, obs)
            out.steps = step
            if opts.onStep then
                pcall(opts.onStep, step, obs, action, result)
            end
            if result.done or (type(action) == "table" and action.type == "done") then
                out.done = true
                out.result = result.result or action.result
                break
            end
            agent.wait(opts.settle or 600)
        end
        return out
    end
    if inCoroutine() or not lemurx.async then
        return loop()
    end
    lemurx.async(function()
        local out = loop()
        if opts.onDone then
            pcall(opts.onDone, out)
        end
    end)
    return nil
end

-- ------------------------------------------------------------------ system 事件的 Lua 糖

local systemEventsOn = false

--- lemurx.system.on("window"|"content"|"click"|"focus"|"text"|"notification"|"connected"|"disconnected", fn)
--- 第一次订阅时才打开系统事件转发（否则空跑刷屏）。
function system.on(name, fn)
    if not systemEventsOn then
        pcall(system.events, true)
        systemEventsOn = true
    end
    return lemurx.tabs.on("system." .. tostring(name), fn)
end

--- 无障碍服务是否已连接（用户在系统设置里打开了）。
function system.ready()
    local ok, st = pcall(system.status)
    return ok and type(st) == "table" and st.connected == true, st
end

--- 没开就带用户去设置页；返回是否已就绪。
function system.ensure()
    local ready, st = system.ready()
    if ready then
        return true
    end
    pcall(system.openSettings)
    return false, st
end

lemurx.log("agent " .. agent.version .. " ready (lemurx.agent / lemurx.system)")
