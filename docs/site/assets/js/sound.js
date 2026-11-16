/* The clack of a flap, synthesised (no audio files). Off until the visitor turns it on. */
(function () {
  'use strict';

  var context = null;
  var noise = null;
  var on = false;
  var lastClack = 0;

  function ensure() {
    if (context) return true;
    var Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) return false;
    context = new Ctor();
    var length = Math.floor(context.sampleRate * 0.03);
    noise = context.createBuffer(1, length, context.sampleRate);
    var data = noise.getChannelData(0);
    for (var i = 0; i < length; i++) data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / length, 3);
    return true;
  }

  function clack(count) {
    if (!on || !context) return;
    var now = context.currentTime;
    if (now - lastClack < 0.024) return;
    lastClack = now;
    var source = context.createBufferSource();
    source.buffer = noise;
    source.playbackRate.value = 0.8 + Math.random() * 0.5;
    var filter = context.createBiquadFilter();
    filter.type = 'bandpass';
    filter.frequency.value = 1700 + Math.random() * 900;
    filter.Q.value = 0.9;
    var gain = context.createGain();
    gain.gain.value = Math.min(0.16, 0.05 + 0.012 * (count || 1));
    source.connect(filter);
    filter.connect(gain);
    gain.connect(context.destination);
    source.start();
  }

  function toggle() {
    if (!on && !ensure()) return false;
    on = !on;
    if (on && context.state === 'suspended') context.resume();
    return on;
  }

  window.FlapSound = { clack: clack, toggle: toggle, isOn: function () { return on; } };
})();
