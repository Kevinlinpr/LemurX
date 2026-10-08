-- @name 网页截图
-- @description 可见区域 / 整页长截图 / 选中元素截图，截完可裁剪、涂抹、画框、加文字，然后存相册、分享、复制链接。
-- @version 1.0.0
-- @icon 📸
-- @category 效率
-- @page lemurx://screenshot/
-- @replaces FireShot · GoFullPage · Awesome Screenshot · Nimbus Capture
--
-- 能力：
--   可见区域   lemurx.tabs.screenshot（PixelCopy，含原生 UI 上的网页视图）
--   整页       CDP Page.getLayoutMetrics + Page.captureScreenshot(captureBeyondViewport)，超长页按 max_height 截断
--   元素       CDP DOM.getBoxModel 取 clip
--   编辑器     lemurx://screenshot/edit?f=… 页面内 canvas：裁剪 / 矩形 / 箭头 / 马赛克 / 文字，POST 回来保存
--   存相册     lemurx.media.save（MediaStore，Pictures/LemurX）；分享 lemurx.share（FileProvider）
local lx = require("lx")
local json = require("lx.json")
local util = require("lx.util")

local ID = "screenshot"
local S

S = lx.register({
    id = ID, name = "网页截图", version = "1.0.0", icon = "📸",
    description = "可见区域 / 整页长截图 / 选中元素截图；裁剪、画框、箭头、马赛克、文字标注；存相册、分享。",
    replaces = "FireShot · GoFullPage · Awesome Screenshot · Nimbus Capture",
    settings = {
        enabled = true,
        format = "jpeg",           -- jpeg | png
        quality = 90,
        max_height = 12000,        -- 整页截图 CSS 像素上限
        scale = 1,                 -- 整页截图缩放（1 = 设备像素比 1）
        open_editor = true,        -- 截完直接进编辑器
        auto_save = false,         -- 截完自动存相册
        keep = 50,                 -- 保留最近多少张
    },
    schema = {
        { key = "format", type = "select", label = "格式", options = { { "jpeg", "JPEG（小）" }, { "png", "PNG（无损）" } } },
        { key = "quality", type = "number", label = "JPEG 质量", min = 30, max = 100 },
        { key = "max_height", type = "number", label = "整页截图最大高度（CSS 像素）", min = 2000, max = 40000, step = 1000 },
        { key = "scale", type = "number", label = "整页截图缩放", min = 0.5, max = 2, step = 0.5, desc = "1 = 逻辑像素 1:1；2 = 高清但文件大" },
        { key = "open_editor", type = "bool", label = "截完打开编辑器" },
        { key = "auto_save", type = "bool", label = "截完自动存相册" },
        { key = "keep", type = "number", label = "保留最近多少张", min = 5, max = 500 },
    },
    menu = {
        { id = "visible", title = "截图：可见区域", onClick = function() S.capture("visible") end },
        { id = "full", title = "截图：整页", onClick = function() S.capture("full") end },
        { id = "open", title = "截图记录", page = "main" },
    },
    api = {},
})
local settings = S.settings

local CAPTURES = "captures"
local index = lx.data(ID):read_json("index.json") or {}
local function save_index()
    local keep = tonumber(settings:get("keep")) or 50
    table.sort(index, function(a, b) return (a.at or 0) > (b.at or 0) end)
    while #index > keep do
        local old = table.remove(index)
        pcall(lemurx.fs.remove, old.path)
    end
    lx.data(ID):write_json("index.json", index)
end

local function ext() return settings:get("format") == "png" and "png" or "jpg" end
local function mime() return settings:get("format") == "png" and "image/png" or "image/jpeg" end
local function new_name(kind) return ("%s/lx_%s_%s.%s"):format(CAPTURES, kind, os.date("%Y%m%d_%H%M%S"), ext()) end

local function record(path, kind, tab, w, h)
    local entry = { path = path, kind = kind, url = tab and tab.url, title = tab and tab.title, at = util.now_ms(), width = w, height = h }
    table.insert(index, 1, entry)
    save_index()
    return entry
end

