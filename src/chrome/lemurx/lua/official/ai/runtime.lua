-- ai/runtime · 注入页面的 AI 助手运行时（JS 源码）
--
-- 三个入口：
--   * 右下角悬浮球 ✦ → 侧边面板：对页面提问 / 总结本页 / 自由对话（带上下文）
--   * 划词工具条：解释 · 翻译 · 润色 · 改写 · 复制
--   * 写作助手：焦点在输入框 / 可编辑区时出现 ✨ → 润色 / 语法 / 续写 / 更正式 / 更简洁 / 翻成英文，
--     结果可一键替换原文（Grammarly / QuillBot / Wordtune / LanguageTool 的用法）
-- 所有模型请求经 window.__lx_ai(JSON) → 渲染进程 Lua → 浏览器进程（API 密钥不进页面）。
-- 配置从 window.__lxai_cfg 读：{ bubble, selection, writing, lang, position }
return [==[
(function(){
if (window.__lxai) return;
var cfg = Object.assign({ bubble: true, selection: true, writing: true, lang: 'zh-CN', position: 'right' }, window.__lxai_cfg || {});
var S = { panel: null, bubble: null, selbar: null, wbtn: null, editable: null, history: [], busy: false, ctxUsed: false };
window.__lxai = S;
function call(o) { return new Promise(function (res, rej) { if (!window.__lx_ai) return rej(new Error('bridge missing')); Promise.resolve(window.__lx_ai(JSON.stringify(o))).then(function (r) { var j = typeof r === 'string' ? JSON.parse(r) : r; if (j && j.error) rej(new Error(j.error)); else res(j); }, rej); }); }
function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
function md(s) {  // 极简 markdown：代码块 / 行内代码 / 粗体 / 标题 / 列表 / 段落
  var blocks = []; s = String(s || '').replace(/```(\w*)\n([\s\S]*?)```/g, function (_, l, c) { blocks.push('<pre><code>' + esc(c) + '</code></pre>'); return '\u0000' + (blocks.length - 1) + '\u0000'; });
  s = esc(s).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').replace(/^### (.*)$/gm, '<h4>$1</h4>').replace(/^## (.*)$/gm, '<h3>$1</h3>').replace(/^# (.*)$/gm, '<h3>$1</h3>')
    .replace(/^\s*[-*] (.*)$/gm, '<li>$1</li>').replace(/(<li>.*<\/li>\n?)+/g, function (m) { return '<ul>' + m + '</ul>'; }).replace(/^\s*(\d+)\. (.*)$/gm, '<li>$2</li>').replace(/\n{2,}/g, '</p><p>').replace(/\n/g, '<br>');
  return ('<p>' + s + '</p>').replace(/\u0000(\d+)\u0000/g, function (_, i) { return blocks[+i]; });
}
function toast(m) { var t = document.createElement('div'); t.textContent = m; t.style.cssText = 'position:fixed;left:50%;bottom:120px;transform:translateX(-50%);background:rgba(0,0,0,.8);color:#fff;padding:8px 14px;border-radius:10px;font:14px -apple-system,Roboto,sans-serif;z-index:2147483647'; document.body.appendChild(t); setTimeout(function () { t.remove(); }, 1800); }
function copy(t) { (navigator.clipboard ? navigator.clipboard.writeText(t) : Promise.reject()).catch(function () { var ta = document.createElement('textarea'); ta.value = t; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove(); }).then(function () { toast('已复制'); }); }

// ---- 页面正文提取（Readability 的简化版）----
function pageText(max) {
  max = max || 12000;
  var cands = Array.prototype.slice.call(document.querySelectorAll('article, main, [role=main], .post, .article, .content, #content, .entry-content, .markdown-body'));
  var best = null, bestLen = 0;
  cands.forEach(function (el) { var l = (el.innerText || '').length; if (l > bestLen) { best = el; bestLen = l; } });
  if (!best || bestLen < 400) best = document.body;
  var clone = best.cloneNode(true);
  clone.querySelectorAll('script,style,noscript,nav,header,footer,aside,form,iframe,svg,[aria-hidden=true],.lxai-root,#lxtr-popup,.lx-tr').forEach(function (e) { e.remove(); });
  var t = (clone.innerText || clone.textContent || '').replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
  if (t.length > max) t = t.slice(0, max) + '\n…（已截断）';
  return t;
}
function pageInfo() { return { title: document.title, url: location.href, lang: document.documentElement.lang || '' }; }

// ---- 样式 ----
var css = '\
.lxai-root{all:initial;font:14px/1.5 -apple-system,Roboto,"PingFang SC","Noto Sans CJK SC",sans-serif;color:#1d1d1f;position:fixed;z-index:2147483645}\
.lxai-root *{box-sizing:border-box;font-family:inherit}\
#lxai-bubble{right:12px;bottom:calc(env(safe-area-inset-bottom) + 130px);width:46px;height:46px;border-radius:23px;background:linear-gradient(135deg,#7c3aed,#0a84ff);color:#fff;display:flex;align-items:center;justify-content:center;font-size:20px;box-shadow:0 6px 20px rgba(10,132,255,.4);touch-action:none;user-select:none;-webkit-user-select:none}\
#lxai-bubble.l{left:12px;right:auto}\
#lxai-panel{inset:auto 0 0 0;height:72vh;background:#fff;border-radius:18px 18px 0 0;box-shadow:0 -10px 40px rgba(0,0,0,.35);display:flex;flex-direction:column;transform:translateY(105%);transition:transform .28s}\
#lxai-panel.on{transform:none}#lxai-panel.full{height:calc(100vh - env(safe-area-inset-top) - 8px)}\
#lxai-panel header{display:flex;align-items:center;gap:8px;padding:10px 14px;border-bottom:1px solid #e5e5ea}#lxai-panel header b{flex:1;font-size:16px}#lxai-panel header button{border:none;background:#f2f2f7;border-radius:8px;padding:6px 10px;font-size:13px;color:#1d1d1f}\
#lxai-msgs{flex:1;overflow:auto;padding:12px 14px;display:flex;flex-direction:column;gap:10px;-webkit-overflow-scrolling:touch}\
.lxai-m{max-width:88%;padding:9px 12px;border-radius:14px;word-break:break-word;white-space:normal}.lxai-m.u{align-self:flex-end;background:#0a84ff;color:#fff;border-bottom-right-radius:4px}.lxai-m.a{align-self:flex-start;background:#f2f2f7;border-bottom-left-radius:4px}.lxai-m.a.err{background:#ffebe9;color:#b3261e}\
.lxai-m p{margin:0 0 6px}.lxai-m p:last-child{margin:0}.lxai-m pre{background:#1c1c1e;color:#f5f5f7;padding:8px 10px;border-radius:8px;overflow:auto;font-size:12px}.lxai-m code{font-family:ui-monospace,Menlo,monospace;font-size:13px}.lxai-m ul{margin:4px 0;padding-left:18px}.lxai-m h3,.lxai-m h4{margin:6px 0 4px;font-size:15px}\
.lxai-m .act{display:flex;gap:6px;margin-top:6px}.lxai-m .act button{border:none;background:rgba(0,0,0,.06);border-radius:6px;padding:3px 8px;font-size:12px;color:#444}\
#lxai-chips{display:flex;gap:6px;padding:0 14px 8px;overflow-x:auto;flex:none}#lxai-chips button{flex:none;border:1px solid #e5e5ea;background:#fff;border-radius:14px;padding:5px 11px;font-size:13px;color:#0a84ff}\
#lxai-in{display:flex;gap:8px;padding:8px 12px calc(env(safe-area-inset-bottom) + 10px);border-top:1px solid #e5e5ea}#lxai-in textarea{flex:1;border:1px solid #e5e5ea;border-radius:14px;padding:9px 12px;font-size:15px;resize:none;max-height:120px;outline:none;color:#1d1d1f;background:#fff}#lxai-in button{border:none;background:#0a84ff;color:#fff;border-radius:14px;width:44px;font-size:18px}#lxai-in button:disabled{opacity:.5}\
.lxai-typing:after{content:"▍";animation:lxai-b 1s infinite}@keyframes lxai-b{50%{opacity:0}}\
#lxai-sel{background:#1c1c1e;color:#fff;border-radius:10px;display:flex;padding:2px;box-shadow:0 4px 16px rgba(0,0,0,.3)}#lxai-sel button{border:none;background:none;color:#fff;padding:8px 10px;font-size:13px;border-radius:8px}#lxai-sel button:active{background:rgba(255,255,255,.15)}\
#lxai-w{width:34px;height:34px;border-radius:17px;background:#fff;box-shadow:0 3px 12px rgba(0,0,0,.25);display:flex;align-items:center;justify-content:center;font-size:17px;border:1px solid #e5e5ea}\
#lxai-wmenu{background:#fff;border-radius:12px;box-shadow:0 6px 24px rgba(0,0,0,.25);padding:4px;display:grid;grid-template-columns:1fr 1fr;gap:2px;min-width:200px}#lxai-wmenu button{border:none;background:none;padding:9px 10px;text-align:left;font-size:14px;color:#1d1d1f;border-radius:8px}#lxai-wmenu button:active{background:#f2f2f7}\
#lxai-wres{background:#fff;border-radius:14px;box-shadow:0 8px 32px rgba(0,0,0,.3);padding:12px;max-width:min(92vw,520px);max-height:50vh;display:flex;flex-direction:column;gap:8px}#lxai-wres .t{overflow:auto;white-space:pre-wrap;font-size:15px;line-height:1.5;flex:1}#lxai-wres .b{display:flex;gap:8px;justify-content:flex-end}#lxai-wres .b button{border:none;border-radius:8px;padding:7px 12px;font-size:13px;background:#f2f2f7;color:#1d1d1f}#lxai-wres .b .p{background:#0a84ff;color:#fff}\
@media(prefers-color-scheme:dark){#lxai-panel,#lxai-wmenu,#lxai-wres,#lxai-w{background:#1c1c1e;color:#f5f5f7}#lxai-panel header{border-color:#2c2c2e}#lxai-panel header button{background:#2c2c2e;color:#f5f5f7}.lxai-m.a{background:#2c2c2e;color:#f5f5f7}#lxai-chips button{background:#1c1c1e;border-color:#2c2c2e}#lxai-in{border-color:#2c2c2e}#lxai-in textarea{background:#2c2c2e;border-color:#2c2c2e;color:#fff}#lxai-wmenu button,#lxai-wres .b button{color:#f5f5f7}#lxai-wres .b button{background:#2c2c2e}#lxai-w{border-color:#2c2c2e}.lxai-m .act button{background:rgba(255,255,255,.1);color:#ddd}}';
var st = document.createElement('style'); st.textContent = css; document.documentElement.appendChild(st);
function root(id, html) { var d = document.createElement('div'); d.className = 'lxai-root'; d.id = id; d.innerHTML = html; document.body.appendChild(d); return d; }

// ---- 面板 ----
var CHIPS = [['总结本页', 'summarize'], ['要点列表', 'keypoints'], ['翻译本页要点', 'translate_page'], ['这页讲了什么？', 'ask:这页主要讲了什么？用三句话说明。'], ['清空', 'clear']];
function ensurePanel() {
  if (S.panel) return S.panel;
  var p = root('lxai-panel', '<header><b>✦ AI 助手</b><button data-a="full">⤢</button><button data-a="hist">历史</button><button data-a="close">收起</button></header><div id="lxai-msgs"></div><div id="lxai-chips">' + CHIPS.map(function (c) { return '<button data-c="' + esc(c[1]) + '">' + esc(c[0]) + '</button>'; }).join('') + '</div><div id="lxai-in"><textarea id="lxai-q" rows="1" placeholder="问点什么…（会带上本页内容）"></textarea><button id="lxai-send">↑</button></div>');
  p.addEventListener('click', function (e) {
    var b = e.target.closest('button'); if (!b) return;
    if (b.dataset.a === 'close') hidePanel(); else if (b.dataset.a === 'full') p.classList.toggle('full'); else if (b.dataset.a === 'hist') location.href = 'lemurx://ai/';
    else if (b.dataset.c) chip(b.dataset.c); else if (b.id === 'lxai-send') send();
    else if (b.dataset.cp !== undefined) copy(S.history[+b.dataset.cp].content);
    else if (b.dataset.re !== undefined) { var i = +b.dataset.re; var q = S.history[i - 1]; S.history.splice(i - 1); renderMsgs(); ask(q.content, q.ctx); }
  });
  var ta = p.querySelector('#lxai-q');
  ta.addEventListener('input', function () { this.style.height = 'auto'; this.style.height = Math.min(120, this.scrollHeight) + 'px'; });
  ta.addEventListener('keydown', function (e) { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); } });
  S.panel = p; renderMsgs();
  return p;
}
function showPanel() { ensurePanel(); requestAnimationFrame(function () { S.panel.classList.add('on'); }); }
function hidePanel() { if (S.panel) S.panel.classList.remove('on'); }
function renderMsgs() {
  var box = S.panel.querySelector('#lxai-msgs'); var h = '';
  if (!S.history.length) h = '<div class="lxai-m a">你好！我可以总结这个页面、回答关于它的问题，或者随便聊聊。选中文字还能解释、翻译、润色。</div>';
  S.history.forEach(function (m, i) { h += '<div class="lxai-m ' + (m.role === 'user' ? 'u' : 'a' + (m.error ? ' err' : '') + (m.typing ? ' lxai-typing' : '')) + '">' + (m.role === 'user' ? esc(m.display || m.content) : md(m.content)) + (m.role === 'assistant' && !m.typing && !m.error ? '<div class="act"><button data-cp="' + i + '">复制</button><button data-re="' + i + '">重答</button></div>' : '') + '</div>'; });
  box.innerHTML = h; box.scrollTop = box.scrollHeight;
}
function chip(c) {
  if (c === 'clear') { S.history = []; S.ctxUsed = false; renderMsgs(); return; }
  if (c === 'summarize') ask('请用中文总结这个网页的内容：先一句话概括，再分点列出主要信息（不超过 6 点），最后给出你的一句评价。', true, '总结本页');
  else if (c === 'keypoints') ask('提取这个网页的关键要点，用简洁的项目符号列出，每点不超过 30 字。', true, '要点列表');
  else if (c === 'translate_page') ask('把这个网页的主要内容翻译成' + (cfg.lang === 'zh-CN' ? '中文' : cfg.lang) + '，保留结构，忽略导航和广告。', true, '翻译本页要点');
  else if (c.indexOf('ask:') === 0) ask(c.slice(4), true);
}
function send() { var ta = S.panel.querySelector('#lxai-q'); var v = ta.value.trim(); if (!v || S.busy) return; ta.value = ''; ta.style.height = 'auto'; ask(v, !S.ctxUsed); }
function ask(text, withCtx, display) {
  if (S.busy) return;
  S.busy = true; S.panel.querySelector('#lxai-send').disabled = true;
  var ctx = withCtx ? pageText() : null;
  if (ctx) S.ctxUsed = true;
  S.history.push({ role: 'user', content: text, display: display, ctx: withCtx });
  var a = { role: 'assistant', content: '', typing: true }; S.history.push(a); renderMsgs();
  var msgs = S.history.filter(function (m) { return !m.typing; }).slice(-12).map(function (m) { return { role: m.role, content: m.content }; });
  call({ type: 'chat', messages: msgs, page: pageInfo(), context: ctx }).then(function (r) { a.content = r.content || ''; a.typing = false; })
    .catch(function (e) { a.content = '出错了：' + e.message + (/密钥|key|401/i.test(e.message) ? '\n\n去 lemurx://ai/ 设置里填 API 密钥。' : ''); a.error = true; a.typing = false; })
    .then(function () { S.busy = false; S.panel.querySelector('#lxai-send').disabled = false; renderMsgs(); });
}

// ---- 悬浮球（可拖）----
function ensureBubble() {
  if (S.bubble || !cfg.bubble) return;
  var b = root('lxai-bubble', '✦'); if (cfg.position === 'left') b.classList.add('l');
  var sy = null, moved = false, startY = 0, startX = 0;
  b.addEventListener('touchstart', function (e) { var t = e.touches[0]; sy = t.clientY; startY = b.offsetTop; startX = t.clientX; moved = false; }, { passive: true });
  b.addEventListener('touchmove', function (e) { var t = e.touches[0]; if (Math.abs(t.clientY - sy) > 6) moved = true; if (moved) { b.style.bottom = 'auto'; b.style.top = Math.max(0, Math.min(innerHeight - 46, startY + t.clientY - sy)) + 'px'; } }, { passive: true });
  b.addEventListener('touchend', function (e) { var dx = (e.changedTouches[0] ? e.changedTouches[0].clientX : startX) - startX; if (Math.abs(dx) > 60 && !moved) { b.classList.toggle('l'); return; } if (!moved) showPanel(); });
  b.addEventListener('click', function () { if (!('ontouchstart' in window)) showPanel(); });
  S.bubble = b;
}

// ---- 划词工具条 ----
var selTimer;
function onSel() {
  if (!cfg.selection) return;
  clearTimeout(selTimer);
  selTimer = setTimeout(function () {
    var sel = window.getSelection(); var t = sel && sel.toString().trim();
    if (S.selbar) { S.selbar.remove(); S.selbar = null; }
    if (!t || t.length < 2 || t.length > 3000) return;
    var n = sel.anchorNode && (sel.anchorNode.nodeType === 1 ? sel.anchorNode : sel.anchorNode.parentElement);
    if (n && (n.closest('.lxai-root') || n.closest('input,textarea,[contenteditable=true]'))) return;
    var r = sel.getRangeAt(0).getBoundingClientRect();
    var bar = root('lxai-sel', '<button data-a="explain">解释</button><button data-a="translate">翻译</button><button data-a="polish">润色</button><button data-a="rewrite">改写</button><button data-a="ask">提问</button>');
    var top = r.top - 48; if (top < 8) top = r.bottom + 8;
    bar.style.top = top + 'px'; bar.style.left = Math.max(8, Math.min(innerWidth - bar.offsetWidth - 8, r.left + r.width / 2 - bar.offsetWidth / 2)) + 'px';
    bar.addEventListener('touchstart', function (e) { e.stopPropagation(); }, { passive: true });
    bar.addEventListener('click', function (e) { var b = e.target.closest('button'); if (!b) return; e.preventDefault(); var a = b.dataset.a; bar.remove(); S.selbar = null;
      showPanel();
      if (a === 'ask') { S.panel.querySelector('#lxai-q').value = '关于「' + t.slice(0, 200) + '」：'; S.panel.querySelector('#lxai-q').focus(); return; }
      var prompts = { explain: '解释下面这段文字（用' + (cfg.lang === 'zh-CN' ? '中文' : cfg.lang) + '，通俗、简短，必要时举例）：\n\n', translate: '把下面的文字翻译成' + (cfg.lang === 'zh-CN' ? '中文（如果原文已是中文则翻译成英文）' : cfg.lang) + '，只输出译文：\n\n', polish: '润色下面的文字，保持原意和语言，让它更通顺、地道，只输出结果：\n\n', rewrite: '换一种说法改写下面的文字，保持原意和语言，只输出结果：\n\n' };
      ask(prompts[a] + t, false, { explain: '解释', translate: '翻译', polish: '润色', rewrite: '改写' }[a] + '：' + t.slice(0, 80) + (t.length > 80 ? '…' : ''));
    });
    S.selbar = bar;
  }, 350);
}
document.addEventListener('selectionchange', onSel);
document.addEventListener('touchstart', function (e) { if (S.selbar && !S.selbar.contains(e.target)) { S.selbar.remove(); S.selbar = null; } }, { passive: true });

// ---- 写作助手 ----
function editableOf(el) { if (!el) return null; if (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && /^(text|search|email|url)$/i.test(el.type || 'text'))) return el; var ce = el.closest && el.closest('[contenteditable=true],[contenteditable=""]'); return ce; }
function getText(el) { return el.value !== undefined ? el.value : el.innerText; }
function setText(el, t) { if (el.value !== undefined) { el.value = t; el.dispatchEvent(new Event('input', { bubbles: true })); } else { el.focus(); document.execCommand('selectAll'); if (!document.execCommand('insertText', false, t)) el.innerText = t; } }
var W_ACTIONS = [['✏️ 润色', 'polish', '润色下面的文字：保持原意和语言，更通顺、自然、有条理。只输出结果。'], ['✅ 语法检查', 'grammar', '检查并修正下面文字的拼写、语法和标点错误，保持原意和语言。只输出修正后的全文。'], ['➡️ 续写', 'continue', '顺着下面的文字自然地续写一段（与原文同一语言、同一语气），只输出续写的部分。'], ['👔 更正式', 'formal', '把下面的文字改写得更正式、专业，保持原意和语言。只输出结果。'], ['✂️ 更简洁', 'concise', '把下面的文字精简到一半左右的长度，保留关键信息，保持语言。只输出结果。'], ['🌐 翻成英文', 'en', '把下面的文字翻译成自然、地道的英文。只输出译文。'], ['🇨🇳 翻成中文', 'zh', '把下面的文字翻译成自然的简体中文。只输出译文。'], ['💬 换个语气', 'tone', '把下面的文字改写成更友好、有亲和力的语气，保持原意和语言。只输出结果。']];
function onFocus(e) {
  if (!cfg.writing) return;
  var el = editableOf(e.target); if (!el || el.closest('.lxai-root')) { return; }
  S.editable = el; positionW();
}
function positionW() {
  var el = S.editable; if (!el) return;
  if (!S.wbtn) { S.wbtn = root('lxai-w', '✨'); S.wbtn.addEventListener('mousedown', function (e) { e.preventDefault(); }); S.wbtn.addEventListener('touchstart', function (e) { e.preventDefault(); wmenu(); }, { passive: false }); S.wbtn.addEventListener('click', wmenu); }
  var r = el.getBoundingClientRect();
  if (r.width === 0 || r.bottom < 0 || r.top > innerHeight) { S.wbtn.style.display = 'none'; return; }
  S.wbtn.style.display = 'flex'; S.wbtn.style.top = Math.max(4, Math.min(innerHeight - 40, r.bottom - 40)) + 'px'; S.wbtn.style.left = Math.max(4, r.right - 40) + 'px';
}
function wmenu() {
  var el = S.editable; if (!el) return;
  var text = getText(el).trim();
  if (!text && S.wmenuEl) return;
  if (S.wmenuEl) { S.wmenuEl.remove(); S.wmenuEl = null; return; }
  var m = root('lxai-wmenu', W_ACTIONS.map(function (a) { return '<button data-a="' + a[1] + '"' + (!text && a[1] !== 'continue' ? ' disabled style="opacity:.4"' : '') + '>' + a[0] + '</button>'; }).join(''));
  var r = S.wbtn.getBoundingClientRect(); m.style.top = Math.max(4, r.top - m.offsetHeight - 6) + 'px'; m.style.left = Math.max(4, Math.min(innerWidth - m.offsetWidth - 4, r.right - m.offsetWidth)) + 'px';
  m.addEventListener('mousedown', function (e) { e.preventDefault(); });
  m.addEventListener('click', function (e) { var b = e.target.closest('button'); if (!b || b.disabled) return; m.remove(); S.wmenuEl = null; var act = W_ACTIONS.filter(function (a) { return a[1] === b.dataset.a; })[0]; wrun(el, act, text); });
  S.wmenuEl = m;
}
function wrun(el, act, text) {
  var box = root('lxai-wres', '<div class="t lxai-typing">' + esc(act[0]) + ' 中…</div><div class="b"><button data-a="x">关闭</button></div>');
  box.style.left = '4vw'; box.style.top = '12vh';
  box.addEventListener('mousedown', function (e) { e.preventDefault(); });
  var close = function () { box.remove(); };
  box.addEventListener('click', function (e) { var b = e.target.closest('button'); if (!b) return; if (b.dataset.a === 'x') close(); else if (b.dataset.a === 'cp') { copy(box.__res); } else if (b.dataset.a === 'rep') { setText(el, act[1] === 'continue' ? (getText(el) + (getText(el).endsWith('\n') || !getText(el) ? '' : ' ') + box.__res) : box.__res); close(); toast('已替换'); } });
  call({ type: 'chat', messages: [{ role: 'user', content: act[2] + '\n\n' + text }], page: pageInfo(), writing: true }).then(function (r) {
    box.__res = (r.content || '').trim();
    box.querySelector('.t').className = 't'; box.querySelector('.t').textContent = box.__res;
    box.querySelector('.b').innerHTML = '<button data-a="x">关闭</button><button data-a="cp">复制</button><button class="p" data-a="rep">' + (act[1] === 'continue' ? '追加到输入框' : '替换原文') + '</button>';
  }).catch(function (e) { box.querySelector('.t').className = 't'; box.querySelector('.t').textContent = '出错了：' + e.message; });
}
document.addEventListener('focusin', onFocus);
document.addEventListener('focusout', function (e) { setTimeout(function () { var a = document.activeElement; if (!editableOf(a) && S.wbtn && !(S.wmenuEl)) { S.wbtn.style.display = 'none'; S.editable = null; } }, 150); });
window.addEventListener('scroll', function () { if (S.editable) positionW(); }, { passive: true, capture: true });
document.addEventListener('touchstart', function (e) { if (S.wmenuEl && !S.wmenuEl.contains(e.target) && !S.wbtn.contains(e.target)) { S.wmenuEl.remove(); S.wmenuEl = null; } }, { passive: true });

ensureBubble();
window.__lxai_cmd = function (c, arg) {
  if (c === 'open') showPanel(); else if (c === 'summarize') { showPanel(); chip('summarize'); } else if (c === 'ask') { showPanel(); ask(String(arg || ''), true); }
  else if (c === 'config') { Object.assign(cfg, arg || {}); if (!cfg.bubble && S.bubble) { S.bubble.remove(); S.bubble = null; } else ensureBubble(); if (S.bubble) S.bubble.classList.toggle('l', cfg.position === 'left'); }
  else if (c === 'pagetext') return pageText(arg || 12000);
  return true;
};
})();
]==]
