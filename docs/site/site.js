/* gta introduction. The loader, menu, clock and scroll are the page's own.
   The workbench and the seven shortcuts replay strings from #copy.
   They are scripted. They do not call a model and they do not run gtaw. */
const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
const coarse = matchMedia('(pointer: coarse)').matches;
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

let lenis = null;
function setLock(on) {
  document.documentElement.classList.toggle('lock', on);
  if (lenis) { if (on) lenis.stop(); else lenis.start(); }
}
if (!reduce) {
  import('./vendor/lenis.js').then((mod) => {
    lenis = new mod.default({ smoothWheel: true, syncTouch: false });
    if (document.documentElement.classList.contains('lock')) lenis.stop();
    const raf = (t) => { lenis.raf(t); requestAnimationFrame(raf); };
    requestAnimationFrame(raf);
  }).catch(() => {});
}

function scaleType() {
  const FONT_BASE = 16, baseWidth = 1920, coef = 0.6666;
  const widthReduction = ((baseWidth - innerWidth) / baseWidth) * 100;
  const size = FONT_BASE - (FONT_BASE * (widthReduction * coef)) / 100;
  document.documentElement.style.fontSize = size > FONT_BASE ? `${size}px` : '';
}
scaleType();
addEventListener('resize', scaleType);

const loader = $('#loader');
const fill = loader && $('.fill', loader);
const count = loader && $('.count', loader);
function dropLoader() {
  document.documentElement.classList.add('ready');
  document.documentElement.classList.remove('lock');
  if (lenis) lenis.start();
  if (loader && loader.isConnected) {
    loader.classList.add('out');
    setTimeout(() => loader.remove(), reduce ? 0 : 720);
  }
}
if (!loader || reduce || document.documentElement.classList.contains('ready')) dropLoader();
else {
  setLock(true);
  const t0 = performance.now();
  const tick = (now) => {
    if (!loader.isConnected || loader.classList.contains('out')) return;
    const t = Math.min(1, (now - t0) / 1300);
    const e = t < 0.5 ? 4 * t * t * t : 1 - ((-2 * t + 2) ** 3) / 2;
    const n = Math.round(e * 100);
    if (count) count.textContent = String(n).padStart(3, '0');
    if (fill) fill.style.width = `${n}%`;
    if (t < 1) requestAnimationFrame(tick);
    else dropLoader();
  };
  requestAnimationFrame(tick);
}

const header = $('.header');
const onScroll = () => { if (header) header.classList.toggle('scrolled', scrollY > 8); };
addEventListener('scroll', onScroll, { passive: true });
onScroll();

const timeEl = $('[data-time]');
const dateEl = $('[data-date]');
if (timeEl && dateEl) {
  const months = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const paintClock = () => {
    const d = new Date();
    let h = d.getHours();
    const ap = h >= 12 ? 'pm' : 'am';
    h = h % 12 || 12;
    timeEl.textContent = `${h}:${String(d.getMinutes()).padStart(2, '0')}${ap}`;
    dateEl.textContent = `${d.getDate()} ${months[d.getMonth()]}, ${d.getFullYear()}`;
  };
  paintClock();
  setInterval(paintClock, 1000);
}

function go(id) {
  const el = document.getElementById(id);
  if (!el) return;
  if (lenis && !reduce) lenis.scrollTo(el, { offset: -8 });
  else el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
}

function trap(root) {
  const sel = 'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])';
  const nodes = () => $$(sel, root).filter((el) => {
    const s = getComputedStyle(el);
    return s.display !== 'none' && s.visibility !== 'hidden';
  });
  const previous = document.activeElement;
  const first = nodes()[0];
  if (first) first.focus();
  const onKey = (e) => {
    if (e.key === 'Escape') { e.preventDefault(); close(); return; }
    if (e.key !== 'Tab') return;
    const list = nodes();
    if (!list.length) return;
    const a = list[0], b = list[list.length - 1];
    if (e.shiftKey && document.activeElement === a) { e.preventDefault(); b.focus(); }
    else if (!e.shiftKey && document.activeElement === b) { e.preventDefault(); a.focus(); }
  };
  root.addEventListener('keydown', onKey);
  let close = () => {};
  return {
    onClose(fn) { close = fn; },
    release(restore = true) {
      root.removeEventListener('keydown', onKey);
      if (restore && previous && previous.focus) previous.focus();
    },
  };
}