-- ===== 三种截法 =====
local function capture_visible(tab)
    local ok, r = pcall(lemurx.tabs.screenshot, tab.id, { quality = tonumber(settings:get("quality")) or 90, scale = 1 })
    if not (ok and type(r) == "table" and r.ok) then return nil, "截图失败：" .. tostring(ok and (r and r.error) or r) end
    -- tabs.screenshot 存的是 JPEG；PNG 设置下也先接受 JPEG（PixelCopy 路径只有 JPEG）
    return r.path, r.width, r.height
end

local function cdp(tab_id, method, params)
    local ok, r = pcall(lemurx.cdp.send, tab_id, method, params or {}, 20000)
    if ok and type(r) == "table" and r.ok then return r.result end
    return nil, ok and (r and r.error) or r
end

local function capture_cdp(tab, clip, kind)
    pcall(lemurx.cdp.attach, tab.id)
    local params = { format = settings:get("format") == "png" and "png" or "jpeg", captureBeyondViewport = true, fromSurface = true }
    if params.format == "jpeg" then params.quality = tonumber(settings:get("quality")) or 90 end
    if clip then params.clip = clip end
    local r, err = cdp(tab.id, "Page.captureScreenshot", params)
    if not r or type(r.data) ~= "string" then return nil, "CDP 截图失败：" .. tostring(err and (err.message or err) or "无数据") end
    local path = new_name(kind)
    local ok, w = pcall(lemurx.fs.write, path, r.data, { base64 = true })
    if not (ok and type(w) == "table" and w.ok) then return nil, "写文件失败" end
    return path, clip and math.floor(clip.width * (clip.scale or 1)) or nil, clip and math.floor(clip.height * (clip.scale or 1)) or nil
end

local function capture_full(tab)
    pcall(lemurx.cdp.attach, tab.id)
    local m, err = cdp(tab.id, "Page.getLayoutMetrics")
    if not m then return nil, "拿不到页面尺寸：" .. tostring(err and (err.message or err)) end
    local size = m.cssContentSize or m.contentSize or {}
    local vp = m.cssLayoutViewport or m.layoutViewport or {}
    local width = math.max(1, math.floor(size.width or vp.clientWidth or 0))
    local height = math.max(1, math.floor(size.height or 0))
    local max_h = tonumber(settings:get("max_height")) or 12000
    local truncated = height > max_h
    if truncated then height = max_h end
    local scale = tonumber(settings:get("scale")) or 1
    -- 像素总量兜底：超过 ~4 千万像素就把 scale 往下压
    while width * height * scale * scale > 40e6 and scale > 0.25 do scale = scale / 2 end
    local path, w, h = capture_cdp(tab, { x = 0, y = 0, width = width, height = height, scale = scale }, "full")
    if not path then return nil, w end
    return path, w, h, truncated
end

local function capture_element(tab, selector)
    pcall(lemurx.cdp.attach, tab.id)
    local doc = cdp(tab.id, "DOM.getDocument", { depth = 1 })
    if not doc then return nil, "DOM 不可用" end
    local q = cdp(tab.id, "DOM.querySelector", { nodeId = doc.root.nodeId, selector = selector })
    if not q or not q.nodeId or q.nodeId == 0 then return nil, "找不到元素 " .. selector end
    pcall(cdp, tab.id, "DOM.scrollIntoViewIfNeeded", { nodeId = q.nodeId })
    local box = cdp(tab.id, "DOM.getBoxModel", { nodeId = q.nodeId })
    if not box or not box.model then return nil, "元素没有盒模型" end
    local b = box.model.border
    local x, y = math.min(b[1], b[7]), math.min(b[2], b[4])
    local w, h = math.abs(b[3] - b[1]), math.abs(b[6] - b[2])
    -- border 坐标相对视口，需要加上滚动量
    local m = cdp(tab.id, "Page.getLayoutMetrics") or {}
    local vp = m.cssVisualViewport or m.visualViewport or {}
    x, y = x + (vp.pageX or 0), y + (vp.pageY or 0)
    return capture_cdp(tab, { x = x, y = y, width = math.max(1, w), height = math.max(1, h), scale = tonumber(settings:get("scale")) or 1 }, "element")
end

