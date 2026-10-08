-- video/runtime · 注入页面的视频增强运行时（JS 源码）
--
-- 由 video/web.lua 在 document-loaded 后注入（每页一次）。页面里出现 <video> 时：
--   * 浮动小工具条：倍速 − / + / 点数值循环、画中画、音量增强、循环、快照
--   * 手势：双击左右 1/3 快退/快进 10s；长按 2 倍速（松开还原）
--   * 键盘（桌面模式 / 外接键盘）：S/D 减加速，R 复位，P 画中画，[ ] 快退快进
--   * 倍速记忆：本站上次用的倍速自动套用；网站改回去了会再改回来（只在用户主动设置过之后）
--   * 音量增强：WebAudio GainNode，最高 600%（跨域且没有 CORS 的视频会静音，运行时会先探测）
-- 与 Lua 的桥：window.__lx_video_bridge(JSON) → 由 web.lua 暴露，用来保存本站倍速。
-- 配置从 window.__lxvid_cfg 读。
return [==[
(function(){
if (window.__lxvid) return;
var cfg = Object.assign({ default_speed: 1, remember: true, site_speed: null, toolbar: true, gestures: true, keys: true, boost_max: 6, step: 0.25, autohide: 3000 }, window.__lxvid_cfg || {});
var STEPS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3];
var state = { speed: cfg.site_speed || cfg.default_speed || 1, pinned: !!cfg.site_speed, boost: 1, loop: false, videos: new Set(), active: null, ui: null, hideTimer: null, ctx: null, nodes: new Map() };
window.__lxvid = state;
function bridge(o) { try { if (window.__lx_video_bridge) window.__lx_video_bridge(JSON.stringify(o)); } catch (e) {} }
function fmt(r) { return (Math.round(r * 100) / 100) + 'x'; }
function toast(m) { var t = document.createElement('div'); t.textContent = m; t.style.cssText = 'position:fixed;left:50%;top:14%;transform:translateX(-50%);background:rgba(0,0,0,.75);color:#fff;padding:8px 16px;border-radius:20px;font:15px/1.2 -apple-system,Roboto,sans-serif;z-index:2147483647;pointer-events:none;transition:opacity .3s'; (document.fullscreenElement || document.body).appendChild(t); setTimeout(function () { t.style.opacity = '0'; }, 800); setTimeout(function () { t.remove(); }, 1200); }

// ---- 倍速 ----
function applySpeed(v) { if (!v) return; if (Math.abs(v.playbackRate - state.speed) > 0.001) { v.__lxvid_setting = true; v.playbackRate = state.speed; v.__lxvid_setting = false; } }
function setSpeed(r, silent) {
  r = Math.max(0.1, Math.min(16, Math.round(r * 100) / 100));
  state.speed = r; state.pinned = true;
  state.videos.forEach(applySpeed);
  if (!silent) toast(fmt(r));
  if (cfg.remember) bridge({ type: 'speed', speed: r });
  updateUi();
}
function onRate(e) {
  var v = e.target; if (v.__lxvid_setting) return;
  if (state.pinned && Math.abs(v.playbackRate - state.speed) > 0.001) {
    // 网站自己改了（比如 YouTube 换视频复位）：若是用户在原生菜单里改的，我们认它；否则改回来
    if (v.__lxvid_userTouch && Date.now() - v.__lxvid_userTouch < 1500) { state.speed = v.playbackRate; bridge({ type: 'speed', speed: state.speed }); updateUi(); }
    else applySpeed(v);
  }
}
// ---- 音量增强 ----
function canBoost(v) {
  var src = v.currentSrc || v.src || '';
  if (!src || src.indexOf('blob:') === 0 || src.indexOf('data:') === 0) return true;   // MSE / blob 同源
  try { var u = new URL(src, location.href); if (u.origin === location.origin) return true; } catch (e) { return false; }
  return v.crossOrigin === 'anonymous' || v.crossOrigin === 'use-credentials';
}
function setBoost(g) {
  var v = state.active; if (!v) return;
  g = Math.max(1, Math.min(cfg.boost_max, g));
  if (g > 1 && !state.nodes.has(v)) {
    if (!canBoost(v)) { toast('这个视频跨域且无 CORS，无法增强音量'); return; }
    try {
      state.ctx = state.ctx || new (window.AudioContext || window.webkitAudioContext)();
      var srcNode = state.ctx.createMediaElementSource(v), gain = state.ctx.createGain();
      srcNode.connect(gain); gain.connect(state.ctx.destination);
      state.nodes.set(v, gain);
    } catch (e) { toast('无法增强：' + e.message); return; }
  }
  state.boost = g;
  var node = state.nodes.get(v); if (node) node.gain.value = g;
  if (state.ctx && state.ctx.state === 'suspended') state.ctx.resume();
  toast('音量 ' + Math.round(g * 100) + '%');
  updateUi();
}
// ---- 画中画 ----
function pip() {
  var v = state.active; if (!v) return;
  if (document.pictureInPictureElement) { document.exitPictureInPicture(); return; }
  if (!document.pictureInPictureEnabled || v.disablePictureInPicture) { toast('此页不支持画中画'); return; }
  v.requestPictureInPicture().catch(function (e) { toast('画中画失败：' + e.message); });
}
function snapshot() {
  var v = state.active; if (!v) return;
  try {
    var c = document.createElement('canvas'); c.width = v.videoWidth; c.height = v.videoHeight;
    c.getContext('2d').drawImage(v, 0, 0);
    var a = document.createElement('a'); a.href = c.toDataURL('image/png'); a.download = (document.title || 'frame').replace(/[\\/:*?"<>|]/g, '_') + '_' + Math.floor(v.currentTime) + 's.png'; a.click();
    toast('已保存当前帧');
  } catch (e) { toast('跨域视频无法截帧'); }
}
// ---- 工具条 ----
var css = '#lxvid{position:fixed;right:10px;bottom:calc(env(safe-area-inset-bottom) + 72px);z-index:2147483646;display:flex;align-items:center;gap:2px;background:rgba(20,20,22,.82);backdrop-filter:blur(12px);border-radius:22px;padding:3px 6px;color:#fff;font:13px/1 -apple-system,Roboto,sans-serif;box-shadow:0 6px 24px rgba(0,0,0,.35);transition:opacity .25s,transform .25s;user-select:none;-webkit-user-select:none}\
#lxvid.hid{opacity:0;transform:translateX(70%);pointer-events:none}#lxvid.mini>*:not(.h){display:none}\
#lxvid button{appearance:none;border:none;background:none;color:#fff;font:inherit;min-width:34px;height:34px;border-radius:17px;padding:0 6px;display:flex;align-items:center;justify-content:center}#lxvid button:active{background:rgba(255,255,255,.18)}\
#lxvid .sp{font-weight:600;min-width:46px;font-variant-numeric:tabular-nums}#lxvid .on{color:#4cd964}#lxvid .h{font-size:16px}';
function ensureUi() {
  if (state.ui || !cfg.toolbar) return;
  var st = document.createElement('style'); st.textContent = css; document.documentElement.appendChild(st);
  var ui = document.createElement('div'); ui.id = 'lxvid';
  ui.innerHTML = '<button class="h" data-a="mini" title="收起">▶</button><button data-a="sd">−</button><button class="sp" data-a="cycle"></button><button data-a="su">+</button><button data-a="boost" title="音量增强">🔊</button><button data-a="pip" title="画中画">⧉</button><button data-a="loop" title="循环">↻</button><button data-a="snap" title="截帧">📷</button>';
  ui.addEventListener('click', function (e) {
    var b = e.target.closest('button'); if (!b) return; e.stopPropagation(); e.preventDefault();
    var a = b.dataset.a;
    if (a === 'sd') setSpeed(state.speed - cfg.step); else if (a === 'su') setSpeed(state.speed + cfg.step);
    else if (a === 'cycle') { var i = STEPS.findIndex(function (s) { return s > state.speed + 0.001; }); setSpeed(i < 0 ? STEPS[0] : STEPS[i]); }
    else if (a === 'boost') { var g = state.boost >= cfg.boost_max ? 1 : Math.min(cfg.boost_max, state.boost + 1); setBoost(g === 1 && state.boost === 1 ? 2 : g); }
    else if (a === 'pip') pip(); else if (a === 'snap') snapshot();
    else if (a === 'loop') { state.loop = !state.loop; if (state.active) state.active.loop = state.loop; toast(state.loop ? '循环开' : '循环关'); updateUi(); }
    else if (a === 'mini') { ui.classList.toggle('mini'); b.textContent = ui.classList.contains('mini') ? '◀' : '▶'; }
    poke();
  });
  ui.addEventListener('contextmenu', function (e) { e.preventDefault(); });
  var lp; ui.querySelector('.sp').addEventListener('touchstart', function () { lp = setTimeout(function () { setSpeed(1); }, 600); }, { passive: true });
  ui.querySelector('.sp').addEventListener('touchend', function () { clearTimeout(lp); });
  (document.fullscreenElement || document.body).appendChild(ui);
  state.ui = ui;
  updateUi();
}
function updateUi() {
  var ui = state.ui; if (!ui) return;
  ui.querySelector('.sp').textContent = fmt(state.speed);
  ui.querySelector('[data-a=boost]').className = state.boost > 1 ? 'on' : ''; ui.querySelector('[data-a=boost]').textContent = state.boost > 1 ? Math.round(state.boost * 100) + '%' : '🔊';
  ui.querySelector('[data-a=loop]').className = state.loop ? 'on' : '';
  ui.querySelector('[data-a=pip]').className = document.pictureInPictureElement ? 'on' : '';
}
function poke() { if (!state.ui) return; state.ui.classList.remove('hid'); clearTimeout(state.hideTimer); if (cfg.autohide > 0) state.hideTimer = setTimeout(function () { if (state.ui && !state.ui.classList.contains('mini')) state.ui.classList.add('hid'); }, cfg.autohide); }
function showUi() { ensureUi(); poke(); }

// ---- 手势 ----
var lastTap = 0, lastX = 0, holdTimer = null, holding = false;
function onTouchStart(e) {
  var v = e.currentTarget; state.active = v; poke();
  if (!cfg.gestures || e.touches.length !== 1) return;
  var x = e.touches[0].clientX, now = Date.now();
  var r = v.getBoundingClientRect(), rel = (x - r.left) / r.width;
  if (now - lastTap < 300 && Math.abs(x - lastX) < 60) {
    if (rel < 0.33) { v.currentTime = Math.max(0, v.currentTime - 10); toast('⏪ 10s'); }
    else if (rel > 0.67) { v.currentTime = Math.min(v.duration || 1e9, v.currentTime + 10); toast('10s ⏩'); }
    lastTap = 0;
  } else lastTap = now;
  lastX = x;
  clearTimeout(holdTimer);
  holdTimer = setTimeout(function () { if (v.paused) return; holding = true; v.__lxvid_setting = true; v.__lxvid_hold = v.playbackRate; v.playbackRate = Math.max(2, state.speed * 2); v.__lxvid_setting = false; toast(fmt(v.playbackRate) + ' 长按加速'); }, 500);
}
function onTouchEnd(e) {
  clearTimeout(holdTimer);
  var v = e.currentTarget;
  if (holding) { holding = false; v.__lxvid_setting = true; v.playbackRate = v.__lxvid_hold || state.speed; v.__lxvid_setting = false; }
  v.__lxvid_userTouch = Date.now();
}
function onKey(e) {
  if (!cfg.keys || !state.active) return;
  var t = e.target; if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
  if (e.ctrlKey || e.metaKey || e.altKey) return;
  var k = e.key.toLowerCase();
  if (k === 's') setSpeed(state.speed - cfg.step); else if (k === 'd') setSpeed(state.speed + cfg.step); else if (k === 'r') setSpeed(1);
  else if (k === 'p') pip(); else if (k === '[') { state.active.currentTime -= 10; } else if (k === ']') { state.active.currentTime += 10; }
  else return;
  e.preventDefault(); e.stopPropagation();
}

// ---- 发现视频 ----
function attach(v) {
  if (state.videos.has(v)) return;
  state.videos.add(v);
  if (!state.active) state.active = v;
  v.addEventListener('ratechange', onRate);
  v.addEventListener('play', function () { state.active = v; if (state.pinned) applySpeed(v); if (state.loop) v.loop = true; showUi(); });
  v.addEventListener('touchstart', onTouchStart, { passive: true });
  v.addEventListener('touchend', onTouchEnd, { passive: true });
  v.addEventListener('enterpictureinpicture', updateUi); v.addEventListener('leavepictureinpicture', updateUi);
  if (state.pinned) applySpeed(v);
  if (!v.paused || v.autoplay) showUi();
}
function scan(root) { (root.querySelectorAll ? root.querySelectorAll('video') : []).forEach(attach); if (root.tagName === 'VIDEO') attach(root); }
scan(document);
new MutationObserver(function (ms) { ms.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) scan(n); }); }); }).observe(document.documentElement, { childList: true, subtree: true });
document.addEventListener('keydown', onKey, true);
document.addEventListener('fullscreenchange', function () { if (state.ui) { (document.fullscreenElement || document.body).appendChild(state.ui); poke(); } });
document.addEventListener('touchstart', function (e) { if (state.ui && !state.ui.contains(e.target) && state.videos.size) poke(); }, { passive: true });

// 由 Lua 调用
window.__lxvid_cmd = function (cmd, arg) {
  if (cmd === 'speed') setSpeed(+arg); else if (cmd === 'pip') pip(); else if (cmd === 'boost') setBoost(+arg);
  else if (cmd === 'toolbar') { cfg.toolbar = !!arg; if (!arg && state.ui) { state.ui.remove(); state.ui = null; } else if (state.videos.size) showUi(); }
  else if (cmd === 'state') return JSON.stringify({ videos: state.videos.size, speed: state.speed, boost: state.boost, pip: !!document.pictureInPictureElement, playing: state.active ? !state.active.paused : false });
  return true;
};
})();
]==]