const menu = $('#menu');
const modal = $('#install-dialog');
let menuTrap = null;
let modalTrap = null;
function openMenu() {
  if (!menu) return;
  menu.classList.add('open');
  menu.setAttribute('aria-hidden', 'false');
  setLock(true);
  menuTrap = trap(menu);
  menuTrap.onClose(closeMenu);
}
function closeMenu(opts = {}) {
  if (!menu || !menu.classList.contains('open')) return;
  const restore = opts.restore !== false;
  const unlock = opts.unlock !== false;
  menu.classList.remove('open');
  menu.setAttribute('aria-hidden', 'true');
  if (menuTrap) { const t = menuTrap; menuTrap = null; t.release(restore); }
  if (unlock && (!modal || !modal.classList.contains('open'))) setLock(false);
}
function openModal() {
  if (!modal) return;
  closeMenu({ restore: false, unlock: false });
  modal.classList.add('open');
  modal.setAttribute('aria-hidden', 'false');
  setLock(true);
  modalTrap = trap(modal);
  modalTrap.onClose(closeModal);
}
function closeModal() {
  if (!modal || !modal.classList.contains('open')) return;
  modal.classList.remove('open');
  modal.setAttribute('aria-hidden', 'true');
  if (modalTrap) { modalTrap.release(); modalTrap = null; }
  setLock(false);
}
$$('[data-menu]').forEach((b) => b.addEventListener('click', openMenu));
$$('[data-close-menu]').forEach((b) => b.addEventListener('click', () => closeMenu()));
$$('[data-open="install"]').forEach((b) => b.addEventListener('click', openModal));
$$('[data-close-modal]').forEach((b) => b.addEventListener('click', closeModal));
if (modal) modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(); });
$$('[data-go]').forEach((b) => b.addEventListener('click', () => { closeMenu(); go(b.dataset.go); }));

const hero = $('.hero');
const scenes = ['lock', 'edit', 'refuse'];
let scene = 0;
const live = $('#scene-live');
function showScene(i) {
  if (!hero) return;
  scene = (i + scenes.length) % scenes.length;
  hero.dataset.scene = scenes[scene];
  $$('.dots i', hero).forEach((dot, k) => dot.classList.toggle('on', k === scene));
  const title = $(`.ttl.only-${scenes[scene]}`, hero);
  if (live && title) live.textContent = title.textContent.trim();
}
$$('[data-prev]').forEach((b) => b.addEventListener('click', () => showScene(scene - 1)));
$$('[data-next]').forEach((b) => b.addEventListener('click', () => showScene(scene + 1)));
showScene(0);

const rig = $('.rig');
if (hero && rig && !reduce && !coarse) {
  let tx = 0, ty = 0, cx = 0, cy = 0, running = false;
  const tick = () => {
    cx += (tx - cx) * 0.08;
    cy += (ty - cy) * 0.08;
    rig.style.transform = `rotateX(${cx.toFixed(2)}deg) rotateY(${cy.toFixed(2)}deg)`;
    if (Math.abs(tx - cx) > 0.05 || Math.abs(ty - cy) > 0.05) requestAnimationFrame(tick);
    else running = false;
  };
  const start = () => { if (!running) { running = true; requestAnimationFrame(tick); } };
  hero.addEventListener('pointermove', (e) => {
    if (e.pointerType !== 'mouse' || document.hidden) return;
    const r = hero.getBoundingClientRect();
    if (r.bottom < 0 || r.top > innerHeight) return;
    const px = (e.clientX - r.left) / r.width - 0.5;
    const py = (e.clientY - r.top) / r.height - 0.5;
    ty = Math.max(-8, Math.min(8, px * 12));
    tx = Math.max(-6, Math.min(6, py * -8));
    start();
  });
  hero.addEventListener('pointerleave', () => { tx = 0; ty = 0; start(); });
}

const rises = $$('.rise');
if (reduce || !('IntersectionObserver' in window)) rises.forEach((el) => el.classList.add('in'));
else {
  const io = new IntersectionObserver((entries) => entries.forEach((e) => {
    if (!e.isIntersecting) return;
    e.target.classList.add('in');
    io.unobserve(e.target);
  }), { rootMargin: '0px 0px -8% 0px' });
  rises.forEach((el) => io.observe(el));
}

const counts = $$('[data-count]');
if (reduce) counts.forEach((el) => { el.textContent = el.dataset.count; });
else {
  let lastCount = 0;
  const paintCounts = () => {
    const now = performance.now();
    if (now - lastCount < 30) return;
    lastCount = now;
    let pending = false;
    counts.forEach((el) => {
      const target = Number(el.dataset.count);
      const r = el.getBoundingClientRect();
      const y0 = innerHeight;
      const y1 = innerHeight / 2 - r.height / 2;
      const p = Math.max(0, Math.min(1, (y0 - r.top) / (y0 - y1 || 1)));
      el.textContent = String(Math.round(p * target));
      if (p < 1) pending = true;
    });
    if (!pending) removeEventListener('scroll', paintCounts);
  };
  addEventListener('scroll', paintCounts, { passive: true });
  paintCounts();
}