-- ===== 入口 =====
function S.capture(kind, opts)
    opts = opts or {}
    local tab = opts.tab or lx.tabs.current()
    -- 从 lemurx:// 页面（截图记录页）发起：截最近看过的那个网页
    if tab and not (tab.url or ""):match("^https?://") then
        local best
        for _, t in ipairs(lx.tabs.list()) do
            if (t.url or ""):match("^https?://") and (not best or (t.lastActive or 0) > (best.lastActive or 0)) then best = t end
        end
        tab = best
    end
    if not tab then lx.toast("没有可截的网页") return nil, "no tab" end
    local path, w, h, truncated
    if kind == "full" then
        lx.toast("正在截整页…")
        path, w, h, truncated = capture_full(tab)
    elseif kind == "element" then
        path, w, h = capture_element(tab, opts.selector or "body")
    else
        path, w, h = capture_visible(tab)
    end
    if not path then lx.toast(tostring(w)) return nil, w end
    local entry = record(path, kind, tab, w, h)
    if truncated then lx.toast(("页面太长，截到 %d 像素"):format(tonumber(settings:get("max_height")) or 12000)) end
    if settings:get("auto_save") then S.save(entry.path) end
    if settings:get("open_editor") and not opts.no_editor then
        lx.tabs.open("lemurx://screenshot/edit?f=" .. util.url.encode(path))
    else
        lx.toast("已截图：" .. path)
    end
    return entry
end

function S.save(path)
    local ok, r = pcall(lemurx.media.save, { path = path, mime = path:match("%.png$") and "image/png" or "image/jpeg", album = "LemurX" })
    if ok and type(r) == "table" and r.ok then lx.toast("已存到相册 " .. tostring(r.path or "")) return r end
    lx.toast("存相册失败：" .. tostring(ok and (r and r.error) or r))
    return nil, ok and (r and r.error) or r
end
function S.share(path, text)
    local ok, r = pcall(lemurx.share, { path = path, mime = path:match("%.png$") and "image/png" or "image/jpeg", text = text, title = "分享截图" })
    return ok and r
end

local function find(path) for i, e in ipairs(index) do if e.path == path then return e, i end end end

-- ===== API =====
S.api.capture = function(args, ctx)
    local tab
    if args.tab then for _, t in ipairs(lx.tabs.list()) do if t.id == tonumber(args.tab) then tab = t end end end
    local e, err = S.capture(args.kind or "visible", { tab = tab, selector = args.selector, no_editor = args.no_editor })
    if not e then return nil, err end
    return { ok = true, entry = e, message = "已截图" }
end
S.api.list = function() return { items = index } end
S.api.save = function(args) local r, err = S.save(args.path) if not r then return nil, err end return { ok = true, message = "已存到相册", uri = r.uri } end
S.api.share = function(args) return { ok = S.share(args.path, args.text) } end
S.api.remove = function(args)
    local e, i = find(args.path)
    if not e then return nil, "not found" end
    table.remove(index, i)
    pcall(lemurx.fs.remove, e.path)
    save_index()
    return { ok = true, message = "已删除", reload = true }
end
S.api.clear = function()
    for _, e in ipairs(index) do pcall(lemurx.fs.remove, e.path) end
    index = {}
    save_index()
    return { ok = true, message = "已清空", reload = true }
end
-- 编辑器保存：POST { path, data(base64 dataURL), replace }
S.api["edit.save"] = function(args)
    local data = type(args.data) == "string" and args.data:gsub("^data:[%w/+%-%.]+;base64,", "") or nil
    if not data or data == "" then return nil, "no data" end
    local src = find(args.path)
    local is_png = args.data:sub(1, 22) == "data:image/png;base64,"
    local path = args.replace and args.path or ("%s/lx_edit_%s.%s"):format(CAPTURES, os.date("%Y%m%d_%H%M%S"), is_png and "png" or "jpg")
    local ok, w = pcall(lemurx.fs.write, path, data, { base64 = true })
    if not (ok and type(w) == "table" and w.ok) then return nil, "写文件失败" end
    local e
    if args.replace and src then
        src.at = util.now_ms() src.width, src.height = args.width, args.height
        e = src
        save_index()
    else
        e = record(path, "edit", src and { url = src.url, title = src.title } or nil, args.width, args.height)
    end
    return { ok = true, path = path, entry = e }
end

-- ===== 路由：图片本体 / 编辑器 =====
S.routes["/img"] = function(ctx)
    local f = ctx.query.f or ""
    if not f:match("^captures/[%w_%-%.]+$") then return "bad path", "text/plain", 400 end
    local ok, r = pcall(lemurx.fs.read, f, { base64 = true })
    if not (ok and type(r) == "table" and r.ok) then return "not found", "text/plain", 404 end
    return util.base64_decode(r.data), f:match("%.png$") and "image/png" or "image/jpeg", 200
