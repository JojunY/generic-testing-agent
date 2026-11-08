/* gta introduction: a faint dot field behind the hero, the IDE demo where four agents relay, and small page interactions.
   No dependencies. Everything is optional: without JavaScript the page reads the same, only still. */
(() => {
  'use strict';
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const watch = (el, fn, margin = '0px') => {
    if (!('IntersectionObserver' in window)) return fn(true);
    new IntersectionObserver(([e]) => fn(e.isIntersecting), { rootMargin: margin }).observe(el);
  };

  /* nav border once the page scrolls */
  const nav = $('.nav');
  const onScroll = () => nav && nav.classList.toggle('scrolled', scrollY > 8);
  addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* sections fade in once */
  const reveals = $$('.reveal');
  if (reduce || !('IntersectionObserver' in window)) reveals.forEach((el) => el.classList.add('in'));
  else {
    const io = new IntersectionObserver((es) => es.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
    }), { rootMargin: '0px 0px -8% 0px' });
    reveals.forEach((el) => io.observe(el));
  }

  /* a soft light that follows the pointer on cards */
  $$('.card').forEach((c) => c.addEventListener('pointermove', (e) => {
    const r = c.getBoundingClientRect();
    c.style.setProperty('--x', `${e.clientX - r.left}px`);
    c.style.setProperty('--y', `${e.clientY - r.top}px`);
  }));

  /* copy buttons */
  $$('[data-copy]').forEach((b) => b.addEventListener('click', async () => {
    const label = b.textContent;
    try { await navigator.clipboard.writeText(b.dataset.copy); b.textContent = b.dataset.done || '✓'; }
    catch (_) { b.textContent = '—'; }
    setTimeout(() => { b.textContent = label; }, 1600);
  }));

  /* hero: a dot grid, brighter near the pointer and under a slow diagonal sweep; one red dot is the defect */
  const canvas = $('#field');
  const ctx = canvas && canvas.getContext('2d');
  if (ctx) {
    const GAP = 26;
    let w = 0, h = 0, dots = [], red = [0, 0], live = true, raf = 0;
    const mouse = { x: -1e4, y: -1e4 };
    const size = () => {
      const dpr = Math.min(devicePixelRatio || 1, 2);
      w = canvas.clientWidth; h = canvas.clientHeight;
      canvas.width = w * dpr; canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      dots = [];
      const ox = (w % GAP) / 2, oy = (h % GAP) / 2;
      for (let y = oy; y < h; y += GAP) for (let x = ox; x < w; x += GAP) dots.push(x, y);
      red = [ox + Math.round((w * 0.8 - ox) / GAP) * GAP, oy + Math.round((h * 0.14 - oy) / GAP) * GAP];
    };
    const draw = (t) => {
      ctx.clearRect(0, 0, w, h);
      const sweep = ((t / 11000) % 1) * (w + h) * 1.3 - h * 0.3;
      for (let i = 0; i < dots.length; i += 2) {
        const x = dots[i], y = dots[i + 1];
        const dm = Math.hypot(x - mouse.x, y - mouse.y);
        const m = dm < 170 ? 1 - dm / 170 : 0;
        const ds = Math.abs(x + y * 0.7 - sweep);
        const s = ds < 160 ? 1 - ds / 160 : 0;
        const a = 0.08 + 0.09 * s * s + 0.5 * m * m;
        ctx.fillStyle = m > 0.08 ? `rgba(139,156,255,${a})` : `rgba(255,255,255,${a})`;
        ctx.fillRect(x - 0.75, y - 0.75, 1.5, 1.5);
      }
      const p = (t / 2600) % 1;
      ctx.fillStyle = `rgba(242,86,75,${0.55 + 0.3 * Math.sin(t / 420)})`;
      ctx.beginPath(); ctx.arc(red[0], red[1], 2, 0, 7); ctx.fill();
      if (!reduce) {
        ctx.strokeStyle = `rgba(242,86,75,${0.35 * (1 - p)})`;
        ctx.beginPath(); ctx.arc(red[0], red[1], 2 + p * 18, 0, 7); ctx.stroke();
      }
    };
    const loop = (t) => { draw(t); raf = live ? requestAnimationFrame(loop) : 0; };
    const start = () => { if (!raf && live && !reduce) raf = requestAnimationFrame(loop); };
    size();
    draw(0);
    addEventListener('resize', () => { size(); draw(performance.now()); });
    const hero = canvas.parentElement;
    hero.addEventListener('pointermove', (e) => {
      const r = canvas.getBoundingClientRect();
      mouse.x = e.clientX - r.left; mouse.y = e.clientY - r.top;
    });
    hero.addEventListener('pointerleave', () => { mouse.x = mouse.y = -1e4; });
    let seen = true;
    watch(hero, (v) => { seen = v; live = seen && !document.hidden; start(); });
    document.addEventListener('visibilitychange', () => { live = seen && !document.hidden; start(); });
  }

  /* the IDE: the steps in #demo-script, played by four agent cursors while the IDE is on screen */
  const ide = $('[data-ide]');
  const script = $('#demo-script');
  if (!ide || !script) return;
  const data = JSON.parse(script.textContent);
  const log = $('.chat-log', ide), input = $('.chat-input', ide), tabs = $('.tabs', ide), code = $('.code', ide), term = $('.term-log', ide);
  const files = {};
  let live = false, seen = false;
  const sleep = (ms) => new Promise((done) => {
    if (reduce) return done();
    const go = () => (live ? done() : setTimeout(go, 250));
    setTimeout(go, ms);
  });
  watch(ide, (v) => { seen = v; live = seen && !document.hidden; }, '-10% 0px');
  document.addEventListener('visibilitychange', () => { live = seen && !document.hidden; });

  const cursors = {};
  Object.entries(data.agents).forEach(([id, a]) => {
    const el = document.createElement('div');
    el.className = 'agent';
    el.innerHTML = `<svg width="14" height="18" viewBox="0 0 14 18" aria-hidden="true"><path d="M1.5 1.5l11 8.4-5 .9-2.4 5.7z" fill="${a.color}" stroke="#0b0c0e" stroke-width="1.2" stroke-linejoin="round"/></svg><span style="background:${a.color}">${a.name}</span>`;
    ide.appendChild(el);
    cursors[id] = el;
  });
  const point = (cursor, el) => { // to the end of el, inside the IDE; only the agent at work shows
    if (reduce || !cursor) return;
    const r = ide.getBoundingClientRect(), t = el.getBoundingClientRect();
    const x = Math.min(t.left - r.left + Math.min(t.width * 0.7, 240), r.width - 150);
    const y = Math.min(t.bottom - r.top - 6, r.height - 30); // the label hangs below the line, not over it
    Object.values(cursors).forEach((c) => { c.style.opacity = c === cursor ? 1 : 0; });
    cursor.style.transform = `translate(${Math.max(8, x)}px, ${Math.max(8, y)}px)`;
  };
  const line = (segments, cls) => {
    const el = document.createElement('span');
    el.className = `ln${cls ? ` ${cls}` : ''}`;
    (typeof segments === 'string' ? [['', segments]] : segments).forEach(([c, text]) => {
      const s = document.createElement('span');
      if (c) s.className = c;
      s.textContent = text;
      el.appendChild(s);
    });
    return el;
  };
  const idle = () => { input.textContent = data.placeholder; input.style.color = ''; };
  const trim = (box, keep) => { // drop old lines and keep the newest in view
    while (box.children.length > keep) box.firstChild.remove();
    box.scrollTop = box.scrollHeight;
  };

  const steps = {
    async you(s) {
      input.innerHTML = '<span></span><i class="caret"></i>';
      input.style.color = 'var(--text)';
      const typed = input.firstChild;
      for (const ch of s.you) { typed.textContent += ch; await sleep(55); }
      await sleep(350);
      const m = document.createElement('div');
      m.className = 'msg you';
      m.textContent = s.you;
      log.appendChild(m); trim(log, 8);
      idle();
    },
    async say(s) {
      const a = data.agents[s.by];
      const m = document.createElement('div');
      m.className = 'msg';
      m.innerHTML = `<span class="who" style="color:${a.color}">${a.name}</span>${s.say}`;
      log.appendChild(m); trim(log, 8);
      point(cursors[s.by], m);
    },
    async file(s) {
      if (!files[s.file]) {
        const t = document.createElement('span');
        t.className = 'tab'; t.textContent = s.file;
        tabs.appendChild(t); files[s.file] = { tab: t, lines: [] };
      }
      Object.values(files).forEach((f) => f.tab.classList.toggle('on', f === files[s.file]));
      code.textContent = '';
      files[s.file].lines.forEach((l) => code.appendChild(line(l)));
      point(cursors[s.by], code);
      await sleep(500);
      for (const l of s.code) {
        files[s.file].lines.push(l);
        const el = line(l);
        code.appendChild(el);
        code.scrollTop = code.scrollHeight;
        point(cursors[s.by], el);
        await sleep(170);
      }
    },
    async cmd(s) {
      const el = line('', 'cmd');
      term.appendChild(el); trim(term, 14);
      point(cursors[s.by], el);
      await sleep(400);
      for (const ch of s.cmd) { el.firstChild.textContent += ch; await sleep(18); }
      for (const [cls, text] of s.out || []) {
        await sleep(420);
        term.appendChild(line(text, cls)); trim(term, 14);
      }
    },
  };
  const play = async (s) => {
    if (s.you) await steps.you(s);
    else if (s.say) await steps.say(s);
    else if (s.file) await steps.file(s);
    else if (s.cmd) await steps.cmd(s);
    await sleep(s.wait || 900);
  };
  const reset = () => {
    log.textContent = ''; tabs.textContent = ''; code.textContent = ''; term.textContent = '';
    Object.keys(files).forEach((k) => delete files[k]);
    Object.values(cursors).forEach((c) => { c.style.opacity = 0; c.style.transform = `translate(${ide.clientWidth / 2}px, ${ide.clientHeight / 2}px)`; });
    idle();
  };
  (async () => {
    do {
      reset();
      await sleep(600);
      for (const s of data.steps) await play(s);
      await sleep(5000);
    } while (!reduce);
  })();
})();
