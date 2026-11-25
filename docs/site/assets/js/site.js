/* The page's behaviour: the twelve scenarios of the title scene (as marks in the picture and as rows of the ledger),
   the replay of the recorded gtaw run (assets/js/run-data.js), and stage mode. Every string a visitor reads comes from
   the page (the #i18n block), so this file is the same for every language. Works from file://, with no dependency. */
(function () {
  'use strict';

  var RUN = window.GTA_RUN || null;
  var TEXT = JSON.parse(document.getElementById('i18n').textContent);
  var reduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  var SVG = 'http://www.w3.org/2000/svg';

  function $(id) {
    return document.getElementById(id);
  }
  function all(selector, root) {
    return [].slice.call((root || document).querySelectorAll(selector));
  }
  function t(key, values) {
    var text = TEXT[key] == null ? key : TEXT[key];
    for (var name in values || {}) text = text.split('{' + name + '}').join(values[name]);
    return text;
  }
  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }
  function escapeHtml(text) {
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  /** A pause that the story needs; with reduced motion nothing is paced. */
  function later(ms, fn) {
    return window.setTimeout(fn, reduced ? Math.min(ms, 40) : ms);
  }
  function shake(node) {
    node.classList.remove('shake');
    void node.offsetWidth;
    node.classList.add('shake');
  }
  /** One of the three shapes: the blob is the agents, the square is gtaw, the circle is the person. */
  function token(who) {
    var svg = document.createElementNS(SVG, 'svg');
    var use = document.createElementNS(SVG, 'use');
    var shape = { ai: 'blob', gtaw: 'square', you: 'circle' }[who];
    svg.setAttribute('class', 'tok tok-' + who);
    svg.setAttribute('aria-hidden', 'true');
    use.setAttribute('href', '#s-' + shape);
    svg.appendChild(use);
    return svg;
  }

  /* ---- what a scenario can be ------------------------------------------------------------------------------------ */

  var STATES = {
    blank: { text: '', who: '' },
    none: { text: 'stNone', who: '', look: 'st st-none' },
    writing: { text: 'stWriting', who: 'ai', look: 'st' },
    running: { text: 'stRunning', who: 'gtaw', look: 'st' },
    review: { text: 'stReview', who: 'ai', look: 'st' },
    failed: { text: 'stFailed', who: 'gtaw', look: 'st st-failed' },
    triage: { text: 'stTriage', who: 'ai', look: 'st' },
    waiting: { text: 'stWaiting', who: 'you', look: 'st st-waiting' },
    confirmed: { text: 'stConfirmed', who: 'you', look: 'st' },
    passed: { text: 'stPassed', who: '', look: 'stamp stamp-passed', locked: true },
    pinned: { text: 'stPinned', who: '', look: 'stamp stamp-pinned', locked: true }
  };

  /**
   * Turns the rows of a table into a ledger: scenario, kind, locked values, verdict, and who holds it. rows is the
   * model ([{scenario, kind, locked, state}]); paint(i) shows row i as its state says.
   */
  function ledger(table, rows) {
    var head = table.querySelector('thead tr');
    if (head) head.appendChild(el('th', '', t('colWho')));
    var lines = all('tbody tr', table).map(function (tr, i) {
      var row = rows[i];
      tr.textContent = '';
      var line = { tr: tr, shown: null };
      var id = el('td', 'c-scn');
      row.scenario.split(/([./])/).forEach(function (part) {
        id.appendChild(document.createTextNode(part));
        if (part === '.' || part === '/') id.appendChild(document.createElement('wbr'));
      });
      tr.appendChild(id);
      tr.appendChild(el('td', 'c-kind', row.kind));
      var lock = el('td', 'c-lock');
      line.lock = lock;
      line.chips = el('span', 'chips');
      row.locked.split(' ').forEach(function (value) {
        if (value) line.chips.appendChild(el('span', 'chip locked', value));
      });
      lock.appendChild(line.chips);
      tr.appendChild(lock);
      line.verdict = el('td', 'c-verdict');
      tr.appendChild(line.verdict);
      line.who = el('td', 'c-who');
      tr.appendChild(line.who);
      return line;
    });

    function paint(i, force) {
      var row = rows[i];
      var line = lines[i];
      var state = STATES[row.state];
      if (line.shown === row.state && !force) return;
      line.shown = row.state;
      line.tr.setAttribute('data-state', row.state);
      line.lock.classList.toggle('has', !!state.locked);
      all('.chip', line.chips).forEach(function (chip, n) {
        chip.style.transitionDelay = n * 90 + 'ms';
        chip.classList.toggle('on', !!state.locked);
      });
      line.verdict.textContent = '';
      if (state.text) line.verdict.appendChild(el('span', state.look, t(state.text)));
      line.who.textContent = '';
      line.who.classList.toggle('is-locked', !!state.locked);
      if (state.locked) { // the values are gtaw's now
        line.who.appendChild(token('gtaw'));
        line.who.appendChild(document.createTextNode(t('whoLocked')));
      }
      if (state.who) {
        line.who.appendChild(token(state.who));
        line.who.appendChild(document.createTextNode(t({ ai: 'whoAi', gtaw: 'whoGtaw', you: 'whoYou' }[state.who])));
      }
    }

    /** Shows a green nobody earned on every row that has no verdict yet; it does not take. */
    function claim() {
      rows.forEach(function (row, i) {
        if (row.state === 'passed' || row.state === 'pinned' || row.state === 'blank') return;
        var line = lines[i];
        line.verdict.textContent = '';
        line.verdict.appendChild(el('span', 'stamp stamp-claimed claim', t('stPassed')));
        line.shown = null;
      });
    }

    rows.forEach(function (row, i) { paint(i); });
    return { paint: paint, claim: claim };
  }

  /* ---- the title scene: twelve scenarios, as marks and as rows --------------------------------------------------- */

  var board = null;

  function setUpBoard() {
    var table = $('ledger-table');
    var canvas = $('art');
    var rows = all('tbody tr', table).map(function (tr) {
      var cells = tr.children;
      return {
        scenario: cells[0].textContent,
        kind: cells[1].textContent,
        locked: cells[2].textContent,
        end: tr.getAttribute('data-end'),
        state: 'blank'
      };
    });
    var paper = ledger(table, rows);
    var style = window.getComputedStyle(document.documentElement);
    function ink(name) {
      return style.getPropertyValue('--' + name).trim();
    }
    var art = window.GtaArt.create(canvas, rows.length, {
      reduced: reduced,
      colors: {
        paper: ink('paper'), ink: ink('ink'), pink: ink('pink'), blue: ink('blue'), yellow: ink('yellow'),
        green: ink('green'), red: ink('red'), violet: ink('violet')
      }
    });
    var order = [0, 4, 1, 7, 2, 9, 5, 11, 3, 8, 6, 10]; // the scenarios do not start top to bottom
    var timers = [];
    var tried = 0;

    function move(index, state) {
      rows[index].state = state;
      paper.paint(index);
      art.set(index, state);
      desk();
    }
    function at(ms, fn) {
      timers.push(later(ms, fn));
    }

    var said = '';
    var live = el('p', 'sr'); // what a screen reader hears: once the scenarios have settled, not each step on the way
    live.setAttribute('role', 'status');
    document.body.appendChild(live);
    function desk() {
      var waiting = rows.filter(function (row) { return row.state === 'waiting'; }).length;
      var open = rows.filter(function (row) { return row.state !== 'passed' && row.state !== 'pinned'; }).length;
      var passed = rows.filter(function (row) { return row.state === 'passed'; }).length;
      var text = waiting > 0 ? (waiting === 1 ? t('okOne') : t('okMany', { n: waiting }))
        : open === 0 ? t('okDone', { p: passed, d: rows.length - passed }) : t('okNone');
      all('[data-key="ok"]').forEach(function (key) {
        var focused = document.activeElement === key;
        key.disabled = waiting === 0;
        if (focused && key.disabled) { // keep the keyboard user's place
          var force = key.closest('.desk').querySelector('[data-key="force"]');
          if (force) force.focus({ preventScroll: true });
        }
      });
      var moving = rows.some(function (row) { return row.state !== 'passed' && row.state !== 'pinned' && row.state !== 'waiting'; });
      if (!moving && live.textContent !== text) live.textContent = text;
      if (text === said) return;
      said = text;
      all('[data-note="ok"]').forEach(function (note) {
        note.textContent = text;
        if (open !== 0) return;
        var again = el('button', 'tool', t('again'));
        again.type = 'button';
        again.addEventListener('click', function () {
          var key = note.parentNode.parentNode.querySelector('[data-key="force"]');
          run();
          if (key) key.focus();
        });
        note.appendChild(document.createTextNode(' '));
        note.appendChild(again);
      });
    }

    /** The illustrative part: each scenario goes through the steps a scenario goes through, and stops where it must. */
    function run() {
      timers.forEach(window.clearTimeout);
      timers = [];
      rows.forEach(function (row, i) { move(i, 'blank'); });
      rows.forEach(function (row, i) {
        at(700 + i * 70, function () { move(i, 'none'); });
      });
      var base = 700 + rows.length * 70 + 900;
      rows.forEach(function (row, i) {
        var start = base + order[i % order.length] * 520;
        at(start, function () { move(i, 'writing'); });
        at(start + 1500, function () { move(i, 'running'); });
        if (row.end === 'passed') {
          at(start + 2800, function () { move(i, 'review'); });
          at(start + 3900, function () { move(i, 'passed'); });
        } else {
          at(start + 2800, function () { move(i, 'failed'); });
          at(start + 3600, function () { move(i, 'triage'); });
          at(start + 4600, function () { move(i, 'waiting'); });
        }
      });
    }

    function ok() {
      var n = 0;
      rows.forEach(function (row, i) {
        if (row.state !== 'waiting') return;
        timers.push(window.setTimeout(function () { move(i, 'pinned'); }, reduced ? 0 : n * 150));
        n++;
      });
      return n > 0;
    }

    /** The visitor tries to make everything green. The claim shows for a moment, then what gtaw can prove. */
    function force(event) {
      tried++;
      all('[data-tried]').forEach(function (node) { node.textContent = tried; });
      all('[data-force-text]').forEach(function (node) {
        node.textContent = t(tried === 1 ? 'forceRefused' : tried === 2 ? 'forceRefused2' : 'forceRefused3');
      });
      all('[data-note="force"]').forEach(function (node) { node.classList.add('refused'); });
      paper.claim();
      art.claim();
      var key = event && event.currentTarget;
      window.setTimeout(function () {
        rows.forEach(function (row, i) { paper.paint(i, true); });
        if (key) shake(key);
      }, 1000);
    }

    all('[data-key="ok"]').forEach(function (key) { key.addEventListener('click', ok); });
    all('[data-key="force"]').forEach(function (key) { key.addEventListener('click', force); });

    var stage = $('art-stage');
    var fit = 0;
    window.addEventListener('resize', function () {
      window.clearTimeout(fit);
      fit = window.setTimeout(function () { art.resize(); }, 120);
    });
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) {
        art.visible = entries[0].isIntersecting;
        if (art.visible) art.start();
      }).observe(stage);
    }
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) art.start();
    });
    $('title').addEventListener('pointermove', function (event) {
      var box = stage.getBoundingClientRect();
      art.point(((event.clientX - box.left) / box.width - 0.5) * 2, ((event.clientY - box.top) / box.height - 0.5) * 2);
    });

    /** Brings every scenario still on its way to where it stops, in about a second and a half. */
    function hurry() {
      timers.forEach(window.clearTimeout);
      timers = [];
      var n = 0;
      order.forEach(function (i) {
        var row = rows[i];
        if (row.state === 'passed' || row.state === 'pinned' || row.state === 'waiting') return;
        at(n++ * 120, function () { move(i, row.end === 'passed' ? 'passed' : 'waiting'); });
      });
    }

    board = {
      ok: ok,
      hurry: hurry,
      run: run,
      running: function () { return rows.some(function (row) { return row.state !== 'passed' && row.state !== 'pinned' && row.state !== 'waiting'; }); },
      done: function () { return rows.every(function (row) { return row.state === 'passed' || row.state === 'pinned'; }); },
      force: function (scene) {
        var key = scene.querySelector('[data-key="force"]');
        if (key) key.click();
      },
      waiting: function () { return rows.some(function (row) { return row.state === 'waiting'; }); },
      stage: function (on) { art.setMarkScale(on ? 1.15 : 1); }
    };
    run();
  }

  /* ---- the replay of the recorded run --------------------------------------------------------------------------- */

  var replay = null;

  function setUpReplay() {
    if (!RUN) return;
    var log = $('log');
    var form = $('reply-form');
    var input = $('reply');
    var key = $('reply-key');
    var hint = $('reply-hint');
    var pen = $('pen');
    var tickets = $('tickets');
    var rows = [
      { scenario: 's01', id: 'borrow.book/s01', kind: '', locked: '', state: 'blank' },
      { scenario: 's02', id: 'borrow.book/s02', kind: '', locked: '', state: 'blank' }
    ];
    var beats = [];
    var next = 0;
    var playing = false;
    var timers = [];
    var lock = {};
    var scenarios = {};
    var refused = false; // the presentation has tried to change a locked value in this run

    RUN.events.forEach(function (event) {
      if (event.actor === 'you') beats.push({ reply: event.did, events: [] });
      else if (beats.length) beats[beats.length - 1].events.push(event);
    });
    try {
      var shard = JSON.parse(RUN.files['shard.json']);
      for (var item in shard) lock[item.split('#')[0]] = shard[item].tokens;
    } catch (e) {
      lock = {};
    }
    // the scenarios of the approved spec: title, WHEN and THEN
    (RUN.files['spec.md'] || '').replace(
      /#### Scenario: (.*)\n<!-- gta: id=(\S+) [^>]*-->\n- \*\*WHEN\*\* (.*)\n- \*\*THEN\*\* (.*)/g,
      function (match, title, id, when, then) {
        scenarios[id] = { title: title, when: when.replace(/`/g, ''), then: then };
      }
    );
    rows.forEach(function (row) { row.locked = (lock[row.id] || []).join(' '); });
    var paper = ledger($('loop-table'), rows);
    function move(index, state) {
      rows[index].state = state;
      paper.paint(index);
    }

    // what the ledger and the tickets do when an event of the recording is shown
    var effects = {
      'spec-r1': function () { move(0, 'none'); move(1, 'none'); },
      question: function () { move(1, 'waiting'); },
      answered: function () { move(1, 'none'); },
      approval: function () { move(0, 'waiting'); move(1, 'waiting'); },
      approved: function () { move(0, 'none'); move(1, 'none'); },
      'author-s01': function () { move(0, 'writing'); },
      's01-submitted': function () { move(0, 'running'); },
      'review-s01': function () { move(0, 'review'); },
      's01-passed': function () { move(0, 'passed'); printTicket(rows[0], 'passed'); },
      'author-s02': function () { move(1, 'writing'); },
      's02-failed': function () {
        if (reduced) return move(1, 'failed'); // every line of a beat lands at once: keep the order
        move(1, 'running');
        timers.push(later(450, function () { move(1, 'failed'); }));
      },
      triage: function () { move(1, 'triage'); },
      defect: function () { move(1, 'waiting'); },
      'defect-confirmed': function () { move(1, 'confirmed'); },
      'author-pin': function () { move(1, 'writing'); },
      pinned: function () { move(1, 'pinned'); printTicket(rows[1], 'pinned'); }
    };

    function setPen(who) {
      pen.setAttribute('data-who', who);
      pen.textContent = '';
      if (who !== 'done') pen.appendChild(token(who));
      pen.appendChild(document.createTextNode(t({ you: 'penYou', ai: 'penAi', gtaw: 'penGtaw', done: 'penDone' }[who])));
    }

    function add(className, who, what) {
      var entry = el('div', 'entry ' + className);
      if (className.indexOf('block') >= 0) {
        var pre = el('pre');
        pre.innerHTML = escapeHtml(what)
          .replace(/^(\s*──.*)$/gm, '<span class="hd">$1</span>')
          .replace(/(←[^\n]*)/g, '<span class="rec">$1</span>');
        entry.appendChild(pre);
      } else {
        entry.appendChild(el('span', 'entry-who', who));
        entry.appendChild(el('span', 'entry-what', what));
      }
      log.appendChild(entry);
      log.scrollTop = log.scrollHeight;
    }

    function command(args) {
      var shown = [];
      for (var i = 0; i < args.length; i++) {
        if (args[i] === '--token') {
          i++; // the brief's one-time token says nothing to a reader
          continue;
        }
        shown.push(/\s/.test(args[i]) ? '"' + args[i] + '"' : args[i]);
      }
      return './gtaw ' + shown.join(' ');
    }

    /** The lines one event of the recording becomes: [className, who, what, pause before the next]. */
    function lines(event) {
      if (event.actor) {
        var who = { analyst: 'actorAnalyst', 'test-author': 'actorAuthor', reviewer: 'actorReviewer' }[event.actor];
        var did = {
          'write-spec': t('didWriteSpec', { file: event.did }),
          'apply-answer': t('didApplyAnswer'),
          'write-seed': t('didWriteSeed'),
          'write-test': t('didWriteTest', { file: event.did }),
          pin: t('didPin', { text: event.did })
        }[event.k];
        return [['agent', t(who), did || event.did, 620]];
      }
      var out = event.out || {};
      var result = [['call', '$', command(event.gtaw), 260]];
      if (out.display) {
        result.push(['block' + (event.k === 'done' ? ' end' : ''), '', out.display, 500]);
      } else if (out.result) {
        var tone = /^passed/.test(out.result) ? ' ok' : /FAILED|^defect-found/.test(out.result) ? ' bad' : '';
        result.push(['gtaw' + tone, t('actorGtaw'), out.result, 520]);
      } else if (out.agent) {
        result.push(['gtaw', t('actorGtaw'), t('dispatch', { agent: out.agent }) + ' · ' + out.brief, 420]);
      } else if (out.items) {
        var items = out.items.map(function (entry) { return entry.id + ' ' + entry.status; }).join(' · ');
        result.push(['gtaw', t('actorGtaw'), t('statusLine', { items: items }), 300]);
      }
      return result;
    }

    function ready() {
      playing = false;
      hint.textContent = '';
      if (next >= beats.length) {
        setPen('done');
        input.disabled = true;
        input.value = '';
        input.placeholder = '';
        hint.textContent = t('replyDone');
        key.disabled = false;
        key.firstChild.nodeValue = t('replayAgain') + ' ';
        showEvidence();
        return;
      }
      setPen('you');
      input.disabled = false;
      input.value = '';
      input.placeholder = beats[next].reply;
      key.disabled = false;
      key.firstChild.nodeValue = beats[next].reply + ' ';
      if (document.body.classList.contains('stage')) return;
      var box = input.getBoundingClientRect();
      var seen = box.bottom > 0 && box.top < window.innerHeight;
      var free = document.activeElement === document.body || $('console').contains(document.activeElement);
      if (seen && free) (typing ? input : key).focus({ preventScroll: true });
    }

    var typing = false; // the reply was typed in the input, so the next one probably will be too
    function play(beat) {
      typing = document.activeElement === input;
      if (!typing && document.activeElement === key) key.blur(); // ready() gives it back
      playing = true;
      input.disabled = true;
      key.disabled = true;
      hint.textContent = '';
      add('you', t('actorYou'), beat.reply);
      var delay = 420;
      beat.events.forEach(function (event) {
        var first = true;
        lines(event).forEach(function (line) {
          var withEffect = first;
          first = false;
          timers.push(later(delay, function () {
            setPen(event.actor ? 'ai' : 'gtaw');
            add(line[0], line[1], line[2]);
            if (withEffect && effects[event.k]) effects[event.k]();
          }));
          delay += line[3];
        });
      });
      timers.push(later(delay, ready));
    }

    function reply(text) {
      if (playing) return false;
      if (next >= beats.length) {
        reset();
        return true;
      }
      var expected = beats[next].reply;
      // gtaw takes a flow's id where it took its number: so does the replay
      var same = /^\d+$/.test(expected) && text === (RUN.meta && RUN.meta.flow);
      if (text && !same && text.toLowerCase() !== expected.toLowerCase()) {
        hint.textContent = t('replyHintWrong', { reply: expected });
        shake(form);
        return false;
      }
      play(beats[next++]);
      return true;
    }

    function reset() {
      timers.forEach(window.clearTimeout);
      timers = [];
      next = 0;
      refused = false;
      log.textContent = '';
      all('.ticket', tickets).forEach(function (ticket) { tickets.removeChild(ticket); });
      $('ticket-slot').hidden = false;
      $('evidence').hidden = true;
      rows.forEach(function (row, i) { move(i, 'blank'); });
      ready();
    }

    /** A ticket: the scenario with its locked values punched in. They can be typed over; they do not stay changed. */
    function printTicket(row, verdict) {
      var scenario = scenarios[row.id] || { when: '' };
      var tokens = lock[row.id] || [];
      $('ticket-slot').hidden = true;
      var ticket = el('div', 'ticket');
      var head = el('div', 'ticket-head');
      head.appendChild(el('span', '', row.id));
      head.appendChild(el('span', 'stamp stamp-' + verdict, t(verdict === 'passed' ? 'stPassed' : 'stPinned')));
      ticket.appendChild(head);
      ticket.appendChild(el('p', 'ticket-label', t('ticketWhen')));
      ticket.appendChild(el('p', 'ticket-when', scenario.when));
      ticket.appendChild(el('p', 'ticket-label', t('ticketThen')));
      var values = el('div', 'ticket-values');
      var note = el('p', 'ticket-try', t('ticketEdit'));
      note.setAttribute('role', 'status');
      tokens.forEach(function (value) {
        var punch = el('input', 'punch');
        punch.value = value;
        punch.size = Math.max(3, value.length);
        punch.spellcheck = false;
        punch.setAttribute('aria-label', t('ticketThen') + ' ' + value);
        var timer = 0;
        function restore() {
          window.clearTimeout(timer);
          if (punch.value === value) return;
          punch.value = value;
          punch.classList.remove('back');
          void punch.offsetWidth;
          punch.classList.add('back');
          note.className = 'ticket-refused';
          note.textContent = t('ticketRefused', { tokens: value });
          note.appendChild(el('span', '', t('ticketRefusedNote')));
        }
        punch.addEventListener('input', function () {
          window.clearTimeout(timer);
          timer = window.setTimeout(restore, 800);
        });
        punch.addEventListener('blur', restore);
        punch.addEventListener('keydown', function (event) {
          if (event.key === 'Enter') restore();
        });
        values.appendChild(punch);
      });
      ticket.appendChild(values);
      if (verdict === 'pinned') {
        var pin = RUN.events.filter(function (event) { return event.k === 'pin'; })[0];
        if (pin) ticket.appendChild(el('p', 'ticket-pin', pin.did));
      }
      ticket.appendChild(note);
      tickets.appendChild(ticket);
    }

    /* what the run left behind */
    function diff(before, after) {
      var a = before.split('\n');
      var b = after.split('\n');
      var table = [];
      var i, j;
      for (i = a.length; i >= 0; i--) {
        table[i] = [];
        for (j = b.length; j >= 0; j--) {
          table[i][j] = i === a.length || j === b.length ? 0 : a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
        }
      }
      var out = [];
      i = 0;
      j = 0;
      while (i < a.length || j < b.length) {
        if (i < a.length && j < b.length && a[i] === b[j]) {
          out.push(['ctx', a[i]]);
          i++;
          j++;
        } else if (i < a.length && (j === b.length || table[i + 1][j] >= table[i][j + 1])) {
          out.push(['del', a[i++]]);
        } else {
          out.push(['add', b[j++]]);
        }
      }
      var keep = out.map(function () { return false; });
      out.forEach(function (line, n) {
        if (line[0] === 'ctx') return;
        for (var k = Math.max(0, n - 1); k <= Math.min(out.length - 1, n + 1); k++) keep[k] = true;
      });
      var html = '';
      var gap = false;
      out.forEach(function (line, n) {
        if (!keep[n]) {
          gap = true;
          return;
        }
        if (gap && html) html += '<span class="gap">  ⋯</span>';
        gap = false;
        var mark = line[0] === 'add' ? '+ ' : line[0] === 'del' ? '- ' : '  ';
        html += '<span class="' + line[0] + '">' + mark + escapeHtml(line[1] || ' ') + '</span>';
      });
      return html;
    }

    var tabs = [
      ['tabSpec', function () { return diff(RUN.files['spec-r1.md'], RUN.files['spec.md']); }],
      ['tabTest', function () {
        return RUN.files['test.java'].split('\n').map(function (line) {
          return '<span class="' + (line.indexOf('@KnownDefect(') >= 0 ? 'hit' : 'ctx') + '">' + escapeHtml(line || ' ') + '</span>';
        }).join('');
      }],
      ['tabLock', function () { return escapeHtml(RUN.files['shard.json']).replace(/("tokens" : )(\[[^\]]*\])/g, '$1<b>$2</b>'); }],
      ['tabCommits', function () { return escapeHtml(RUN.files['commits.txt']).replace(/^([0-9a-f]{7})/gm, '<b>$1</b>'); }],
      ['tabReport', function () { return escapeHtml(RUN.files['report.md']); }]
    ];
    function showTab(index) {
      var body = $('tab-body');
      body.textContent = '';
      body.appendChild(el('p', 'tab-note', t(tabs[index][0] + 'Note')));
      var pre = el('pre');
      pre.innerHTML = tabs[index][1]();
      pre.tabIndex = 0; // it scrolls: the keyboard can scroll it
      pre.setAttribute('aria-label', t(tabs[index][0]));
      body.appendChild(pre);
      body.setAttribute('aria-labelledby', 'tab-' + index);
      all('.tab', $('tabs')).forEach(function (button, n) {
        button.setAttribute('aria-selected', n === index ? 'true' : 'false');
        button.tabIndex = n === index ? 0 : -1;
      });
      var hit = pre.querySelector('.hit');
      if (hit) pre.scrollTop = hit.offsetTop - pre.offsetTop - 120;
    }
    tabs.forEach(function (tab, index) {
      var button = el('button', 'tab', t(tab[0]));
      button.type = 'button';
      button.id = 'tab-' + index;
      button.setAttribute('role', 'tab');
      button.setAttribute('aria-controls', 'tab-body');
      button.addEventListener('click', function () { showTab(index); });
      button.addEventListener('keydown', function (event) {
        var step = { ArrowRight: 1, ArrowLeft: -1 }[event.key];
        if (event.key === 'Home') step = -index;
        if (event.key === 'End') step = tabs.length - 1 - index;
        if (step === undefined) return;
        if (document.body.classList.contains('stage') && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) return; // they present
        event.preventDefault();
        event.stopPropagation();
        var to = (index + step + tabs.length) % tabs.length;
        showTab(to);
        $('tab-' + to).focus();
      });
      $('tabs').appendChild(button);
    });
    function showEvidence() {
      $('evidence').hidden = false;
      showTab(0);
    }

    form.addEventListener('submit', function (event) {
      event.preventDefault();
      reply(input.value.trim());
    });
    $('run-meta').textContent = t('runMeta', RUN.meta);
    /** Types over the first locked value of the first ticket, the way a model would, and lets gtaw refuse it. */
    function refuse() {
      var punch = tickets.querySelector('.punch');
      refused = true;
      if (!punch) return false;
      var value = punch.value;
      punch.value = value.slice(0, -1) + (value.slice(-1) === '0' ? '1' : '0');
      punch.dispatchEvent(new Event('input'));
      return true;
    }
    log.setAttribute('data-empty', t('logEmpty'));
    replay = {
      canRefuse: function () { return !refused && !!tickets.querySelector('.punch'); },
      refuse: refuse,
      firstTicket: function () { return tickets.querySelector('.ticket'); },
      canReply: function () { return !playing && next < beats.length; },
      playing: function () { return playing; },
      reply: function () { return reply(''); }
    };
    ready();
  }

  /* ---- the rest of the page ------------------------------------------------------------------------------------- */

  var PUNCT = /^([^\u3001\u3002\uff0c\uff1a\uff1b\uff01\uff1f]+)([\u3001\u3002\uff0c\uff1a\uff1b\uff01\uff1f]+)$/;
  /** Without the rising words: a Chinese character and the punctuation after it stay together, the mark not underlined. */
  function punctuation(heading) {
    all('em', heading).forEach(function (em) {
      [].slice.call(em.childNodes).forEach(function (child) {
        if (child.nodeType !== 3) return;
        var fragment = document.createDocumentFragment();
        child.nodeValue.split(/([^\s\u3001\u3002\uff0c\uff1a\uff1b\uff01\uff1f][\u3001\u3002\uff0c\uff1a\uff1b\uff01\uff1f]+)/).forEach(function (part) {
          var tail = part.match(PUNCT);
          if (!tail) {
            if (part) fragment.appendChild(document.createTextNode(part));
            return;
          }
          var pair = el('span', 'nb', tail[1]);
          pair.appendChild(el('span', 'pn', tail[2]));
          fragment.appendChild(pair);
        });
        em.replaceChild(fragment, child);
      });
    });
  }

  /** Wraps the words of a heading (the characters, where a language has no spaces) so that they can rise in turn. */
  function kinetic(heading) {
    var perCharacter = /^zh/.test(document.documentElement.lang);
    var count = 0;
    function wrap(text) {
      // a character keeps the punctuation that follows it, so that a line never starts with a full stop
      var parts = perCharacter ? text.match(/\s+|[^\s][\u3001\u3002\uff0c\uff1a\uff1b\uff01\uff1f.,:;!?]*/g) || [] : text.split(/(\s+)/);
      var fragment = document.createDocumentFragment();
      parts.forEach(function (part) {
        if (!part) return;
        if (/^\s+$/.test(part)) {
          fragment.appendChild(document.createTextNode(' '));
          return;
        }
        var outer = el('span', 'kw');
        var inner = el('span', '', part);
        var tail = perCharacter && part.match(PUNCT);
        if (tail) {
          inner.textContent = tail[1];
          inner.appendChild(el('span', 'pn', tail[2]));
        }
        inner.style.setProperty('--i', count++);
        outer.appendChild(inner);
        fragment.appendChild(outer);
      });
      return fragment;
    }
    function walk(node) {
      [].slice.call(node.childNodes).forEach(function (child) {
        if (child.nodeType === 3) node.replaceChild(wrap(child.nodeValue), child);
        else if (child.nodeType === 1 && child.tagName !== 'CODE') walk(child);
      });
    }
    heading.setAttribute('aria-label', heading.textContent);
    walk(heading);
    heading.classList.add('kin');
  }

  function setUpPage() {
    var scenes = all('[data-scene]');
    var links = all('.nav a');

    if ('IntersectionObserver' in window && !reduced) {
      all('[data-kinetic]').forEach(kinetic);
      var reveal = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          entry.target.classList.add('in');
          reveal.unobserve(entry.target);
        });
      }, { threshold: 0.15 });
      all('.reveal, .kin').forEach(function (node) { reveal.observe(node); });
    } else {
      all('.reveal').forEach(function (node) { node.classList.add('in'); });
      if (/^zh/.test(document.documentElement.lang)) all('[data-kinetic]').forEach(punctuation);
    }

    function currentScene() {
      var middle = window.innerHeight / 2;
      var found = scenes[0];
      scenes.forEach(function (scene) {
        if (scene.getBoundingClientRect().top <= middle) found = scene;
      });
      return found;
    }
    var top = $('top');
    var nights = all('.night');
    var ticking = false;
    var shown = null; // the scene the reader stopped on
    var into = 0; // and how far into it
    var padded = []; // scenes given room below for their last stop
    var HEAD = 56; // the header's height
    var resizing = false;
    var holding = 0; // until then, the re-anchoring after a resize does not overwrite where the presenter was
    /** The header names the scene in view, and turns dark over the dark scenes. */
    function follow() {
      ticking = false;
      if (!resizing && Date.now() > holding) {
        shown = currentScene();
        into = Math.max(0, HEAD - shown.getBoundingClientRect().top);
      }
      var id = currentScene().id;
      links.forEach(function (link) { link.classList.toggle('here', link.getAttribute('href') === '#' + id); });
      var line = top.offsetHeight + 1; // just below the header
      var dark = nights.some(function (night) {
        var box = night.getBoundingClientRect();
        return box.top <= line && box.bottom > line;
      });
      top.classList.toggle('on-night', dark);
    }
    window.addEventListener('scroll', function () {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(follow);
    }, { passive: true });
    follow();

    // the moving shapes of the roles run only while they can be seen
    var roles = document.querySelector('.roles');
    if (roles && 'IntersectionObserver' in window) {
      roles.classList.add('paused');
      new IntersectionObserver(function (entries) {
        roles.classList.toggle('paused', !entries[0].isIntersecting);
      }).observe(roles);
    }

    /** Copies text; where the browser refuses, the old way, and where that fails too, the text is selected. */
    var said = el('p', 'sr');
    said.setAttribute('role', 'status');
    document.body.appendChild(said);
    function copy(text, button, pre) {
      function done() {
        said.textContent = t('copied');
        button.textContent = t('copied');
        window.setTimeout(function () { button.textContent = t('copy'); }, 1400);
      }
      function fallback() {
        var area = el('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        var copied = false;
        try {
          copied = document.execCommand('copy');
        } catch (e) {
          copied = false;
        }
        document.body.removeChild(area);
        button.focus({ preventScroll: true });
        if (copied) return done();
        var range = document.createRange();
        range.selectNodeContents(pre.firstChild);
        window.getSelection().removeAllRanges();
        window.getSelection().addRange(range);
        button.textContent = t('copyFailed');
        said.textContent = t('copyFailed');
        window.setTimeout(function () { button.textContent = t('copy'); }, 2600);
      }
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, fallback);
      else fallback();
    }
    all('pre[data-copy]').forEach(function (pre) {
      var text = pre.textContent;
      var button = el('button', 'copy', t('copy'));
      button.type = 'button';
      button.addEventListener('click', function () { copy(text, button, pre); });
      pre.appendChild(button);
    });

    /* stage mode, keys */
    var stageKey = $('stage-key');
    var hud = $('stage-hud');
    var help = $('help');
    var hudTimer = 0;
    var opener = null;
    var smooth = reduced ? 'auto' : 'smooth';

    function staged() {
      return document.body.classList.contains('stage');
    }
    // stage mode keeps its scene when the window changes size (full screen, another projector): the scene is the one
    // the presenter last stopped on, read before the layout changed
    var settle = 0;
    window.addEventListener('resize', function () {
      resizing = true;
      window.clearTimeout(settle);
      settle = window.setTimeout(function () {
        if (staged() && shown) { // the same scene, and the same place in it as far as it still goes
          shown.scrollIntoView({ behavior: 'instant', block: 'start' }); // not smooth: the next line measures from here
          var further = Math.min(into, Math.max(0, shown.getBoundingClientRect().bottom - window.innerHeight));
          if (further) window.scrollBy({ top: further, behavior: 'instant' });
          holding = Date.now() + 400;
        }
        resizing = false;
      }, 180);
    });
    function stage(target) {
      var on = !staged();
      var scene = target || currentScene();
      shown = scene;
      into = 0;
      document.body.classList.toggle('stage', on);
      stageKey.setAttribute('aria-pressed', on ? 'true' : 'false');
      stageKey.textContent = t(on ? 'stageOn' : 'stage');
      hud.hidden = !on;
      hud.classList.remove('quiet');
      window.clearTimeout(hudTimer);
      if (on) hudTimer = window.setTimeout(function () { hud.classList.add('quiet'); }, 5000);
      if (board) board.stage(on);
      if (board && on && scene.id === 'title' && board.done()) board.run();
      if (!on) padded.splice(0).forEach(function (each) { each.style.paddingBottom = ''; });
      window.dispatchEvent(new Event('resize'));
      window.setTimeout(function () { scene.scrollIntoView({ behavior: 'auto', block: 'start' }); }, 160);
    }
    function go(step) {
      var index = scenes.indexOf(currentScene()) + step;
      if (index < 0 || index >= scenes.length) return;
      scenes[index].scrollIntoView({ behavior: smooth, block: 'start' });
    }
    /** Brings a part of a scene into view before something happens in it, so that the audience sees it happen. */
    function show(node) {
      var box = node.getBoundingClientRect();
      if (box.top >= HEAD + 8 && box.bottom <= window.innerHeight) return;
      var scene = node.closest('[data-scene]').getBoundingClientRect();
      var dy = box.bottom > window.innerHeight
        ? Math.min(box.bottom - window.innerHeight + 16, box.top - HEAD - 8) // up just enough, the top kept in view
        : box.top - HEAD - 28;
      window.scrollBy({ top: Math.max(dy, scene.top - HEAD), behavior: smooth });
    }
    /**
     * One step of a presentation: reply OK where the page waits for a person; otherwise the rest of a scene that is
     * taller than the screen; otherwise the next scene.
     */
    function advance() {
      var scene = currentScene();
      var id = scene.id;
      if ((id === 'title' || id === 'ledger') && board && board.running()) return board.hurry();
      if ((id === 'title' || id === 'ledger') && board && board.waiting()) {
        if (id === 'ledger') show(scene.querySelector('.sheet-desk'));
        return board.ok();
      }
      if (id === 'loop' && replay) {
        if (replay.playing()) return undefined;
        if (replay.canReply() && replay.canRefuse()) { // once a ticket is printed, try to change it before going on
          show(replay.firstTicket());
          return replay.refuse();
        }
        if (replay.canReply()) {
          show($('console'));
          return replay.reply();
        }
      }
      if (Math.abs(scene.getBoundingClientRect().top - HEAD) < 4) scene.style.paddingBottom = ''; // first press in it
      var height = window.innerHeight;
      var box = scene.getBoundingClientRect();
      var end = box.bottom - parseFloat(window.getComputedStyle(scene).paddingBottom); // the end of its content
      if (end <= height + 8) return go(1);
      var lead = HEAD + 28; // a stop puts a block this far down, just under the header
      var reach = height - lead; // the longest step that skips nothing: the bottom of the screen comes up to the lead
      var rest = end - height;
      // the blocks on screen that are worth a step to bring up
      var stops = all('[data-stop]', scene).map(function (stop) { return stop.offsetParent ? stop.getBoundingClientRect() : null; })
        .filter(function (r) { return r && r.top >= lead + 120 && r.top <= height; });
      // the first of them from which the rest of the scene fits on one screen goes to the top, and the scene gets the
      // room it lacks below, so the last stop reads as a slide instead of slicing a heading at the top of the screen
      for (var f = 0; f < stops.length; f++) {
        var lift = stops[f].top - lead;
        var room = lift - rest;
        if (end - lift <= height && room <= height * 0.6) {
          if (room > 0) {
            scene.style.paddingBottom = parseFloat(window.getComputedStyle(scene).paddingBottom) + room + 'px';
            padded.push(scene);
          }
          return window.scrollBy({ top: lift, behavior: smooth });
        }
      }
      for (var n = 0; n < stops.length; n++) { // otherwise the first block not seen whole
        if (stops[n].bottom > height + 8) {
          var up = stops[n].top - lead;
          return window.scrollBy({ top: rest > up && rest - up <= 28 ? last(scene, rest) : up, behavior: smooth }); // no 9 px tail
        }
      }
      // otherwise a plain step: never past what was not on screen, never cutting a block that crosses the bottom edge
      // (it starts the next screen, from its own top rather than from a line inside it), and never a short last one
      [0.25, 0.5, 0.75].forEach(function (x) {
        var under = document.elementsFromPoint(window.innerWidth * x, height - 2)[0];
        if (!under || !scene.contains(under)) return;
        for (var node = under; node && node !== scene; node = node.parentElement) {
          var top = node.getBoundingClientRect().top;
          if (top > lead + 80 && top < height) reach = Math.min(reach, top - lead);
        }
      });
      // a tail of up to 28 px goes with this step: the lead keeps that much room under the header, so nothing is lost
      var step = rest <= reach + 28 ? rest : Math.min(reach, rest - 120); // and leave the last step something to show
      if (step < 80) step = Math.min(reach, rest); // what is left is short: take it now, never creep
      return window.scrollBy({ top: step === rest ? last(scene, step) : step, behavior: smooth });
    }
    /**
     * The last step of a scene, to its end: a row that would end up half under the header goes all the way up, the
     * scene getting the few pixels of room that takes.
     */
    function last(scene, step) {
      var bottom = 0;
      [0.25, 0.5, 0.75].forEach(function (x) {
        var cut = document.elementsFromPoint(window.innerWidth * x, HEAD + 2 + step)[0];
        var row = cut && scene.contains(cut) ? cut.closest('tr, li, p, h2, h3, pre, .ticket') : null;
        if (!row || !scene.contains(row)) return;
        var box = row.getBoundingClientRect();
        if (box.top - step < HEAD && box.bottom - step > HEAD) bottom = Math.max(bottom, box.bottom - step - HEAD);
      });
      if (bottom <= 0 || bottom > 120) return step;
      scene.style.paddingBottom = parseFloat(window.getComputedStyle(scene).paddingBottom) + bottom + 'px';
      padded.push(scene);
      return step + bottom;
    }
    /** Back: to the top of the scene when part of it was stepped through, otherwise the scene before. */
    function back() {
      var top = currentScene().getBoundingClientRect().top;
      if (top < -24) return currentScene().scrollIntoView({ behavior: smooth, block: 'start' });
      return go(-1);
    }
    function toggleHelp(show) {
      if (show === !help.hidden) return;
      help.hidden = !show;
      if (show) {
        opener = document.activeElement;
        $('help-close').focus();
      } else if (opener && opener.focus) {
        opener.focus({ preventScroll: true });
        opener = null;
      }
    }

    stageKey.addEventListener('click', function () { stage(); });
    $('help-close').addEventListener('click', function () { toggleHelp(false); });
    help.addEventListener('click', function (event) {
      if (event.target === help) toggleHelp(false);
    });

    // the other language opens at the same scene, in stage mode if this one is in it
    var languages = all('.tool[hreflang]');
    function point() {
      languages.forEach(function (link) {
        link.href = link.getAttribute('href').split(/[?#]/)[0] + (staged() ? '?stage' : '') + '#' + currentScene().id;
      });
    }
    languages.forEach(function (link) {
      ['click', 'auxclick', 'pointerdown', 'focus', 'contextmenu'].forEach(function (type) { link.addEventListener(type, point); });
    });

    document.addEventListener('keydown', function (event) {
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      var target = event.target;
      if (!help.hidden) { // a modal dialog: only its own keys
        if (event.key === 'Escape') toggleHelp(false);
        else if (event.key === 'Tab') {
          event.preventDefault();
          $('help-close').focus();
        } else if (event.key !== 'Enter' && event.key !== ' ') {
          event.preventDefault();
        }
        return;
      }
      var typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
      if (event.key === 'Escape') {
        if (typing) target.blur();
        return;
      }
      // in stage mode an empty reply box does not take the presenter's keys
      var presenting = { ArrowRight: 1, ArrowLeft: 1, PageDown: 1, PageUp: 1, ' ': 1 }[event.key];
      if (typing && staged() && presenting && target.id === 'reply' && !target.value.trim()) {
        target.value = '';
        target.blur();
        typing = false;
      }
      if (typing) return;
      var onControl = target && (target.tagName === 'BUTTON' || target.tagName === 'A');
      switch (event.key) {
        case 'p': case 'P': stage(); break;
        case 'f': case 'F':
          if (document.fullscreenElement) document.exitFullscreen();
          else if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen().catch(function () {});
          break;
        case '?': toggleHelp(true); break;
        case 'g': case 'G':
          if (board && (currentScene().id === 'title' || currentScene().id === 'ledger')) board.force(currentScene());
          break;
        case 'ArrowRight': case 'PageDown':
          if (staged()) { event.preventDefault(); advance(); }
          break;
        case ' ': // in stage mode Space presents, even on a focused button (Enter still presses it)
          if (staged()) { event.preventDefault(); advance(); }
          break;
        case 'ArrowLeft': case 'PageUp':
          if (staged()) { event.preventDefault(); back(); }
          break;
        case 'Enter':
          var scene = currentScene().id;
          if (!onControl && board && (scene === 'title' || scene === 'ledger')) board.ok();
          break;
        default: return;
      }
    });
    return { stage: stage };
  }

  var page = setUpPage();
  setUpBoard();
  setUpReplay();
  if (/[?&]stage\b/.test(window.location.search) && window.innerWidth > 700) { // opened from the other language in stage mode, at its scene
    var at = window.location.hash ? document.getElementById(window.location.hash.slice(1)) : null;
    page.stage(at && at.hasAttribute('data-scene') ? at : null);
  }
})();
