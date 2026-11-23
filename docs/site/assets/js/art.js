/* The picture of the title scene, drawn on a 2D canvas: three printed shapes and twelve marks.
   A pink blob is the agents, a blue square is gtaw, a yellow circle is the person. Where the square covers the blob the
   ink is violet (work under test); where the square and the circle overlap it is green. A mark is one scenario: it is
   written in the blob, runs in the square, and rests in the green only when gtaw has the evidence and a person has
   signed. No dependencies. */
(function () {
  'use strict';

  var TAU = Math.PI * 2;
  var HEIGHT = 0.77; // the picture is this tall, in widths
  var BLOB = { x: 0.27, y: 0.31, r: 0.2 };
  var SQUARE = { x: 0.385, y: 0.09, s: 0.36 };
  var CIRCLE = { x: 0.705, y: 0.5, r: 0.215 };
  var FINAL = { passed: true, pinned: true };
  var IN_SQUARE = { running: true, review: true, failed: true, triage: true };

  function clamp(v) {
    return v < 0 ? 0 : v > 1 ? 1 : v;
  }
  function backOut(p) {
    var c = 1.5;
    return 1 + (c + 1) * Math.pow(p - 1, 3) + c * Math.pow(p - 1, 2);
  }

  /** Places in the green where a mark comes to rest: inside the square and inside the circle, row by row. */
  function restingPlaces() {
    var places = [];
    var margin = 0.024;
    for (var y = SQUARE.y + SQUARE.s - 0.027; y > CIRCLE.y - CIRCLE.r; y -= 0.04) {
      var row = [];
      for (var x = SQUARE.x + SQUARE.s - 0.027; x > CIRCLE.x - CIRCLE.r; x -= 0.042) {
        var dx = x - CIRCLE.x;
        var dy = y - CIRCLE.y;
        if (Math.sqrt(dx * dx + dy * dy) <= CIRCLE.r - margin) row.unshift({ x: x, y: y });
      }
      places = places.concat(row);
    }
    return places;
  }

  function Art(canvas, count, options) {
    options = options || {};
    this.canvas = canvas;
    this.g = canvas.getContext('2d');
    this.colors = options.colors;
    this.reduced = !!options.reduced;
    this.places = restingPlaces();
    this.rested = 0;
    this.waited = 0;
    this.failed = 0;
    this.marks = [];
    for (var i = 0; i < count; i++) {
      var angle = i * 2.39996; // the golden angle: an even scatter
      var radius = BLOB.r * 0.62 * Math.sqrt((i + 0.6) / count);
      var home = { x: BLOB.x + Math.cos(angle) * radius, y: BLOB.y + Math.sin(angle) * radius };
      this.marks.push({
        index: i, state: 'blank', home: home, x: home.x, y: home.y, tx: home.x, ty: home.y,
        tilt: (i * 1.7) % Math.PI, angle: (i * 1.7) % Math.PI, pop: 0, seed: i * 12.9898, rest: -1, wait: -1, fail: -1
      });
    }
    this.flecks = [];
    for (var f = 0; f < 30; f++) {
      this.flecks.push({ a: f * 2.1, r: 0.12 + ((f * 0.37) % 0.7), w: 0.12 + ((f * 0.13) % 0.25), s: 0.6 + ((f * 0.29) % 0.8) });
    }
    this.pointer = { x: 0, y: 0, tx: 0, ty: 0 };
    this.claimUntil = 0;
    this.markScale = 1;
    this.thud = 0;
    this.time = 0;
    this.last = 0;
    this.born = 0;
    this.running = false;
    this.visible = true;
    this.frame = this.frame.bind(this);
    this.grain = this.makeGrain();
    this.resize();
    this.start();
  }

  Art.prototype.makeGrain = function () {
    var tile = document.createElement('canvas');
    tile.width = tile.height = 96;
    var g = tile.getContext('2d');
    var image = g.createImageData(96, 96);
    var value = 7;
    for (var i = 0; i < image.data.length; i += 4) {
      value = (value * 16807) % 2147483647; // the same specks on every visit
      image.data[i + 3] = value % 100 < 16 ? 70 + (value % 110) : 0;
    }
    g.putImageData(image, 0, 0);
    return tile;
  };

  Art.prototype.resize = function () {
    var width = this.canvas.parentNode.clientWidth || 600;
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.u = width;
    this.dpr = dpr;
    this.canvas.width = Math.round(width * dpr);
    this.canvas.height = Math.round(width * HEIGHT * dpr);
    this.canvas.style.height = width * HEIGHT + 'px';
    this.pattern = this.g.createPattern(this.grain, 'repeat');
    this.draw(this.time);
  };

  /** Where a mark belongs in each state, and how it looks there. */
  Art.prototype.place = function (mark) {
    var i = mark.index;
    switch (mark.state) {
      case 'writing':
        return { x: 0.402 + (i % 2) * 0.022, y: 0.235 + Math.floor(i / 2) * 0.028 };
      case 'running': case 'review': case 'failed':
        return { x: 0.5 + (i % 4) * 0.058, y: 0.135 + Math.floor(i / 4) * 0.05 };
      case 'triage':
        if (mark.fail < 0) mark.fail = this.failed++;
        return { x: 0.468, y: 0.31 + mark.fail * 0.022 };
      case 'waiting':
        if (mark.wait < 0) mark.wait = this.waited++;
        var w = mark.wait % 6; // a block of three by two in the circle, below the green
        return { x: CIRCLE.x - 0.064 + (w % 3) * 0.064, y: CIRCLE.y + 0.062 + Math.floor(w / 3) * 0.06 };
      case 'passed': case 'pinned':
        if (mark.rest < 0) mark.rest = this.rested++;
        return this.places[mark.rest % this.places.length];
      default:
        return mark.home;
    }
  };

  /** Moves a mark to a state of its scenario. */
  Art.prototype.set = function (index, state) {
    var mark = this.marks[index];
    if (!mark || mark.state === state) return;
    if (state === 'blank') {
      mark.rest = mark.wait = mark.fail = -1;
      if (!this.marks.some(function (m) { return m !== mark && m.state !== 'blank'; })) this.rested = this.waited = this.failed = 0;
    }
    mark.state = state;
    var to = this.place(mark);
    mark.tx = to.x;
    mark.ty = to.y;
    mark.pop = 1;
    if (this.reduced) {
      mark.x = to.x;
      mark.y = to.y;
      mark.pop = 0;
      this.draw(this.time);
    }
  };

  /** The visitor claims green for everything. The picture shows the claim, and then what can be proven. */
  Art.prototype.claim = function () {
    var art = this;
    this.claimUntil = performance.now() + 950;
    this.thudAt = this.claimUntil;
    if (this.reduced) {
      this.draw(this.time);
      window.setTimeout(function () { art.draw(art.time); }, 980);
    }
  };

  /** Stage mode: the marks are drawn larger, for the back of the room. */
  Art.prototype.setMarkScale = function (scale) {
    this.markScale = scale;
    this.draw(this.time);
  };

  Art.prototype.point = function (x, y) {
    this.pointer.tx = x;
    this.pointer.ty = y;
  };

  Art.prototype.start = function () {
    if (this.running || this.reduced) return;
    this.running = true;
    this.last = 0;
    window.requestAnimationFrame(this.frame);
  };

  Art.prototype.frame = function (now) {
    if (!this.visible || document.hidden) {
      this.running = false;
      return;
    }
    var dt = this.last ? Math.min(64, now - this.last) : 16;
    this.last = now;
    if (!this.born) this.born = now;
    this.time += dt / 1000;
    var ease = 1 - Math.exp(-dt / 190);
    for (var i = 0; i < this.marks.length; i++) {
      var m = this.marks[i];
      m.x += (m.tx - m.x) * ease;
      m.y += (m.ty - m.y) * ease;
      m.pop *= Math.exp(-dt / 160);
    }
    this.pointer.x += (this.pointer.tx - this.pointer.x) * (1 - Math.exp(-dt / 400));
    this.pointer.y += (this.pointer.ty - this.pointer.y) * (1 - Math.exp(-dt / 400));
    if (this.thudAt && now >= this.thudAt) {
      this.thud = 1;
      this.thudAt = 0;
    }
    this.thud *= Math.exp(-dt / 140);
    this.draw(this.time, now);
    window.requestAnimationFrame(this.frame);
  };

  Art.prototype.blobPath = function (g, t, scale, dx, dy) {
    var u = this.u;
    var cx = (BLOB.x + this.pointer.x * 0.014) * u + dx;
    var cy = (BLOB.y + this.pointer.y * 0.014) * u + dy;
    var n = 10;
    var points = [];
    for (var k = 0; k < n; k++) {
      var a = (k / n) * TAU + 0.16 * Math.sin(t * 0.21 + k);
      var r = BLOB.r * u * scale * (1 + 0.1 * Math.sin(t * 0.9 + k * 1.9) + 0.065 * Math.sin(t * 0.53 + k * 3.3 + 1.2));
      points.push([cx + Math.cos(a) * r, cy + Math.sin(a) * r]);
    }
    g.beginPath();
    for (var p = 0; p <= n; p++) {
      var here = points[p % n];
      var next = points[(p + 1) % n];
      var mx = (here[0] + next[0]) / 2;
      var my = (here[1] + next[1]) / 2;
      if (p === 0) g.moveTo(mx, my);
      else g.quadraticCurveTo(here[0], here[1], mx, my);
    }
    g.closePath();
  };

  Art.prototype.squarePath = function (g, scale, dx, dy) {
    var u = this.u;
    var s = SQUARE.s * u * scale;
    var cx = (SQUARE.x + SQUARE.s / 2) * u + dx;
    var cy = (SQUARE.y + SQUARE.s / 2) * u + dy;
    g.beginPath();
    g.rect(cx - s / 2, cy - s / 2, s, s);
  };

  Art.prototype.circlePath = function (g, scale, dx, dy) {
    var u = this.u;
    g.beginPath();
    g.arc(CIRCLE.x * u + dx, CIRCLE.y * u + dy, CIRCLE.r * u * scale, 0, TAU);
  };

  /** The circle as a hand draws it: not quite round, and the ends do not meet. */
  Art.prototype.loopPath = function (g, scale, dx, dy) {
    var u = this.u;
    g.beginPath();
    for (var k = 0; k <= 70; k++) {
      var a = -1.95 + (k / 70) * (TAU + 0.32);
      var r = CIRCLE.r * u * scale * (0.99 + 0.045 * (k / 70) + 0.012 * Math.sin(3 * a + 1) + 0.007 * Math.sin(7 * a));
      var x = CIRCLE.x * u + dx + Math.cos(a) * r;
      var y = CIRCLE.y * u + dy + Math.sin(a) * r;
      if (k === 0) g.moveTo(x, y);
      else g.lineTo(x, y);
    }
  };

  Art.prototype.draw = function (t, now) {
    var g = this.g;
    var u = this.u;
    var c = this.colors;
    if (now) this.now = now;
    var age = this.reduced ? 9999 : this.born ? this.now - this.born : -1; // nothing is printed before the first frame
    var sCircle = this.reduced ? 1 : backOut(clamp(age / 650));
    var sSquare = this.reduced ? 1 : backOut(clamp((age - 160) / 650)) * (1 + 0.03 * this.thud);
    var sBlob = this.reduced ? 1 : backOut(clamp((age - 320) / 650));
    var claiming = performance.now() < this.claimUntil;
    g.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    g.clearRect(0, 0, u, u * HEIGHT);
    g.lineCap = 'round';
    g.lineJoin = 'round';

    if (sCircle > 0) {
      this.circlePath(g, sCircle, 0, 0);
      g.fillStyle = c.yellow;
      g.fill();
    }
    if (sBlob > 0) {
      this.blobPath(g, t, sBlob, 0, 0);
      g.fillStyle = c.pink;
      g.fill();
    }
    if (sSquare > 0) {
      this.squarePath(g, sSquare, 0, 0);
      g.fillStyle = c.blue;
      g.fill();
      g.save(); // inside the square the inks overprint
      this.squarePath(g, sSquare, 0, 0);
      g.clip();
      if (sBlob > 0) {
        this.blobPath(g, t, sBlob, 0, 0);
        g.fillStyle = c.violet;
        g.fill();
      }
      if (sCircle > 0) {
        this.circlePath(g, sCircle, 0, 0);
        g.fillStyle = c.green;
        g.fill();
      }
      g.restore();
    }

    // specks where the ink did not take
    g.save();
    g.globalCompositeOperation = 'destination-out';
    g.globalAlpha = 0.5;
    g.fillStyle = this.pattern;
    g.fillRect(0, 0, u, u * HEIGHT);
    g.restore();

    // the line plate, printed a little off
    var off = u * 0.011;
    g.strokeStyle = c.ink;
    g.lineWidth = Math.max(1.2, u * 0.0026);
    if (sBlob > 0) {
      this.blobPath(g, t, sBlob, off, off * 0.8);
      g.stroke();
    }
    if (sSquare > 0) {
      this.squarePath(g, sSquare, off, off * 0.8);
      g.stroke();
    }
    if (sCircle > 0) {
      this.loopPath(g, sCircle, off, off * 0.8);
      g.stroke();
    }
    if (claiming) {
      g.save();
      g.setLineDash([u * 0.012, u * 0.012]);
      g.strokeStyle = c.green;
      g.lineWidth = Math.max(2, u * 0.005);
      this.blobPath(g, t, sBlob * 1.06, 0, 0);
      g.stroke();
      g.restore();
    }

    if (age < 500) return;
    var fade = this.reduced ? 1 : clamp((age - 500) / 500);
    g.globalAlpha = fade;

    // a pointer from the caption to the green
    g.beginPath();
    g.moveTo(u * 0.795, u * 0.215);
    g.lineTo(u * 0.715, u * 0.345);
    g.stroke();
    g.beginPath();
    g.arc(u * 0.715, u * 0.345, u * 0.005, 0, TAU);
    g.fillStyle = c.ink;
    g.fill();

    // the scan of the square
    var busy = this.marks.some(function (m) { return IN_SQUARE[m.state]; });
    var scan = (SQUARE.y + SQUARE.s * ((t * 0.32) % 1)) * u;
    g.strokeStyle = c.paper;
    g.globalAlpha = fade * (busy ? 0.55 : 0.16);
    g.lineWidth = Math.max(1, u * 0.002);
    g.beginPath();
    g.moveTo(SQUARE.x * u, scan);
    g.lineTo((SQUARE.x + SQUARE.s) * u, scan);
    g.stroke();

    // what is written and never submitted
    g.fillStyle = c.paper;
    for (var f = 0; f < this.flecks.length; f++) {
      var fleck = this.flecks[f];
      var a = fleck.a + t * fleck.w;
      var r = BLOB.r * u * fleck.r * (0.9 + 0.1 * Math.sin(t * 0.7 + f));
      g.globalAlpha = fade * 0.55;
      g.beginPath();
      g.arc((BLOB.x + this.pointer.x * 0.014) * u + Math.cos(a) * r, (BLOB.y + this.pointer.y * 0.014) * u + Math.sin(a) * r * 0.92, u * 0.0032 * fleck.s, 0, TAU);
      g.fill();
    }
    g.globalAlpha = fade;

    for (var i = 0; i < this.marks.length; i++) this.drawMark(this.marks[i], t, claiming);
    g.globalAlpha = 1;
  };

  Art.prototype.drawMark = function (m, t, claiming) {
    if (m.state === 'blank') return;
    var g = this.g;
    var u = this.u;
    var c = this.colors;
    var len = u * 0.015;
    g.save();
    g.translate(m.x * u, m.y * u);
    var grow = (1 + 0.7 * m.pop) * this.markScale;
    g.scale(grow, grow);
    g.lineWidth = u * 0.0078;
    if (claiming && !FINAL[m.state]) {
      g.setLineDash([u * 0.006, u * 0.007]);
      g.strokeStyle = m.state === 'waiting' ? c.ink : c.paper;
      g.lineWidth = u * 0.0042;
      g.beginPath();
      g.arc(0, 0, u * 0.015, 0, TAU);
      g.stroke();
      g.setLineDash([]);
      g.strokeStyle = c.green;
      g.beginPath();
      g.arc(0, 0, u * 0.0085, 0, TAU);
      g.stroke();
      g.restore();
      return;
    }
    switch (m.state) {
      case 'none':
      case 'writing':
        var live = m.state === 'writing';
        g.rotate(m.tilt + (live ? 0.7 * Math.sin(t * 9 + m.seed) : 0.2 * Math.sin(t * 1.1 + m.seed)));
        g.globalAlpha *= live ? 1 : 0.6;
        g.strokeStyle = c.paper;
        g.beginPath();
        g.moveTo(-len, 0);
        g.lineTo(len, 0);
        g.stroke();
        break;
      case 'running':
      case 'review':
        g.strokeStyle = c.paper;
        g.beginPath();
        g.moveTo(-len, 0);
        g.lineTo(len, 0);
        g.stroke();
        if (m.state === 'review') {
          g.fillStyle = c.pink;
          g.beginPath();
          g.arc(len + u * 0.014, 0, u * 0.0058, 0, TAU);
          g.fill();
        }
        break;
      case 'failed':
      case 'triage':
      case 'waiting':
        var waiting = m.state === 'waiting';
        g.strokeStyle = waiting ? c.ink : c.paper;
        g.beginPath();
        g.moveTo(-len * 0.72, -len * 0.72);
        g.lineTo(len * 0.72, len * 0.72);
        g.moveTo(len * 0.72, -len * 0.72);
        g.lineTo(-len * 0.72, len * 0.72);
        g.stroke();
        if (waiting) {
          g.lineWidth = u * 0.0032;
          g.globalAlpha *= 0.35 + 0.5 * Math.abs(Math.sin(t * 2.2 + m.seed));
          g.beginPath();
          g.arc(0, 0, u * (0.021 + 0.003 * Math.sin(t * 2.2 + m.seed)), 0, TAU);
          g.stroke();
        }
        break;
      case 'passed':
        g.fillStyle = c.paper;
        g.beginPath();
        g.arc(0, 0, u * 0.0125, 0, TAU);
        g.fill();
        break;
      case 'pinned':
        g.fillStyle = c.red;
        g.beginPath();
        g.arc(0, 0, u * 0.0125, 0, TAU);
        g.fill();
        g.strokeStyle = c.paper;
        g.lineWidth = u * 0.0036;
        g.stroke();
        break;
    }
    g.restore();
  };

  window.GtaArt = { create: function (canvas, count, options) { return new Art(canvas, count, options); } };
})();