end

local esc = lx.html.escape
S.routes["/edit"] = function(ctx)
    local f = ctx.query.f or ""
    if not f:match("^captures/[%w_%-%.]+$") then return "bad path", "text/plain", 400 end
    local e = find(f) or { path = f }
    return lx.html.page({
        title = "编辑截图", icon = "📸", back_url = "lemurx://screenshot/", back_label = "截图",
        css = [[
body{overflow:hidden}main{padding:0;display:flex;flex-direction:column;height:calc(100vh - 52px)}
#tools{display:flex;gap:6px;padding:8px;overflow-x:auto;flex:none;background:var(--card,#fff);border-bottom:1px solid var(--line,#eee)}#tools button{flex:none;padding:8px 12px;border-radius:10px;border:1px solid var(--line,#ddd);background:var(--card,#fff);color:inherit;font-size:14px}#tools button.on{background:var(--accent,#0a84ff);color:#fff;border-color:transparent}
#wrap{flex:1;overflow:auto;background:#222;position:relative;touch-action:none}#cv{display:block;margin:0 auto;max-width:none}
#bar{display:flex;gap:8px;padding:8px calc(env(safe-area-inset-bottom) + 8px) calc(env(safe-area-inset-bottom) + 8px) 8px;flex:none;background:var(--card,#fff);border-top:1px solid var(--line,#eee)}#bar button{flex:1}
#colors{display:flex;gap:6px;align-items:center}#colors i{width:22px;height:22px;border-radius:11px;display:inline-block;border:2px solid transparent}#colors i.on{border-color:#000}
]],
        body = ([[
<div id="tools"><button data-t="crop">✂️ 裁剪</button><button data-t="rect" class="on">▭ 框</button><button data-t="arrow">➚ 箭头</button><button data-t="pen">✏️ 画笔</button><button data-t="blur">▩ 马赛克</button><button data-t="text">T 文字</button><button id="undo">↶ 撤销</button>
 <span id="colors"><i data-c="#ff3b30" style="background:#ff3b30" class="on"></i><i data-c="#ffcc00" style="background:#ffcc00"></i><i data-c="#34c759" style="background:#34c759"></i><i data-c="#0a84ff" style="background:#0a84ff"></i><i data-c="#000" style="background:#000"></i><i data-c="#fff" style="background:#fff;border-color:#ccc"></i></span></div>
<div id="wrap"><canvas id="cv"></canvas></div>
<div id="bar"><button class="sec" id="save">存相册</button><button class="sec" id="share">分享</button><button id="done">保存副本</button></div>
<img id="src" src="/img?f=%s" style="display:none" crossorigin="anonymous">]]):format(esc(f)),
        js = ([==[
var PATH=%s;var img=lx.q('#src'),cv=lx.q('#cv'),ctx=cv.getContext('2d');var tool='rect',color='#ff3b30',ops=[],cur=null,base=null,fit=1;
img.onload=function(){base=document.createElement('canvas');base.width=img.naturalWidth;base.height=img.naturalHeight;base.getContext('2d').drawImage(img,0,0);cv.width=base.width;cv.height=base.height;fit=Math.min(1,(lx.q('#wrap').clientWidth-8)/cv.width);cv.style.width=(cv.width*fit)+'px';draw()};
img.onerror=function(){lx.toast('图片读不到')};
function draw(){ctx.clearRect(0,0,cv.width,cv.height);ctx.drawImage(base,0,0);ops.concat(cur?[cur]:[]).forEach(function(o){ctx.save();ctx.strokeStyle=o.color;ctx.fillStyle=o.color;ctx.lineWidth=Math.max(3,cv.width/250);ctx.lineCap='round';ctx.lineJoin='round';
 if(o.t==='rect'){ctx.strokeRect(o.x0,o.y0,o.x1-o.x0,o.y1-o.y0)}
 else if(o.t==='arrow'){var a=Math.atan2(o.y1-o.y0,o.x1-o.x0),h=Math.max(12,cv.width/60);ctx.beginPath();ctx.moveTo(o.x0,o.y0);ctx.lineTo(o.x1,o.y1);ctx.stroke();ctx.beginPath();ctx.moveTo(o.x1,o.y1);ctx.lineTo(o.x1-h*Math.cos(a-.4),o.y1-h*Math.sin(a-.4));ctx.lineTo(o.x1-h*Math.cos(a+.4),o.y1-h*Math.sin(a+.4));ctx.closePath();ctx.fill()}
 else if(o.t==='pen'){ctx.beginPath();o.pts.forEach(function(p,i){i?ctx.lineTo(p[0],p[1]):ctx.moveTo(p[0],p[1])});ctx.stroke()}
 else if(o.t==='blur'){var x=Math.min(o.x0,o.x1),y=Math.min(o.y0,o.y1),w=Math.abs(o.x1-o.x0),hh=Math.abs(o.y1-o.y0);if(w>2&&hh>2){var s=Math.max(8,Math.round(cv.width/60));var tmp=document.createElement('canvas');tmp.width=Math.max(1,Math.round(w/s));tmp.height=Math.max(1,Math.round(hh/s));tmp.getContext('2d').drawImage(cv,x,y,w,hh,0,0,tmp.width,tmp.height);ctx.imageSmoothingEnabled=false;ctx.drawImage(tmp,0,0,tmp.width,tmp.height,x,y,w,hh);ctx.imageSmoothingEnabled=true}}
 else if(o.t==='text'){ctx.font='bold '+Math.max(18,cv.width/25)+'px sans-serif';ctx.lineWidth=Math.max(4,cv.width/150);ctx.strokeStyle='rgba(0,0,0,.6)';ctx.strokeText(o.text,o.x0,o.y0);ctx.fillText(o.text,o.x0,o.y0)}
 else if(o.t==='crop'){ctx.fillStyle='rgba(0,0,0,.45)';ctx.fillRect(0,0,cv.width,cv.height);ctx.clearRect(o.x0,o.y0,o.x1-o.x0,o.y1-o.y0);ctx.drawImage(base,o.x0,o.y0,o.x1-o.x0,o.y1-o.y0,o.x0,o.y0,o.x1-o.x0,o.y1-o.y0);ctx.setLineDash([8,6]);ctx.strokeStyle='#fff';ctx.strokeRect(o.x0,o.y0,o.x1-o.x0,o.y1-o.y0)}
 ctx.restore()})}
function pos(e){var r=cv.getBoundingClientRect();var t=e.touches?e.touches[0]:e;return[(t.clientX-r.left)/fit,(t.clientY-r.top)/fit]}
var down=false;
cv.addEventListener('pointerdown',function(e){e.preventDefault();down=true;var p=pos(e);if(tool==='text'){var t=prompt('文字');if(t){ops.push({t:'text',x0:p[0],y0:p[1],text:t,color:color});draw()}down=false;return}cur={t:tool,x0:p[0],y0:p[1],x1:p[0],y1:p[1],color:color,pts:[p]}});
cv.addEventListener('pointermove',function(e){if(!down||!cur)return;e.preventDefault();var p=pos(e);cur.x1=p[0];cur.y1=p[1];if(tool==='pen')cur.pts.push(p);draw()});
function up(){if(!down)return;down=false;if(!cur)return;if(cur.t==='crop'){var x0=Math.min(cur.x0,cur.x1),y0=Math.min(cur.y0,cur.y1),w=Math.abs(cur.x1-cur.x0),h=Math.abs(cur.y1-cur.y0);cur=null;if(w>10&&h>10&&confirm('裁剪到选区？')){draw();var nb=document.createElement('canvas');nb.width=w;nb.height=h;nb.getContext('2d').drawImage(cv,x0,y0,w,h,0,0,w,h);base=nb;ops=[];cv.width=w;cv.height=h;fit=Math.min(1,(lx.q('#wrap').clientWidth-8)/cv.width);cv.style.width=(cv.width*fit)+'px'}draw();return}
 if(Math.abs(cur.x1-cur.x0)+Math.abs(cur.y1-cur.y0)>3||cur.t==='pen')ops.push(cur);cur=null;draw()}
cv.addEventListener('pointerup',up);cv.addEventListener('pointercancel',up);cv.addEventListener('pointerleave',up);
document.querySelectorAll('#tools button[data-t]').forEach(function(b){b.onclick=function(){tool=b.dataset.t;document.querySelectorAll('#tools button[data-t]').forEach(function(x){x.classList.toggle('on',x===b)})}});
document.querySelectorAll('#colors i').forEach(function(i){i.onclick=function(){color=i.dataset.c;document.querySelectorAll('#colors i').forEach(function(x){x.classList.toggle('on',x===i)})}});
lx.q('#undo').onclick=function(){ops.pop();draw()};
function exportData(){cur=null;draw();var png=/\.png$/.test(PATH);return cv.toDataURL(png?'image/png':'image/jpeg',0.92)}
function persist(replace){return lx.api('edit.save',{path:PATH,data:exportData(),replace:!!replace,width:cv.width,height:cv.height})}
lx.q('#done').onclick=function(){persist(false).then(function(r){lx.toast('已保存 '+r.path);location.href='lemurx://screenshot/'}).catch(function(e){lx.toast(e.message)})};
lx.q('#save').onclick=function(){persist(ops.length?false:true).then(function(r){return lx.api('save',{path:r.path})}).then(function(){lx.toast('已存到相册')}).catch(function(e){lx.toast(e.message)})};
lx.q('#share').onclick=function(){persist(ops.length?false:true).then(function(r){return lx.api('share',{path:r.path})}).catch(function(e){lx.toast(e.message)})};
]==]):format(json.encode(f)),
    }), "text/html", 200
