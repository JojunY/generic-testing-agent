/* The page's behaviour: the verdict board of the hero, the small boards of the scenes, the replay of the recorded gtaw
   run (assets/js/run-data.js), and stage mode. Every string a visitor reads comes from the page (the #i18n block), so
   this file is the same for every language. Works from file://, with no dependency. */
(function () {
  'use strict';

  var Board = window.Flap.Board;
  var RUN = window.GTA_RUN || null;
  var TEXT = JSON.parse(document.getElementById('i18n').textContent);
  var reduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

  function $(id) {
    return document.getElementById(id);
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
  function clack(count) {
    window.FlapSound.clack(count);
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

  /* ---- what a row of a board can say ---------------------------------------------------------------------------- */

  var STATES = {
    blank: { wide: '', short: '', style: 'dim', who: '', lamp: 'off', hide: true },
    none: { wide: 'NO EVIDENCE', short: 'NO PROOF', style: 'dim', who: '', lamp: 'off' },
    writing: { wide: 'AI WRITING', short: 'WRITING', style: 'ivory', who: 'AI', lamp: 'off' },
    running: { wide: 'GTAW RUNNING', short: 'RUNNING', style: 'ivory', who: 'GTAW', lamp: 'off' },
    review: { wide: 'AI REVIEWING', short: 'REVIEW', style: 'ivory', who: 'AI', lamp: 'off' },
    failed: { wide: 'FAILED', short: 'FAILED', style: 'ivory', who: 'GTAW', lamp: 'off' },
    triage: { wide: 'AI TRIAGE', short: 'TRIAGE', style: 'ivory', who: 'AI', lamp: 'off' },
    waiting: { wide: 'WAITING FOR YOU', short: 'WAITING', style: 'amber', who: 'YOU', lamp: 'amber' },
    claimed: { wide: 'PASSED', short: 'PASSED', style: 'hollow', who: '', lamp: 'off', locked: true },
    passed: { wide: 'PASSED', short: 'PASSED', style: 'green', who: '', lamp: 'green', locked: true },
    pinned: { wide: 'DEFECT PINNED', short: 'PINNED', style: 'red', who: '', lamp: 'red', locked: true }
  };

  /** Writes one row of the model ({scenario, kind, locked, state}) to a board, in the columns the board has. */
  function paintRow(board, index, row, options) {
    options = options || {};
    var state = STATES[row.state];
    var base = { instant: options.instant, delay: options.delay || 0 };
    function write(key, text, style, extra) {
      var o = { instant: base.instant, delay: base.delay + (extra || 0), style: style };
      board.set(index, key, text, o);
    }
    write('scenario', state.hide ? '' : row.scenario, row.state === 'none' ? 'dim' : 'ivory');
    write('kind', state.hide ? '' : row.kind, 'muted');
    write('locked', state.locked ? row.locked : '', 'ivory', 120);
    write('verdict', board.short ? state.short : state.wide, state.style, 60);
    write('who', state.who, state.who === 'YOU' ? 'amber' : 'dim', 60);
    board.lamp(index, state.lamp);
  }

  /** Keeps a board laid out for its container, and pauses its lamps when it is off screen. */
  function keepFitted(make, container) {
    var board = make();
    var width = container.clientWidth;
    var timer = 0;
    window.addEventListener('resize', function () {
      window.clearTimeout(timer);
      timer = window.setTimeout(function () {
        if (container.clientWidth === width) return;
        width = container.clientWidth;
        board = make(board);
      }, 120);
    });
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) {
        board.visible = entries[0].isIntersecting;
        if (board.visible) board.start();
      }).observe(container);
    }
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) board.start(); // the lamps stop pulsing in a hidden tab
    });
    return function () {
      return board;
    };
  }

  /* ---- the hero: the verdict board ------------------------------------------------------------------------------ */

  var hero = null;

  function setUpHero() {
    var hall = $('hall');
    var canvas = $('main-board');
    var holder = canvas.parentNode;
    var rows = [].map.call(document.querySelectorAll('#board-data tbody tr'), function (tr) {
      var cells = tr.children;
      return {
        scenario: cells[0].textContent,
        kind: cells[1].textContent,
        locked: cells[2].textContent,
        end: tr.getAttribute('data-end'),
        state: 'blank'
      };
    });
    var order = [0, 4, 1, 7, 2, 9, 5, 11, 3, 8, 6, 10]; // the rows do not start top to bottom
    var timers = [];
    var tried = 0;
    var introOn = false;
    var skipIntro = null;

    function make() {
      var inner = holder.clientWidth - 40; // the holder's padding
      var columns;
      var short = false;
      if (inner >= 900) {
        columns = [
          { key: 'scenario', len: 26, label: t('colScenario') },
          { key: 'kind', len: 11, label: t('colKind') },
          { key: 'locked', len: 18, label: t('colLocked') },
          { key: 'verdict', len: 15, label: t('colVerdict') },
          { key: 'who', len: 4, label: t('colWho') }
        ];
      } else if (inner >= 560) {
        columns = [
          { key: 'scenario', len: 26, label: t('colScenario') },
          { key: 'locked', len: 18, label: t('colLocked') },
          { key: 'verdict', len: 15, label: t('colVerdict') }
        ];
      } else {
        short = true;
        columns = [
          { key: 'scenario', len: 25, label: t('colScenario') },
          { key: 'verdict', len: 8, label: t('colVerdict') }
        ];
      }
      var board = new Board(canvas, {
        columns: columns,
        rows: rows.length,
        lampAfter: 'verdict',
        maxCell: 22,
        ratio: 1.62,
        instant: reduced,
        onFlap: clack
      });
      board.short = short;
      for (var i = 0; i < rows.length; i++) paintRow(board, i, rows[i], { instant: true });
      return board;
    }
    var current = keepFitted(make, holder);

    function paint(index, delay) {
      paintRow(current(), index, rows[index], { delay: delay });
      desk();
    }
    function at(ms, fn) {
      timers.push(later(ms, fn));
    }
    function move(index, state) {
      rows[index].state = state;
      paint(index);
    }

    function desk() {
      var waiting = rows.filter(function (row) { return row.state === 'waiting'; }).length;
      var open = rows.filter(function (row) { return row.state !== 'passed' && row.state !== 'pinned'; }).length;
      var key = $('ok-key');
      var note = $('ok-note');
      key.disabled = waiting === 0;
      if (waiting > 0) {
        note.textContent = waiting === 1 ? t('okOne') : t('okMany', { n: waiting });
      } else if (open === 0) {
        var passed = rows.filter(function (row) { return row.state === 'passed'; }).length;
        note.textContent = t('okDone', { p: passed, d: rows.length - passed }) + ' ';
        var again = el('button', 'tool', t('again'));
        again.type = 'button';
        again.addEventListener('click', run);
        note.appendChild(again);
      } else {
        note.textContent = t('okNone');
      }
    }

    /** The illustrative part: each row goes through the steps a scenario goes through, and stops where it must. */
    function run() {
      timers.forEach(window.clearTimeout);
      timers = [];
      rows.forEach(function (row, i) {
        row.state = 'blank';
        paintRow(current(), i, row, { instant: true });
      });
      desk();
      rows.forEach(function (row, i) {
        at(i * 90, function () { move(i, 'none'); });
      });
      var base = rows.length * 90 + 700;
      rows.forEach(function (row, i) {
        var start = base + order[i % order.length] * 430;
        at(start, function () { move(i, 'writing'); });
        at(start + 1300, function () { move(i, 'running'); });
        if (row.end === 'passed') {
          at(start + 2500, function () { move(i, 'review'); });
          at(start + 3600, function () { move(i, 'passed'); });
        } else {
          at(start + 2500, function () { move(i, 'failed'); });
          at(start + 3300, function () { move(i, 'triage'); });
          at(start + 4300, function () { move(i, 'waiting'); });
        }
      });
    }

    function ok() {
      var n = 0;
      rows.forEach(function (row, i) {
        if (row.state !== 'waiting') return;
        row.state = 'pinned';
        paint(i, n++ * 140);
      });
      return n > 0;
    }

    /** The visitor tries to make the board green. It shows the claim for a moment, then what gtaw can prove. */
    function force() {
      var board = current();
      tried++;
      $('tried').textContent = tried;
      $('force-text').textContent = t(tried === 1 ? 'forceRefused' : tried === 2 ? 'forceRefused2' : 'forceRefused3');
      $('force-note').classList.add('refused');
      rows.forEach(function (row, i) {
        if (row.state === 'passed' || row.state === 'blank') return;
        board.set(i, 'verdict', 'PASSED', { style: 'hollow', delay: i * 22 });
        board.lamp(i, 'off');
      });
      window.setTimeout(function () {
        rows.forEach(function (row, i) { paint(i, i * 22); });
        shake($('force-key'));
      }, 950);
    }

    function intro() {
      if (reduced) {
        run();
        return;
      }
      introOn = true;
      hall.classList.add('intro');
      var macro = new Board($('macro-board'), {
        columns: [{ key: 'word', len: 11 }],
        rows: 1,
        header: false,
        maxCell: 92,
        ratio: 1.42,
        onFlap: clack
      });
      var steps = [];
      function pull() {
        if (!introOn) return;
        introOn = false;
        steps.forEach(window.clearTimeout);
        document.removeEventListener('pointerdown', pull, true);
        hall.classList.remove('intro');
        hall.classList.add('pull');
        $('macro').classList.add('back');
        run();
        window.setTimeout(function () { hall.classList.remove('pull'); }, 900);
      }
      skipIntro = pull;
      steps.push(window.setTimeout(function () { macro.set(0, 'word', '  PASSED', { style: 'hollow', stagger: 45 }); }, 350));
      steps.push(window.setTimeout(function () { macro.set(0, 'word', 'NO EVIDENCE', { style: 'ivory', stagger: 45 }); }, 1750));
      steps.push(window.setTimeout(pull, 3150));
      document.addEventListener('pointerdown', pull, true);
    }

    $('ok-key').addEventListener('click', ok);
    $('force-key').addEventListener('click', force);
    hero = {
      ok: ok,
      waiting: function () { return rows.some(function (row) { return row.state === 'waiting'; }); },
      inIntro: function () { return introOn; },
      skip: function () { if (skipIntro) skipIntro(); }
    };
    intro();
  }

  /* ---- small boards in the scenes ------------------------------------------------------------------------------- */

  function whenSeen(node, fn) {
    if (!('IntersectionObserver' in window)) {
      fn();
      return;
    }
    var seen = new IntersectionObserver(function (entries) {
      if (!entries[0].isIntersecting) return;
      seen.disconnect();
      fn();
    }, { threshold: 0.4 });
    seen.observe(node);
  }

  function setUpMinis() {
    [].forEach.call(document.querySelectorAll('canvas[data-mini]'), function (canvas) {
      var self = canvas.getAttribute('data-mini') === 'self';
      var holder = canvas.parentNode;
      var row = { scenario: 'borrow.book/s02', kind: '', locked: self ? '201 BOOKED' : '503 PENDING_LOCATE', state: 'none' };
      function make() {
        var columns = [
          { key: 'locked', len: 18, label: self ? t('colExpected') : t('colLocked') },
          { key: 'verdict', len: 13, label: t('colVerdict') }
        ];
        var board = new Board(canvas, { columns: columns, rows: 1, lampAfter: 'verdict', maxCell: 18, ratio: 1.62, instant: reduced, onFlap: clack });
        paintRow(board, 0, row, { instant: true });
        return board;
      }
      var current = keepFitted(make, holder);
      whenSeen(canvas, function () {
        later(500, function () {
          row.state = self ? 'claimed' : 'pinned';
          paintRow(current(), 0, row, { delay: self ? 0 : 900 });
        });
      });
    });

    var safety = $('safety-board');
    if (safety) {
      var names = ['SIT', 'UAT', 'PROD'];
      var shown = false;
      var makeSafety = function () {
        var board = new Board(safety, {
          columns: [
            { key: 'env', len: 5, label: t('colEnv') },
            { key: 'service', len: 11, label: t('colService') }
          ],
          rows: names.length,
          maxCell: 30,
          instant: reduced,
          onFlap: clack
        });
        names.forEach(function (name, i) {
          board.set(i, 'env', name, { instant: true });
          if (shown) board.set(i, 'service', t('noService'), { instant: true, style: 'dim' });
        });
        return board;
      };
      var currentSafety = keepFitted(makeSafety, safety.parentNode);
      whenSeen(safety, function () {
        shown = true;
        names.forEach(function (name, i) {
          currentSafety().set(i, 'service', t('noService'), { style: 'dim', delay: 300 + i * 380 });
        });
      });
    }
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
    var canvas = $('loop-board');
    var holder = canvas.parentNode;
    var rows = [
      { scenario: 'S01', id: 'borrow.book/s01', kind: '', locked: '', state: 'blank' },
      { scenario: 'S02', id: 'borrow.book/s02', kind: '', locked: '', state: 'blank' }
    ];
    var beats = [];
    var next = 0;
    var playing = false;
    var timers = [];
    var lock = {};
    var scenarios = {};

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
      function (all, title, id, when, then) {
        scenarios[id] = { title: title, when: when.replace(/`/g, ''), then: then };
      }
    );
    rows.forEach(function (row) { row.locked = (lock[row.id] || []).join(' '); });

    function make() {
      var board = new Board(canvas, {
        columns: [
          { key: 'scenario', len: 3, label: '#' },
          { key: 'locked', len: 18, label: t('colLocked') },
          { key: 'verdict', len: 15, label: t('colVerdict') }
        ],
        rows: 2,
        lampAfter: 'verdict',
        maxCell: 18,
        instant: reduced,
        onFlap: clack
      });
      for (var i = 0; i < rows.length; i++) paintRow(board, i, rows[i], { instant: true });
      return board;
    }
    var current = keepFitted(make, holder);
    function move(index, state) {
      rows[index].state = state;
      paintRow(current(), index, rows[index]);
    }

    // what the board and the tickets do when an event of the recording is shown
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
      's02-failed': function () { move(1, 'failed'); },
      triage: function () { move(1, 'triage'); },
      defect: function () { move(1, 'waiting'); },
      'defect-confirmed': function () { move(1, 'none'); },
      'author-pin': function () { move(1, 'writing'); },
      pinned: function () { move(1, 'pinned'); printTicket(rows[1], 'pinned'); }
    };

    function setPen(who) {
      pen.setAttribute('data-who', who);
      pen.textContent = t({ you: 'penYou', ai: 'penAi', gtaw: 'penGtaw', done: 'penDone' }[who]);
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
        var items = out.items.map(function (item) { return item.id + ' ' + item.status; }).join(' · ');
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
      if (form.getAttribute('data-typed')) input.focus({ preventScroll: true });
    }

    function play(beat) {
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
      if (text && text.toLowerCase() !== expected.toLowerCase()) {
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
      log.textContent = '';
      [].slice.call(tickets.querySelectorAll('.ticket')).forEach(function (ticket) { tickets.removeChild(ticket); });
      $('ticket-slot').hidden = false;
      $('evidence').hidden = true;
      rows.forEach(function (row, i) {
        row.state = 'blank';
        paintRow(current(), i, row, { instant: true });
      });
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
      head.appendChild(el('span', 'ticket-stamp ' + verdict, t(verdict === 'passed' ? 'ticketPassed' : 'ticketPinned')));
      ticket.appendChild(head);
      ticket.appendChild(el('p', 'ticket-label', t('ticketWhen')));
      ticket.appendChild(el('p', 'ticket-when', scenario.when));
      ticket.appendChild(el('p', 'ticket-label', t('ticketThen')));
      var values = el('div', 'ticket-values');
      var note = el('p', 'ticket-try', t('ticketEdit'));
      tokens.forEach(function (token) {
        var punch = el('input', 'punch');
        punch.value = token;
        punch.size = Math.max(3, token.length);
        punch.spellcheck = false;
        punch.setAttribute('aria-label', t('ticketThen') + ' ' + token);
        var timer = 0;
        function restore() {
          window.clearTimeout(timer);
          if (punch.value === token) return;
          punch.value = token;
          punch.classList.remove('back');
          void punch.offsetWidth;
          punch.classList.add('back');
          note.className = 'ticket-refused';
          note.textContent = t('ticketRefused', { tokens: token });
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
      body.appendChild(pre);
      [].forEach.call($('tabs').children, function (button, n) {
        button.setAttribute('aria-selected', n === index ? 'true' : 'false');
      });
      var hit = pre.querySelector('.hit');
      if (hit) pre.scrollTop = hit.offsetTop - pre.offsetTop - 120;
    }
    tabs.forEach(function (tab, index) {
      var button = el('button', 'tab', t(tab[0]));
      button.type = 'button';
      button.setAttribute('role', 'tab');
      button.addEventListener('click', function () { showTab(index); });
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
    input.addEventListener('focus', function () { form.setAttribute('data-typed', '1'); });
    $('run-meta').textContent = t('runMeta', RUN.meta);
    replay = {
      canReply: function () { return !playing && next < beats.length; },
      playing: function () { return playing; },
      reply: function () { return reply(''); }
    };
    ready();
  }

  /* ---- the rest of the page ------------------------------------------------------------------------------------- */

  function setUpPage() {
    var scenes = [].slice.call(document.querySelectorAll('[data-scene]'));
    var links = [].slice.call(document.querySelectorAll('.nav a'));

    if ('IntersectionObserver' in window && !reduced) {
      var reveal = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          entry.target.classList.add('in');
          reveal.unobserve(entry.target);
        });
      }, { threshold: 0.15 });
      [].forEach.call(document.querySelectorAll('.reveal'), function (node) { reveal.observe(node); });
    } else {
      [].forEach.call(document.querySelectorAll('.reveal'), function (node) { node.classList.add('in'); });
    }

    function currentScene() {
      var middle = window.innerHeight / 2;
      var found = scenes[0];
      scenes.forEach(function (scene) {
        if (scene.getBoundingClientRect().top <= middle) found = scene;
      });
      return found;
    }
    var ticking = false;
    window.addEventListener('scroll', function () {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(function () {
        ticking = false;
        var id = currentScene().id;
        links.forEach(function (link) { link.classList.toggle('here', link.getAttribute('href') === '#' + id); });
      });
    }, { passive: true });

    [].forEach.call(document.querySelectorAll('pre[data-copy]'), function (pre) {
      var text = pre.textContent;
      var button = el('button', 'copy', t('copy'));
      button.type = 'button';
      button.addEventListener('click', function () {
        function done() {
          button.textContent = t('copied');
          window.setTimeout(function () { button.textContent = t('copy'); }, 1400);
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done, function () {});
        } else {
          var area = el('textarea');
          area.value = text;
          document.body.appendChild(area);
          area.select();
          try {
            document.execCommand('copy');
            done();
          } catch (e) { /* the command stays selectable */ }
          document.body.removeChild(area);
        }
      });
      pre.appendChild(button);
    });

    /* sound, stage mode, keys */
    var soundKey = $('sound-key');
    var stageKey = $('stage-key');
    var hud = $('stage-hud');
    var help = $('help');
    var hudTimer = 0;

    function sound() {
      var on = window.FlapSound.toggle();
      soundKey.setAttribute('aria-pressed', on ? 'true' : 'false');
      soundKey.textContent = t(on ? 'soundOn' : 'soundOff');
      if (on) clack(3);
    }
    function stage() {
      var on = !document.body.classList.contains('stage');
      var scene = currentScene();
      document.body.classList.toggle('stage', on);
      stageKey.setAttribute('aria-pressed', on ? 'true' : 'false');
      stageKey.textContent = t(on ? 'stageOn' : 'stage');
      hud.hidden = !on;
      hud.classList.remove('quiet');
      window.clearTimeout(hudTimer);
      if (on) hudTimer = window.setTimeout(function () { hud.classList.add('quiet'); }, 5000);
      window.dispatchEvent(new Event('resize'));
      window.setTimeout(function () { scene.scrollIntoView({ behavior: 'auto', block: 'start' }); }, 160);
    }
    function go(step) {
      var index = scenes.indexOf(currentScene()) + step;
      if (index < 0 || index >= scenes.length) return;
      scenes[index].scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
    }
    /** One step of a presentation: reply OK where the page waits for a person, otherwise the next section. */
    function advance() {
      var id = currentScene().id;
      if (hero && hero.inIntro()) return hero.skip();
      if (id === 'board' && hero && hero.waiting()) return hero.ok();
      if (id === 'loop' && replay) {
        if (replay.playing()) return undefined;
        if (replay.canReply()) return replay.reply();
      }
      return go(1);
    }
    function toggleHelp(show) {
      help.hidden = !show;
      if (show) $('help-close').focus();
    }

    soundKey.addEventListener('click', sound);
    stageKey.addEventListener('click', stage);
    $('help-close').addEventListener('click', function () { toggleHelp(false); });
    help.addEventListener('click', function (event) {
      if (event.target === help) toggleHelp(false);
    });

    document.addEventListener('keydown', function (event) {
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      var target = event.target;
      var typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable);
      if (event.key === 'Escape') {
        if (!help.hidden) toggleHelp(false);
        else if (typing) target.blur();
        return;
      }
      if (typing) return;
      var onControl = target && (target.tagName === 'BUTTON' || target.tagName === 'A');
      var staged = document.body.classList.contains('stage');
      switch (event.key) {
        case 'p': case 'P': stage(); break;
        case 'm': case 'M': sound(); break;
        case 'f': case 'F':
          if (document.fullscreenElement) document.exitFullscreen();
          else if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen().catch(function () {});
          break;
        case '?': toggleHelp(help.hidden); break;
        case 'ArrowRight': case 'PageDown':
          if (staged) { event.preventDefault(); advance(); }
          break;
        case ' ':
          if (staged && !onControl) { event.preventDefault(); advance(); }
          break;
        case 'ArrowLeft': case 'PageUp':
          if (staged) { event.preventDefault(); go(-1); }
          break;
        case 'Enter':
          if (!onControl && currentScene().id === 'board' && hero) {
            if (hero.inIntro()) hero.skip();
            else hero.ok();
          }
          break;
        default: return;
      }
    });
  }

  function begin() {
    setUpPage();
    setUpHero();
    setUpMinis();
    setUpReplay();
  }

  // the flaps are drawn with the condensed face: wait for it, but not for long
  var started = false;
  function once() {
    if (started) return;
    started = true;
    begin();
  }
  if (document.fonts && document.fonts.load) {
    document.fonts.load('600 20px "Barlow Condensed"').then(once, once);
    window.setTimeout(once, 1500);
  } else {
    once();
  }
})();