$$('[data-copy]').forEach((b) => b.addEventListener('click', async () => {
  const label = b.textContent;
  try { await navigator.clipboard.writeText(b.dataset.copy); b.textContent = b.dataset.done || '✓'; }
  catch (_) { b.textContent = '—'; }
  setTimeout(() => { b.textContent = label; }, 1600);
}));

let copy = null;
const copyNode = $('#copy');
if (copyNode) {
  try { copy = JSON.parse(copyNode.textContent); }
  catch (_) { copy = null; }
}

const line = (segments, cls) => {
  const el = make('span', `ln${cls ? ` ${cls}` : ''}`);
  (typeof segments === 'string' ? [['', segments]] : segments).forEach(([c, text]) => el.appendChild(make('span', c || '', text)));
  return el;
};

class Scene {
  constructor(el) { this.el = el; this.started = false; this.play = async () => {}; scenesOnPage.push(this); }
  visible() {
    if (document.hidden) return false;
    const r = this.el.getBoundingClientRect();
    const seen = (Math.min(r.bottom, innerHeight) - Math.max(r.top, 0)) / Math.min(innerHeight, Math.max(1, r.height));
    return seen >= 0.2;
  }
  async wait(ms) {
    if (reduce) return;
    let left = ms;
    while (left > 0) {
      if (!this.visible()) { await sleep(150); continue; }
      const step = Math.min(80, left);
      await sleep(step);
      left -= step;
    }
  }
}
const scenesOnPage = [];
const arm = () => {
  for (const s of scenesOnPage) {
    if (s.started) continue;
    if (reduce || s.visible()) { s.started = true; s.play(); }
  }
};

if (copy && copy.demo) {
  const ide = $('[data-ide]');
  if (ide) {
    const sec = ide.closest('section');
    const S = new Scene(sec);
    const D = copy.demo;
    const log = $('.chat-log', ide), input = $('.chat-input', ide), tabs = $('.tabs', ide), code = $('.code', ide), term = $('.term-log', ide);
    const countEl = $('.count', ide), stageEls = $$('.stage', sec);
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
        const bar = $('.meter b', el);
        if (bar) bar.style.width = k < i ? '100%' : k > i ? '0%' : `${Math.min(100, frac * 100)}%`;
      });
      if (countEl) countEl.textContent = `${total}/${D.steps.length}`;
    };
    const showFile = (name) => {
      if (!files[name]) { files[name] = { tab: make('span', 'tab', name), lines: [] }; tabs.appendChild(files[name].tab); }
      Object.entries(files).forEach(([k, f]) => f.tab.classList.toggle('on', k === name));
      if (shown !== name) { code.textContent = ''; files[name].lines.forEach((l) => code.appendChild(line(l))); shown = name; }
    };
    const reset = () => {
      log.textContent = ''; tabs.textContent = ''; code.textContent = ''; term.textContent = ''; shown = '';
      Object.keys(files).forEach((k) => delete files[k]);
      idleInput();
      stage(0, 0, 0);
    };
    const step = async (s, fast, alive) => {
      const W = (ms) => (fast || reduce ? Promise.resolve() : S.wait(ms));
      if (s.you) {
        if (!fast && !reduce) {
          await W(280);
          if (!alive()) return;
          input.classList.add('typing');
          input.textContent = '';
          for (const ch of s.you) { if (!alive()) return; input.textContent += ch; await W(42); }
          await W(180);
          if (!alive()) return;
        }
        log.appendChild(make('div', 'msg you', s.you));
        trim(log, 9);
        idleInput();
      } else if (s.say) {
        const a = copy.agents[s.by];
        const m = make('div', 'msg');
        m.style.setProperty('--c', a.color);
        m.innerHTML = `<span class="who">${esc(a.name)}</span>${s.say}`;
        log.appendChild(m);
        trim(log, 9);
      } else if (s.file) {
        showFile(s.file);
        if (!fast) await W(220);
        for (const l of s.code) {
          if (!alive()) return;
          files[s.file].lines.push(l);
          code.appendChild(line(l));
          code.scrollTop = code.scrollHeight;
          if (!fast) await W(120);
        }
      } else if (s.cmd) {
        const el = line('', 'cmd');
        term.appendChild(el);
        trim(term, 14);
        for (const ch of s.cmd) { if (!alive()) return; el.firstChild.textContent += ch; await W(12); }
        if (s.lock) {
          await W(200);
          const name = Object.keys(files).pop();
          if (name) showFile(name);
          $$('.num', code).filter((n) => n.textContent === s.lock).forEach((n) => { n.classList.add('locked'); n.dataset.l = D.locked; });
        }
        for (const [cls, text] of s.out || []) { await W(280); if (!alive()) return; term.appendChild(line(text, cls)); trim(term, 14); }
      }
    };
    const play = async (from) => {
      const my = ++run;
      const alive = () => my === run;
      const start = Math.max(0, from);
      const pressed = D.steps[start] ? D.steps[start].stage : 0;
      reset();
      stage(pressed, 0, start, pressed);
      const done = [0, 0, 0, 0];
      for (let i = 0; i < D.steps.length; i++) {
        const s = D.steps[i];
        const fast = i < from;
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
      if (alive() && S.visible()) play(0);
    };
    S.play = () => play(0);
    stageEls.forEach((el, i) => el.addEventListener('click', () => {
      S.started = true;
      play(D.steps.findIndex((s) => s.stage === i));
    }));
    const restart = $('[data-restart]', ide);
    if (restart) restart.addEventListener('click', () => { S.started = true; play(0); });
    reset();
  }
}

