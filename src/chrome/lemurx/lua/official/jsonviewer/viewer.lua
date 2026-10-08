-- jsonviewer/viewer · 注入到 JSON 文档里的查看器（JS 源码，作为 Lua 字符串返回）
--
-- 由 jsonviewer/web.lua 在 document-loaded 后注入。页面只有一个 <pre>（Chromium 对
-- application/json 响应的默认呈现）时接管：解析 → 可折叠树 / 原文 / 搜索 / 复制路径。
-- 配置从 window.__lxjv_cfg 读：{ theme, indent, collapse_depth, max_bytes, wrap }
return [==[
(function(){
if (window.__lxjv) return;
var cfg = Object.assign({ theme: 'auto', indent: 2, collapse_depth: 2, max_bytes: 4 * 1024 * 1024, wrap: false }, window.__lxjv_cfg || {});
var pre = document.body && document.body.children.length === 1 && document.body.firstElementChild.tagName === 'PRE' ? document.body.firstElementChild : null;
var raw = pre ? pre.textContent : (document.body ? document.body.textContent : '');
if (!raw) return;
var trimmed = raw.trim();
if (!trimmed || !/^[\[{]/.test(trimmed) && !/^"/.test(trimmed) && !/^(true|false|null|-?\d)/.test(trimmed)) return;
if (trimmed.length > cfg.max_bytes) return;
var data;
try { data = JSON.parse(trimmed); } catch (e) {
  // JSONP：callback({...});
  var m = trimmed.match(/^[\w$.]+\((.*)\);?$/s);
  if (!m) return;
  try { data = JSON.parse(m[1]); } catch (e2) { return; }
}
if (data === null || typeof data !== 'object') {
  // 纯标量也展示，但只在 contentType 明确是 json 时
  if (!/json/i.test(document.contentType || '')) return;
}
window.__lxjv = { data: data, raw: trimmed };

var dark = cfg.theme === 'dark' || (cfg.theme === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
var css = '\
:root{--bg:#fff;--fg:#1d1d1f;--muted:#8e8e93;--line:#e5e5ea;--key:#7c3aed;--str:#0a7d3a;--num:#1a56db;--bool:#c2410c;--null:#8e8e93;--bar:#f5f5f7;--hl:#fff3b0;--acc:#0a84ff}\
.lxjv-dark{--bg:#1c1c1e;--fg:#f5f5f7;--muted:#8e8e93;--line:#2c2c2e;--key:#c4b5fd;--str:#86efac;--num:#93c5fd;--bool:#fdba74;--null:#8e8e93;--bar:#2c2c2e;--hl:#854d0e}\
html,body{margin:0;background:var(--bg)!important;color:var(--fg)}body{font:13px/1.55 ui-monospace,SFMono-Regular,Menlo,Consolas,"Noto Sans Mono CJK SC",monospace;padding-bottom:40px;word-break:break-all}\
#lxjv-bar{position:sticky;top:0;z-index:9;display:flex;gap:6px;align-items:center;padding:8px 10px;background:var(--bar);border-bottom:1px solid var(--line);font-family:-apple-system,Roboto,"PingFang SC",sans-serif;font-size:13px;flex-wrap:wrap}\
#lxjv-bar button{border:1px solid var(--line);background:var(--bg);color:var(--fg);border-radius:8px;padding:5px 10px;font-size:13px}#lxjv-bar button.on{background:var(--acc);color:#fff;border-color:var(--acc)}\
#lxjv-bar input{flex:1;min-width:120px;border:1px solid var(--line);background:var(--bg);color:var(--fg);border-radius:8px;padding:5px 10px;font-size:13px;outline:none}#lxjv-bar .n{color:var(--muted);font-size:12px;white-space:nowrap}\
#lxjv-tree{padding:8px 12px 8px 4px}#lxjv-raw{display:none;padding:8px 12px;white-space:pre;overflow:auto}#lxjv-raw.wrap{white-space:pre-wrap}\
.n{padding-left:18px;position:relative}.n>.row{display:flex;align-items:flex-start;min-height:22px}.tg{position:absolute;left:0;top:0;width:18px;height:22px;display:flex;align-items:center;justify-content:center;color:var(--muted);cursor:pointer;user-select:none;font-size:10px}\
.k{color:var(--key)}.k:after{content:": ";color:var(--muted)}.s{color:var(--str)}.s a{color:inherit;text-decoration:underline dotted}.num{color:var(--num)}.b{color:var(--bool)}.nul{color:var(--null);font-style:italic}\
.br{color:var(--muted)}.cnt{color:var(--muted);font-size:11px;margin-left:4px;cursor:pointer}.col>.ch{display:none}.col>.row .cnt{display:inline}.n:not(.col)>.row .cnt{display:none}\
.hit{background:var(--hl);border-radius:3px}.row:active{background:var(--bar)}\
#lxjv-path{position:fixed;left:0;right:0;bottom:0;background:var(--bar);border-top:1px solid var(--line);padding:6px 10px;font-size:12px;color:var(--muted);display:none;align-items:center;gap:8px;z-index:9}#lxjv-path span{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--fg)}#lxjv-path button{border:none;background:var(--acc);color:#fff;border-radius:6px;padding:4px 10px}\
';
var st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
if (dark) document.documentElement.classList.add('lxjv-dark');
document.body.innerHTML = '';
document.body.style.padding = '0';

var bar = document.createElement('div'); bar.id = 'lxjv-bar';
bar.innerHTML = '<button id="lxjv-t" class="on">树</button><button id="lxjv-r">原文</button><button id="lxjv-exp">展开</button><button id="lxjv-colp">折叠</button><input id="lxjv-q" placeholder="搜索键 / 值" autocomplete="off"><span class="n" id="lxjv-n"></span><button id="lxjv-copy">复制</button>';
document.body.appendChild(bar);
var tree = document.createElement('div'); tree.id = 'lxjv-tree'; document.body.appendChild(tree);
var rawEl = document.createElement('pre'); rawEl.id = 'lxjv-raw'; if (cfg.wrap) rawEl.className = 'wrap'; document.body.appendChild(rawEl);
var pathBar = document.createElement('div'); pathBar.id = 'lxjv-path'; pathBar.innerHTML = '<span></span><button>复制路径</button><button id="lxjv-cv">复制值</button>'; document.body.appendChild(pathBar);

function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
function isUrl(s) { return /^https?:\/\/\S+$/.test(s) && s.length < 2048; }
var count = 0;
function render(v, key, depth, path) {
  count++;
  var isArr = Array.isArray(v), isObj = v !== null && typeof v === 'object';
  var n = document.createElement('div'); n.className = 'n'; n.dataset.path = path;
  var row = document.createElement('div'); row.className = 'row';
  var kh = key === undefined ? '' : '<span class="k">' + esc(key) + '</span>';
  if (isObj) {
    var keys = isArr ? null : Object.keys(v);
    var len = isArr ? v.length : keys.length;
    var open = isArr ? '[' : '{', close = isArr ? ']' : '}';
    row.innerHTML = '<span class="tg">▼</span>' + kh + '<span class="br">' + open + '</span><span class="cnt">… ' + len + ' 项 ' + close + '</span>';
    n.appendChild(row);
    var ch = document.createElement('div'); ch.className = 'ch';
    if (len === 0) { row.querySelector('.br').textContent = open + close; row.querySelector('.tg').style.visibility = 'hidden'; }
    else {
      var lazy = len > 200;
      var build = function () {
        for (var i = 0; i < len; i++) {
          var k = isArr ? i : keys[i];
          ch.appendChild(render(v[k], isArr ? undefined : k, depth + 1, path + (isArr ? '[' + i + ']' : (/^[A-Za-z_$][\w$]*$/.test(k) ? '.' + k : '[' + JSON.stringify(k) + ']'))));
        }
        var end = document.createElement('div'); end.className = 'br'; end.style.paddingLeft = '18px'; end.textContent = close; ch.appendChild(end);
        ch.dataset.built = '1';
      };
      if (!lazy) build(); else n.__build = build;
      if (depth >= cfg.collapse_depth || lazy) n.classList.add('col');
    }
    n.appendChild(ch);
  } else {
    var vh;
    if (typeof v === 'string') vh = '<span class="s">"' + (isUrl(v) ? '<a href="' + esc(v) + '">' + esc(v) + '</a>' : esc(v)) + '"</span>';
    else if (typeof v === 'number') vh = '<span class="num">' + v + '</span>';
    else if (typeof v === 'boolean') vh = '<span class="b">' + v + '</span>';
    else vh = '<span class="nul">null</span>';
    row.innerHTML = '<span class="tg" style="visibility:hidden"></span>' + kh + vh;
    n.appendChild(row);
  }
  return n;
}
function get(path) {
  var v = data;
  var re = /\.([^.\[]+)|\[(\d+)\]|\["((?:[^"\\]|\\.)*)"\]/g, m;
  while ((m = re.exec(path))) { var k = m[1] !== undefined ? m[1] : m[2] !== undefined ? +m[2] : JSON.parse('"' + m[3] + '"'); if (v == null) return undefined; v = v[k]; }
  return v;
}
tree.appendChild(render(data, undefined, 0, '$'));
rawEl.textContent = JSON.stringify(data, null, cfg.indent);
document.getElementById('lxjv-n').textContent = count + ' 个节点 · ' + (trimmed.length > 1024 ? (trimmed.length / 1024).toFixed(1) + ' KB' : trimmed.length + ' B');

function ensureBuilt(n) { if (n.__build && !n.querySelector(':scope > .ch').dataset.built) { n.__build(); n.__build = null; } }
tree.addEventListener('click', function (e) {
  var tg = e.target.closest('.tg, .cnt');
  if (tg) { var n = tg.closest('.n'); ensureBuilt(n); n.classList.toggle('col'); return; }
  if (e.target.tagName === 'A') return;
  var row = e.target.closest('.row'); if (!row) return;
  var n = row.parentElement;
  pathBar.style.display = 'flex'; pathBar.querySelector('span').textContent = n.dataset.path; pathBar.dataset.path = n.dataset.path;
});
function setAll(collapsed, root) {
  root.querySelectorAll('.n').forEach(function (n) { if (!n.querySelector(':scope > .ch')) return; if (!collapsed) ensureBuilt(n); n.classList.toggle('col', collapsed); });
}
document.getElementById('lxjv-exp').onclick = function () { setAll(false, tree); };
document.getElementById('lxjv-colp').onclick = function () { setAll(true, tree); tree.firstElementChild.classList.remove('col'); };
document.getElementById('lxjv-t').onclick = function () { tree.style.display = ''; rawEl.style.display = 'none'; this.classList.add('on'); document.getElementById('lxjv-r').classList.remove('on'); };
document.getElementById('lxjv-r').onclick = function () { tree.style.display = 'none'; rawEl.style.display = 'block'; this.classList.add('on'); document.getElementById('lxjv-t').classList.remove('on'); };
document.getElementById('lxjv-copy').onclick = function () { copy(rawEl.textContent, '已复制格式化 JSON'); };
pathBar.querySelector('button').onclick = function () { copy(pathBar.dataset.path, '已复制路径'); };
document.getElementById('lxjv-cv').onclick = function () { var v = get(pathBar.dataset.path); copy(typeof v === 'string' ? v : JSON.stringify(v, null, cfg.indent), '已复制值'); };
function copy(t, msg) { (navigator.clipboard ? navigator.clipboard.writeText(t) : Promise.reject()).catch(function () { var ta = document.createElement('textarea'); ta.value = t; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove(); }).then(function () { toast(msg); }); }
function toast(m) { var t = document.createElement('div'); t.textContent = m; t.style.cssText = 'position:fixed;left:50%;bottom:60px;transform:translateX(-50%);background:rgba(0,0,0,.8);color:#fff;padding:8px 14px;border-radius:10px;font-size:13px;z-index:99;font-family:sans-serif'; document.body.appendChild(t); setTimeout(function () { t.remove(); }, 1500); }

var qTimer;
document.getElementById('lxjv-q').addEventListener('input', function () {
  clearTimeout(qTimer); var q = this.value.trim().toLowerCase();
  qTimer = setTimeout(function () {
    tree.querySelectorAll('.hit').forEach(function (e) { e.classList.remove('hit'); });
    if (!q) return;
    setAll(false, tree);
    var hits = 0, first = null;
    tree.querySelectorAll('.row').forEach(function (row) {
      var k = row.querySelector('.k'), v = row.querySelector('.s,.num,.b,.nul');
      var t = ((k ? k.textContent : '') + ' ' + (v ? v.textContent : '')).toLowerCase();
      if (t.indexOf(q) >= 0) { row.classList.add('hit'); hits++; if (!first) first = row; }
    });
    document.getElementById('lxjv-n').textContent = hits + ' 个匹配';
    if (first) first.scrollIntoView({ block: 'center' });
  }, 200);
});
})();
]==]