end

S.page_css = [[
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;padding:4px 0}.shot{border:1px solid var(--line,#eee);border-radius:12px;overflow:hidden;background:var(--card,#fff)}.shot img{width:100%;height:150px;object-fit:cover;object-position:top;display:block}.shot .m{padding:6px 8px;font-size:12px}.shot .m .t{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.shot .b{display:flex;gap:4px;padding:0 6px 6px}.shot .b button{flex:1;padding:5px 0;font-size:12px}
.cap{display:flex;gap:8px}.cap button{flex:1}
]]
S.summary = function()
    local cards = {}
    for _, e in ipairs(index) do
        cards[#cards + 1] = ([[<div class="shot"><a href="lemurx://screenshot/edit?f=%s"><img src="/img?f=%s" loading="lazy"></a><div class="m"><div class="t">%s</div><div style="opacity:.6">%s · %s%s</div></div>
<div class="b"><button class="sec" data-api="save" data-args='%s'>相册</button><button class="sec" data-api="share" data-args='%s'>分享</button><button class="sec" data-api="remove" data-args='%s'>删</button></div></div>]])
            :format(esc(util.url.encode(e.path)), esc(util.url.encode(e.path)), esc(e.title or e.url or e.path), os.date("%m-%d %H:%M", math.floor((e.at or 0) / 1000)),
                ({ visible = "可见区域", full = "整页", element = "元素", edit = "编辑" })[e.kind] or e.kind or "",
                e.width and (" · " .. e.width .. "×" .. (e.height or "?")) or "",
                esc(json.encode({ path = e.path })), esc(json.encode({ path = e.path })), esc(json.encode({ path = e.path })))
    end
    return ([[
<div class="card"><div class="row" style="display:block"><div class="t">截当前页</div><div class="d">从任意网页的三点菜单也能截。</div>
 <div class="cap" style="margin-top:8px"><button data-api="capture" data-args='{"kind":"visible","no_editor":true}'>可见区域</button><button data-api="capture" data-args='{"kind":"full","no_editor":true}'>整页</button><button class="sec" id="el">选中元素</button></div></div></div>
<div class="card"><div class="row"><div class="l"><div class="t">最近截图 <span class="badge">%d</span></div></div>%s</div><div class="grid">%s</div></div>]])
        :format(#index, #index > 0 and "<button class=\"sec\" data-api=\"clear\">清空</button>" or "",
            #cards > 0 and table.concat(cards) or "<div class=\"d\">还没有截图</div>")
end
S.page_js = [[
lx.q('#el').onclick=function(){var s=prompt('元素 CSS 选择器（会截取上一个标签里的该元素）','article');if(!s)return;var tabs=null;lx.api('capture',{kind:'element',selector:s,no_editor:true}).then(function(){location.reload()}).catch(function(e){lx.toast(e.message)})};
document.addEventListener('click',function(e){var b=e.target.closest('button[data-api=capture]');if(b)setTimeout(function(){location.reload()},1500)});
]]

return S
