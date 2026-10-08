-- octotree/runtime · 页面内 JS（作为 Lua 长字串返回，由 octotree/web.lua 注入 github.com）
--
-- 行为：
--   * 识别 /owner/repo[/tree|blob/<ref>/<path>] 页面，其他页面（settings、marketplace…）不显示
--   * 左侧固定侧栏：仓库文件树（懒加载展开、过滤框、当前文件高亮并自动展开到它）
--   * 树数据来自 window.__lx_octotree(JSON)（浏览器进程带 token 请求 GitHub API，见 octotree.lua）
--   * GitHub 是 Turbo SPA：拦 pushState / popstate / turbo:load，切仓库或切文件时增量刷新
--   * 手机竖屏：侧栏浮层覆盖；平板/横屏：把 body 往右挤 width 像素（跟 Octotree 一样）
--   * 面板状态（开/关）按仓库记在 localStorage
return [==[
(function(){
if (window.__lxot) return; 
var cfg = window.__lxot_cfg || {};
var WIDTH = cfg.width || 280;
var RESERVED = {settings:1,marketplace:1,explore:1,topics:1,trending:1,collections:1,events:1,sponsors:1,notifications:1,issues:1,pulls:1,login:1,join:1,orgs:1,organizations:1,new:1,search:1,about:1,features:1,pricing:1,codespaces:1,apps:1,dashboard:1,account:1,sessions:1,site:1,security:1,enterprise:1,team:1,customer_stories:1,readme:1,users:1,stars:1};
var TAB_PATHS = {issues:1,pulls:1,actions:1,projects:1,wiki:1,security:1,pulse:1,graphs:1,settings:1,discussions:1,releases:1,tags:1,branches:1,commits:1,compare:1,network:1,stargazers:1,watchers:1,forks:1,packages:1,labels:1,milestones:1,activity:1};

function parseRepo(){
  var p = location.pathname.split('/').filter(Boolean);
  if (p.length < 2 || RESERVED[p[0]]) return null;
  var r = {owner:p[0], repo:p[1], ref:null, path:'', kind:'root'};
  if (p.length >= 3) {
    if (TAB_PATHS[p[2]] && p[2] !== 'tree' && p[2] !== 'blob') { r.kind = 'tab'; return r; }
    if (p[2] === 'tree' || p[2] === 'blob') {
      r.kind = p[2];
      if (p.length >= 4) {
        // ref 可能含 "/"（release/1.0），GitHub 页面里 branch 选择器有权威值
        var sel = document.querySelector('[data-hotkey="w"] span.css-truncate-target, #branch-picker-repos-header-ref-selector .ref-selector-button-text-container, button[id^="branch-picker"] span');
        var refText = sel && sel.textContent.trim();
        var rest = p.slice(3);
        if (refText && rest.join('/').indexOf(refText + '/') === 0) { r.ref = refText; r.path = rest.join('/').slice(refText.length + 1); }
        else if (refText && rest.join('/') === refText) { r.ref = refText; r.path = ''; }
        else { r.ref = rest[0]; r.path = rest.slice(1).join('/'); }
      }
    }
  }
  return r;
}

var state = {key:null, tree:null, filter:'', open:{}, loading:false, error:null, ref:null, repo:null};
var els = {};

function css(){
  var s = document.createElement('style'); s.id = '__lxot_css';
  s.textContent = 
  '#__lxot{position:fixed;top:0;left:0;bottom:0;width:'+WIDTH+'px;max-width:92vw;z-index:2147483000;background:var(--bgColor-default,#fff);color:var(--fgColor-default,#1f2328);border-right:1px solid var(--borderColor-default,#d0d7de);box-shadow:2px 0 12px rgba(0,0,0,.12);display:flex;flex-direction:column;font:13px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif;transform:translateX(-100%);transition:transform .18s ease}'+
  '#__lxot.on{transform:none}'+
  '#__lxot_tg{position:fixed;left:0;top:50%;margin-top:-22px;z-index:2147483001;width:22px;height:44px;border-radius:0 8px 8px 0;background:#24292f;color:#fff;border:0;cursor:pointer;font-size:14px;opacity:.85;padding:0;display:flex;align-items:center;justify-content:center}'+
  'body.__lxot_push{margin-left:'+WIDTH+'px !important;transition:margin-left .18s}'+
  '#__lxot .hd{display:flex;align-items:center;gap:6px;padding:8px 10px;border-bottom:1px solid var(--borderColor-default,#d0d7de)}'+
  '#__lxot .hd .name{flex:1;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'+
  '#__lxot .hd .ref{font-size:11px;color:var(--fgColor-muted,#656d76);max-width:90px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;background:var(--bgColor-muted,#f6f8fa);padding:1px 6px;border-radius:10px}'+
  '#__lxot .hd button{border:0;background:transparent;color:inherit;font-size:16px;cursor:pointer;padding:2px 4px}'+
  '#__lxot .ft{padding:6px 10px;border-bottom:1px solid var(--borderColor-default,#d0d7de)}'+
  '#__lxot .ft input{width:100%;box-sizing:border-box;padding:5px 8px;border:1px solid var(--borderColor-default,#d0d7de);border-radius:6px;background:var(--bgColor-muted,#f6f8fa);color:inherit;font-size:13px}'+
  '#__lxot .tree{flex:1;overflow:auto;padding:4px 0 40px;-webkit-overflow-scrolling:touch}'+
  '#__lxot .n{display:flex;align-items:center;gap:4px;padding:4px 8px 4px 0;cursor:pointer;white-space:nowrap;user-select:none;min-height:26px}'+
  '#__lxot .n:hover{background:var(--bgColor-muted,#f6f8fa)}'+
  '#__lxot .n.cur{background:var(--bgColor-accent-muted,#ddf4ff);font-weight:600}'+
  '#__lxot .n .ar{width:14px;text-align:center;color:var(--fgColor-muted,#656d76);font-size:10px;flex:none}'+
  '#__lxot .n .ic{flex:none;width:16px;text-align:center}'+
  '#__lxot .n .t{overflow:hidden;text-overflow:ellipsis}'+
  '#__lxot .n .sz{margin-left:auto;font-size:10px;color:var(--fgColor-muted,#656d76);padding-left:6px}'+
  '#__lxot .msg{padding:14px 12px;color:var(--fgColor-muted,#656d76);font-size:12px;line-height:1.6}'+
  '#__lxot .msg a{color:var(--fgColor-accent,#0969da)}'+
  '@media (max-width:767px){body.__lxot_push{margin-left:0 !important}}';
  document.documentElement.appendChild(s);
}

function human(n){ if(!n&&n!==0)return ''; if(n<1024)return n+'B'; if(n<1048576)return (n/1024).toFixed(0)+'K'; return (n/1048576).toFixed(1)+'M'; }
function icon(name, dir){ if(dir) return '📁'; var e=(name.split('.').pop()||'').toLowerCase(); var m={js:'🟨',ts:'🟦',tsx:'🟦',jsx:'🟨',json:'📋',md:'📝',lua:'🌙',py:'🐍',go:'🐹',rs:'🦀',java:'☕',kt:'🟪',c:'🔧',h:'🔧',cc:'🔧',cpp:'🔧',html:'🌐',css:'🎨',scss:'🎨',png:'🖼',jpg:'🖼',svg:'🖼',gif:'🖼',yml:'⚙️',yaml:'⚙️',toml:'⚙️',lock:'🔒',sh:'💲',gn:'⚙️',gni:'⚙️',txt:'📄'}; return m[e]||'📄'; }

function buildTree(list){
  var root = {name:'', dir:true, children:{}, order:[]};
  for (var i=0;i<list.length;i++){
    var it = list[i], parts = it.path.split('/'), node = root;
    for (var j=0;j<parts.length;j++){
      var name = parts[j], last = j===parts.length-1;
      if (!node.children[name]) { node.children[name] = {name:name, dir:!last || it.type==='tree', children:{}, order:[], path:parts.slice(0,j+1).join('/'), size:last?it.size:undefined}; node.order.push(name); }
      node = node.children[name];
    }
  }
  return root;
}
function sortOrder(node){
  node.order.sort(function(a,b){ var A=node.children[a],B=node.children[b]; if(A.dir!==B.dir) return A.dir?-1:1; return a.localeCompare(b, undefined, {sensitivity:'base', numeric:true}); });
  for (var k in node.children) if (node.children[k].dir) sortOrder(node.children[k]);
}

function fileUrl(node){
  var r = state.repo; return '/'+r.owner+'/'+r.repo+'/'+(node.dir?'tree':'blob')+'/'+encodeURI(state.ref)+'/'+node.path.split('/').map(encodeURIComponent).join('/');
}

function render(){
  var box = els.tree; if (!box) return;
  box.innerHTML = '';
  if (state.loading) { box.innerHTML = '<div class="msg">加载文件树…</div>'; return; }
  if (state.error) { box.innerHTML = '<div class="msg">'+state.error+'</div>'; return; }
  if (!state.tree) return;
  var frag = document.createDocumentFragment();
  var f = state.filter.toLowerCase();
  var cur = state.repo && state.repo.path || '';
  function walk(node, depth){
    for (var i=0;i<node.order.length;i++){
      var c = node.children[node.order[i]];
      var match = !f || c.path.toLowerCase().indexOf(f) >= 0;
      var showChildren = c.dir && (f ? true : state.open[c.path]);
      if (f && c.dir) { // 过滤时只显示含匹配子项的目录
        if (!subtreeMatches(c, f)) continue;
      } else if (!match && !c.dir) continue;
      var row = document.createElement('div');
      row.className = 'n' + (cur === c.path ? ' cur' : '');
      row.style.paddingLeft = (8 + depth*14) + 'px';
      row.innerHTML = '<span class="ar">'+(c.dir ? (showChildren?'▾':'▸') : '')+'</span><span class="ic">'+icon(c.name,c.dir)+'</span><span class="t">'+esc(c.name)+'</span>'+(c.dir?'':'<span class="sz">'+human(c.size)+'</span>');
      row.setAttribute('data-p', c.path);
      (function(c){
        row.addEventListener('click', function(ev){
          ev.preventDefault();
          if (c.dir) { state.open[c.path] = !state.open[c.path]; render(); }
          else { go(fileUrl(c)); if (window.innerWidth < 768) setOpen(false); }
        });
        if (c.dir) row.addEventListener('contextmenu', function(ev){ ev.preventDefault(); go(fileUrl(c)); });
      })(c);
      frag.appendChild(row);
      if (showChildren) walk(c, depth+1);
    }
  }
  walk(state.tree, 0);
  box.appendChild(frag);
  var curEl = box.querySelector('.n.cur'); if (curEl) { try { curEl.scrollIntoView({block:'center'}); } catch(e){} }
}
function subtreeMatches(node, f){
  for (var k in node.children){ var c=node.children[k]; if (c.path.toLowerCase().indexOf(f)>=0) return true; if (c.dir && subtreeMatches(c,f)) return true; }
  return false;
}
function esc(s){ return String(s).replace(/[&<>"]/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]; }); }

function go(url){
  // 走 GitHub 自己的 Turbo 导航（不整页刷新）；找不到就硬跳
  var a = document.createElement('a'); a.href = url; a.style.display='none'; document.body.appendChild(a);
  try { a.click(); } finally { setTimeout(function(){ a.remove(); }, 0); }
  setTimeout(function(){ if (location.pathname !== url.split('?')[0]) location.href = url; }, 800);
}

function expandToCurrent(){
  var p = state.repo && state.repo.path; if (!p) return;
  var parts = p.split('/'); var acc='';
  for (var i=0;i<parts.length-1;i++){ acc = acc ? acc+'/'+parts[i] : parts[i]; state.open[acc] = true; }
  if (state.repo.kind === 'tree') state.open[p] = true;
}

function storeKey(){ return '__lxot:'+state.repo.owner+'/'+state.repo.repo; }
function setOpen(on){
  els.panel.classList.toggle('on', !!on);
  document.body.classList.toggle('__lxot_push', !!on && !!cfg.push);
  els.tg.textContent = on ? '‹' : '›';
  els.tg.style.left = (on && window.innerWidth >= 768) ? (Math.min(WIDTH, window.innerWidth*0.92)) + 'px' : '0';
  try { localStorage.setItem(storeKey(), on ? '1' : '0'); } catch(e){}
}
function isOpen(){ return els.panel.classList.contains('on'); }

function mount(){
  if (els.panel) return;
  css();
  var panel = document.createElement('div'); panel.id='__lxot';
  panel.innerHTML = '<div class="hd"><span class="name"></span><span class="ref"></span><button class="rl" title="刷新">↻</button><button class="cl" title="收起">×</button></div><div class="ft"><input placeholder="过滤文件…" type="search" autocomplete="off"></div><div class="tree"></div>';
  document.documentElement.appendChild(panel);
  var tg = document.createElement('button'); tg.id='__lxot_tg'; tg.textContent='›'; tg.title='文件树';
  document.documentElement.appendChild(tg);
  els.panel = panel; els.tg = tg; els.tree = panel.querySelector('.tree'); els.name = panel.querySelector('.name'); els.ref = panel.querySelector('.ref');
  tg.addEventListener('click', function(){ setOpen(!isOpen()); if (isOpen() && !state.tree && !state.loading) load(true); });
  panel.querySelector('.cl').addEventListener('click', function(){ setOpen(false); });
  panel.querySelector('.rl').addEventListener('click', function(){ load(true, true); });
  var inp = panel.querySelector('input'); var t;
  inp.addEventListener('input', function(){ clearTimeout(t); t = setTimeout(function(){ state.filter = inp.value.trim(); render(); }, 120); });
  window.addEventListener('resize', function(){ if (isOpen()) setOpen(true); });
}
function unmount(){
  if (!els.panel) return;
  els.panel.remove(); els.tg.remove(); var s=document.getElementById('__lxot_css'); if(s) s.remove();
  document.body.classList.remove('__lxot_push'); els = {};
}

function bridge(req){
  return new Promise(function(res, rej){
    try { var r = window.__lx_octotree(JSON.stringify(req)); if (r && r.then) r.then(function(v){ res(typeof v==='string'?JSON.parse(v):v); }, rej); else res(typeof r==='string'?JSON.parse(r):r); }
    catch(e){ rej(e); }
  });
}

function load(force, nocache){
  var r = state.repo; if (!r) return;
  var key = r.owner+'/'+r.repo+'@'+(r.ref||'');
  if (!force && state.key === key && state.tree) { expandToCurrent(); render(); return; }
  state.loading = true; state.error = null; state.key = key; render();
  bridge({type:'tree', owner:r.owner, repo:r.repo, ref:r.ref, host:location.host, nocache:!!nocache}).then(function(d){
    state.loading = false;
    if (!d || d.error) { state.error = esc(d && d.error || '加载失败'); if (d && d.need_token) state.error += '<br><a href="lemurx://octotree/" target="_blank">去设置里填 GitHub Token</a>'; render(); return; }
    state.ref = d.ref || r.ref; r.ref = state.ref;
    var tree = buildTree(d.tree || []); sortOrder(tree); state.tree = tree;
    if (d.truncated) state.truncated = true;
    els.ref.textContent = state.ref || ''; els.ref.title = state.ref || '';
    expandToCurrent(); render();
    if (d.truncated) { var m=document.createElement('div'); m.className='msg'; m.textContent='仓库太大，GitHub 只返回了部分文件'; els.tree.appendChild(m); }
  }, function(e){ state.loading=false; state.error = esc(String(e && e.message || e)); render(); });
}

function sync(){
  var r = parseRepo();
  if (!r || r.kind === 'tab' && !cfg.show_on_tabs) {
    if (r && r.kind === 'tab' && els.panel) { /* issues/pulls 等页保留按钮但收起 */ }
    else { unmount(); state.repo = null; return; }
  }
  var switched = !state.repo || state.repo.owner !== r.owner || state.repo.repo !== r.repo;
  var refChanged = state.repo && r.ref && state.repo.ref && r.ref !== state.repo.ref;
  state.repo = r;
  mount();
  els.name.textContent = r.owner + '/' + r.repo; els.name.title = els.name.textContent;
  if (switched) { state.tree = null; state.key = null; state.open = {}; state.filter=''; var inp = els.panel.querySelector('input'); if (inp) inp.value=''; }
  if (refChanged) { state.tree = null; state.key = null; }
  var want = null; try { want = localStorage.getItem(storeKey()); } catch(e){}
  var open = want === null ? (cfg.auto_open !== false && window.innerWidth >= 768) : want === '1';
  setOpen(open);
  if (open || state.tree) load(false); else { expandToCurrent(); render(); }
}

// SPA 导航钩子
var _ps = history.pushState, _rs = history.replaceState;
history.pushState = function(){ var r=_ps.apply(this, arguments); setTimeout(sync, 50); return r; };
history.replaceState = function(){ var r=_rs.apply(this, arguments); setTimeout(sync, 50); return r; };
window.addEventListener('popstate', function(){ setTimeout(sync, 50); });
document.addEventListener('turbo:load', function(){ setTimeout(sync, 50); });
document.addEventListener('turbo:render', function(){ setTimeout(sync, 50); });
document.addEventListener('pjax:end', function(){ setTimeout(sync, 50); });

window.__lxot = {
  sync: sync,
  toggle: function(){ if (!els.panel) sync(); if (els.panel) { setOpen(!isOpen()); if (isOpen() && !state.tree) load(true); } },
  reload: function(){ load(true, true); },
  off: function(){ unmount(); state.repo = null; },
  config: function(c){ for (var k in c) cfg[k] = c[k]; WIDTH = cfg.width || WIDTH; unmount(); sync(); },
};
if (document.body) sync(); else document.addEventListener('DOMContentLoaded', sync);
})();
]==]
