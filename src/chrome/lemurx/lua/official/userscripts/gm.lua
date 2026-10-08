-- userscripts.gm —— 页面里的 GM_* 运行时（JS 源码，由渲染进程 Lua 注入）
--
-- 每个新文档注入一次，挂成不可枚举的 window.__lxus；随后每个匹配的用户脚本：
--   window.__lxus.run(info, values, resources, function(GM_getValue, ...){ ...脚本... })
--
-- 与浏览器进程的通话走三个 luakit register_function 暴露的桥函数（全部 JSON 字符串进出）：
--   __lx_us_set(json)   持久化 GM_setValue / deleteValue
--   __lx_us_xhr(json)   GM_xmlhttpRequest（浏览器进程 fetch，无 CORS）→ Promise<json>
--   __lx_us_call(json)  其他：openInTab / notification / clipboard / download / menu / log
--
-- 语义照 Tampermonkey 文档；跑在页面主世界（Violentmonkey 的 @inject-into page），unsafeWindow === window。

return [==[
(function () {
  if (window.__lxus) return;
  var bset = window.__lx_us_set, bxhr = window.__lx_us_xhr, bcall = window.__lx_us_call;
  try { delete window.__lx_us_set; delete window.__lx_us_xhr; delete window.__lx_us_call; } catch (e) {}
  var J = JSON, stringify = J.stringify, parse = J.parse;
  var scripts = {};           // id -> state
  var menuSeq = 0;

  function call(fn, args) {
    try { return bcall(stringify(Object.assign({ fn: fn }, args || {}))); } catch (e) { return Promise.reject(e); }
  }
  function toB64(str) {
    try { return btoa(unescape(encodeURIComponent(str))); } catch (e) { return btoa(str); }
  }
  function b64ToBuf(b64) {
    var bin = atob(b64), len = bin.length, buf = new Uint8Array(len);
    for (var i = 0; i < len; i++) buf[i] = bin.charCodeAt(i);
    return buf.buffer;
  }
  function parseHeaders(str) {
    var out = {};
    (str || '').split(/\r?\n/).forEach(function (l) { var i = l.indexOf(':'); if (i > 0) out[l.slice(0, i).trim().toLowerCase()] = l.slice(i + 1).trim(); });
    return out;
  }
  function headersToString(h) {
    if (!h) return '';
    if (typeof h === 'string') return h;
    return Object.keys(h).map(function (k) { return k + ': ' + h[k]; }).join('\r\n');
  }
  function serializeBody(data) {
    if (data == null) return null;
    if (typeof data === 'string') return { body: data };
    if (data instanceof URLSearchParams) return { body: data.toString(), ct: 'application/x-www-form-urlencoded;charset=UTF-8' };
    if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
      var u8 = data instanceof ArrayBuffer ? new Uint8Array(data) : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
      var s = ''; for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
      return { body: btoa(s), base64: true };
    }
    if (typeof FormData !== 'undefined' && data instanceof FormData) {
      // multipart 需要 Blob 读取（异步）；在此退化为 urlencoded（文件字段丢失）
      var p = new URLSearchParams(); data.forEach(function (v, k) { if (typeof v === 'string') p.append(k, v); });
      return { body: p.toString(), ct: 'application/x-www-form-urlencoded;charset=UTF-8' };
    }
    if (typeof Blob !== 'undefined' && data instanceof Blob) return { blob: data };
    try { return { body: stringify(data), ct: 'application/json' }; } catch (e) { return { body: String(data) }; }
  }

  function makeContext(info, values, resources) {
    var id = info.id;
    var st = scripts[id] = { info: info, values: values || {}, listeners: {}, lseq: 0, menu: {} };
    var api = {};

    // ---- 存储 ----
    function persist(key, value, del) {
      try { bset(stringify({ id: id, key: key, value: del ? null : value, del: !!del })); } catch (e) {}
    }
    function fire(key, oldV, newV, remote) {
      Object.keys(st.listeners).forEach(function (lid) {
        var l = st.listeners[lid];
        if (l.name === key) { try { l.cb(key, oldV, newV, !!remote); } catch (e) { console.error('[userscripts] listener', e); } }
      });
    }
    api.GM_getValue = function (key, def) { return Object.prototype.hasOwnProperty.call(st.values, key) ? st.values[key] : def; };
    api.GM_setValue = function (key, value) { var old = st.values[key]; st.values[key] = value; persist(key, value, false); fire(key, old, value, false); };
    api.GM_deleteValue = function (key) { var old = st.values[key]; delete st.values[key]; persist(key, null, true); fire(key, old, undefined, false); };
    api.GM_listValues = function () { return Object.keys(st.values); };
    api.GM_getValues = function (keys) {
      var out = {};
      if (Array.isArray(keys)) keys.forEach(function (k) { if (k in st.values) out[k] = st.values[k]; });
      else if (keys && typeof keys === 'object') Object.keys(keys).forEach(function (k) { out[k] = k in st.values ? st.values[k] : keys[k]; });
      else Object.assign(out, st.values);
      return out;
    };
    api.GM_setValues = function (obj) { Object.keys(obj || {}).forEach(function (k) { api.GM_setValue(k, obj[k]); }); };
    api.GM_deleteValues = function (keys) { (keys || []).forEach(function (k) { api.GM_deleteValue(k); }); };
    api.GM_addValueChangeListener = function (name, cb) { var lid = String(++st.lseq); st.listeners[lid] = { name: name, cb: cb }; return lid; };
    api.GM_removeValueChangeListener = function (lid) { delete st.listeners[String(lid)]; };

    // ---- DOM ----
    function attach(el) {
      var parent = document.head || document.body || document.documentElement;
      if (parent) parent.appendChild(el);
      else { var mo = new MutationObserver(function () { var p = document.head || document.documentElement; if (p) { mo.disconnect(); p.appendChild(el); } }); mo.observe(document, { childList: true, subtree: true }); }
      return el;
    }
    api.GM_addStyle = function (css) { var s = document.createElement('style'); s.textContent = css; s.setAttribute('data-userscript', info.name || id); return attach(s); };
    api.GM_addElement = function (a, b, c) {
      var parent, tag, attrs;
      if (typeof a === 'string') { tag = a; attrs = b; } else { parent = a; tag = b; attrs = c; }
      var el = document.createElement(tag);
      Object.keys(attrs || {}).forEach(function (k) { if (k === 'textContent') el.textContent = attrs[k]; else el.setAttribute(k, attrs[k]); });
      if (parent) { parent.appendChild(el); return el; }
      return attach(el);
    };

    // ---- 资源 ----
    api.GM_getResourceText = function (name) { var r = resources && resources[name]; return r ? (r.text != null ? r.text : (r.b64 ? decodeURIComponent(escape(atob(r.b64))) : undefined)) : undefined; };
    api.GM_getResourceURL = function (name) {
      var r = resources && resources[name]; if (!r) return undefined;
      if (r.b64) return 'data:' + (r.mime || 'application/octet-stream') + ';base64,' + r.b64;
      return 'data:' + (r.mime || 'text/plain') + ';base64,' + toB64(r.text || '');
    };

    // ---- 杂项 ----
    api.GM_log = function () { var a = Array.prototype.slice.call(arguments); a.unshift('[' + (info.name || id) + ']'); console.log.apply(console, a); };
    api.GM_openInTab = function (url, opts) {
      var o = typeof opts === 'boolean' ? { active: !opts } : (opts || {});
      call('openInTab', { url: String(url), active: o.active !== false && !o.loadInBackground, incognito: !!o.incognito });
      return { close: function () {}, closed: false, onclose: null, name: '' };
    };
    api.GM_setClipboard = function (text, type) { call('clipboard', { text: String(text), type: typeof type === 'string' ? type : (type && type.type) || 'text' }); };
    api.GM_notification = function (a, b, c, d) {
      var det = typeof a === 'object' && a ? a : { text: a, title: b, image: c, onclick: d };
      call('notification', { title: det.title || info.name || '用户脚本', text: det.text || '', url: det.url });
      if (det.ondone) setTimeout(function () { try { det.ondone(); } catch (e) {} }, 0);
    };
    api.GM_registerMenuCommand = function (caption, fn, opt) {
      var mid = (opt && typeof opt === 'object' && opt.id) || ('m' + (++menuSeq));
      st.menu[mid] = { caption: String(caption), fn: fn, title: opt && opt.title };
      syncMenu();
      return mid;
    };
    api.GM_unregisterMenuCommand = function (mid) { delete st.menu[mid]; syncMenu(); };
    function syncMenu() {
      var cmds = Object.keys(st.menu).map(function (k) { return { id: k, caption: st.menu[k].caption }; });
      call('menu', { script: id, name: info.name, cmds: cmds });
    }
    api.GM_getTab = function (cb) { var v; try { v = parse(sessionStorage.getItem('__lxus_tab_' + id) || '{}'); } catch (e) { v = {}; } cb && cb(v); };
    api.GM_saveTab = function (obj, cb) { try { sessionStorage.setItem('__lxus_tab_' + id, stringify(obj || {})); } catch (e) {} cb && cb(); };
    api.GM_getTabs = function (cb) { cb && cb({}); };
    api.GM_download = function (a, b) {
      var det = typeof a === 'object' && a ? a : { url: a, name: b };
      var p = call('download', { url: String(det.url), name: det.name, headers: det.headers, saveAs: !!det.saveAs });
      if (det.onload) p.then(function () { try { det.onload(); } catch (e) {} });
      if (det.onerror) p.catch(function (e) { try { det.onerror({ error: 'unknown', details: String(e) }); } catch (x) {} });
      return { abort: function () {} };
    };
    api.GM_cookie = {
      list: function (d, cb) { cb && cb([], 'GM_cookie not supported'); },
      set: function (d, cb) { cb && cb('GM_cookie not supported'); },
      delete: function (d, cb) { cb && cb('GM_cookie not supported'); }
    };
    api.GM_webRequest = function () {};

    // ---- GM_xmlhttpRequest ----
    api.GM_xmlhttpRequest = function (details) {
      details = details || {};
      var aborted = false;
      var body = serializeBody(details.data);
      var headers = Object.assign({}, details.headers || {});
      if (body && body.ct && !Object.keys(headers).some(function (k) { return k.toLowerCase() === 'content-type'; })) headers['Content-Type'] = body.ct;
      var rt = (details.responseType || '').toLowerCase();
      var send = function (b) {
        var req = {
          method: (details.method || 'GET').toUpperCase(), url: String(details.url), headers: headers,
          body: b ? b.body : null, base64: !!(b && b.base64), timeout: details.timeout || 0,
          binary: rt === 'arraybuffer' || rt === 'blob' || rt === 'stream' || !!details.binary,
          anonymous: !!details.anonymous, user: details.user, password: details.password,
          redirect: details.redirect, nocache: !!details.nocache, overrideMimeType: details.overrideMimeType
        };
        if (details.onloadstart) { try { details.onloadstart({ readyState: 1 }); } catch (e) {} }
        var p;
        try { p = bxhr(stringify(req)); } catch (e) { p = Promise.reject(e); }
        Promise.resolve(p).then(function (json) {
          if (aborted) return;
          var r = typeof json === 'string' ? parse(json) : json;
          var text = r.body || '';
          if (r.base64 && !req.binary) { try { text = decodeURIComponent(escape(atob(text))); } catch (e) { text = atob(text); } }
          var resp = {
            readyState: 4, status: r.status || 0, statusText: r.statusText || (r.status ? '' : 'error'),
            responseHeaders: headersToString(r.headers), finalUrl: r.url || req.url, context: details.context,
            responseText: req.binary ? undefined : text, response: undefined, responseXML: undefined
          };
          try {
            if (rt === 'json') resp.response = parse(text);
            else if (rt === 'arraybuffer') resp.response = r.base64 ? b64ToBuf(text) : new TextEncoder().encode(text).buffer;
            else if (rt === 'blob') { var buf = r.base64 ? b64ToBuf(text) : new TextEncoder().encode(text).buffer; resp.response = new Blob([buf], { type: (parseHeaders(headersToString(r.headers))['content-type'] || '').split(';')[0] }); }
            else if (rt === 'document') { resp.response = new DOMParser().parseFromString(text, 'text/html'); resp.responseXML = resp.response; }
            else resp.response = text;
          } catch (e) { resp.response = text; }
          if (!r.status) {
            var ev = Object.assign({}, resp, { error: r.error || 'network error' });
            if (r.error === 'timeout' && details.ontimeout) details.ontimeout(ev);
            else if (details.onerror) details.onerror(ev);
          } else {
            if (details.onreadystatechange) { try { details.onreadystatechange(resp); } catch (e) {} }
            if (details.onprogress) { try { details.onprogress(Object.assign({ lengthComputable: true, loaded: r.bytes || 0, total: r.bytes || 0 }, resp)); } catch (e) {} }
            if (details.onload) details.onload(resp);
          }
          if (details.onloadend) { try { details.onloadend(resp); } catch (e) {} }
        }).catch(function (e) {
          if (aborted) return;
          if (details.onerror) details.onerror({ readyState: 4, status: 0, statusText: String(e), error: String(e), responseHeaders: '', finalUrl: req.url, context: details.context });
          if (details.onloadend) { try { details.onloadend({ readyState: 4, status: 0 }); } catch (x) {} }
        });
      };
      if (body && body.blob) {
        var fr = new FileReader();
        fr.onload = function () { send({ body: String(fr.result).split(',')[1], base64: true, ct: body.blob.type }); };
        fr.readAsDataURL(body.blob);
      } else send(body);
      return { abort: function () { aborted = true; if (details.onabort) { try { details.onabort({ readyState: 4, status: 0 }); } catch (e) {} } } };
    };

    // ---- GM.* Promise 版 ----
    var GM = {};
    function promisify(name) { return function () { var a = arguments; return new Promise(function (res, rej) { try { res(api[name].apply(null, a)); } catch (e) { rej(e); } }); }; }
    ['getValue', 'setValue', 'deleteValue', 'listValues', 'getValues', 'setValues', 'deleteValues', 'getResourceUrl', 'getResourceText', 'addStyle', 'addElement', 'openInTab', 'setClipboard', 'notification', 'registerMenuCommand', 'unregisterMenuCommand', 'getTab', 'saveTab', 'getTabs', 'log', 'download'].forEach(function (n) {
      var legacy = 'GM_' + n; if (n === 'getResourceUrl') legacy = 'GM_getResourceURL';
      if (api[legacy]) GM[n] = promisify(legacy);
    });
    GM.addValueChangeListener = api.GM_addValueChangeListener;
    GM.removeValueChangeListener = api.GM_removeValueChangeListener;
    GM.xmlHttpRequest = function (d) {
      return new Promise(function (res, rej) {
        var det = Object.assign({}, d);
        var oload = det.onload, oerr = det.onerror, otime = det.ontimeout, oabort = det.onabort;
        det.onload = function (r) { oload && oload(r); res(r); };
        det.onerror = function (r) { oerr && oerr(r); rej(r); };
        det.ontimeout = function (r) { otime && otime(r); rej(r); };
        det.onabort = function (r) { oabort && oabort(r); rej(r); };
        var h = api.GM_xmlhttpRequest(det);
        if (d && typeof d === 'object') d.abort = h.abort;
      });
    };
    GM.cookie = { list: function () { return Promise.reject('not supported'); }, set: function () { return Promise.reject('not supported'); }, delete: function () { return Promise.reject('not supported'); } };

    // ---- GM_info ----
    var gminfo = {
      script: {
        name: info.name, namespace: info.namespace || '', version: info.version || '', description: info.description || '',
        author: info.author, icon: info.icon, icon64: info.icon64, homepage: info.homepage, supportURL: info.support,
        matches: info.match || [], includes: info.include || [], excludes: info.exclude || [], grant: info.grant || [],
        resources: (info.resources || []).map(function (r) { return { name: r.name, url: r.url }; }),
        requires: (info.require || []).map(function (u) { return { url: u }; }),
        'run-at': info.run_at, runAt: info.run_at, noframes: !!info.noframes, uuid: info.id,
        header: info.header || '', downloadURL: info.downloadURL, updateURL: info.updateURL, antifeatures: {}, options: {}
      },
      scriptHandler: 'LemurX', version: info.handler_version || '1.0', scriptMetaStr: info.header || '',
      scriptSource: undefined, scriptUpdateURL: info.updateURL, scriptWillUpdate: !!info.updateURL,
      injectInto: 'page', isIncognito: false, isFirstPartyIsolation: false, sandboxMode: 'js',
      platform: { arch: 'arm64', browserName: 'LemurX', browserVersion: info.browser_version || '', os: 'android' },
      userAgent: navigator.userAgent, userAgentData: navigator.userAgentData
    };
    api.GM_info = gminfo; GM.info = gminfo;
    api.GM = GM;
    api.unsafeWindow = window;
    api.cloneInto = function (o) { return o; };
    api.exportFunction = function (f) { return f; };
    api.createObjectIn = function (o) { return o; };
    st.api = api;
    return st;
  }

  function when(runAt, fn) {
    var rs = document.readyState;
    if (runAt === 'document-start') return fn();
    if (runAt === 'document-body') {
      if (document.body) return fn();
      var mo = new MutationObserver(function () { if (document.body) { mo.disconnect(); fn(); } });
      return mo.observe(document.documentElement || document, { childList: true, subtree: true });
    }
    if (runAt === 'document-end') {
      if (rs !== 'loading') return fn();
      return document.addEventListener('DOMContentLoaded', fn, { once: true });
    }
    // document-idle（默认）：load 之后；已完成则下一个宏任务
    if (rs === 'complete') return setTimeout(fn, 0);
    window.addEventListener('load', function () { setTimeout(fn, 0); }, { once: true });
    // 兜底：DOMContentLoaded 后 3 秒还没 load 也跑（长期挂起的资源）
    document.addEventListener('DOMContentLoaded', function () { setTimeout(function () { if (!fn.__done) fn(); }, 3000); }, { once: true });
  }

  var lxus = {
    run: function (info, values, resources, names, body) {
      var st = makeContext(info, values, resources);
      var args = names.map(function (n) { return st.api[n]; });
      var done = false;
      var go = function () {
        if (done) return; done = true; go.__done = true;
        try { body.apply(window, args); }
        catch (e) { console.error('[userscripts] ' + (info.name || info.id) + ':', e); call('log', { script: info.id, level: 'error', text: String(e && e.stack || e) }); }
      };
      when(info.run_at, go);
    },
    onValue: function (id, key, value, del) {
      var st = scripts[id]; if (!st) return;
      var old = st.values[key];
      if (del) delete st.values[key]; else st.values[key] = value;
      Object.keys(st.listeners).forEach(function (lid) { var l = st.listeners[lid]; if (l.name === key) { try { l.cb(key, old, del ? undefined : value, true); } catch (e) {} } });
    },
    runMenu: function (id, mid) {
      var st = scripts[id]; var m = st && st.menu[mid];
      if (m) { try { m.fn(); } catch (e) { console.error('[userscripts] menu', e); } return true; }
      return false;
    },
    menus: function () {
      var out = [];
      Object.keys(scripts).forEach(function (id) { var st = scripts[id]; Object.keys(st.menu).forEach(function (k) { out.push({ script: id, name: st.info.name, id: k, caption: st.menu[k].caption }); }); });
      return out;
    }
  };
  Object.defineProperty(window, '__lxus', { value: lxus, enumerable: false, configurable: false, writable: false });
})();
]==]
