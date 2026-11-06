// gta introduction page: the swarm opening, the terminal demo and scroll reveals. Plain JavaScript, no dependencies.
(function () {
  "use strict";
  document.documentElement.classList.remove("no-js");
  var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // ------------------------------------------------------------------ the swarm
  // A crowd of agents (cursors) rushes in from every edge, settles into the word "gta", and one of them stays out
  // of line in terracotta: the defect gta caught.
  var canvas = document.getElementById("swarm");
  if (canvas && canvas.getContext) swarm(canvas);

  function swarm(canvas) {
    var ctx = canvas.getContext("2d");
    var INK = "#141413", IVORY = "#faf9f5", CLAY = "#d97757";
    var label = canvas.getAttribute("data-defect") || "";
    var W = 0, H = 0, dpr = 1, particles = [], defect = null, t0 = 0, last = 0, running = true, visible = true;
    var mouse = { x: -1e4, y: -1e4 }, shock = null, IMPACT = 2.15, REVEAL = 2.7, word = null;

    // a mouse pointer, tip at (0, 0)
    var ARROW = new Path2D("M0 0 L0 15 L3.6 11.4 L6.4 17.6 L8.8 16.6 L6 10.6 L11 10.6 Z");

    function layout() {
      var r = canvas.getBoundingClientRect();
      W = r.width; H = r.height; dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
      var targets = sampleWord();
      var max = W < 700 ? 420 : 1000;
      shuffle(targets);
      targets = targets.slice(0, max);
      var old = particles;
      particles = targets.map(function (p, i) {
        var q = old[i] || spawn(i);
        q.tx = p.x; q.ty = p.y;
        return q;
      });
      // the defect: one agent the formation does not take, just right of the word
      defect = particles[particles.length - 1];
      if (defect && word) {
        // beside the word on a wide screen, under it on a narrow one, where its label has room
        defect.tx = word.narrow ? word.left + 6 : Math.min(W - 40, word.right + word.size * 0.12);
        defect.ty = word.narrow ? word.bottom + 18 : word.top + word.size * 0.18;
        defect.isDefect = true;
      }
      // smaller cursors on a small screen, so the letters stay readable
      particles.forEach(function (p) { p.s = (p.base || (p.base = p.s)) * (W < 700 ? 0.62 : 1); });
      if (reduced) particles.forEach(function (p) { p.x = p.tx; p.y = p.ty; p.vx = p.vy = 0; p.delay = 0; p.rot = 0; });
    }

    function sampleWord() {
      var off = document.createElement("canvas");
      off.width = Math.max(1, Math.round(W)); off.height = Math.max(1, Math.round(H));
      var o = off.getContext("2d");
      var narrow = W < 700;
      var size = narrow ? Math.min(W * 0.66, H * 0.3) : Math.min(W * 0.4, H * 0.5);
      var font = "600 " + size + "px " + getComputedStyle(document.body).getPropertyValue("--serif");
      o.font = font; o.textAlign = "center"; o.textBaseline = "alphabetic";
      var cx = W / 2, base = (narrow ? H * 0.3 : H * 0.36) + size * 0.36;
      o.fillText("gta", cx, base);
      var m = o.measureText("gta");
      word = { left: cx - m.width / 2, right: cx + m.width / 2, top: base - size * 0.72, bottom: base + size * 0.22, size: size, narrow: narrow };
      var data = o.getImageData(0, 0, off.width, off.height).data, pts = [];
      var step = Math.max(3, Math.round(size / 46));
      for (var y = 0; y < off.height; y += step) {
        for (var x = 0; x < off.width; x += step) {
          if (data[(y * off.width + x) * 4 + 3] > 128) pts.push({ x: x, y: y });
        }
      }
      return pts;
    }

    function spawn(i) {
      // from a random point just outside the edges, released over the first half second
      var side = i % 4, u = Math.random();
      var x = side === 0 ? -20 : side === 1 ? W + 20 : u * W;
      var y = side === 2 ? -20 : side === 3 ? H + 20 : u * H;
      if (side < 2) y = Math.random() * H; else x = Math.random() * W;
      return {
        x: x, y: y, vx: 0, vy: 0, rot: Math.random() * 6.28,
        delay: Math.random() * 0.55, k: 0.018 + Math.random() * 0.03, swirl: (Math.random() < 0.5 ? -1 : 1) * (0.6 + Math.random()),
        s: 0.62 + Math.random() * 0.32, ph: Math.random() * 6.28
      };
    }

    function shuffle(a) {
      for (var i = a.length - 1; i > 0; i--) { var j = (Math.random() * (i + 1)) | 0, t = a[i]; a[i] = a[j]; a[j] = t; }
    }

    function step(now) {
      if (!running) return;
      var t = (now - t0) / 1000, dt = Math.min(0.05, (now - last) / 1000 || 0.016);
      last = now;
      if (visible) { update(t, dt); draw(t); }
      requestAnimationFrame(step);
    }

    function update(t, dt) {
      if (!shock && t >= IMPACT) shock = { t: t };
      var f = dt * 60;
      for (var i = 0; i < particles.length; i++) {
        var p = particles[i];
        if (t < p.delay) continue;
        var dx = p.tx - p.x, dy = p.ty - p.y;
        // the swarm swirls on the way in, then the swirl dies down and the formation holds
        var swirl = Math.max(0, 1 - t / 1.8) * p.swirl * 0.9;
        var ax = dx * p.k - dy * p.k * swirl, ay = dy * p.k + dx * p.k * swirl;
        var mx = p.x - mouse.x, my = p.y - mouse.y, md = mx * mx + my * my;
        if (md < 9000) { var push = (9000 - md) / 9000 * 2.2; var d = Math.sqrt(md) || 1; ax += mx / d * push; ay += my / d * push; }
        p.vx = (p.vx + ax * f) * Math.pow(0.86, f);
        p.vy = (p.vy + ay * f) * Math.pow(0.86, f);
        p.x += p.vx * f; p.y += p.vy * f;
        var speed = Math.abs(p.vx) + Math.abs(p.vy);
        var want = speed > 0.6 ? Math.atan2(p.vy, p.vx) + Math.PI / 2 + 0.33 : 0;
        p.rot += angleDiff(want, p.rot) * Math.min(1, 0.18 * f);
      }
    }

    function angleDiff(a, b) { var d = a - b; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; return d; }

    function draw(t) {
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.fillStyle = IVORY; ctx.fillRect(0, 0, W, H);
      // the snap: the formation tightens a little at the moment of impact
      var snap = shock ? 1 + 0.035 * Math.exp(-(t - shock.t) * 7) * Math.sin((t - shock.t) * 18) : 1;
      ctx.save();
      ctx.translate(W / 2, H * 0.4); ctx.scale(snap, snap); ctx.translate(-W / 2, -H * 0.4);
      ctx.lineWidth = 1.1; ctx.strokeStyle = IVORY; ctx.lineJoin = "round";
      for (var i = 0; i < particles.length; i++) {
        var p = particles[i];
        if (t < p.delay || p.isDefect) continue;
        var breathe = reduced ? 0 : Math.sin(t * 1.3 + p.ph) * 0.5;
        cursor(p.x + breathe, p.y, p.rot, p.s, INK);
      }
      ctx.restore();
      if (shock && !reduced) {
        var a = t - shock.t;
        if (a < 1.1) {
          ctx.beginPath();
          ctx.arc(W / 2, H * 0.4, a * Math.max(W, H) * 0.9, 0, Math.PI * 2);
          ctx.strokeStyle = "rgba(217,119,87," + (0.55 * (1 - a / 1.1)) + ")";
          ctx.lineWidth = 2 + 10 * (1 - a / 1.1);
          ctx.stroke();
        }
      }
      if (defect && t >= defect.delay) {
        var on = reduced || t >= REVEAL;
        cursor(defect.x, defect.y, defect.rot, defect.s * (on ? 1.5 : 1), on ? CLAY : INK);
        if (on) {
          var k = reduced ? 1 : Math.min(1, (t - REVEAL) / 0.6);
          var pulse = reduced ? 0 : (t * 0.9) % 1;
          ctx.beginPath();
          ctx.arc(defect.x + 4, defect.y + 7, 14 + pulse * 26, 0, Math.PI * 2);
          ctx.strokeStyle = "rgba(217,119,87," + (0.5 * (1 - pulse) * k) + ")";
          ctx.lineWidth = 1.5; ctx.stroke();
          if (label) {
            ctx.globalAlpha = k;
            ctx.font = "12px " + getComputedStyle(document.body).getPropertyValue("--mono");
            ctx.fillStyle = "#b85c3d"; ctx.textAlign = "left";
            var lx = defect.x + 22, ly = defect.y + 30;
            if (lx + ctx.measureText(label).width > W - 12) { ctx.textAlign = "right"; lx = defect.x - 10; }
            ctx.fillText(label, lx, ly);
            ctx.globalAlpha = 1;
          }
        }
      }
    }

    function cursor(x, y, rot, s, color) {
      ctx.save();
      ctx.translate(x, y); ctx.rotate(rot); ctx.scale(s, s);
      ctx.fillStyle = color; ctx.stroke(ARROW); ctx.fill(ARROW);
      ctx.restore();
    }

    canvas.addEventListener("pointermove", function (e) { var r = canvas.getBoundingClientRect(); mouse.x = e.clientX - r.left; mouse.y = e.clientY - r.top; });
    canvas.addEventListener("pointerleave", function () { mouse.x = mouse.y = -1e4; });
    var resizeTimer;
    window.addEventListener("resize", function () { clearTimeout(resizeTimer); resizeTimer = setTimeout(function () { layout(); if (reduced) draw(99); }, 120); });
    if ("IntersectionObserver" in window) {
      new IntersectionObserver(function (es) { visible = es[0].isIntersecting; }).observe(canvas);
    }

    var start = function () {
      layout();
      if (reduced) { draw(99); return; }
      t0 = last = performance.now();
      requestAnimationFrame(step);
    };
    // the word is drawn in the page's serif: wait for fonts so the formation does not jump
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(start); else start();
  }

  // ------------------------------------------------------------------ the terminal demo
  var term = document.querySelector("[data-terminal]");
  if (term) {
    var lines = Array.prototype.slice.call(term.querySelectorAll(".l"));
    var played = false;
    var play = function () {
      if (played) return;
      played = true;
      if (reduced) { lines.forEach(function (l) { l.classList.add("on"); }); return; }
      var t = 0;
      lines.forEach(function (l) {
        t += Number(l.getAttribute("data-wait") || 90);
        setTimeout(function () {
          l.classList.add("on");
          if (l.hasAttribute("data-type")) type(l);
        }, t);
        if (l.hasAttribute("data-type")) t += l.textContent.length * 38;
      });
    };
    var type = function (el) {
      var text = el.textContent, i = 0;
      el.textContent = "";
      var caret = document.createElement("span"); caret.className = "caret";
      var tick = function () {
        el.textContent = text.slice(0, ++i);
        el.appendChild(caret);
        if (i < text.length) setTimeout(tick, 38); else setTimeout(function () { caret.remove(); }, 300);
      };
      tick();
    };
    if ("IntersectionObserver" in window) {
      new IntersectionObserver(function (es, ob) { if (es[0].isIntersecting) { play(); ob.disconnect(); } }, { threshold: 0.35 }).observe(term);
    } else play();
  }

  // ------------------------------------------------------------------ reveals and the counter
  var items = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && !reduced) {
    var ob = new IntersectionObserver(function (es) {
      es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add("in"); ob.unobserve(e.target); } });
    }, { threshold: 0.15 });
    items.forEach(function (el) { ob.observe(el); });
  } else items.forEach(function (el) { el.classList.add("in"); });

  document.querySelectorAll("[data-count]").forEach(function (el) {
    var n = Number(el.getAttribute("data-count"));
    if (reduced || !("IntersectionObserver" in window)) { el.textContent = n; return; }
    el.textContent = "0";
    new IntersectionObserver(function (es, o) {
      if (!es[0].isIntersecting) return;
      o.disconnect();
      var s = performance.now();
      (function f(now) {
        var k = Math.min(1, (now - s) / 900);
        el.textContent = Math.round(n * (1 - Math.pow(1 - k, 3)));
        if (k < 1) requestAnimationFrame(f);
      })(s);
    }, { threshold: 0.5 }).observe(el);
  });
})();
