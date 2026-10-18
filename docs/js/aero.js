/* Aero landing: language toggle, nav, agent health chip. */
(function () {
  "use strict";

  const dict = {
    zh: {
      nav_anti: "反爬",
      nav_muse: "长任务",
      nav_tab: "标签",
      nav_chat: "对话",
      eye: "Personal Agent Browser",
      lede: "私人智能体浏览器。核心就四件事。",
      status_wait: "正在探测智能体服务…",
      status_on: "智能体服务在线",
      status_off: "智能体服务暂时不可达",
      p1t: "反爬虫机制",
      p1p: "不注入脚本，不挂 DevTools。文字来自一次无障碍树快照，点击是真的触摸事件。反自动化站点面对的是一只真实的手，不是爬虫。",
      p2t: "长任务调度",
      p2p: "盯站点、做计划、连续操作，都可以作为长任务跑下去。浏览器退到后台时，任务可以接着跑；回到前台，你仍然接得住。",
      p3t: "Agent 捆绑标签页",
      p3p: "一个标签绑一个 Agent。它读过的页、点过的按钮、交过的任务，都留在这个标签里，不和别的标签串台。",
      p4t: "Agent 对话",
      p4p: "浏览器的主界面就是对话。原生聊天、流式回答；需要上网时，Agent 在同一个标签里打开真网页，你看得见每一步。",
      foot: "Aero · Osman Group · 官方 Chromium 稳定版",
    },
    en: {
      nav_anti: "Anti-bot",
      nav_muse: "Long tasks",
      nav_tab: "Tabs",
      nav_chat: "Chat",
      eye: "Personal Agent Browser",
      lede: "A personal agent browser. Four things, said plainly.",
      status_wait: "Checking the agent service…",
      status_on: "Agent service is online",
      status_off: "Agent service is unreachable",
      p1t: "Anti-bot",
      p1p: "No injected scripts. No DevTools attach. Text comes from a one-shot accessibility snapshot. Clicks are real touch events. Anti-automation pages meet a real hand, not a crawler.",
      p2t: "Long-task scheduling",
      p2p: "Watch a site, make a plan, run a chain of actions as a long task. If the browser backgrounds, the task keeps going. You still take over in the foreground.",
      p3t: "Agent bound to a tab",
      p3p: "One tab, one agent. Pages it read, buttons it tapped, tasks it ran stay in that tab. Threads do not leak.",
      p4t: "Agent conversation",
      p4p: "The home screen of the browser is a conversation. Native chat, streamed answers. When it needs the web, the agent opens real pages in the same tab — and you see every step.",
      foot: "Aero · Osman Group · official Chromium stable",
    },
  };

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll("[data-i]"));
  const nav = $("#nav");
  const links = $("#navLinks");
  const toggle = $("#navToggle");
  const select = $("#lang");

  const apply = (lang) => {
    const pack = dict[lang] || dict.zh;
    document.documentElement.lang = lang === "en" ? "en" : "zh-CN";
    $$().forEach((el) => {
      const key = el.getAttribute("data-i");
      if (key && pack[key]) el.textContent = pack[key];
    });
    const chip = $("#status");
    if (chip && chip.dataset.ok) {
      chip.textContent = pack[chip.dataset.ok === "1" ? "status_on" : "status_off"];
    }
    localStorage.setItem("aero-lang", lang);
  };

  const saved = localStorage.getItem("aero-lang");
  const start =
    saved || ((navigator.language || "").toLowerCase().startsWith("zh") ? "zh" : "en");
  if (select) select.value = start;
  apply(start);
  select?.addEventListener("change", () => apply(select.value));

  const onScroll = () => nav?.classList.toggle("is-scrolled", window.scrollY > 8);
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });
  toggle?.addEventListener("click", () => {
    const open = links.classList.toggle("is-open");
    toggle.setAttribute("aria-expanded", String(open));
  });
  links?.querySelectorAll("a").forEach((a) =>
    a.addEventListener("click", () => {
      links.classList.remove("is-open");
      toggle?.setAttribute("aria-expanded", "false");
    }),
  );

  const chip = $("#status");
  if (chip) {
    fetch("/api/health", { cache: "no-store" })
      .then((r) => r.json())
      .then((j) => {
        const on = j && j.ok !== false && !j.disabled;
        chip.dataset.ok = on ? "1" : "0";
        chip.textContent = (dict[select?.value || start] || dict.zh)[on ? "status_on" : "status_off"];
      })
      .catch(() => {
        chip.dataset.ok = "0";
        chip.textContent = (dict[select?.value || start] || dict.zh).status_off;
      });
  }
})();
