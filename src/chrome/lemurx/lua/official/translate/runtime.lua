-- translate.runtime —— 页面内翻译运行时（JS 源码，渲染进程 Lua 注入）
--
-- window.__lxtr：
--   configure(cfg)          { target, display: 'bilingual'|'replace', style, fontScale, minLen, exclude }
--   translatePage()         扫段落 → 分批送翻译（IntersectionObserver 先可见后其余；MutationObserver 跟踪新增）
--   restore()               撤掉译文，恢复原文
--   toggle()                在翻译 / 原文之间切换，返回新状态
--   translateSelection()    翻译当前选区并弹卡片
--   enableSelectionPopup(on)划词后出现「译」按钮
--   state()                 { on, done, pending, total }
-- 翻译请求走 window.__lx_tr_req(json{texts:[...]}) → Promise<json{results:[...]} | {error}>
--   （由渲染进程 Lua 暴露，转到浏览器进程调翻译引擎）

return [==[
(function () {
  if (window.__lxtr) return;
  var req = window.__lx_tr_req;
  try { delete window.__lx_tr_req; } catch (e) {}
  var cfg = { target: 'zh-CN', display: 'bilingual', style: 'border', fontScale: 1, minLen: 2, exclude: '' };
  var on = false, io = null, mo = null, queue = [], inflight = 0, total = 0, done = 0, timer = null, popupOn = false;
  var BLOCK = 'p,h1,h2,h3,h4,h5,h6,li,dd,dt,td,th,blockquote,figcaption,caption,summary,label,legend,article,section,div,span,a,font,strong,em,b,i,button,option,pre';
  var SKIP = 'script,style,noscript,textarea,input,select,code,kbd,samp,var,svg,math,canvas,video,audio,iframe,object,embed,[contenteditable],[translate=no],.notranslate,.lx-tr,.lx-tr-wrap,#lx-tr-popup,#lx-tr-card';
  var INLINE = { A: 1, B: 1, STRONG: 1, I: 1, EM: 1, U: 1, S: 1, SPAN: 1, FONT: 1, SUP: 1, SUB: 1, SMALL: 1, MARK: 1, ABBR: 1, TIME: 1, CITE: 1, Q: 1, LABEL: 1, BR: 1, WBR: 1, IMG: 1, CODE: 1, KBD: 1, VAR: 1, SAMP: 1, BDI: 1, BDO: 1, DATA: 1, DFN: 1, INS: 1, DEL: 1, RUBY: 1, RT: 1, RP: 1 };
  var CSS_ID = 'lx-tr-style';

  function css() {
    if (document.getElementById(CSS_ID)) return;
    var s = document.createElement('style'); s.id = CSS_ID;
    var base = '.lx-tr{display:block;margin-top:.3em;font-size:' + cfg.fontScale + 'em;line-height:1.5;white-space:normal;word-break:break-word;color:inherit}' +
      'font.lx-tr{unicode-bidi:normal}' +
      '.lx-tr-inline{display:inline;margin:0 0 0 .3em}' +
      '.lx-tr-border{border-left:2px solid #4285f4;padding-left:.5em;opacity:.95}' +
      '.lx-tr-underline{text-decoration:underline;text-decoration-color:#4285f4;text-underline-offset:3px}' +
      '.lx-tr-dashed{text-decoration:underline dashed;text-decoration-color:#4285f4;text-underline-offset:3px}' +
      '.lx-tr-quote{border-left:3px solid #ccc;padding-left:.5em;color:#666;font-style:italic}' +
      '.lx-tr-bg{background:rgba(66,133,244,.08);padding:.1em .3em;border-radius:4px}' +
      '.lx-tr-plain{}' +
      '.lx-tr-loading{opacity:.5;font-size:.85em;font-style:italic}' +
      '.lx-tr-err{color:#d93025;font-size:.85em}' +
      '#lx-tr-popup{position:fixed;z-index:2147483646;background:#1a73e8;color:#fff;border-radius:16px;padding:6px 12px;font:14px/1 -apple-system,Roboto,sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.3);cursor:pointer;user-select:none}' +
      '#lx-tr-card{position:fixed;left:8px;right:8px;bottom:16px;z-index:2147483647;background:#fff;color:#202124;border-radius:14px;padding:14px 16px;font:15px/1.5 -apple-system,Roboto,"PingFang SC",sans-serif;box-shadow:0 4px 24px rgba(0,0,0,.35);max-height:50vh;overflow:auto}' +
      '@media(prefers-color-scheme:dark){#lx-tr-card{background:#202124;color:#e8eaed}}' +
      '#lx-tr-card .o{color:#5f6368;font-size:13px;margin-bottom:6px;max-height:8em;overflow:auto}#lx-tr-card .t{font-size:16px}' +
      '#lx-tr-card .b{display:flex;gap:8px;margin-top:10px;justify-content:flex-end}#lx-tr-card button{font:13px -apple-system,Roboto,sans-serif;border:0;border-radius:8px;padding:6px 12px;background:#e8f0fe;color:#1a73e8}';
    s.textContent = base;
    (document.head || document.documentElement).appendChild(s);
  }

  function isSkipped(el) {
    for (var e = el; e && e.nodeType === 1; e = e.parentElement) {
      if (e.matches && e.matches(SKIP)) return true;
      if (cfg.exclude && e.matches && (function () { try { return e.matches(cfg.exclude); } catch (x) { return false; } })()) return true;
    }
    return false;
  }
  function visibleText(el) {
    var t = (el.innerText != null ? el.innerText : el.textContent) || '';
    return t.replace(/\s+/g, ' ').trim();
  }
  function hasLetters(s) { return /[A-Za-z\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF\u0590-\u05FF\u0600-\u06FF\u0E00-\u0E7F\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF]/.test(s); }
  // 文本看起来已经是目标语言 → 不用翻。按字符系判断；拉丁语系之间分不清语种，交给引擎（同语种会原样返回）
  function looksLikeTarget(s) {
    var t = cfg.target.toLowerCase();
    var letters = s.replace(/[^A-Za-z\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF]/g, '');
    var n = letters.length; if (!n) return true;
    function cnt(re) { return (letters.match(re) || []).length; }
    var han = cnt(/[\u3400-\u9FFF]/g), kana = cnt(/[\u3040-\u30FF]/g), hangul = cnt(/[\uAC00-\uD7AF]/g), cyr = cnt(/[\u0400-\u04FF]/g), latin = cnt(/[A-Za-z\u00C0-\u024F]/g);
    if (t.indexOf('zh') === 0) return han / n > 0.45 && kana === 0;
    if (t === 'ja') return (kana + han) / n > 0.45 && kana > 0;
    if (t === 'ko') return hangul / n > 0.45;
    if (t === 'ru' || t === 'uk' || t === 'bg' || t === 'sr') return cyr / n > 0.6;
    if (t === 'en') return latin / n > 0.8;
    return false;
  }
  // 是否"叶子块"：自身有文本，且没有块级子元素
  function isLeafBlock(el) {
    if (!el.firstChild) return false;
    var directText = 0;
    for (var c = el.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) { if (c.nodeValue.trim()) directText++; }
      else if (c.nodeType === 1) {
        if (!INLINE[c.tagName]) {
          var d = getComputedStyle(c).display;
          if (d !== 'inline' && d !== 'inline-block' && d !== 'contents') return false;
        }
      }
    }
    return true;
  }
  function insideCollected(el) { for (var e = el.parentElement; e; e = e.parentElement) if (e.__lxtr) return true; return false; }
  function collect(root) {
    var out = [];
    var all = (root.querySelectorAll ? root.querySelectorAll(BLOCK) : []);
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      // 文档序：父块先被收走，其内部的 span/a 就不再单独算
      if (el.__lxtr || insideCollected(el) || isSkipped(el) || !isLeafBlock(el)) continue;
      var text = visibleText(el);
      if (text.length < cfg.minLen || text.length > 5000) continue;
      if (!hasLetters(text) || looksLikeTarget(text)) continue;
      if (!el.offsetParent && getComputedStyle(el).position !== 'fixed') continue; // display:none
      el.__lxtr = { text: text, state: 'new' };
      out.push(el);
    }
    return out;
  }

  function render(el, translated) {
    var st = el.__lxtr; if (!st) return;
    var node;
    if (cfg.display === 'replace') {
      if (!st.orig) st.orig = el.innerHTML;
      el.innerHTML = '';
      node = document.createElement('font'); node.className = 'lx-tr lx-tr-inline lx-tr-' + cfg.style; node.setAttribute('lang', cfg.target); node.textContent = translated;
      el.appendChild(node);
    } else {
      node = document.createElement('font');
      var inlineTag = INLINE[el.tagName] || el.tagName === 'BUTTON' || el.tagName === 'OPTION' || getComputedStyle(el).display.indexOf('inline') === 0;
      node.className = 'lx-tr ' + (inlineTag ? 'lx-tr-inline ' : '') + 'lx-tr-' + cfg.style; node.setAttribute('lang', cfg.target); node.setAttribute('translate', 'no');
      node.textContent = translated;
      if (el.tagName === 'OPTION') { el.textContent = st.text + ' · ' + translated; st.state = 'done'; return; }
      el.appendChild(node);
    }
    st.node = node; st.state = 'done';
  }
  function markLoading(el) {
    if (cfg.display === 'replace') return;
    var n = document.createElement('font'); n.className = 'lx-tr lx-tr-loading'; n.textContent = '…'; n.setAttribute('translate', 'no');
    el.appendChild(n); el.__lxtr.node = n;
  }
  function clearLoading(el) { var st = el.__lxtr; if (st && st.node && st.node.parentNode && st.node.classList.contains('lx-tr-loading')) st.node.parentNode.removeChild(st.node); }

  function flush() {
    timer = null;
    if (!on || !queue.length) return;
    var batch = [], chars = 0;
    while (queue.length && batch.length < 40 && chars < 4500) {
      var el = queue.shift();
      if (!el.__lxtr || el.__lxtr.state !== 'queued') continue;
      batch.push(el); chars += el.__lxtr.text.length;
    }
    if (!batch.length) { if (queue.length) flush(); return; }
    batch.forEach(function (el) { el.__lxtr.state = 'inflight'; markLoading(el); });
    inflight++;
    var texts = batch.map(function (el) { return el.__lxtr.text; });
    var p; try { p = req(JSON.stringify({ texts: texts, target: cfg.target })); } catch (e) { p = Promise.reject(e); }
    Promise.resolve(p).then(function (json) {
      var r = typeof json === 'string' ? JSON.parse(json) : json;
      if (!r || r.error) throw new Error(r && r.error || 'no result');
      batch.forEach(function (el, i) {
        clearLoading(el);
        if (!on || !el.__lxtr) return;
        var t = r.results && r.results[i];
        if (t && t !== el.__lxtr.text) render(el, t); else el.__lxtr.state = 'same';
        done++;
      });
    }).catch(function (e) {
      batch.forEach(function (el) { clearLoading(el); if (el.__lxtr) { el.__lxtr.state = 'error'; if (cfg.display !== 'replace') { var n = document.createElement('font'); n.className = 'lx-tr lx-tr-err'; n.textContent = '翻译失败：' + (e && e.message || e); el.appendChild(n); el.__lxtr.node = n; } } });
    }).then(function () { inflight--; if (queue.length) schedule(); });
    if (queue.length && inflight < 3) schedule();
  }
  function schedule() { if (!timer) timer = setTimeout(flush, 120); }
  function enqueue(el, front) {
    var st = el.__lxtr; if (!st || st.state !== 'new') return;
    st.state = 'queued';
    if (front) queue.unshift(el); else queue.push(el);
    schedule();
  }
  function observe(els) {
    if (!io) {
      io = new IntersectionObserver(function (entries) {
        entries.forEach(function (en) { if (en.isIntersecting) { enqueue(en.target, true); io.unobserve(en.target); } });
      }, { rootMargin: '600px 0px' });
    }
    els.forEach(function (el) { io.observe(el); });
    // 不可见的也要翻，但排在后面（滚到时会被提前）
    setTimeout(function () { els.forEach(function (el) { enqueue(el, false); }); }, 1500);
  }
  function translatePage() {
    css(); on = true;
    var els = collect(document.body || document.documentElement);
    total += els.length;
    observe(els);
    if (!mo) {
      mo = new MutationObserver(function (muts) {
        if (!on) return;
        var added = [];
        muts.forEach(function (m) { for (var i = 0; i < m.addedNodes.length; i++) { var n = m.addedNodes[i]; if (n.nodeType === 1 && !(n.classList && n.classList.contains('lx-tr')) && !isSkipped(n)) added.push(n); } });
        if (!added.length) return;
        clearTimeout(mo._t); mo._pending = (mo._pending || []).concat(added);
        mo._t = setTimeout(function () {
          var list = mo._pending; mo._pending = [];
          var els = [];
          list.forEach(function (n) { if (!document.contains(n)) return; if (n.matches && n.matches(BLOCK)) { var one = collect({ querySelectorAll: function () { return [n]; } }); els = els.concat(one); } els = els.concat(collect(n)); });
          if (els.length) { total += els.length; observe(els); }
        }, 300);
      });
      mo.observe(document.documentElement, { childList: true, subtree: true });
    }
    return total;
  }
  function restore() {
    on = false; queue = [];
    if (mo) { mo.disconnect(); mo = null; }
    if (io) { io.disconnect(); io = null; }
    var nodes = document.querySelectorAll('.lx-tr');
    for (var i = 0; i < nodes.length; i++) if (nodes[i].parentNode) nodes[i].parentNode.removeChild(nodes[i]);
    var all = document.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) { var st = all[j].__lxtr; if (st) { if (st.orig != null) all[j].innerHTML = st.orig; delete all[j].__lxtr; } }
    total = 0; done = 0;
  }

  // ---- 划词 ----
  var popup, card;
  function selText() { var s = window.getSelection && window.getSelection(); return s && String(s).trim() || ''; }
  function hidePopup() { if (popup && popup.parentNode) popup.parentNode.removeChild(popup); popup = null; }
  function hideCard() { if (card && card.parentNode) card.parentNode.removeChild(card); card = null; }
  function showPopup() {
    var t = selText(); if (!t || t.length > 3000 || !hasLetters(t)) { hidePopup(); return; }
    var s = window.getSelection(); var r = s.rangeCount ? s.getRangeAt(0).getBoundingClientRect() : null; if (!r) return;
    css();
    if (!popup) { popup = document.createElement('div'); popup.id = 'lx-tr-popup'; popup.textContent = '译'; popup.addEventListener('mousedown', function (e) { e.preventDefault(); }); popup.addEventListener('touchstart', function (e) { e.preventDefault(); e.stopPropagation(); translateSelection(); }, { passive: false }); popup.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); translateSelection(); }); }
    var top = r.bottom + 8; if (top + 36 > innerHeight) top = r.top - 40;
    popup.style.top = Math.max(4, top) + 'px'; popup.style.left = Math.min(Math.max(4, r.left + r.width / 2 - 20), innerWidth - 60) + 'px';
    (document.body || document.documentElement).appendChild(popup);
  }
  function translateSelection() {
    var t = selText(); hidePopup(); if (!t) return Promise.resolve(null);
    css(); hideCard();
    card = document.createElement('div'); card.id = 'lx-tr-card';
    card.innerHTML = '<div class="o"></div><div class="t">翻译中…</div><div class="b"><button data-a="copy">复制译文</button><button data-a="close">关闭</button></div>';
    card.querySelector('.o').textContent = t.length > 400 ? t.slice(0, 400) + '…' : t;
    card.addEventListener('click', function (e) { var b = e.target.closest('button'); if (!b) return; if (b.dataset.a === 'close') hideCard(); else { var tx = card.querySelector('.t').textContent; try { navigator.clipboard.writeText(tx); } catch (x) {} b.textContent = '已复制'; } });
    (document.body || document.documentElement).appendChild(card);
    var p; try { p = req(JSON.stringify({ texts: [t], target: cfg.target })); } catch (e) { p = Promise.reject(e); }
    return Promise.resolve(p).then(function (json) {
      var r = typeof json === 'string' ? JSON.parse(json) : json;
      if (!card) return null; if (!r || r.error) throw new Error(r && r.error || 'no result');
      card.querySelector('.t').textContent = r.results[0] || '';
      return r.results[0];
    }).catch(function (e) { if (card) card.querySelector('.t').textContent = '翻译失败：' + (e && e.message || e); return null; });
  }
  var selHandler = function () { setTimeout(showPopup, 50); };
  var selClear = function (e) { if (popup && !popup.contains(e.target) && !selText()) hidePopup(); };
  function enableSelectionPopup(v) {
    if (v === popupOn) return; popupOn = v;
    if (v) { document.addEventListener('selectionchange', selHandler); document.addEventListener('touchend', selHandler); document.addEventListener('mouseup', selHandler); document.addEventListener('touchstart', selClear, true); document.addEventListener('mousedown', selClear, true); }
    else { document.removeEventListener('selectionchange', selHandler); document.removeEventListener('touchend', selHandler); document.removeEventListener('mouseup', selHandler); document.removeEventListener('touchstart', selClear, true); document.removeEventListener('mousedown', selClear, true); hidePopup(); }
  }

  var api = {
    configure: function (c) { var wasOn = on; var oldTarget = cfg.target, oldDisplay = cfg.display; Object.assign(cfg, c || {}); var s = document.getElementById(CSS_ID); if (s) s.remove(); if (wasOn && (oldTarget !== cfg.target || oldDisplay !== cfg.display)) { restore(); translatePage(); } else if (wasOn) css(); },
    translatePage: translatePage, restore: restore,
    toggle: function () { if (on) { restore(); return false; } translatePage(); return true; },
    translateSelection: translateSelection, enableSelectionPopup: enableSelectionPopup,
    state: function () { return { on: on, done: done, pending: queue.length, total: total, inflight: inflight }; },
    pageLang: function () { var l = (document.documentElement.lang || '').toLowerCase(); if (l) return l; var t = (document.body && document.body.innerText || '').slice(0, 2000); var han = (t.match(/[\u3400-\u9FFF]/g) || []).length; var kana = (t.match(/[\u3040-\u30FF]/g) || []).length; var hangul = (t.match(/[\uAC00-\uD7AF]/g) || []).length; var latin = (t.match(/[A-Za-z]/g) || []).length; if (kana > 20) return 'ja'; if (hangul > 20) return 'ko'; if (han > latin) return 'zh'; if (/[\u0400-\u04FF]{20}/.test(t)) return 'ru'; return latin > 50 ? 'en' : ''; }
  };
  Object.defineProperty(window, '__lxtr', { value: api, enumerable: false, configurable: false, writable: false });
})();
]==]
