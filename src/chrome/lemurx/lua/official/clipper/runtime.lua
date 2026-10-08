-- clipper/runtime · 页面内 JS：正文提取 + HTML → Markdown
--
-- 由 clipper/web.lua 注入后调用 window.__lxclip_extract(mode)，返回 JSON：
--   { title, url, byline, excerpt, markdown, text, words, site }
-- mode: "article"（Readability 式主正文）| "selection"（当前选区）| "page"（整个 body）
--
-- 正文提取思路（简化版 Readability）：给每个块级容器按"段落文本长度 + 逗号数 − 链接密度"打分，
-- 沿父链向上传播，取最高分节点；再剥掉导航/侧栏/评论/广告类 class/id。
return [==[
(function(){
if (window.__lxclip_extract) return;
var BAD = /(^|[\s_-])(nav|menu|sidebar|footer|header|comment|comments|share|social|related|recommend|promo|ad|ads|advert|sponsor|banner|popup|modal|cookie|subscribe|newsletter|breadcrumb|toolbar|pagination|widget|login|signup)([\s_-]|$)/i;
var GOOD = /(^|[\s_-])(article|content|main|post|entry|body|text|story|blog|markdown|md-content|rich_media|RichText|article-content)([\s_-]|$)/i;
var BLOCK = {P:1,DIV:1,SECTION:1,ARTICLE:1,MAIN:1,TD:1,PRE:1,BLOCKQUOTE:1,LI:1,UL:1,OL:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,TABLE:1,FIGURE:1};
var SKIP = {SCRIPT:1,STYLE:1,NOSCRIPT:1,IFRAME:1,SVG:1,CANVAS:1,TEMPLATE:1,BUTTON:1,INPUT:1,SELECT:1,TEXTAREA:1,FORM:1};

function txt(el){ return (el.textContent||'').replace(/\s+/g,' ').trim(); }
function linkDensity(el){ var t = txt(el).length; if(!t) return 0; var l=0, as=el.getElementsByTagName('a'); for(var i=0;i<as.length;i++) l += txt(as[i]).length; return l/t; }
function cls(el){ return ((el.className && typeof el.className==='string' ? el.className : '') + ' ' + (el.id||'')); }

function pickArticle(){
  var cands = document.querySelectorAll('article, [role=main], main, #content, .post-content, .article-content, .entry-content, .rich_media_content, #js_content, .markdown-body, .content');
  var best=null, bestScore=0;
  function scoreOf(el){ var t=txt(el); if(t.length<200) return 0; var s=t.length*(1-linkDensity(el)); s += (t.match(/[,，。.!?]/g)||[]).length*3; if(GOOD.test(cls(el))) s*=1.3; if(BAD.test(cls(el))) s*=0.3; return s; }
  for (var i=0;i<cands.length;i++){ var s=scoreOf(cands[i]); if(s>bestScore){best=cands[i];bestScore=s;} }
  // 通用打分：所有 <p> 的父容器
  var scores = new Map();
  var ps = document.getElementsByTagName('p');
  for (var i=0;i<ps.length;i++){
    var p = ps[i], t = txt(p); if (t.length < 25) continue;
    var sc = 1 + Math.min(Math.floor(t.length/100), 3) + (t.match(/[,，]/g)||[]).length;
    var par = p.parentElement, gp = par && par.parentElement;
    if (par) scores.set(par, (scores.get(par)||0)+sc);
    if (gp) scores.set(gp, (scores.get(gp)||0)+sc/2);
  }
  scores.forEach(function(v, el){ var s = v*(1-linkDensity(el)); if (BAD.test(cls(el))) s*=0.3; if (GOOD.test(cls(el))) s*=1.3; if (s>bestScore){best=el;bestScore=s;} });
  return best || document.body;
}

function clean(root){
  var c = root.cloneNode(true);
  var all = c.querySelectorAll('*');
  for (var i=all.length-1;i>=0;i--){
    var el = all[i], tag = el.tagName;
    if (SKIP[tag]) { el.remove(); continue; }
    if (tag==='ASIDE' || tag==='NAV' || tag==='FOOTER' || (tag==='HEADER' && el!==c)) { el.remove(); continue; }
    if (el.hidden || el.getAttribute('aria-hidden')==='true') { el.remove(); continue; }
    if (tag!=='A' && tag!=='IMG' && BAD.test(cls(el)) && !GOOD.test(cls(el)) && txt(el).length < 400) { el.remove(); continue; }
    if ((tag==='DIV'||tag==='SECTION') && txt(el).length<40 && !el.querySelector('img,pre,table,video')) { if (linkDensity(el)>0.5) { el.remove(); continue; } }
  }
  return c;
}

function absUrl(u){ try { return new URL(u, location.href).href; } catch(e){ return u; } }
function imgSrc(el){ return el.getAttribute('data-src') || el.getAttribute('data-original') || el.currentSrc || el.getAttribute('src') || ''; }

// HTML → Markdown（覆盖常见标签；表格转管道表；代码块保留语言）
function md(node, ctx){
  ctx = ctx || {list:[]};
  var out='';
  for (var n=node.firstChild; n; n=n.nextSibling){
    if (n.nodeType===3){ out += n.nodeValue.replace(/\s+/g,' '); continue; }
    if (n.nodeType!==1) continue;
    var t = n.tagName, inner;
    switch(t){
      case 'H1': case 'H2': case 'H3': case 'H4': case 'H5': case 'H6':
        out += '\n\n' + '#'.repeat(+t[1]) + ' ' + md(n,ctx).trim() + '\n\n'; break;
      case 'P': out += '\n\n' + md(n,ctx).trim() + '\n\n'; break;
      case 'BR': out += '  \n'; break;
      case 'HR': out += '\n\n---\n\n'; break;
      case 'STRONG': case 'B': inner = md(n,ctx).trim(); if(inner) out += '**'+inner+'**'; break;
      case 'EM': case 'I': inner = md(n,ctx).trim(); if(inner) out += '*'+inner+'*'; break;
      case 'DEL': case 'S': inner = md(n,ctx).trim(); if(inner) out += '~~'+inner+'~~'; break;
      case 'CODE': if (n.parentElement && n.parentElement.tagName==='PRE') { out += md(n,ctx); } else { out += '`'+txt(n)+'`'; } break;
      case 'PRE': { var code = n.querySelector('code'); var lang = ((code&&code.className||n.className||'').match(/(?:language|lang)-([\w+-]+)/)||[])[1]||''; out += '\n\n```'+lang+'\n'+(n.textContent||'').replace(/\n$/,'')+'\n```\n\n'; break; }
      case 'BLOCKQUOTE': out += '\n\n' + md(n,ctx).trim().split('\n').map(function(l){return '> '+l;}).join('\n') + '\n\n'; break;
      case 'A': { inner = md(n,ctx).trim(); var href = n.getAttribute('href'); if (!href || href.indexOf('javascript:')===0) { out += inner; } else if (inner) { out += '['+inner+']('+absUrl(href)+')'; } break; }
      case 'IMG': { var s = imgSrc(n); if (s && s.indexOf('data:')!==0) out += '\n\n!['+(n.getAttribute('alt')||'')+']('+absUrl(s)+')\n\n'; break; }
      case 'FIGURE': out += '\n\n' + md(n,ctx).trim() + '\n\n'; break;
      case 'FIGCAPTION': out += '\n*' + md(n,ctx).trim() + '*\n'; break;
      case 'UL': case 'OL': {
        ctx.list.push({tag:t, i:0}); var body = md(n,ctx); ctx.list.pop();
        out += (ctx.list.length? '\n' : '\n\n') + body.replace(/\n+$/,'') + (ctx.list.length? '\n' : '\n\n'); break; }
      case 'LI': {
        var L = ctx.list[ctx.list.length-1] || {tag:'UL',i:0}; L.i++;
        var indent = '  '.repeat(Math.max(0, ctx.list.length-1));
        var bullet = L.tag==='OL' ? (L.i+'. ') : '- ';
        var cb = n.querySelector('input[type=checkbox]'); if (cb) bullet += cb.checked ? '[x] ' : '[ ] ';
        var body = md(n,ctx).trim().replace(/\n{2,}/g,'\n').split('\n').map(function(l,i){ return (i && !/^\s/.test(l)) ? indent+'  '+l : l; }).join('\n');
        out += indent + bullet + body + '\n'; break; }
      case 'TABLE': {
        var rows = n.querySelectorAll('tr'); if (!rows.length) break; out += '\n\n';
        for (var r=0;r<rows.length;r++){ var cells = rows[r].querySelectorAll('th,td'); var line=[]; for (var c=0;c<cells.length;c++) line.push(md(cells[c],ctx).trim().replace(/\|/g,'\\|').replace(/\n+/g,' ')); out += '| '+line.join(' | ')+' |\n'; if (r===0) out += '|'+line.map(function(){return ' --- ';}).join('|')+'|\n'; }
        out += '\n'; break; }
      case 'VIDEO': case 'AUDIO': { var vs = n.getAttribute('src') || (n.querySelector('source')&&n.querySelector('source').getAttribute('src')); if (vs) out += '\n\n['+t.toLowerCase()+']('+absUrl(vs)+')\n\n'; break; }
      default:
        if (BLOCK[t] || t==='DL' || t==='DD' || t==='DT') out += '\n' + md(n,ctx) + '\n'; else out += md(n,ctx);
    }
  }
  return out;
}
function tidy(s){ return s.replace(/[ \t]+\n/g,'\n').replace(/\n{3,}/g,'\n\n').replace(/^\s+|\s+$/g,''); }

function meta(name){ var m = document.querySelector('meta[property="'+name+'"],meta[name="'+name+'"]'); return m && m.getAttribute('content') || ''; }

window.__lxclip_extract = function(mode){
  var root, sel = window.getSelection && window.getSelection();
  if (mode==='selection' && sel && !sel.isCollapsed && sel.rangeCount) {
    var frag = sel.getRangeAt(0).cloneContents(); root = document.createElement('div'); root.appendChild(frag);
  } else if (mode==='page') { root = clean(document.body); }
  else { root = clean(pickArticle()); }
  var m = tidy(md(root));
  var title = meta('og:title') || document.title || '';
  var text = txt(root);
  var words = (text.match(/[\u4e00-\u9fff]/g)||[]).length + (text.replace(/[\u4e00-\u9fff]/g,' ').match(/[A-Za-z0-9_]+/g)||[]).length;
  return JSON.stringify({
    title: title.trim(), url: location.href, site: meta('og:site_name') || location.host,
    byline: meta('author') || meta('article:author') || (document.querySelector('[rel=author],.author,.byline')||{}).textContent || '',
    excerpt: meta('description') || meta('og:description') || text.slice(0,200),
    markdown: m, text: text, words: words,
    image: meta('og:image') ? absUrl(meta('og:image')) : '',
  });
};
})();
]==]
