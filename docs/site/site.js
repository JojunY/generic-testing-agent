/* gta introduction. One layer of named marks lives on the page.
   Each section borrows them while it fills the screen. Every string comes from #copy.
   The marks are scripted. They do not call a model and they do not run gtaw. */
(() => {
  'use strict';
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
  const make = (tag, cls, text) => {
    const el = document.createElement(tag);
    if (cls) el.className = cls;
    if (text != null) el.textContent = text;
    return el;
  };
  const copyData = $('#copy');
  if (!copyData) return;
  const T = JSON.parse(copyData.textContent);

  const nav = $('.top');
  const navLinks = $$('.links a[href^="#"]');
  const onScroll = () => {
    if (nav) nav.classList.toggle('scrolled', scrollY > 8);
    let current = '';
    for (const a of navLinks) {
      const el = document.querySelector(a.getAttribute('href'));
      if (!el) continue;
      if (el.getBoundingClientRect().top < 120) current = a.getAttribute('href');
    }
    navLinks.forEach((a) => a.toggleAttribute('aria-current', a.getAttribute('href') === current));
  };
  addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  const reveals = $$('.reveal');
  if (reduce || !('IntersectionObserver' in window)) reveals.forEach((el) => el.classList.add('in'));
  else {
    const io = new IntersectionObserver((es) => es.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
    }), { rootMargin: '0px 0px -8% 0px' });
    reveals.forEach((el) => io.observe(el));
  }

  $$('[data-copy]').forEach((b) => b.addEventListener('click', async () => {
    const label = b.textContent;
    try { await navigator.clipboard.writeText(b.dataset.copy); b.textContent = b.dataset.done || '✓'; }
    catch (_) { b.textContent = '—'; }
    setTimeout(() => { b.textContent = label; }, 1600);
  }));

  const pointer = { x: 0, y: 0, in: false };
  addEventListener('pointermove', (e) => {
    if (e.pointerType !== 'mouse') return;
    pointer.x = e.clientX; pointer.y = e.clientY; pointer.in = true;
  }, { passive: true });
  document.addEventListener('pointerleave', () => { pointer.in = false; });

  const line = (segments, cls) => {
    const el = make('span', `ln${cls ? ` ${cls}` : ''}`);
    (typeof segments === 'string' ? [['', segments]] : segments).forEach(([c, text]) => el.appendChild(make('span', c || '', text)));
    return el;
  };
  const textRect = (el) => {
    const r = document.createRange();
    r.selectNodeContents(el);
    const b = r.getBoundingClientRect();
    if (b.width || b.height) return b;
    const e = el.getBoundingClientRect();
    return { left: e.left, top: e.top, width: 0, height: e.height };
  };

  const layer = make('div', 'agents');
  layer.setAttribute('aria-hidden', 'true');
  document.body.appendChild(layer);
  const MARK = '<svg class="arrow" width="18" height="18" viewBox="0 0 18 18"><rect x="1" y="1" width="7" height="7"/><path d="M8 8 L16 16" stroke-width="1.4" stroke-linecap="square"/></svg>';
  const BEAM = 'M8.5 2h3M12.5 2h3M12 2v20M8.5 22h3M12.5 22h3';
  const IBEAM = `<svg class="ibeam" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke-linecap="square"><path d="${BEAM}" stroke="#efe6d6" stroke-width="4.4"/><path d="${BEAM}" style="stroke:var(--c)" stroke-width="2"/></svg>`;
  const HOT = { arrow: [4.5, 4.5], ibeam: [12, 12] };
  const agents = {};
  Object.entries(T.agents).forEach(([id, a], i) => {
    const el = make('div', 'agent');
    el.dataset.agent = id;
    el.style.setProperty('--c', a.color);
    el.innerHTML = `${MARK}${IBEAM}<span class="press"></span><span class="tag">${esc(a.name)}</span>`;
    layer.appendChild(el);
    agents[id] = { id, el, x: 0, y: 0, vx: 0, vy: 0, hold: null, tool: 'arrow', on: false, placed: false, phase: i * 1.9, room: 36 + a.name.length * 6.4 };
  });
  const tool = (id, t) => { agents[id].tool = t; agents[id].el.dataset.tool = t; };
  const click = (id) => { const el = agents[id].el; el.classList.remove('click'); void el.offsetWidth; el.classList.add('click'); };

  let obstacles = [];
  let obstaclesAt = 0;
  const refreshObstacles = (t) => {
    if (t - obstaclesAt < 200) return;
    obstaclesAt = t;
    obstacles = $$('a, button, summary').map((el) => el.getBoundingClientRect())
      .filter((r) => r.width > 2 && r.height > 2 && r.bottom > 0 && r.top < innerHeight);
  };
  const clearOf = (x, y) => {
    const pad = 18;
    for (let pass = 0; pass < 2; pass++) {
      for (const r of obstacles) {
        const left = r.left - pad, right = r.right + pad, top = r.top - pad, bottom = r.bottom + pad;
        if (x > left && x < right && y > top && y < bottom) {
          const dl = x - left, dr = right - x, dt = y - top, db = bottom - y;
          const m = Math.min(dl, dr, dt, db);
          if (m === dl) x = left;
          else if (m === dr) x = right;
          else if (m === dt) y = top;
          else y = bottom;
        }
      }
    }
    return [x, y];
  };

  const targetOf = (a, t) => {
    const h = a.hold;
    if (!h) return null;
    let x, y;
    const r = h.text ? textRect(h.el) : h.el.getBoundingClientRect();
    if (r.bottom < 4 || r.top > innerHeight - 4 || r.right < 0 || r.left > innerWidth) return null;
    const over = pointer.in && pointer.x > r.left && pointer.x < r.left + r.width && pointer.y > r.top && pointer.y < r.top + r.height;
    if (h.follow && over) { x = pointer.x + h.dx; y = pointer.y + h.dy; }
    else { x = r.left + r.width * h.fx + h.dx; y = r.top + r.height * h.fy + h.dy; }
    if (h.wander && !reduce) { x += Math.sin(t / 900 + a.phase) * 6; y += Math.cos(t / 1100 + a.phase * 1.3) * 4; }
    if (h.shy && pointer.in) {
      const dx = x - pointer.x, dy = y - pointer.y, d = Math.hypot(dx, dy);
      if (d < 72) { x += (dx / (d || 1)) * (72 - d); y += (dy / (d || 1)) * (72 - d); }
    }
    refreshObstacles(t);
    [x, y] = clearOf(x, y);
    x = Math.max(8, Math.min(innerWidth - (h.bare ? 24 : a.room), x));
    y = Math.max(8, Math.min(innerHeight - 28, y));
    return [x + scrollX, y + scrollY];
  };
  let last = performance.now();
  let ticking = false;
  const frame = (t) => {
    const dt = Math.min((t - last) / 1000, 1 / 30);
    last = t;
    for (const a of Object.values(agents)) {
      const tg = reduce ? null : targetOf(a, t);
      if (!!tg !== a.on) { a.on = !!tg; a.el.classList.toggle('on', a.on); }
      if (!tg) continue;
      a.el.classList.toggle('bare', !!(a.hold && a.hold.bare));
      if (!a.placed) { [a.x, a.y] = a.hold.from ? a.hold.from() : [tg[0] + 80, tg[1] + 140]; a.placed = true; }
      const k = a.hold.stiff || 150, c = 2 * Math.sqrt(k) * 0.82;
      a.vx += (k * (tg[0] - a.x) - c * a.vx) * dt;
      a.vy += (k * (tg[1] - a.y) - c * a.vy) * dt;
      a.x += a.vx * dt;
      a.y += a.vy * dt;
      const [hx, hy] = HOT[a.tool];
      a.el.style.transform = `translate3d(${(a.x - scrollX - hx).toFixed(1)}px, ${(a.y - scrollY - hy).toFixed(1)}px, 0)`;
    }
  };
  function startLoop() {
    if (reduce || ticking || document.hidden) return;
    ticking = true;
    last = performance.now();
    requestAnimationFrame(pump);
  }
  function pump(t) {
    if (document.hidden) { ticking = false; return; }
    frame(t);
    if (!Object.values(agents).some((a) => a.hold)) { ticking = false; return; }
    requestAnimationFrame(pump);
  }

  const scenes = [];
  let active = null;
  class Scene {
    constructor(el) { this.el = el; this.holds = {}; this.on = false; this.started = false; this.play = async () => {}; scenes.push(this); }
    hold(id, el, o = {}) {
      this.holds[id] = { el, fx: o.fx ?? 0.5, fy: o.fy ?? 0.5, dx: o.dx || 0, dy: o.dy || 0, text: !!o.text, wander: !!o.wander, shy: !!o.shy, follow: !!o.follow, stiff: o.stiff, from: o.from, bare: !!o.bare };
      if (this.on && agents[id]) agents[id].hold = this.holds[id];
      startLoop();
    }
    free(id) { delete this.holds[id]; if (this.on && agents[id]) agents[id].hold = null; }
    async wait(ms) {
      if (reduce) return;
      await sleep(ms);
      while (!this.on) await sleep(150);
    }
  }
  const pick = () => {
    let best = null, score = 0;
    for (const s of scenes) {
      const r = s.el.getBoundingClientRect();
      const seen = (Math.min(r.bottom, innerHeight) - Math.max(r.top, 0)) / Math.min(innerHeight, r.height);
      if (seen > score) { score = seen; best = s; }
    }
    if (score < 0.45) best = null;
    if (best === active) { startLoop(); return; }
    if (active) active.on = false;
    active = best;
    for (const a of Object.values(agents)) a.hold = (active && active.holds[a.id]) || null;
    if (active) {
      active.on = true;
      if (!active.started) { active.started = true; active.play(); }
    }
    startLoop();
  };
  let picking = false;
  const schedulePick = () => { if (!picking) { picking = true; requestAnimationFrame(() => { picking = false; pick(); }); } };

  const hero = $('.hero');
  if (hero) {
    const S = new Scene(hero);
    const ai = $('h1 .ai', hero), det = $('h1 .det', hero);
    const cjk = /[\u3400-\u9fff]/.test(ai.textContent);
    const chars = [];
    const text = ai.textContent;
    ai.textContent = '';
    for (const tok of text.match(/[A-Za-z0-9.,'’!?:-]+|\s+|[^\sA-Za-z0-9]/g) || []) {
      if (/^\s+$/.test(tok)) { const s = make('span', 'ch', ' '); ai.appendChild(s); chars.push(s); continue; }
      const w = make('span', 'w');
      for (const ch of tok) { const s = make('span', 'ch', ch); w.appendChild(s); chars.push(s); }
      ai.appendChild(w);
    }
    ai.classList.add('split');
    const sel = make('div', 'sel');
    sel.innerHTML = '<i></i><i></i><i></i><i></i><b></b>';
    sel.lastChild.textContent = cjk ? 'analyst · 在读' : 'analyst · reading';
    hero.appendChild(sel);
    const placeSel = (p) => {
      const hr = hero.getBoundingClientRect(), r = textRect(ai), pad = 12;
      const x = r.left - hr.left - pad, y = r.top - hr.top - pad / 2;
      const w = (r.width + pad * 2) * p, h = (r.height + pad) * p;
      Object.assign(sel.style, { left: `${x}px`, top: `${y}px`, width: `${w}px`, height: `${h}px` });
    };
    const corner = (fx, fy) => () => [scrollX + innerWidth * fx, scrollY + innerHeight * fy];
    const idle = () => {
      tool('author', 'arrow');
      ['analyst', 'reviewer', 'author', 'orchestrator'].forEach((id) => S.free(id));
    };
    let stop = false;
    const alive = () => !stop && !hero.classList.contains('done');
    const finish = () => {
      if (hero.classList.contains('done')) return;
      stop = true;
      chars.forEach((c) => c.classList.add('on'));
      det.classList.add('on');
      sel.classList.remove('on');
      hero.classList.add('done');
      idle();
    };
    S.play = async () => {
      if (reduce) { chars.forEach((c) => c.classList.add('on')); det.classList.add('on'); hero.classList.add('done'); return; }
      S.hold('analyst', ai, { fx: 0, fy: 0, dx: -8, dy: -8, from: corner(0.02, 0.2) });
      S.hold('author', ai, { fx: 0, fy: 0.6, dx: -36, from: corner(0.02, 0.9) });
      S.hold('reviewer', ai, { fx: 1, fy: 0, dx: 28, dy: -40, wander: true, from: corner(0.96, 0.12) });
      S.hold('orchestrator', det, { fx: 0, fy: 1, dx: -40, dy: 12, wander: true, from: corner(0.9, 0.92) });
      await S.wait(700);
      if (!alive()) return;
      sel.classList.add('on');
      S.hold('analyst', sel, { fx: 1, fy: 1, stiff: 520 });
      const t0 = performance.now();
      for (let p = 0; p < 1;) {
        if (!alive()) return;
        p = Math.min(1, (performance.now() - t0) / 640);
        placeSel(1 - (1 - p) ** 3);
        await new Promise(requestAnimationFrame);
      }
      if (!alive()) return;
      S.hold('analyst', sel, { fx: 0, fy: 1, dx: -28, dy: 28, wander: true, shy: true });
      tool('author', 'ibeam');
      for (const c of chars) {
        if (!alive()) return;
        c.classList.add('on');
        S.hold('author', c, { fx: 1, fy: 0.7, dx: 2, stiff: 480 });
        await S.wait(c.textContent === ' ' ? 24 : cjk ? 90 : 36 + Math.random() * 28);
      }
      if (!alive()) return;
      tool('author', 'arrow');
      S.hold('author', ai, { fx: 1, fy: 1, dx: 14, dy: 10, wander: true, shy: true });
      S.hold('reviewer', det, { fx: 1, fy: 0, dx: 8, dy: -6 });
      await S.wait(520);
      if (!alive()) return;
      click('reviewer');
      S.hold('orchestrator', det, { fx: 0.15, fy: 0.8, shy: true });
      await S.wait(560);
      if (!alive()) return;
      click('orchestrator');
      det.classList.add('on');
      await S.wait(420);
      if (!alive()) return;
      sel.classList.remove('on');
      hero.classList.add('done');
      idle();
    };
    addEventListener('scroll', () => { if (scrollY > innerHeight * 0.25) finish(); }, { passive: true });
    addEventListener('resize', () => { if (sel.classList.contains('on')) placeSel(1); });
  }

  const versus = $('.versus');
  if (versus) {
    const S = new Scene(versus.closest('section'));
    const parts = (c) => ({ box: c, val: $('.val', c), lis: $$('.out li', c), verdict: $('.verdict', c), lock: $('.lock', c) });
    const [L, R] = $$('.vs', versus).map(parts);
    let run = 0;
    const reset = () => {
      for (const p of [L, R]) {
        p.val.textContent = '10.00';
        p.val.className = 'val';
        p.lis.forEach((li, i) => li.classList.toggle('on', i === 0));
        p.verdict.classList.remove('on');
      }
      if (R.lock) R.lock.classList.remove('on');
    };
    const typeVal = async (p, text, alive) => {
      p.val.classList.add('selected');
      tool('author', 'ibeam');
      S.hold('author', p.val, { text: true, fx: 1, fy: 0.55, stiff: 420 });
      await S.wait(400);
      if (!alive()) return;
      p.val.classList.remove('selected');
      p.val.textContent = '';
      for (const ch of text) { if (!alive()) return; p.val.textContent += ch; await S.wait(110); }
    };
    S.play = async () => {
      const my = ++run, alive = () => my === run;
      reset();
      tool('author', 'arrow');
      S.hold('author', L.val, { fx: 0, fy: 0.5, dx: -14, shy: true });
      await S.wait(800); if (!alive()) return;
      await typeVal(L, '0.00', alive); if (!alive()) return;
      tool('author', 'arrow');
      S.hold('author', L.box, { fx: 0.86, fy: 0.08, wander: true, shy: true });
      await S.wait(360); L.lis[1].classList.add('on');
      await S.wait(620); L.lis[2].classList.add('on');
      await S.wait(420); L.verdict.classList.add('on');
      await S.wait(1100); if (!alive()) return;
      S.hold('author', R.val, { fx: 0, fy: 0.5, dx: -14, shy: true });
      await S.wait(800); if (!alive()) return;
      await typeVal(R, '0', alive); if (!alive()) return;
      await S.wait(120);
      R.val.textContent = '10.00';
      R.val.classList.add('shake');
      if (R.lock) R.lock.classList.add('on');
      tool('author', 'arrow');
      click('author');
      S.hold('author', R.box, { fx: 0.86, fy: 0.08, wander: true, shy: true });
      for (const li of R.lis.slice(1)) { await S.wait(560); if (!alive()) return; li.classList.add('on'); }
      await S.wait(420);
      R.verdict.classList.add('on');
      S.free('author');
    };
    const again = $('[data-replay]', S.el);
    if (again) again.addEventListener('click', () => S.play());
  }

  const ide = $('[data-ide]');
  if (ide && T.demo) {
    const sec = ide.closest('section');
    const S = new Scene(sec);
    const D = T.demo;
    const log = $('.chat-log', ide), input = $('.chat-input', ide), tabs = $('.tabs', ide), code = $('.code', ide), term = $('.term-log', ide);
    const seats = $$('.avatars span', ide);
    const seat = { orchestrator: seats[0], analyst: seats[1], author: seats[2], reviewer: seats[3] };
    const count = $('.count', ide), stageEls = $$('.stage', sec);
    const perStage = [0, 0, 0, 0];
    D.steps.forEach((s) => { perStage[s.stage]++; });
    const files = {};
    let run = 0, shown = '';
    const trim = (box, keep) => { while (box.children.length > keep) box.firstChild.remove(); box.scrollTop = box.scrollHeight; };
    const idleInput = () => { input.textContent = D.placeholder; input.classList.remove('typing'); };
    const stage = (i, done, total, pressed = i) => {
      stageEls.forEach((el, k) => {
        el.classList.toggle('on', k === pressed);
        el.setAttribute('aria-pressed', k === pressed ? 'true' : 'false');
        const frac = perStage[k] ? done / perStage[k] : 0;
        el.querySelector('.meter b').style.width = k < i ? '100%' : k > i ? '0%' : `${Math.min(100, frac * 100)}%`;
      });
      count.textContent = `${total}/${D.steps.length}`;
    };
    const showFile = (name) => {
      if (!files[name]) { files[name] = { tab: make('span', 'tab', name), lines: [] }; tabs.appendChild(files[name].tab); }
      Object.entries(files).forEach(([k, f]) => f.tab.classList.toggle('on', k === name));
      if (shown !== name) { code.textContent = ''; files[name].lines.forEach((l) => code.appendChild(line(l))); shown = name; }
    };
    const reset = () => {
      log.textContent = ''; tabs.textContent = ''; code.textContent = ''; term.textContent = ''; shown = '';
      Object.keys(files).forEach((k) => delete files[k]);
      Object.keys(T.agents).forEach((id) => { S.free(id); tool(id, 'arrow'); });
      idleInput();
      stage(0, 0, 0);
    };
    const step = async (s, fast, alive) => {
      const W = (ms) => (fast ? Promise.resolve() : S.wait(ms));
      if (s.you) {
        if (!fast) {
          S.hold('you', input, { fx: 0.08, fy: 0.55, stiff: 260, shy: true });
          await W(500); if (!alive()) return;
          tool('you', 'ibeam');
          input.classList.add('typing');
          input.textContent = '';
          const typed = make('span');
          input.appendChild(typed);
          S.hold('you', typed, { text: true, fx: 1, fy: 0.55, dx: 2, stiff: 500 });
          for (const ch of s.you) { if (!alive()) return; typed.textContent += ch; await W(62); }
          await W(280); if (!alive()) return;
          tool('you', 'arrow');
        }
        const m = make('div', 'msg you', s.you);
        log.appendChild(m); trim(log, 9);
        idleInput();
        if (!fast) S.free('you');
      } else if (s.say) {
        const a = T.agents[s.by];
        const m = make('div', 'msg');
        m.style.setProperty('--c', a.color);
        m.innerHTML = `<span class="who">${esc(a.name)}</span>${s.say}`;
        log.appendChild(m); trim(log, 9);
        if (!fast && seat[s.by]) S.hold(s.by, seat[s.by], { fx: 0.5, fy: 0.5, bare: true });
      } else if (s.file) {
        showFile(s.file);
        if (!fast) { S.hold(s.by, code, { fx: 0.35, fy: 0.06, shy: true }); await W(400); }
        for (const l of s.code) {
          if (!alive()) return;
          files[s.file].lines.push(l);
          const el = line(l);
          code.appendChild(el);
          code.scrollTop = code.scrollHeight;
          if (!fast) { tool(s.by, 'ibeam'); S.hold(s.by, el, { text: true, fx: 1, fy: 0.65, dx: 4, stiff: 300 }); await W(160); }
        }
        tool(s.by, 'arrow');
        if (!fast && seat[s.by]) S.hold(s.by, seat[s.by], { fx: 0.5, fy: 0.5, bare: true });
      } else if (s.cmd) {
        const el = line('', 'cmd');
        term.appendChild(el); trim(term, 14);
        if (!fast) { S.hold(s.by, el, { text: true, fx: 1, fy: 0.6, dx: 18, shy: true }); await W(400); }
        for (const ch of s.cmd) { if (!alive()) return; el.firstChild.textContent += ch; await W(14); }
        if (s.lock) {
          await W(300);
          showFile(Object.keys(files).pop());
          $$('.num', code).filter((n) => n.textContent === s.lock).forEach((n) => { n.classList.add('locked'); n.dataset.l = D.locked; });
        }
        for (const [cls, text] of s.out || []) { await W(400); if (!alive()) return; term.appendChild(line(text, cls)); trim(term, 14); }
        if (!fast && seat[s.by]) S.hold(s.by, seat[s.by], { fx: 0.5, fy: 0.5, bare: true });
      }
    };
    const play = async (from) => {
      const my = ++run, alive = () => my === run;
      const start = Math.max(0, from);
      const pressed = D.steps[start] ? D.steps[start].stage : 0;
      reset();
      stage(pressed, 0, start, pressed);
      const done = [0, 0, 0, 0];
      for (let i = 0; i < D.steps.length; i++) {
        const s = D.steps[i], fast = i < from;
        if (!fast && i === from && from > 0) await S.wait(240);
        await step(s, fast, alive);
        if (!alive()) return;
        done[s.stage]++;
        stage(s.stage, done[s.stage], i + 1, fast ? pressed : s.stage);
        if (!fast) await S.wait(s.wait || 900);
        if (!alive()) return;
      }
      if (reduce) return;
      await S.wait(7000);
      if (alive() && S.on) play(0);
    };
    S.play = () => play(0);
    stageEls.forEach((el, i) => el.addEventListener('click', () => {
      S.started = true;
      play(D.steps.findIndex((s) => s.stage === i));
    }));
    const restart = $('[data-restart]', ide);
    if (restart) restart.addEventListener('click', () => play(0));
    reset();
  }

  const team = $('.team-grid');
  if (team) {
    const S = new Scene(team.closest('section'));
    const members = $$('.member', team);
    S.play = async () => {
      for (const m of members) {
        S.hold(m.dataset.agent, m, { fx: 0, fy: 0, dx: 3, dy: 3, bare: true });
        await S.wait(140);
      }
    };
    members.forEach((m) => m.addEventListener('pointerenter', () => { if (m.dataset.agent) click(m.dataset.agent); }));
  }

  const arena = $('.arena');
  if (arena && T.cheat) {
    const C = T.cheat;
    const S = new Scene(arena.closest('section'));
    const editor = $('.editor', arena), tabsEl = $('.tabs', editor), codeEl = $('.code', editor), termEl = $('.term-log', editor);
    const buttons = $$('.attack', arena), score = $('[data-score]', S.el), won = $('.won', S.el);
    const total = C.attacks.length;
    let open = 'test', busy = false, caught = 0;
    const W = (ms) => (reduce ? Promise.resolve() : sleep(ms));
    const show = (key) => {
      open = key;
      tabsEl.textContent = '';
      Object.entries(C.files).forEach(([k, f]) => tabsEl.appendChild(make('span', `tab${k === key ? ' on' : ''}`, f.name)));
      codeEl.textContent = '';
      C.files[key].lines.forEach((l) => codeEl.appendChild(line(l)));
    };
    const say = (cls, text) => { termEl.appendChild(line(text, cls)); while (termEl.children.length > 12) termEl.firstChild.remove(); termEl.scrollTop = termEl.scrollHeight; };
    const typeCmd = async (cmd) => {
      const el = line('', 'cmd');
      termEl.appendChild(el);
      termEl.scrollTop = termEl.scrollHeight;
      S.hold('author', el, { text: true, fx: 1, fy: 0.6, dx: 16, shy: true });
      for (const ch of cmd) { el.firstChild.textContent += ch; await W(12); }
    };
    const setScore = () => { score.innerHTML = `${caught}<span>/${total}</span>`; };
    const reset = () => {
      caught = 0; setScore();
      buttons.forEach((b) => { b.classList.remove('caught', 'busy'); b.removeAttribute('aria-disabled'); $('.st', b).textContent = b.dataset.try; });
      won.classList.remove('on');
      termEl.textContent = '';
      C.intro.forEach(([cls, text]) => say(cls, text));
      show('test');
      S.hold('author', tabsEl, { fx: 0.82, fy: 0.5, shy: true });
    };
    buttons.forEach((b) => { b.dataset.try = $('.st', b).textContent; });
    const attack = async (i, b) => {
      if (busy || b.classList.contains('caught')) return;
      busy = true;
      b.classList.add('busy');
      const A = C.attacks[i];
      let target = null;
      if (A.file) {
        if (open !== A.file) { show(A.file); await W(220); }
        const lines = codeEl.children;
        if (A.edit === 'insert') {
          target = line(A.text, 'edit');
          codeEl.insertBefore(target, lines[A.line] || null);
          const segs = Array.from(target.children), full = segs.map((s) => s.textContent);
          segs.forEach((s) => { s.textContent = ''; });
          tool('author', 'ibeam');
          S.hold('author', target, { text: true, fx: 1, fy: 0.65, dx: 2, stiff: 420 });
          await W(420);
          for (let k = 0; k < segs.length; k++) for (const ch of full[k]) { segs[k].textContent += ch; await W(36); }
        } else {
          target = lines[A.line];
          target.classList.add('edit');
          S.hold('author', target, { text: true, fx: 1, fy: 0.65, dx: 6 });
          await W(520);
          if (A.edit === 'strike') target.classList.add('gone');
          else {
            const seg = Array.from(target.children).find((s) => s.textContent.includes(A.from));
            const at = seg.textContent.indexOf(A.from), before = seg.textContent.slice(0, at), after = seg.textContent.slice(at + A.from.length);
            tool('author', 'ibeam');
            S.hold('author', seg, { text: true, fx: 1, fy: 0.65, dx: 2, stiff: 420 });
            for (let k = A.from.length; k >= 0; k--) { seg.textContent = before + A.from.slice(0, k) + after; await W(18); }
            for (let k = 1; k <= A.to.length; k++) { seg.textContent = before + A.to.slice(0, k) + after; await W(32); }
          }
        }
        tool('author', 'arrow');
        await W(240);
      }
      if (A.cmd) { await typeCmd(A.cmd); await W(240); }
      await typeCmd(C.submit);
      for (const [cls, text] of A.out) { await W(320); say(cls, text); }
      if (target) { target.classList.remove('edit'); target.classList.add('blocked'); }
      click('author');
      await W(900);
      if (A.file) {
        show(A.file);
        const back = codeEl.children[A.line];
        if (back && A.edit !== 'insert') back.classList.add('restored');
      }
      (A.after || []).forEach(([cls, text]) => say(cls, text));
      S.hold('author', tabsEl, { fx: 0.82, fy: 0.5, shy: true });
      b.classList.remove('busy');
      b.classList.add('caught');
      b.setAttribute('aria-disabled', 'true');
      $('.st', b).textContent = `✓ ${C.caught}`;
      caught++;
      setScore();
      if (caught === total) won.classList.add('on');
      busy = false;
    };
    buttons.forEach((b, i) => b.addEventListener('click', () => attack(i, b)));
    const again = $('[data-again]', S.el);
    if (again) again.addEventListener('click', reset);
    S.play = async () => { S.hold('author', tabsEl, { fx: 0.82, fy: 0.5, shy: true }); };
    reset();
  }

  const fin = $('.final');
  if (fin) {
    const S = new Scene(fin);
    S.play = async () => {};
  }

  if (reduce) scenes.forEach((s) => { s.on = true; s.started = true; s.play(); });
  else {
    addEventListener('scroll', schedulePick, { passive: true });
    addEventListener('resize', schedulePick);
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        if (active) { active.on = false; active = null; }
        ticking = false;
      } else schedulePick();
    });
    schedulePick();
  }
})();
