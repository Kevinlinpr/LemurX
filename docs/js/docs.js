/* Lemur Lua reference — sidebar behaviour. No dependencies; main.js handles nav, i18n, highlighting, copy. */
(function () {
  "use strict";
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

  /* ---- signature colouring: `lemurx.tabs.open(url[, opts]) -> id` ---- */
  $$(".api dt > code").forEach((c) => {
    const raw = c.textContent;
    const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    const m = raw.match(/^([^(]+?)(\(.*?\))?(\s*(?:->|→).*)?$/s);
    if (!m) return;
    const [, name, args = "", ret = ""] = m;
    c.innerHTML = `<span class="n">${esc(name)}</span><span class="a">${esc(args)}</span><span class="r">${esc(ret)}</span>`;
  });

  /* ---- language: lua.html (en) <-> lua.zh-CN.html (zh-CN)
     main.js applies the site locale (select / localStorage / navigator). When it lands on
     zh-CN we serve the Chinese page and vice versa. An explicit ?lang= in the URL (the
     toggle links) pins the page on first load. main.js also rewrites <title>/<meta description>
     from the homepage dictionary — put ours back. ---- */
  const docLang = document.documentElement.dataset.docLang || "en";
  const ownTitle = document.title;
  const ownDesc = $('meta[name="description"]') && $('meta[name="description"]').getAttribute("content");
  let firstLocale = true;
  function syncLocale() {
    document.title = ownTitle;
    if (ownDesc) $('meta[name="description"]').setAttribute("content", ownDesc);
    const pinned = firstLocale && new URLSearchParams(location.search).has("lang");
    firstLocale = false;
    if (pinned) return;
    const code = document.documentElement.dataset.lang || "en";
    const wantZh = code === "zh-CN";
    if (wantZh && docLang !== "zh-CN") location.replace("lua.zh-CN.html" + location.hash);
    else if (!wantZh && docLang === "zh-CN") location.replace("lua.html" + location.hash);
  }
  document.addEventListener("lemur:locale", syncLocale);
  // English needs no dictionary, so main.js has already applied it (and fired the event)
  // before this script ran.
  if (document.documentElement.dataset.lang) syncLocale();

  /* ---- collapse the index on phones (it is a <details>) ---- */
  const det = $(".side details");
  if (det && window.matchMedia("(max-width: 860px)").matches) det.removeAttribute("open");

  /* ---- active section in sidebar ---- */
  const links = $$(".side a[href^='#']");
  const byId = new Map(links.map((a) => [a.getAttribute("href").slice(1), a]));
  const targets = [...byId.keys()].map((id) => document.getElementById(id)).filter(Boolean);
  let active = null;
  const setActive = (id) => {
    if (active === id) return;
    active = id;
    links.forEach((a) => a.classList.toggle("is-active", a.getAttribute("href") === "#" + id));
    const a = byId.get(id);
    if (a && window.matchMedia("(min-width: 861px)").matches) {
      const side = $(".side");
      const r = a.getBoundingClientRect(), s = side.getBoundingClientRect();
      if (r.top < s.top || r.bottom > s.bottom) a.scrollIntoView({ block: "nearest" });
    }
  };
  if ("IntersectionObserver" in window && targets.length) {
    const visible = new Map();
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => visible.set(e.target.id, e.isIntersecting ? e.boundingClientRect.top : Infinity));
      let best = null, bestTop = Infinity;
      visible.forEach((top, id) => { if (top < bestTop) { bestTop = top; best = id; } });
      if (best) setActive(best);
    }, { rootMargin: "-80px 0px -60% 0px", threshold: 0 });
    targets.forEach((t) => io.observe(t));
  }

  /* ---- search: filters sidebar entries and API rows ---- */
  const search = $("#docSearch");
  const rows = $$(".api dt").map((dt) => ({ dt, dd: dt.nextElementSibling, text: (dt.textContent + " " + (dt.nextElementSibling ? dt.nextElementSibling.textContent : "")).toLowerCase() }));
  const count = $("#docSearchCount");
  const run = () => {
    const q = (search.value || "").trim().toLowerCase();
    if (!q) {
      rows.forEach((r) => { r.dt.classList.remove("is-hidden"); r.dd && r.dd.classList.remove("is-hidden"); });
      $$(".side li").forEach((li) => li.classList.remove("is-hidden"));
      $$(".article > section").forEach((s) => s.classList.remove("is-hidden"));
      if (count) count.textContent = "";
      return;
    }
    let n = 0;
    const hitSections = new Set();
    rows.forEach((r) => {
      const hit = r.text.includes(q);
      r.dt.classList.toggle("is-hidden", !hit);
      if (r.dd) r.dd.classList.toggle("is-hidden", !hit);
      if (hit) { n++; hitSections.add(r.dt.closest("section")); }
    });
    $$(".article > section").forEach((s) => {
      const own = (s.querySelector("h2") ? s.querySelector("h2").textContent : "").toLowerCase().includes(q);
      s.classList.toggle("is-hidden", !(hitSections.has(s) || own));
    });
    $$(".side li").forEach((li) => {
      const a = li.querySelector("a"); const id = a && a.getAttribute("href").slice(1);
      const sec = id && document.getElementById(id);
      li.classList.toggle("is-hidden", !(sec && !sec.classList.contains("is-hidden")) && !li.textContent.toLowerCase().includes(q));
    });
    if (count) count.textContent = docLang === "zh-CN" ? "匹配 " + n + " 条接口" : n + " matching entries";
  };
  if (search) {
    search.addEventListener("input", run);
    document.addEventListener("keydown", (e) => {
      if (e.key === "/" && document.activeElement !== search && !/input|textarea/i.test(document.activeElement.tagName)) { e.preventDefault(); search.focus(); }
      if (e.key === "Escape" && document.activeElement === search) { search.value = ""; run(); search.blur(); }
    });
  }

  /* ---- back to top ---- */
  const top = $(".totop");
  if (top) {
    const sync = () => top.classList.toggle("is-on", window.scrollY > 900);
    sync(); window.addEventListener("scroll", sync, { passive: true });
    top.addEventListener("click", () => window.scrollTo({ top: 0, behavior: "smooth" }));
  }

  /* ---- deep link: open on load ---- */
  if (location.hash) {
    const el = document.getElementById(location.hash.slice(1));
    if (el) setTimeout(() => el.scrollIntoView({ block: "start" }), 50);
  }
})();
