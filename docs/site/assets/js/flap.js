/* Split-flap board on a 2D canvas. One renderer for every board of the site (the verdict board, the intro, the small
   boards in the scenes). A cell flips through the glyphs of its drum, as the mechanical boards do; only the cells that
   move are redrawn. No dependencies. */
(function () {
  'use strict';

  var DRUM = ' ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-/:#%>?_+,@';
  var STYLES = {
    ivory: { fill: '#ece6d8' },
    dim: { fill: '#77736a' },
    muted: { fill: '#a8a295' },
    amber: { fill: '#f0a63a' },
    green: { fill: '#35c07a' },
    red: { fill: '#e5534b' },
    hollow: { stroke: '#35c07a' } // a green nobody earned: outline only
  };
  var LAMPS = { off: '#23262c', amber: '#f0a63a', green: '#35c07a', red: '#e5534b' };
  var STEP_MS = 52; // one flap falling
  var MAX_STEPS = 7; // a cell never takes longer than this many flaps to arrive

  function index(ch) {
    var i = DRUM.indexOf(ch);
    return i < 0 ? 0 : i;
  }

  /**
   * columns: [{ key, len, label }], drawn left to right with a gap between them; lamp: true draws a lamp after the
   * column `lampAfter`. rows: number of rows. onFlap: called (throttled by the caller) when a flap lands.
   */
  function Board(canvas, options) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.columns = options.columns;
    this.rowCount = options.rows;
    this.lampAfter = options.lampAfter || null;
    this.header = options.header !== false;
    this.maxCell = options.maxCell || 64;
    this.minCell = options.minCell || 7;
    this.ratio = options.ratio || 1.5;
    this.instant = !!options.instant;
    this.onFlap = options.onFlap || null;
    this.cols = 0;
    this.offsets = {};
    for (var c = 0; c < this.columns.length; c++) {
      this.offsets[this.columns[c].key] = this.cols;
      this.cols += this.columns[c].len;
    }
    this.cells = [];
    this.lamps = [];
    for (var r = 0; r < this.rowCount; r++) {
      var row = [];
      for (var k = 0; k < this.cols; k++) {
        row.push({ cur: 0, style: 'ivory', tgt: 0, tgtStyle: 'ivory', steps: 0, t: 0, wait: 0, next: 0, nextStyle: 'ivory' });
      }
      this.cells.push(row);
      this.lamps.push('off');
    }
    this.running = false;
    this.last = 0;
    this.active = 0;
    this.tick = this.tick.bind(this);
    this.resize();
  }

  Board.prototype.has = function (key) {
    return Object.prototype.hasOwnProperty.call(this.offsets, key);
  };

  /** Lays the board out for the canvas's current width and repaints everything. */
  Board.prototype.resize = function () {
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    var parent = this.canvas.parentNode; // the parent decides; the canvas is as wide as its cells
    var box = window.getComputedStyle(parent);
    var width = (parent.clientWidth || 600) - parseFloat(box.paddingLeft || 0) - parseFloat(box.paddingRight || 0);
    var gaps = this.columns.length - 1;
    var lamp = this.lampAfter ? 1.6 : 0;
    var units = this.cols + gaps * 1.1 + lamp;
    var cw = Math.max(this.minCell, Math.min(this.maxCell, Math.floor((width / units) * dpr) / dpr));
    var ch = Math.round(cw * this.ratio);
    var gap = Math.max(1, Math.round(cw * 0.09));
    this.cw = cw;
    this.ch = ch;
    this.colGap = cw * 1.1;
    this.rowGap = Math.max(3, Math.round(ch * 0.2));
    this.headH = this.header ? Math.max(16, Math.round(ch * 0.8)) : 0;
    this.inner = gap;
    var x = 0;
    this.x = [];
    this.colX = {};
    for (var c = 0; c < this.columns.length; c++) {
      this.colX[this.columns[c].key] = x;
      for (var k = 0; k < this.columns[c].len; k++) {
        this.x.push(x);
        x += cw;
      }
      if (this.lampAfter === this.columns[c].key) {
        this.lampX = x + cw * 0.3;
        x += cw * lamp;
      }
      x += this.colGap;
    }
    this.width = x - this.colGap;
    this.height = this.headH + this.rowCount * (ch + this.rowGap) - this.rowGap;
    this.dpr = dpr;
    this.canvas.width = Math.ceil(this.width * dpr);
    this.canvas.height = Math.ceil(this.height * dpr);
    this.canvas.style.width = this.width + 'px';
    this.canvas.style.height = this.height + 'px';
    this.atlas = {};
    this.paint();
  };

  /** The tiles of one style: every glyph of the drum on its flap, drawn once. */
  Board.prototype.tiles = function (style) {
    if (this.atlas[style]) return this.atlas[style];
    var dpr = this.dpr;
    var w = Math.ceil(this.cw * dpr);
    var h = Math.ceil(this.ch * dpr);
    var sheet = document.createElement('canvas');
    sheet.width = w * DRUM.length;
    sheet.height = h;
    var g = sheet.getContext('2d');
    var pad = this.inner * dpr * 0.5;
    var radius = Math.max(1, w * 0.12);
    var size = Math.round(h * 0.88);
    g.textAlign = 'center';
    g.textBaseline = 'alphabetic';
    g.font = '600 ' + size + 'px "Barlow Condensed", "Arial Narrow", Arial, sans-serif';
    var metrics = g.measureText('H');
    var capHeight = metrics.actualBoundingBoxAscent || size * 0.7;
    var base = Math.round(h / 2 + capHeight / 2);
    var look = STYLES[style] || STYLES.ivory;
    for (var i = 0; i < DRUM.length; i++) {
      var x = i * w;
      var body = g.createLinearGradient(0, 0, 0, h);
      body.addColorStop(0, '#20232a');
      body.addColorStop(0.5, '#1a1d23');
      body.addColorStop(0.5, '#15181d');
      body.addColorStop(1, '#181b21');
      g.fillStyle = body;
      round(g, x + pad, 0, w - pad * 2, h, radius);
      g.fill();
      var glyph = DRUM.charAt(i);
      if (glyph !== ' ') {
        var maxW = w - pad * 2 - w * 0.1;
        if (look.stroke) {
          g.lineWidth = Math.max(1, h * 0.035);
          g.strokeStyle = look.stroke;
          g.strokeText(glyph, x + w / 2, base, maxW);
        } else {
          g.fillStyle = look.fill;
          g.fillText(glyph, x + w / 2, base, maxW);
        }
      }
    }
    this.atlas[style] = sheet;
    return sheet;
  };

  function round(g, x, y, w, h, r) {
    g.beginPath();
    g.moveTo(x + r, y);
    g.lineTo(x + w - r, y);
    g.quadraticCurveTo(x + w, y, x + w, y + r);
    g.lineTo(x + w, y + h - r);
    g.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    g.lineTo(x + r, y + h);
    g.quadraticCurveTo(x, y + h, x, y + h - r);
    g.lineTo(x, y + r);
    g.quadraticCurveTo(x, y, x + r, y);
    g.closePath();
  }

  Board.prototype.rowY = function (row) {
    return this.headH + row * (this.ch + this.rowGap);
  };

  Board.prototype.paint = function () {
    var g = this.ctx;
    var dpr = this.dpr;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, this.canvas.width, this.canvas.height);
    if (this.header) {
      g.fillStyle = '#8d887c';
      g.textBaseline = 'top';
      g.textAlign = 'left';
      var size = Math.max(9, Math.min(13, this.cw * 0.78));
      g.font = '500 ' + size * dpr + 'px "Barlow Condensed", "Arial Narrow", Arial, sans-serif';
      for (var c = 0; c < this.columns.length; c++) {
        var label = this.columns[c].label;
        if (label) spaced(g, label, (this.colX[this.columns[c].key] + this.inner) * dpr, 1 * dpr, size * dpr * 0.14);
      }
    }
    for (var r = 0; r < this.rowCount; r++) {
      for (var k = 0; k < this.cols; k++) this.drawCell(r, k);
      this.drawLamp(r, 0);
    }
  };

  function spaced(g, text, x, y, spacing) {
    for (var i = 0; i < text.length; i++) {
      g.fillText(text.charAt(i), x, y);
      x += g.measureText(text.charAt(i)).width + spacing;
    }
  }

  Board.prototype.drawLamp = function (row, now) {
    if (!this.lampAfter) return;
    var g = this.ctx;
    var dpr = this.dpr;
    var state = this.lamps[row];
    var size = Math.max(4, this.cw * 0.62);
    var x = this.lampX * dpr;
    var y = (this.rowY(row) + (this.ch - size) / 2) * dpr;
    g.clearRect(x - 2 * dpr, this.rowY(row) * dpr, (size + 4) * dpr, this.ch * dpr);
    g.globalAlpha = state === 'amber' ? 0.55 + 0.45 * Math.abs(Math.sin(now / 420)) : 1;
    g.fillStyle = LAMPS[state] || LAMPS.off;
    g.beginPath();
    g.arc(x + (size * dpr) / 2, y + (size * dpr) / 2, (size * dpr) / 2, 0, Math.PI * 2);
    g.fill();
    g.globalAlpha = 1;
  };

  Board.prototype.drawCell = function (row, col) {
    var cell = this.cells[row][col];
    var g = this.ctx;
    var dpr = this.dpr;
    var w = Math.ceil(this.cw * dpr);
    var h = Math.ceil(this.ch * dpr);
    var half = Math.floor(h / 2);
    var x = Math.round(this.x[col] * dpr);
    var y = Math.round(this.rowY(row) * dpr);
    g.clearRect(x, y, w, h);
    if (cell.steps === 0 || cell.wait > 0 || cell.t <= 0) {
      g.drawImage(this.tiles(cell.style), cell.cur * w, 0, w, h, x, y, w, h);
    } else {
      var from = this.tiles(cell.style);
      var to = this.tiles(cell.nextStyle);
      var a = cell.cur * w;
      var b = cell.next * w;
      g.drawImage(to, b, 0, w, half, x, y, w, half); // the next glyph's upper half, already in place behind
      g.drawImage(from, a, half, w, h - half, x, y + half, w, h - half); // the old lower half, still there
      var p = cell.t;
      if (p < 0.5) {
        var s = Math.cos(p * Math.PI);
        var fh = Math.max(1, Math.round(half * s));
        g.drawImage(from, a, 0, w, half, x, y + half - fh, w, fh);
        g.fillStyle = 'rgba(0,0,0,' + (0.45 * (1 - s)).toFixed(3) + ')';
        g.fillRect(x, y + half - fh, w, fh);
      } else {
        var s2 = -Math.cos(p * Math.PI);
        var fh2 = Math.max(1, Math.round((h - half) * s2));
        g.drawImage(to, b, half, w, h - half, x, y + half, w, fh2);
        g.fillStyle = 'rgba(255,255,255,' + (0.06 * (1 - s2)).toFixed(3) + ')';
        g.fillRect(x, y + half, w, fh2);
      }
    }
    g.fillStyle = 'rgba(0,0,0,0.62)'; // the axle between the two halves
    g.fillRect(x, y + half - Math.max(1, Math.round(dpr * 0.5)), w, Math.max(1, Math.round(dpr)));
  };

  /**
   * Writes text into a column of a row. options: style, delay (ms before the first cell starts), stagger (ms between
   * cells), instant (no flipping).
   */
  Board.prototype.set = function (row, key, text, options) {
    if (!this.has(key) || row >= this.rowCount) return;
    options = options || {};
    var start = this.offsets[key];
    var len = 0;
    for (var c = 0; c < this.columns.length; c++) if (this.columns[c].key === key) len = this.columns[c].len;
    var value = String(text == null ? '' : text).toUpperCase();
    if (options.align === 'right') while (value.length < len) value = ' ' + value;
    var style = options.style || 'ivory';
    var stagger = options.stagger == null ? 16 : options.stagger;
    var instant = this.instant || options.instant;
    for (var i = 0; i < len; i++) {
      var cell = this.cells[row][start + i];
      var target = index(value.charAt(i) || ' ');
      cell.tgt = target;
      cell.tgtStyle = style;
      if (instant) {
        cell.cur = target;
        cell.style = style;
        cell.steps = 0;
        cell.t = 0;
        cell.wait = 0;
        this.drawCell(row, start + i);
        continue;
      }
      if (cell.cur === target && cell.style === style && cell.steps === 0) continue;
      var distance = (target - cell.cur + DRUM.length) % DRUM.length;
      if (distance === 0) distance = 1; // same glyph in another colour: one flap
      cell.steps = Math.min(distance, MAX_STEPS);
      cell.t = 0;
      cell.wait = (options.delay || 0) + i * stagger;
      this.plan(cell);
    }
    if (!instant) this.start();
  };

  /** Chooses the glyph the next flap shows: the last flaps before the target are its neighbours on the drum. */
  Board.prototype.plan = function (cell) {
    if (cell.steps <= 1) {
      cell.next = cell.tgt;
      cell.nextStyle = cell.tgtStyle;
    } else {
      cell.next = (cell.tgt - (cell.steps - 1) + DRUM.length * 2) % DRUM.length;
      cell.nextStyle = cell.style;
    }
  };

  Board.prototype.lamp = function (row, state) {
    if (row >= this.rowCount) return;
    this.lamps[row] = state;
    this.drawLamp(row, 0);
    if (state === 'amber' && !this.instant) this.start();
  };

  Board.prototype.start = function () {
    if (this.running) return;
    this.running = true;
    this.last = 0;
    window.requestAnimationFrame(this.tick);
  };

  Board.prototype.busy = function () {
    return this.active > 0;
  };

  Board.prototype.tick = function (now) {
    var dt = this.last ? Math.min(64, now - this.last) : 16;
    this.last = now;
    var active = 0;
    var landed = 0;
    for (var r = 0; r < this.rowCount; r++) {
      var row = this.cells[r];
      for (var k = 0; k < this.cols; k++) {
        var cell = row[k];
        if (cell.steps === 0) continue;
        active++;
        if (cell.wait > 0) {
          cell.wait -= dt;
          continue;
        }
        cell.t += dt / STEP_MS;
        if (cell.t >= 1) {
          cell.cur = cell.next;
          cell.style = cell.nextStyle;
          cell.steps--;
          cell.t = 0;
          landed++;
          if (cell.steps > 0) this.plan(cell);
        }
        this.drawCell(r, k);
      }
    }
    var pulsing = false;
    for (var l = 0; l < this.rowCount; l++) {
      if (this.lamps[l] === 'amber') {
        pulsing = true;
        this.drawLamp(l, now);
      }
    }
    this.active = active;
    if (landed && this.onFlap) this.onFlap(landed);
    if (active > 0 || (pulsing && !document.hidden && this.visible !== false)) {
      window.requestAnimationFrame(this.tick);
    } else {
      this.running = false;
    }
  };

  window.Flap = { Board: Board, DRUM: DRUM };
})();
