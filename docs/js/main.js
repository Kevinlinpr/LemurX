/* Lemur Browser site — no dependencies. */
(function () {
  "use strict";

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ------------------------------------------------------------ nav */
  const nav = $("#nav");
  const navLinks = $("#navLinks");
  const navToggle = $("#navToggle");

  const onScroll = () => nav.classList.toggle("is-scrolled", window.scrollY > 8);
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });

  navToggle.addEventListener("click", () => {
    const open = navLinks.classList.toggle("is-open");
    navToggle.setAttribute("aria-expanded", String(open));
  });
  $$("a", navLinks).forEach((a) =>
    a.addEventListener("click", () => {
      navLinks.classList.remove("is-open");
      navToggle.setAttribute("aria-expanded", "false");
    })
  );

  /* ------------------------------------------------------------ Lua highlighter
     Small regex tokenizer: comments, strings (incl. long brackets), numbers,
     keywords, lemur.* chains, calls. Output is escaped HTML. */
  const KEYWORDS = new Set(("and break do else elseif end false for function goto if in local nil not or " +
    "repeat return then true until while").split(" "));
  const TOKEN = /(--\[\[[\s\S]*?\]\]|--[^\n]*)|(\[\[[\s\S]*?\]\]|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(\b0x[0-9a-fA-F]+\b|\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|(\blemur(?:\.[A-Za-z_][\w]*)+)|([A-Za-z_]\w*)(?=\s*[({"'\[])|([A-Za-z_]\w*)/g;
  const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

  function highlightLua(src) {
    let out = "";
    let last = 0;
    src.replace(TOKEN, (m, cm, str, num, lem, fn, word, offset) => {
      out += esc(src.slice(last, offset));
      last = offset + m.length;
      if (cm) out += `<span class="tok-cm">${esc(m)}</span>`;
      else if (str) out += `<span class="tok-str">${esc(m)}</span>`;
      else if (num) out += `<span class="tok-num">${esc(m)}</span>`;
      else if (lem) out += `<span class="tok-lem">${esc(m)}</span>`;
      else if (fn) out += KEYWORDS.has(fn) ? `<span class="tok-kw">${fn}</span>` : `<span class="tok-fn">${fn}</span>`;
      else if (word) out += KEYWORDS.has(word) ? `<span class="tok-kw">${word}</span>` : esc(word);
      return m;
    });
    return out + esc(src.slice(last));
  }

  $$("code.lang-lua").forEach((el) => {
    if (el.id === "typewriter") return;
    el.innerHTML = highlightLua(el.textContent);
  });

  /* ------------------------------------------------------------ hero typewriter */
  const HELLO = [
    'lemur.tabs.on("loaded", function(ev)',
    '  lemur.toast("Hello, " .. ev.title)',
    "end)",
    "",
    'lemur.ui.render("toolbar.end", {',
    '  type = "button", text = "λ",',
    "  onClick = function() lemur.help() end,",
    "})",
  ].join("\n");

  const tw = $("#typewriter");
  if (tw) {
    if (reduceMotion) {
      tw.innerHTML = highlightLua(HELLO);
    } else {
      let i = 0;
      const step = () => {
        i = Math.min(HELLO.length, i + 1);
        tw.innerHTML = highlightLua(HELLO.slice(0, i));
        if (i < HELLO.length) {
          const ch = HELLO[i - 1];
          setTimeout(step, ch === "\n" ? 140 : ch === " " ? 24 : 18 + Math.random() * 30);
        }
      };
      setTimeout(step, 500);
    }
  }

  /* ------------------------------------------------------------ code tabs */
  const tabs = $("#codeTabs");
  if (tabs) {
    const buttons = $$('[role="tab"]', tabs);
    const panels = $$('[role="tabpanel"]', tabs);
    const select = (name) => {
      buttons.forEach((b) => b.setAttribute("aria-selected", String(b.dataset.tab === name)));
      panels.forEach((p) => (p.hidden = p.dataset.panel !== name));
    };
    buttons.forEach((b, idx) => {
      b.addEventListener("click", () => select(b.dataset.tab));
      b.addEventListener("keydown", (e) => {
        if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
        const next = buttons[(idx + (e.key === "ArrowRight" ? 1 : buttons.length - 1)) % buttons.length];
        next.focus();
        select(next.dataset.tab);
      });
    });
  }

  /* ------------------------------------------------------------ i18n
     English is baked into the HTML. Other locales live in i18n/<code>.json
     as key -> HTML fragment; elements carry data-i18n="<key>". */
  const LOCALES = {
    en: "English",
    id: "Bahasa Indonesia",
    "zh-CN": "简体中文",
    "pt-BR": "Português (Brasil)",
    "es-MX": "Español (México)",
    fil: "Filipino",
    hi: "हिन्दी",
    vi: "Tiếng Việt",
    bn: "বাংলা",
    ms: "Bahasa Melayu",
  };
  const STORAGE_KEY = "lemur.lang";
  const original = new Map(); // element -> English innerHTML / attr
  let dict = {}; // active dictionary (empty for English)

  const t = (key, fallback) => dict[key] || fallback;

  function detectLocale() {
    const fromUrl = new URLSearchParams(location.search).get("lang");
    if (fromUrl && LOCALES[fromUrl]) return fromUrl;
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved && LOCALES[saved]) return saved;
    } catch (_) {}
    for (const raw of navigator.languages || [navigator.language || "en"]) {
      const l = raw.toLowerCase();
      if (l.startsWith("zh")) return "zh-CN";
      if (l.startsWith("pt")) return "pt-BR";
      if (l.startsWith("es")) return "es-MX";
      if (l.startsWith("id") || l.startsWith("in")) return "id";
      if (l.startsWith("fil") || l.startsWith("tl")) return "fil";
      if (l.startsWith("hi")) return "hi";
      if (l.startsWith("vi")) return "vi";
      if (l.startsWith("bn")) return "bn";
      if (l.startsWith("ms")) return "ms";
      if (l.startsWith("en")) return "en";
    }
    return "en";
  }

  function remember(el, prop) {
    if (!original.has(el)) original.set(el, {});
    const slot = original.get(el);
    if (!(prop in slot)) slot[prop] = prop === "html" ? el.innerHTML : el.getAttribute(prop);
    return slot[prop];
  }

  function applyDict(code) {
    $$("[data-i18n]").forEach((el) => {
      const en = remember(el, "html");
      const key = el.dataset.i18n;
      el.innerHTML = dict[key] || en;
    });
    $$("[data-i18n-alt]").forEach((el) => {
      const en = remember(el, "alt");
      el.setAttribute("alt", dict[el.dataset.i18nAlt] || en);
    });
    $$("[data-i18n-aria]").forEach((el) => {
      const en = remember(el, "aria-label");
      el.setAttribute("aria-label", dict[el.dataset.i18nAria] || en);
    });
    const title = $("title");
    if (title) title.textContent = t("meta.title", remember(title, "html"));
    const desc = $('meta[name="description"]');
    if (desc) desc.setAttribute("content", t("meta.description", remember(desc, "content")));
    document.documentElement.lang = code;
    document.documentElement.dataset.lang = code;
    // The typewriter and highlighted code blocks are code — left in place.
    document.dispatchEvent(new CustomEvent("lemur:locale"));
  }

  async function setLocale(code, persist) {
    if (!LOCALES[code]) code = "en";
    if (code === "en") {
      dict = {};
    } else {
      try {
        const res = await fetch(`i18n/${code}.json`, { cache: "no-cache" });
        dict = res.ok ? await res.json() : {};
      } catch (_) {
        dict = {};
        code = "en";
      }
    }
    applyDict(code);
    const sel = $("#langSelect");
    if (sel) sel.value = code;
    if (persist) {
      try { localStorage.setItem(STORAGE_KEY, code); } catch (_) {}
    }
  }

  (function initLangSwitcher() {
    const sel = $("#langSelect");
    if (!sel) return;
    Object.entries(LOCALES).forEach(([code, name]) => {
      const opt = document.createElement("option");
      opt.value = code;
      opt.textContent = name;
      sel.appendChild(opt);
    });
    sel.addEventListener("change", () => setLocale(sel.value, true));
    setLocale(detectLocale(), false);
  })();

  /* ------------------------------------------------------------ copy buttons */
  $$(".copy").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const code = $("pre code", btn.closest(".code"));
      try {
        await navigator.clipboard.writeText(code.textContent);
        btn.textContent = t("ui.copied", "Copied");
        btn.classList.add("is-done");
      } catch (_) {
        btn.textContent = t("ui.selectCopy", "Select & copy");
        const range = document.createRange();
        range.selectNodeContents(code);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
      setTimeout(() => {
        btn.textContent = t("ui.copy", "Copy");
        btn.classList.remove("is-done");
      }, 1600);
    });
  });

  /* ------------------------------------------------------------ mobile "show all" */
  const mq = window.matchMedia("(max-width: 860px)");
  $$("[data-more]").forEach((box) => {
    const items = [...box.children].filter((el) => !el.classList.contains("more-btn") && !el.classList.contains("compare__row--head"));
    const keep = Number(box.dataset.more);
    if (items.length <= keep) return;
    const btn = document.createElement("button");
    btn.type = "button"; btn.className = "more-btn";
    const label = document.createElement("span");
    btn.append(label);
    btn.insertAdjacentHTML("beforeend", '<svg class="ico" aria-hidden="true"><use href="#i-chevron-down"/></svg>');
    box.append(btn);
    const sync = () => {
      const collapsed = box.classList.contains("is-collapsed");
      label.textContent = collapsed ? t("ui.showAll", "Show all {n}").replace("{n}", items.length) : t("ui.showLess", "Show less");
      btn.setAttribute("aria-expanded", String(!collapsed));
    };
    const render = () => {
      const collapsed = box.classList.contains("is-collapsed");
      items.forEach((el, i) => el.classList.toggle("is-hidden", collapsed && i >= keep));
      sync();
    };
    const apply = () => { box.classList.toggle("is-collapsed", mq.matches); render(); };
    btn.addEventListener("click", () => { box.classList.toggle("is-collapsed"); render(); if (box.classList.contains("is-collapsed")) box.scrollIntoView({ block: "nearest" }); });
    mq.addEventListener("change", apply);
    document.addEventListener("lemur:locale", sync);
    apply();
  });

  /* ------------------------------------------------------------ reveal on scroll */
  const reveals = $$(".reveal");
  if (reduceMotion || !("IntersectionObserver" in window)) {
    reveals.forEach((el) => el.classList.add("is-in"));
  } else {
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((e) => {
          if (e.isIntersecting) {
            e.target.classList.add("is-in");
            io.unobserve(e.target);
          }
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.08 }
    );
    reveals.forEach((el) => io.observe(el));
  }
})();
