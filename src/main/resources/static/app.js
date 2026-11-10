// Margin works without this file: every action is a plain form. This adds motion,
// instant ticking, toasts, filtering, a theme switch and keyboard shortcuts.
(() => {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const COLORS = ["#ffd23f", "#ff6fb0", "#6ee7a0", "#5cc8ff", "#ff9f4a", "#b18cff", "#5b3df5"];

  /* ---------- confetti ---------- */
  function confetti(x, y, n = 26) {
    if (reduced) return;
    for (let i = 0; i < n; i++) {
      const p = document.createElement("i");
      p.className = "confetti";
      const a = Math.random() * Math.PI * 2, d = 60 + Math.random() * 120;
      p.style.left = x + "px";
      p.style.top = y + "px";
      p.style.background = COLORS[i % COLORS.length];
      p.style.setProperty("--dx", Math.cos(a) * d + "px");
      p.style.setProperty("--dy", Math.sin(a) * d + 80 + "px");
      p.style.setProperty("--rot", (Math.random() * 720 - 360) + "deg");
      p.style.animationDuration = (0.7 + Math.random() * 0.6) + "s";
      document.body.appendChild(p);
      p.addEventListener("animationend", () => p.remove());
    }
  }
  const burstFrom = el => { const r = el.getBoundingClientRect(); confetti(r.left + r.width / 2, r.top + r.height / 2); };

  /* ---------- toasts ---------- */
  const shelf = $(".toasts") || document.body.appendChild(Object.assign(document.createElement("div"), { className: "toasts" }));
  function dismiss(t) { t.classList.add("leaving"); t.addEventListener("animationend", () => t.remove(), { once: true }); }
  function toast(text) {
    if (!text) return;
    const t = document.createElement("div");
    t.className = "toast";
    t.setAttribute("role", "status");
    t.textContent = text;
    shelf.appendChild(t);
    setTimeout(() => dismiss(t), 4200);
  }
  $$(".toast", shelf).forEach(t => setTimeout(() => dismiss(t), 4800));

  /* ---------- buttons that take a while (AI, Classroom) say so ---------- */
  document.addEventListener("submit", e => {
    const b = e.target.querySelector("button[data-busy]");
    if (b && !e.defaultPrevented) { b.textContent = b.dataset.busy; b.classList.add("busy-label"); }
  });

  /* ---------- confirm before deleting ---------- */
  document.addEventListener("submit", e => {
    const msg = e.target.getAttribute("data-confirm");
    if (msg && !confirm(msg)) e.preventDefault();
  }, true);

  /* ---------- theme ---------- */
  const root = document.documentElement;
  function setTheme(next) {
    const go = () => { root.dataset.theme = next; try { localStorage.setItem("theme", next); } catch (e) { /* ignore */ } };
    document.startViewTransition && !reduced ? document.startViewTransition(go) : go();
  }
  const flipTheme = () => setTheme(root.dataset.theme === "dark" ? "light" : "dark");
  $$("[data-theme-toggle]").forEach(b => b.addEventListener("click", flipTheme));

  /* ---------- sliding nav highlight ---------- */
  const nav = $(".nav");
  const current = nav && $("a[aria-current=page]", nav);
  if (nav && current) {
    const blob = document.createElement("span");
    blob.className = "nav-blob";
    nav.prepend(blob);
    const place = el => { blob.style.width = el.offsetWidth + "px"; blob.style.transform = `translateX(${el.offsetLeft}px)`; };
    place(current);
    requestAnimationFrame(() => nav.classList.add("ready"));
    $$("a", nav).forEach(a => {
      a.addEventListener("mouseenter", () => place(a));
      a.addEventListener("focus", () => place(a));
    });
    nav.addEventListener("mouseleave", () => place(current));
    addEventListener("resize", () => place(current));
  }

  /* ---------- numbers count up, rings fill ---------- */
  const fmt = m => { const h = Math.floor(m / 60), r = m % 60; return h === 0 ? r + " min" : r === 0 ? h + " h" : `${h} h ${r} min`; };
  $$("[data-count]").forEach(el => {
    const end = Number(el.dataset.count), minutes = el.hasAttribute("data-minutes");
    if (reduced || !end) return;
    const t0 = performance.now(), dur = 900;
    const step = now => {
      const k = Math.min(1, (now - t0) / dur), v = Math.round(end * (1 - Math.pow(1 - k, 3)));
      el.textContent = minutes ? fmt(v) : v;
      if (k < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  });
  requestAnimationFrame(() => $$(".ring[data-v]").forEach(r => r.style.setProperty("--v", r.dataset.v)));

  /* ---------- instant actions: tick tasks and chapters, resolve study blocks ---------- */
  async function send(form) {
    const res = await fetch(form.action, {
      method: "POST",
      body: new URLSearchParams(new FormData(form)),
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
    });
    if (!res.ok) throw new Error(res.status);
    const page = new DOMParser().parseFromString(await res.text(), "text/html");
    const msg = page.querySelector(".toast");
    return msg ? msg.textContent.trim() : "";
  }

  document.addEventListener("submit", async e => {
    const form = e.target;
    const kind = form.dataset.async;
    if (!kind || e.defaultPrevented) return;
    e.preventDefault();
    const button = $("button", form);
    button && button.classList.add("busy");
    try {
      const msg = await send(form);
      const next = $("input[name=done]", form);
      if (kind === "task") {
        const on = next.value === "1";
        next.value = on ? "0" : "1";
        button.classList.toggle("on", on);
        const row = form.closest(".row");
        row.classList.toggle("is-done", on);
        const side = $(".side", row);
        if (side && on) { side.textContent = "Done"; side.classList.remove("overdue"); }
        if (on) burstFrom(button);
        toast(msg);
      } else if (kind === "chapter") {
        const on = next.value === "1";
        next.value = on ? "0" : "1";
        button.classList.toggle("on", on);
        form.closest("li").classList.toggle("done", on);
        const exam = form.closest(".exam"), all = $$(".chapters li", exam), done = all.filter(l => l.classList.contains("done")).length;
        const bar = $(".meter > span", exam);
        if (bar) bar.style.width = Math.round(done / all.length * 100) + "%";
        const covered = $("[data-covered]", exam);
        if (covered) covered.textContent = `${done} of ${all.length} chapters covered`;
        if (on) burstFrom(button);
        if (on && done === all.length) { burstFrom($(".days-left", exam)); toast("Every chapter covered. Nice work."); }
      } else if (kind === "session") {
        const action = form.getAttribute("action"), done = action.endsWith("/done");
        const base = action.replace(/\/(done|missed)$/, "");
        $$(`[popover] form[action^="${base}/"]`).forEach(f => {
          const pop = f.closest("[popover]"), block = $(`[popovertarget="${pop.id}"].block`);
          if (block) { block.classList.remove("done", "missed"); block.classList.add(done ? "done" : "missed"); }
          if (pop !== form.closest("[popover]")) $(".actions", pop)?.remove();
        });
        const slip = form.closest("[popover]");
        if (slip) { $(".actions", slip)?.remove(); slip.hidePopover?.(); }
        const item = form.closest(".followup li");
        if (item) { item.style.opacity = 0; item.style.transform = "translateX(20px)"; setTimeout(() => { const ul = item.parentElement; item.remove(); if (!ul.children.length) ul.closest(".followup").remove(); }, 300); }
        if (done) burstFrom(button);
        toast(msg);
      }
    } catch (err) {
      form.removeAttribute("data-async");
      form.submit();   // fall back to a normal page load
    } finally {
      button && button.classList.remove("busy");
    }
  });

  /* ---------- on narrow screens, scroll the week to today ---------- */
  $$(".week").forEach(w => {
    const today = $(".week-day-head.today", w);
    if (today && w.scrollWidth > w.clientWidth) w.scrollLeft = today.offsetLeft - 60;
  });

  /* ---------- SRM import: new captcha, read a saved page ---------- */
  $$("[data-captcha-refresh]").forEach(b => b.addEventListener("click", () => {
    const img = $("[data-captcha]");
    img.src = "/import/captcha?" + Date.now();
  }));
  $$("input[type=file][data-load-into]").forEach(input => input.addEventListener("change", async () => {
    const file = input.files[0];
    if (file) document.getElementById(input.dataset.loadInto).value = await file.text();
  }));

  /* ---------- datesheet upload: read the file here and send it as base64 ---------- */
  $$("input[type=file][data-file-to]").forEach(input => input.addEventListener("change", () => {
    const file = input.files[0], key = input.dataset.fileTo;
    if (!file) return;
    if (file.size > 8 * 1024 * 1024) { toast("That file is over 8 MB. Use a smaller screenshot or PDF."); input.value = ""; return; }
    const reader = new FileReader();
    reader.onload = () => {
      const url = String(reader.result);
      document.getElementById(key + "-data").value = url.slice(url.indexOf(",") + 1);
      document.getElementById(key + "-mime").value = file.type || (file.name.toLowerCase().endsWith(".pdf") ? "application/pdf" : "image/jpeg");
    };
    reader.readAsDataURL(file);
  }));

  /* ---------- filter the task list ---------- */
  const search = $("[data-filter]");
  if (search) {
    let subject = "";
    const chips = $$(".chip[data-subject]");
    const apply = () => {
      const q = search.value.trim().toLowerCase();
      $$(".rows > li[data-title]").forEach(li => {
        const hit = (!q || li.dataset.title.includes(q)) && (!subject || li.dataset.subject === subject);
        li.classList.toggle("hidden", !hit);
      });
      $$(".group-head").forEach(h => {
        const list = h.nextElementSibling;
        const shown = list ? $$(":scope > li:not(.hidden)", list).length : 0;
        h.classList.toggle("hidden", shown === 0);
        if (list) list.style.display = shown ? "" : "none";
        const c = $(".count", h); if (c) c.textContent = shown;
      });
    };
    search.addEventListener("input", apply);
    chips.forEach(c => c.addEventListener("click", () => {
      subject = c.dataset.subject;
      chips.forEach(x => x.setAttribute("aria-pressed", String(x === c)));
      apply();
    }));
  }

  /* ---------- keyboard shortcuts ---------- */
  const links = nav ? $$("a", nav) : [];
  const help = document.createElement("dialog");
  help.className = "help";
  help.innerHTML = `<h2>Keyboard shortcuts</h2><dl>
    <dt><kbd>1</kbd> to <kbd>7</kbd></dt><dd>Go to a page in the menu</dd>
    <dt><kbd>/</kbd></dt><dd>Filter tasks</dd>
    <dt><kbd>n</kbd></dt><dd>New task, subject or exam</dd>
    <dt><kbd>d</kbd></dt><dd>Switch light and dark</dd>
    <dt><kbd>?</kbd></dt><dd>Show this list</dd></dl>
    <form method="dialog"><button class="button small">Close</button></form>`;
  document.body.appendChild(help);
  document.addEventListener("keydown", e => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const tag = (e.target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "textarea" || tag === "select" || e.target.isContentEditable) {
      if (e.key === "Escape") e.target.blur();
      return;
    }
    if (/^[1-7]$/.test(e.key) && links[e.key - 1]) { links[e.key - 1].click(); }
    else if (e.key === "/" && search) { e.preventDefault(); search.focus(); }
    else if (e.key === "n") { const f = $("aside form.sheet input[type=text]"); if (f) { e.preventDefault(); f.focus(); f.scrollIntoView({ block: "center", behavior: reduced ? "auto" : "smooth" }); } }
    else if (e.key === "d") { flipTheme(); }
    else if (e.key === "?") { help.showModal(); }
  });
})();
