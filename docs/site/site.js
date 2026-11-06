/* gta introduction. One layer of agent cursors lives on the page and travels with it. Each section is a scene that
   borrows the cursors: the hero, the problem, the IDE demo, the team, the cheating game and the end.
   Every text comes from the page (#copy), so this file is the same for every language. No dependencies. */
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

  /* ── small page interactions ─────────────────────────────────────────────── */
  const nav = $('.nav');
  const onScroll = () => nav && nav.classList.toggle('scrolled', scrollY > 8);
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
    try { await navigator.clipboard.writeText(b.dataset.copy); b.textContent = b.dataset.done || '✓'; } catch (_) { b.textContent = '—'; }
    setTimeout(() => { b.textContent = label; }, 1600);
  }));

  const pointer = { x: 0, y: 0, in: false };
  addEventListener('pointermove', (e) => {
    if (e.pointerType !== 'mouse') return;
    pointer.x = e.clientX; pointer.y = e.clientY; pointer.in = true;
  }, { passive: true });
  document.addEventListener('pointerleave', () => { pointer.in = false; });

  /* text helpers shared by the IDE and the game */
  const line = (segments, cls) => {
    const el = make('span', `ln${cls ? ` ${cls}` : ''}`);
    (typeof segments === 'string' ? [['', segments]] : segments).forEach(([c, text]) => el.appendChild(make('span', c || '', text)));
    return el;
  };
  const textRect = (el) => { // the box of the text itself, not of the block around it
    const r = document.createRange();
    r.selectNodeContents(el);
    const b = r.getBoundingClientRect();
    if (b.width || b.height) return b;
    const e = el.getBoundingClientRect();
    return { left: e.left, top: e.top, width: 0, height: e.height };
  };

  /* ── the agent layer ─────────────────────────────────────────────────────────
     Each cursor springs towards a target that is anchored to an element, so it moves with the page when you scroll.
     Positions are page coordinates; a scene decides where each cursor goes, and a cursor no scene holds fades out. */
  const layer = make('div', 'agents');
  layer.setAttribute('aria-hidden', 'true');
  document.body.appendChild(layer);
  const ARROW = '<svg class="arrow" width="24" height="24" viewBox="0 0 24 24"><path d="M3.6 2.6l16.2 7.1-7 2.3-2.6 7.1z"/></svg>';
  const BEAM = 'M8.5 2h3M12.5 2h3M12 2v20M8.5 22h3M12.5 22h3';
  const IBEAM = `<svg class="ibeam" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke-linecap="round"><path d="${BEAM}" stroke="#fff" stroke-width="4.4"/><path d="${BEAM}" style="stroke:var(--c)" stroke-width="2"/></svg>`;
  const HOT = { arrow: [3.6, 2.6], ibeam: [12, 12] };
  const agents = {};
  Object.entries(T.agents).forEach(([id, a], i) => {
    const el = make('div', 'agent');
    el.style.setProperty('--c', a.color);
    el.innerHTML = `${ARROW}${IBEAM}<span class="press"></span><span class="tag">${esc(a.name)}</span>`;
    layer.appendChild(el);
    agents[id] = { id, el, x: 0, y: 0, vx: 0, vy: 0, hold: null, tool: 'arrow', on: false, placed: false, phase: i * 1.9, room: 40 + a.name.length * 7 };
  });
  const tool = (id, t) => { agents[id].tool = t; agents[id].el.dataset.tool = t; };
  const click = (id) => { const el = agents[id].el; el.classList.remove('click'); void el.offsetWidth; el.classList.add('click'); };

  const targetOf = (a, t) => {
    const h = a.hold;
    if (!h) return null;
    let x, y;
    const r = h.text ? textRect(h.el) : h.el.getBoundingClientRect();
    const over = pointer.in && pointer.x > r.left && pointer.x < r.left + r.width && pointer.y > r.top && pointer.y < r.top + r.height;
    if (h.follow && over) { x = pointer.x + h.dx; y = pointer.y + h.dy; }
    else { x = r.left + r.width * h.fx + h.dx; y = r.top + r.height * h.fy + h.dy; }
    if (h.wander) { x += Math.sin(t / 900 + a.phase) * 7; y += Math.cos(t / 1100 + a.phase * 1.3) * 5; }
    if (h.shy && pointer.in) { // step aside when the pointer comes close
      const dx = x - pointer.x, dy = y - pointer.y, d = Math.hypot(dx, dy);
      if (d < 90) { x += (dx / (d || 1)) * (90 - d); y += (dy / (d || 1)) * (90 - d); }
    }
    x = Math.max(6, Math.min(innerWidth - (h.bare ? 30 : a.room), x)); // keep the name tag on screen
    return [x + scrollX, y + scrollY];
  };
  let last = performance.now();
  const frame = (t) => {
    const dt = Math.min((t - last) / 1000, 1 / 30);
    last = t;
    for (const a of Object.values(agents)) {
      const tg = reduce ? null : targetOf(a, t);
      if (!!tg !== a.on) { a.on = !!tg; a.el.classList.toggle('on', a.on); }
      if (!tg) continue;
      a.el.classList.toggle('bare', a.hold.bare);
      if (!a.placed) { [a.x, a.y] = a.hold.from ? a.hold.from() : [tg[0] + 120, tg[1] + 220]; a.placed = true; }
      const k = a.hold.stiff || 150, c = 2 * Math.sqrt(k) * 0.8;
      a.vx += (k * (tg[0] - a.x) - c * a.vx) * dt;
      a.vy += (k * (tg[1] - a.y) - c * a.vy) * dt;
      a.x += a.vx * dt;
      a.y += a.vy * dt;
      const [hx, hy] = HOT[a.tool];
      a.el.style.transform = `translate3d(${(a.x - scrollX - hx).toFixed(1)}px, ${(a.y - scrollY - hy).toFixed(1)}px, 0)`;
    }
    requestAnimationFrame(frame);
  };
  if (!reduce) requestAnimationFrame(frame);

  /* a scene owns the cursors while it fills most of the screen; its timeline pauses while it does not */
  const scenes = [];
  let active = null;
  class Scene {
    constructor(el) { this.el = el; this.holds = {}; this.on = false; this.started = false; this.play = async () => {}; scenes.push(this); }
    hold(id, el, o = {}) {
      this.holds[id] = { el, fx: o.fx ?? 0.5, fy: o.fy ?? 0.5, dx: o.dx || 0, dy: o.dy || 0, text: !!o.text, wander: !!o.wander, shy: !!o.shy, follow: !!o.follow, stiff: o.stiff, from: o.from, bare: !!o.bare };
      if (this.on) agents[id].hold = this.holds[id];
    }
    free(id) { delete this.holds[id]; if (this.on) agents[id].hold = null; }
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
    if (best === active) return;
    if (active) active.on = false;
    active = best;
    for (const a of Object.values(agents)) a.hold = (active && active.holds[a.id]) || null;
    if (active) {
      active.on = true;
      if (!active.started) { active.started = true; active.play(); }
    }
  };
  let picking = false;
  const schedulePick = () => { if (!picking) { picking = true; requestAnimationFrame(() => { picking = false; pick(); }); } };

  /* ── hero: the agents write the first line; gtaw stamps the second ──────────── */
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
    hero.appendChild(sel);
    let mark = null;
    const rulerTop = $('.ruler.top canvas', hero), rulerLeft = $('.ruler.left canvas', hero);
    const drawRulers = () => {
      for (const [cv, across] of [[rulerTop, true], [rulerLeft, false]]) {
        if (!cv || !cv.offsetWidth) continue;
        const dpr = Math.min(devicePixelRatio || 1, 2), w = cv.offsetWidth, h = cv.offsetHeight;
        cv.width = w * dpr; cv.height = h * dpr;
        const g = cv.getContext('2d');
        g.setTransform(dpr, 0, 0, dpr, 0, 0);
        const off = across ? 0 : 22, len = across ? w : h;
        if (mark) {
          const a = (across ? mark[0] : mark[1]) - off, b = (across ? mark[2] : mark[3]) - off;
          g.fillStyle = 'rgba(46, 211, 198, .2)';
          if (across) g.fillRect(a, 0, b - a, h); else g.fillRect(0, a, w, b - a);
        }
        g.fillStyle = 'rgba(255, 255, 255, .24)';
        g.font = '9px "Geist Mono", ui-monospace, monospace';
        for (let p = (10 - (off % 10)) % 10; p < len; p += 10) {
          const v = p + off, big = v % 100 === 0, mid = v % 50 === 0, t = big ? 8 : mid ? 5 : 3;
          if (across) g.fillRect(p, h - t, 1, t); else g.fillRect(w - t, p, t, 1);
          if (big && v > 0) {
            if (across) g.fillText(String(v), p + 3, 10);
            else { g.save(); g.translate(10, p + 3); g.rotate(-Math.PI / 2); g.textAlign = 'right'; g.fillText(String(v), 0, 0); g.restore(); }
          }
        }
      }
    };
    const placeSel = (p) => { // p: how far the analyst has dragged, 0..1
      const hr = hero.getBoundingClientRect(), r = textRect(ai), pad = 14;
      const x = r.left - hr.left - pad, y = r.top - hr.top - pad / 2, w = (r.width + pad * 2) * p, h = (r.height + pad) * p;
      Object.assign(sel.style, { left: `${x}px`, top: `${y}px`, width: `${w}px`, height: `${h}px` });
      sel.lastChild.textContent = `${Math.round(w)} × ${Math.round(h)}`;
      mark = [x, y, x + w, y + h];
      drawRulers();
    };
    drawRulers();
    if (document.fonts) document.fonts.ready.then(() => { if (!mark) drawRulers(); });
    addEventListener('resize', () => { if (mark && sel.classList.contains('on')) placeSel(1); else drawRulers(); });

    const corner = (fx, fy) => () => [scrollX + innerWidth * fx, scrollY + innerHeight * fy];
    const idle = () => {
      tool('author', 'arrow');
      if (innerWidth < 680) { // no room beside the headline: wait in the corners
        [['analyst', 0.08, 0.1], ['reviewer', 0.66, 0.1], ['orchestrator', 0.08, 0.94], ['author', 0.6, 0.94]]
          .forEach(([id, fx, fy]) => S.hold(id, hero, { fx, fy, wander: true }));
        return;
      }
      S.hold('analyst', ai, { fx: 0, fy: 0, dx: -60, dy: -36, wander: true, shy: true });
      S.hold('reviewer', ai, { fx: 1, fy: 0, dx: 36, dy: -20, wander: true, shy: true });
      S.hold('author', ai, { fx: 1, fy: 1, dx: 24, dy: 6, wander: true, shy: true });
      S.hold('orchestrator', det, { fx: 0, fy: 0.5, dx: -150, wander: true, shy: true });
    };
    S.play = async () => {
      if (reduce) { chars.forEach((c) => c.classList.add('on')); det.classList.add('on'); hero.classList.add('done'); return; }
      S.hold('analyst', ai, { fx: 0, fy: 0, dx: -12, dy: -6, from: corner(-0.04, 0.15) });
      S.hold('author', ai, { fx: 0, fy: 0.55, dx: -40, from: corner(-0.04, 0.95) });
      S.hold('reviewer', ai, { fx: 1, fy: 0, dx: 40, dy: -50, wander: true, from: corner(1.04, 0.1) });
      S.hold('orchestrator', det, { fx: 0, fy: 1, dx: -60, dy: 20, wander: true, from: corner(1.04, 0.95) });
      await S.wait(900);
      sel.classList.add('on');
      S.hold('analyst', sel, { fx: 1, fy: 1, stiff: 600 });
      const t0 = performance.now();
      for (let p = 0; p < 1;) {
        p = Math.min(1, (performance.now() - t0) / 700);
        placeSel(1 - (1 - p) ** 3);
        await new Promise(requestAnimationFrame);
      }
      S.hold('analyst', sel, { fx: 0, fy: 1, dx: -40, dy: 36, wander: true });
      tool('author', 'ibeam');
      for (const c of chars) {
        c.classList.add('on');
        S.hold('author', c, { fx: 1, fy: 0.55, dx: 2, stiff: 520 });
        await S.wait(c.textContent === ' ' ? 30 : cjk ? 110 : 45 + Math.random() * 35);
      }
      tool('author', 'arrow');
      S.hold('author', ai, { fx: 1, fy: 1, dx: 24, dy: 6, wander: true });
      S.hold('reviewer', sel, { fx: 1, fy: 0, dx: 2, dy: 2 });
      await S.wait(650);
      click('reviewer');
      S.hold('orchestrator', det, { fx: 0.5, fy: 0.55 });
      await S.wait(700);
      click('orchestrator');
      det.classList.add('on');
      await S.wait(500);
      sel.classList.remove('on');
      mark = null;
      drawRulers();
      hero.classList.add('done');
      idle();
    };
    // a click or a scroll before the line is written finishes it at once
    const finish = () => {
      if (hero.classList.contains('done')) return;
      chars.forEach((c) => c.classList.add('on'));
      det.classList.add('on');
      sel.classList.remove('on');
      mark = null;
      drawRulers();
      hero.classList.add('done');
    };
    addEventListener('scroll', () => { if (scrollY > innerHeight * 0.3) finish(); }, { passive: true });
  }

  /* ── the problem: the same failing test, without gta and with it ──────────── */
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
      await S.wait(450);
      p.val.classList.remove('selected');
      p.val.textContent = '';
      for (const ch of text) { if (!alive()) return; p.val.textContent += ch; await S.wait(120); }
    };
    S.play = async () => {
      const my = ++run, alive = () => my === run;
      reset();
      tool('author', 'arrow');
      S.hold('author', L.val, { fx: 0, fy: 0.5, dx: -16 });
      await S.wait(1000); if (!alive()) return;
      await typeVal(L, '0.00', alive); if (!alive()) return;
      tool('author', 'arrow');
      S.hold('author', L.box, { fx: 0.84, fy: 0.12, wander: true });
      await S.wait(400); L.lis[1].classList.add('on');
      await S.wait(700); L.lis[2].classList.add('on');
      await S.wait(500); L.verdict.classList.add('on');
      await S.wait(1300); if (!alive()) return;
      S.hold('author', R.val, { fx: 0, fy: 0.5, dx: -16 });
      await S.wait(1000); if (!alive()) return;
      await typeVal(R, '0', alive); if (!alive()) return;
      await S.wait(140);
      R.val.textContent = '10.00';
      R.val.classList.add('shake');
      if (R.lock) R.lock.classList.add('on');
      tool('author', 'arrow');
      click('author');
      S.hold('author', R.box, { fx: 0.84, fy: 0.12, wander: true });
      for (const li of R.lis.slice(1)) { await S.wait(650); if (!alive()) return; li.classList.add('on'); }
      await S.wait(500);
      R.verdict.classList.add('on');
    };
    const again = $('[data-replay]', S.el);
    if (again) again.addEventListener('click', () => S.play());
  }

  /* ── how it works: the IDE where the agents relay, step by step ───────────── */
  const ide = $('[data-ide]');
  if (ide && T.demo) {
    const sec = ide.closest('section');
    const S = new Scene(sec);
    const D = T.demo;
    const log = $('.chat-log', ide), input = $('.chat-input', ide), tabs = $('.tabs', ide), code = $('.code', ide), term = $('.term-log', ide);
    const count = $('.count', ide), stageEls = $$('.stage', sec);
    const perStage = [0, 0, 0, 0];
    D.steps.forEach((s) => { perStage[s.stage]++; });
    const files = {};
    let run = 0, shown = '';
    const trim = (box, keep) => { while (box.children.length > keep) box.firstChild.remove(); box.scrollTop = box.scrollHeight; };
    const idleInput = () => { input.textContent = D.placeholder; input.classList.remove('typing'); };
    const stage = (i, done, total) => {
      stageEls.forEach((el, k) => {
        el.classList.toggle('on', k === i);
        el.querySelector('.bar b').style.width = k < i ? '100%' : k > i ? '0%' : `${(done / perStage[k]) * 100}%`;
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
          S.hold('you', input, { fx: 0.12, fy: 0.5, stiff: 260 });
          await W(600); if (!alive()) return;
          tool('you', 'ibeam');
          input.classList.add('typing');
          input.textContent = '';
          const typed = make('span');
          input.appendChild(typed);
          S.hold('you', typed, { text: true, fx: 1, fy: 0.5, dx: 2, stiff: 520 });
          for (const ch of s.you) { if (!alive()) return; typed.textContent += ch; await W(70); }
          await W(350); if (!alive()) return;
          tool('you', 'arrow');
        }
        const m = make('div', 'msg you', s.you);
        log.appendChild(m); trim(log, 9);
        idleInput();
        if (!fast) S.hold('you', m, { fx: 0.3, fy: 1, dy: 10, wander: true });
      } else if (s.say) {
        const a = T.agents[s.by];
        const m = make('div', 'msg');
        m.style.setProperty('--c', a.color);
        m.innerHTML = `<span class="who">${esc(a.name)}</span>${s.say}`;
        log.appendChild(m); trim(log, 9);
        if (!fast) S.hold(s.by, m, { fx: 0.6, fy: 1, dy: -6, wander: true });
      } else if (s.file) {
        showFile(s.file);
        if (!fast) { S.hold(s.by, code, { fx: 0.4, fy: 0.08 }); await W(450); }
        for (const l of s.code) {
          if (!alive()) return;
          files[s.file].lines.push(l);
          const el = line(l);
          code.appendChild(el);
          code.scrollTop = code.scrollHeight;
          if (!fast) { S.hold(s.by, el, { text: true, fx: 1, fy: 0.6, dx: 3, stiff: 300 }); await W(180); }
        }
        if (!fast) S.hold(s.by, code, { fx: 0.8, fy: s.by === 'analyst' ? 0.25 : 0.6, wander: true });
      } else if (s.cmd) {
        const el = line('', 'cmd');
        term.appendChild(el); trim(term, 14);
        if (!fast) { S.hold(s.by, el, { text: true, fx: 1, fy: 0.6, dx: 22 }); await W(450); }
        for (const ch of s.cmd) { if (!alive()) return; el.firstChild.textContent += ch; await W(16); }
        if (s.lock) { // gtaw locks the expected value: no cursor does this
          await W(350);
          showFile(Object.keys(files).pop());
          $$('.num', code).filter((n) => n.textContent === s.lock).forEach((n) => { n.classList.add('locked'); n.dataset.l = D.locked; });
        }
        for (const [cls, text] of s.out || []) { await W(450); if (!alive()) return; term.appendChild(line(text, cls)); trim(term, 14); }
        if (!fast) S.hold(s.by, term, { fx: 0.7, fy: 0.4, wander: true });
      }
    };
    const play = async (from) => {
      const my = ++run, alive = () => my === run;
      reset();
      const done = [0, 0, 0, 0];
      for (let i = 0; i < D.steps.length; i++) {
        const s = D.steps[i], fast = i < from;
        if (!fast && i === from && from > 0) await S.wait(300);
        await step(s, fast, alive);
        if (!alive()) return;
        done[s.stage]++;
        stage(s.stage, done[s.stage], i + 1);
        if (!fast) await S.wait(s.wait || 1000);
        if (!alive()) return;
      }
      if (reduce) return;
      await S.wait(7000);
      if (alive()) play(0);
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

  /* ── the team: each agent stands on its own card ──────────────────────────── */
  const team = $('.team-grid');
  if (team) {
    const S = new Scene(team.closest('section'));
    const members = $$('.member', team);
    S.play = async () => {
      for (const m of members) { S.hold(m.dataset.agent, m, { fx: 0.88, fy: 0.1, wander: true, shy: true, bare: true }); await S.wait(160); }
    };
    members.forEach((m) => m.addEventListener('pointerenter', () => click(m.dataset.agent)));
  }

  /* ── try to cheat: the visitor picks a shortcut, the author tries it, gtaw refuses ── */
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
      S.hold('author', el, { text: true, fx: 1, fy: 0.6, dx: 20 });
      for (const ch of cmd) { el.firstChild.textContent += ch; await W(14); }
    };
    const setScore = () => { score.innerHTML = `${caught}<span>/${total}</span>`; };
    const reset = () => {
      caught = 0; setScore();
      buttons.forEach((b) => { b.classList.remove('caught', 'busy'); b.removeAttribute('aria-disabled'); $('.st', b).textContent = b.dataset.try; });
      won.classList.remove('on');
      termEl.textContent = '';
      C.intro.forEach(([cls, text]) => say(cls, text));
      show('test');
    };
    buttons.forEach((b) => { b.dataset.try = $('.st', b).textContent; });
    const attack = async (i, b) => {
      if (busy || b.classList.contains('caught')) return;
      busy = true;
      b.classList.add('busy');
      const A = C.attacks[i];
      let target = null;
      if (A.file) {
        if (open !== A.file) { show(A.file); await W(250); }
        const lines = codeEl.children;
        if (A.edit === 'insert') {
          target = line(A.text, 'edit');
          codeEl.insertBefore(target, lines[A.line] || null);
          const segs = Array.from(target.children), full = segs.map((s) => s.textContent);
          segs.forEach((s) => { s.textContent = ''; });
          tool('author', 'ibeam');
          S.hold('author', target, { text: true, fx: 1, fy: 0.6, dx: 2, stiff: 420 });
          await W(500);
          for (let k = 0; k < segs.length; k++) for (const ch of full[k]) { segs[k].textContent += ch; await W(45); }
        } else {
          target = lines[A.line];
          target.classList.add('edit');
          S.hold('author', target, { text: true, fx: 1, fy: 0.6, dx: 6 });
          await W(650);
          if (A.edit === 'strike') target.classList.add('gone');
          else {
            const seg = Array.from(target.children).find((s) => s.textContent.includes(A.from));
            const at = seg.textContent.indexOf(A.from), before = seg.textContent.slice(0, at), after = seg.textContent.slice(at + A.from.length);
            tool('author', 'ibeam');
            S.hold('author', seg, { text: true, fx: 1, fy: 0.6, dx: 2, stiff: 420 });
            for (let k = A.from.length; k >= 0; k--) { seg.textContent = before + A.from.slice(0, k) + after; await W(22); }
            for (let k = 1; k <= A.to.length; k++) { seg.textContent = before + A.to.slice(0, k) + after; await W(40); }
          }
        }
        tool('author', 'arrow');
        await W(300);
      }
      if (A.cmd) { await typeCmd(A.cmd); await W(300); }
      await typeCmd(C.submit);
      for (const [cls, text] of A.out) { await W(380); say(cls, text); }
      if (target) { target.classList.remove('edit'); target.classList.add('blocked'); }
      click('author');
      await W(1100);
      if (A.file) { // gtaw reverts, or the author has to put it back: either way the file is as before
        show(A.file);
        const back = codeEl.children[A.line];
        if (back && A.edit !== 'insert') back.classList.add('restored');
      }
      (A.after || []).forEach(([cls, text]) => say(cls, text));
      S.hold('author', editor, { fx: 0.88, fy: 0.12, wander: true });
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
    S.play = async () => { S.hold('author', editor, { fx: 0.88, fy: 0.12, wander: true }); };
    reset();
  }

  /* ── the end: the agents gather round your pointer ───────────────────────── */
  const fin = $('.final');
  if (fin) {
    const S = new Scene(fin);
    S.play = async () => {
      ['orchestrator', 'analyst', 'author', 'reviewer'].forEach((id, i) => {
        const ang = (i / 4) * Math.PI * 2 + 0.7;
        S.hold(id, fin, { follow: true, fx: 0.5, fy: 0.72, dx: Math.cos(ang) * 120, dy: Math.sin(ang) * 70, wander: true, stiff: 50 + i * 16 });
      });
    };
  }

  /* start: the scenes on screen take the cursors; with reduced motion every scene shows its end state at once */
  if (reduce) scenes.forEach((s) => { s.on = true; s.started = true; s.play(); });
  else {
    addEventListener('scroll', schedulePick, { passive: true });
    addEventListener('resize', schedulePick);
    document.addEventListener('visibilitychange', () => { if (document.hidden && active) { active.on = false; active = null; } else schedulePick(); });
    schedulePick();
  }
})();