if (copy && copy.cheat) {
  const arena = $('.arena');
  if (arena) {
    const C = copy.cheat;
    const S = new Scene(arena.closest('section'));
    const editor = $('.editor', arena), tabsEl = $('.tabs', editor), codeEl = $('.code', editor), termEl = $('.term-log', editor);
    const buttons = $$('.attack', arena), score = $('[data-score]', S.el), won = $('.won', S.el);
    const total = C.attacks.length;
    let open = 'test', busy = false, caught = 0;
    const W = (ms) => (reduce ? Promise.resolve() : S.wait(ms));
    const show = (key) => {
      open = key;
      tabsEl.textContent = '';
      Object.entries(C.files).forEach(([k, f]) => tabsEl.appendChild(make('span', `tab${k === key ? ' on' : ''}`, f.name)));
      codeEl.textContent = '';
      C.files[key].lines.forEach((l) => codeEl.appendChild(line(l)));
    };
    const say = (cls, text) => { termEl.appendChild(line(text, cls)); while (termEl.children.length > 12) termEl.firstChild.remove(); termEl.scrollTop = termEl.scrollHeight; };
    const setScore = () => { score.innerHTML = `${caught}<span>/${total}</span>`; };
    const reset = () => {
      caught = 0; setScore(); busy = false;
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
        if (open !== A.file) { show(A.file); await W(220); }
        const lines = codeEl.children;
        if (A.edit === 'insert') {
          target = line(A.text, 'edit');
          codeEl.insertBefore(target, lines[A.line] || null);
          const segs = Array.from(target.children), full = segs.map((s) => s.textContent);
          segs.forEach((s) => { s.textContent = ''; });
          await W(280);
          for (let k = 0; k < segs.length; k++) for (const ch of full[k]) { segs[k].textContent += ch; await W(28); }
        } else {
          target = lines[A.line];
          target.classList.add('edit');
          await W(360);
          if (A.edit === 'strike') target.classList.add('gone');
          else {
            const seg = Array.from(target.children).find((s) => s.textContent.includes(A.from));
            const at = seg.textContent.indexOf(A.from);
            const before = seg.textContent.slice(0, at), after = seg.textContent.slice(at + A.from.length);
            for (let k = A.from.length; k >= 0; k--) { seg.textContent = before + A.from.slice(0, k) + after; await W(16); }
            for (let k = 1; k <= A.to.length; k++) { seg.textContent = before + A.to.slice(0, k) + after; await W(24); }
          }
        }
        await W(200);
      }
      if (A.cmd) {
        const typed = line('', 'cmd');
        termEl.appendChild(typed);
        for (const ch of A.cmd) { typed.firstChild.textContent += ch; await W(12); }
        await W(180);
      }
      const cmd = line('', 'cmd');
      termEl.appendChild(cmd);
      for (const ch of C.submit) { cmd.firstChild.textContent += ch; await W(12); }
      for (const [cls, text] of A.out) { await W(260); say(cls, text); }
      if (target) { target.classList.remove('edit'); target.classList.add('blocked'); }
      await W(700);
      if (A.file) {
        show(A.file);
        const back = codeEl.children[A.line];
        if (back && A.edit !== 'insert') back.classList.add('restored');
      }
      (A.after || []).forEach(([cls, text]) => say(cls, text));
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
    S.play = async () => {};
    reset();
  }
}

if (reduce) scenesOnPage.forEach((s) => { s.started = true; s.play(); });
else {
  addEventListener('scroll', arm, { passive: true });
  addEventListener('resize', arm);
  document.addEventListener('visibilitychange', arm);
  arm();
}
