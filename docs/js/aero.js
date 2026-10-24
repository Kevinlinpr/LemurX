/* Aero landing: language toggle, nav, agent health chip. */
(function () {
  "use strict";

  const dict = {
    zh: {
      nav_anti: "反爬",
      nav_tab: "标签",
      nav_chat: "对话",
      nav_test: "测试",
      eye: "Personal Agent Browser",
      lede: "私人智能体浏览器。对话是主界面，操作发生在你正在看的这个标签里。",
      cta: "下载 Android 测试版",
      privacy: "隐私政策",
      status_wait: "正在探测智能体服务…",
      status_on: "智能体服务在线",
      status_off: "智能体服务暂时不可达",
      p1t: "反爬虫机制",
      p1p: "不注入脚本，不挂 DevTools。文字来自一次无障碍树快照，点击是真的触摸事件。反自动化站点面对的是一只真实的手，不是爬虫。",
      p2t: "只在前台操作",
      p2p: "点击、输入、跳转都发生在你看着的这个标签里。切到后台，这一轮会挂起。回到 Aero 后点继续，它才接着做。不会把页面交给另一台看不见的浏览器。",
      test_t: "Android 对外测试",
      test_p: "当前测试版 0.1.8。不需要账号，打开后可以用游客身份对话。测试版会在打开时检查更新，有新版本就必须更新。",
      s1: "下载 APK，允许 Aero 安装未知来源应用。",
      s2: "打开即可使用。Google 登录和游客模式都可以。",
      s3: "之后的测试包由应用自己提示更新，不必再来这页找安装包。",
      p3t: "Agent 捆绑标签页",
      p3p: "一个标签绑一个 Agent。它读过的页、点过的按钮、交过的任务，都留在这个标签里，不和别的标签串台。",
      p4t: "Agent 对话",
      p4p: "浏览器的主界面就是对话。原生聊天、流式回答；需要上网时，Agent 在同一个标签里打开真网页，你看得见每一步。",
      foot: "Aero · 奥斯曼集团有限公司 Osman Group Limited · 香港",
    },
    en: {
      nav_anti: "Anti-bot",
      nav_tab: "Tabs",
      nav_chat: "Chat",
      nav_test: "Test",
      eye: "Personal Agent Browser",
      lede: "A personal agent browser. The home screen is a conversation, and every action stays in the tab you are looking at.",
      cta: "Download the Android test build",
      privacy: "Privacy policy",
      status_wait: "Checking the agent service…",
      status_on: "Agent service is online",
      status_off: "Agent service is unreachable",
      p1t: "Anti-bot",
      p1p: "No injected scripts. No DevTools attach. Text comes from a one-shot accessibility snapshot. Clicks are real touch events. Anti-automation pages meet a real hand, not a crawler.",
      p2t: "Foreground only",
      p2p: "Clicks, typing and navigation happen in the tab you can see. Leaving the app suspends that turn. Come back and tap Resume to continue. The page is never handed to a browser you cannot see.",
      test_t: "Android external test",
      test_p: "Current test build 0.1.8. No account is required; guest chat works. Test builds check for an update on launch, and a newer build is mandatory.",
      s1: "Download the APK and allow Aero to install unknown apps.",
      s2: "Open it. Google sign-in and guest mode both work.",
      s3: "Later test builds update from inside the app. You do not need to come back here for the next package.",
      p3t: "Agent bound to a tab",
      p3p: "One tab, one agent. Pages it read, buttons it tapped, tasks it ran stay in that tab. Threads do not leak.",
      p4t: "Agent conversation",
      p4p: "The home screen of the browser is a conversation. Native chat, streamed answers. When it needs the web, the agent opens real pages in the same tab — and you see every step.",
      foot: "Aero · Osman Group Limited · Hong Kong",
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
