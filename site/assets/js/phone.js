/* NeriPlayer site · phone —— 页面里的三台手机：共享播放器、波形进度条、同步歌词、音效面板、首页调色 */
(function () {
  'use strict';

  var NP = window.NP;
  var M = NP.math;
  var $ = NP.$;
  var $$ = NP.$$;

  var SCRIPT_BASE = ((document.currentScript && document.currentScript.src) || '').replace(/[^/]*$/, '');
  var PRIMARY = '#d2bcfd';
  var INACTIVE = 'rgba(230, 224, 233, 0.3)';
  var PX = 1 / 2.625; // App 源码里的像素值按 420 dpi 换算成 dp

  function fmt(t) {
    t = Math.max(0, Math.floor(t));
    var m = Math.floor(t / 60);
    var s = t % 60;
    return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }

  /* ---------- 共享播放器 ---------- */
  var audio = $('#player');
  var P = (NP.player = {
    t: 8.2,
    dur: 72.45,
    playing: false,
    audible: false,
    userPaused: false,
    speed: 1,
    pitch: 1,
    volume: 1,
    repeat: 0, // 0 关闭 · 1 列表循环 · 2 单曲循环，与 PlayerQueueNavigationOwner.nextRepeatMode 的顺序一致
    shuffle: false,
    liked: false,
    listeners: [],
  });
  function emit() { for (var i = 0; i < P.listeners.length; i++) P.listeners[i](P); }
  P.on = function (fn) { P.listeners.push(fn); fn(P); };
  P.emit = emit;

  /* 信号链：音源 → 移调 → 五段均衡 → 响度增强 → 限幅 → 音量 */
  var fx = null;
  function setupAudioGraph() {
    if (fx !== null || !/^https?:$/.test(location.protocol)) return;
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) { fx = false; return; }
    try {
      var ctx = new AC();
      var src = ctx.createMediaElementSource(audio);
      var pre = ctx.createGain();
      var bands = [60, 230, 910, 3600, 14000].map(function (f, i) {
        var b = ctx.createBiquadFilter();
        b.type = i === 0 ? 'lowshelf' : i === 4 ? 'highshelf' : 'peaking';
        b.frequency.value = f;
        b.Q.value = 0.9;
        return b;
      });
      var loud = ctx.createGain();
      var limiter = ctx.createDynamicsCompressor();
      limiter.threshold.value = -1.5;
      limiter.knee.value = 0;
      limiter.ratio.value = 20;
      limiter.attack.value = 0.002;
      limiter.release.value = 0.12;
      var vol = ctx.createGain();
      src.connect(pre);
      var node = pre;
      bands.forEach(function (b) { node.connect(b); node = b; });
      node.connect(loud);
      loud.connect(limiter);
      limiter.connect(vol);
      vol.connect(ctx.destination);
      fx = { ctx: ctx, src: src, pre: pre, bands: bands, loud: loud, vol: vol, pitch: null };
      if (ctx.audioWorklet && SCRIPT_BASE) {
        ctx.audioWorklet.addModule(SCRIPT_BASE + 'pitch-worklet.js').then(function () {
          var node = new AudioWorkletNode(ctx, 'neri-pitch', { outputChannelCount: [2] });
          src.disconnect();
          src.connect(node);
          node.connect(pre);
          fx.pitch = node;
          applyFx();
        }).catch(function () { /* 不支持 AudioWorklet 时只保留倍速 */ });
      }
      applyFx();
    } catch (e) {
      fx = false;
    }
  }
  var sound = { eqOn: true, levels: [0, 0, 0, 0, 0], loud: 0, preset: -1 };
  function applyFx() {
    if (!fx) return;
    var t = fx.ctx.currentTime;
    for (var i = 0; i < 5; i++) fx.bands[i].gain.setTargetAtTime(sound.eqOn ? sound.levels[i] : 0, t, 0.03);
    fx.loud.gain.setTargetAtTime(Math.pow(10, sound.loud / 20), t, 0.03);
    fx.vol.gain.setTargetAtTime(P.volume, t, 0.03);
    if (fx.pitch) fx.pitch.parameters.get('ratio').setValueAtTime(P.pitch, t);
  }
  NP.sound = { state: sound, apply: applyFx, live: function () { return !!fx && P.audible; } };

  P.play = function () {
    if (!audio) return;
    setupAudioGraph();
    if (fx && fx.ctx.state === 'suspended') fx.ctx.resume();
    P.pending = P.t;
    if (audio.readyState === 0) audio.load();
    else { try { audio.currentTime = P.t; P.pending = null; } catch (e) { /* 元数据未就绪时在 loadedmetadata 里补 */ } }
    audio.playbackRate = P.speed;
    P.audible = true;
    P.playing = true;
    P.userPaused = false;
    var pr = audio.play();
    if (pr && pr.catch) {
      pr.catch(function () {
        P.audible = false;
        NP.toast(NP.L('浏览器拦下了自动播放，再点一次播放键试试', 'The browser blocked autoplay, try the play button again'));
        emit();
      });
    }
    emit();
  };
  P.pause = function () {
    P.playing = false;
    P.audible = false;
    P.userPaused = true;
    if (audio) audio.pause();
    emit();
  };
  P.toggle = function () {
    if (P.playing && P.audible) P.pause();
    else P.play();
  };
  P.seek = function (t) {
    P.t = M.clamp(t, 0, P.dur - 0.05);
    if (P.audible && audio) {
      if (audio.readyState >= 1) { try { audio.currentTime = P.t; } catch (e) { /* ignore */ } }
      else P.pending = P.t;
    }
    P.snap = true;
    emit();
  };
  P.setSpeed = function (v) {
    P.speed = v;
    if (audio) {
      if ('preservesPitch' in audio) audio.preservesPitch = true;
      else if ('webkitPreservesPitch' in audio) audio.webkitPreservesPitch = true;
      audio.playbackRate = v;
    }
    emit();
  };
  P.setPitch = function (v) { P.pitch = v; applyFx(); emit(); };
  P.setVolume = function (v) {
    P.volume = M.clamp(v, 0, 1);
    if (fx) applyFx();
    else if (audio) audio.volume = P.volume;
    emit();
  };
  P.startPreview = function () {
    if (P.playing || P.userPaused || NP.env.reduced) return;
    P.playing = true;
    P.audible = false;
    emit();
  };
  P.stopPreview = function () {
    if (!P.playing || P.audible) return;
    P.playing = false;
    emit();
  };

  if (audio) {
    audio.addEventListener('loadedmetadata', function () {
      if (isFinite(audio.duration) && audio.duration > 1) P.dur = audio.duration;
      if (P.pending != null) {
        try { audio.currentTime = P.pending; } catch (e) { /* ignore */ }
        P.pending = null;
      }
      emit();
    });
    audio.addEventListener('ended', function () {
      // 示例队列里《星轨》排在第 10 首：单曲循环重播；列表循环会回到第 1 首，这里只带了这一首的音频，同样从头播
      if (P.repeat) {
        P.t = 0;
        audio.currentTime = 0;
        audio.play();
      } else {
        P.playing = false;
        P.audible = false;
        P.t = 0;
        emit();
      }
    });
    audio.addEventListener('pause', function () {
      if (P.audible && !audio.ended) { P.playing = false; P.audible = false; P.userPaused = true; emit(); }
    });
  }

  NP.addFrame(function (dt) {
    if (!P.playing) return;
    if (P.audible && audio) {
      // 缓冲或等待跳转时停在原位，避免进度被尚未加载的音频拉回 0
      if (audio.paused || audio.seeking || audio.readyState < 3 || P.pending != null) return;
      var at = audio.currentTime;
      P.t += dt * P.speed;
      if (Math.abs(at - P.t) > 0.12) P.t = at;
    } else if (!P.audible) {
      P.t += dt * P.speed;
      if (P.t >= P.dur) { P.t = 0; P.snap = true; }
    }
  }, -5);

  /* 节拍能量：《星轨》为 128 BPM，按段落给出电平 */
  function songEnergy(t) {
    var beat = 60 / 128;
    var ph = t / beat;
    var frac = ph - Math.floor(ph);
    var down = Math.floor(ph) % 4 === 0;
    var level = t < 7.5 ? 0.34 : t < 30 ? 0.62 : t < 56 ? 0.8 : 0.42;
    return { level: level, beat: Math.exp(-frac * 6) * (down ? 1 : 0.6) * level };
  }

  /* ---------- 波形进度条（WaveformSlider） ---------- */
  function Wave(canvas) {
    this.c = canvas;
    this.ctx = canvas.getContext('2d');
    this.phase = 0;
    this.amp = 0;
    this.w = 0;
    this.drag = false;
    var self = this;
    function pos(e) {
      var r = canvas.getBoundingClientRect();
      return M.clamp((e.clientX - r.left) / r.width);
    }
    canvas.addEventListener('pointerdown', function (e) {
      self.drag = true;
      NP.capture(canvas, e);
      P.seek(pos(e) * P.dur);
    });
    canvas.addEventListener('pointermove', function (e) { if (self.drag) P.seek(pos(e) * P.dur); });
    function up() { self.drag = false; }
    canvas.addEventListener('pointerup', up);
    canvas.addEventListener('pointercancel', up);
  }
  Wave.prototype.resize = function (scale) {
    var w = this.c.clientWidth;
    var h = 48;
    var k = scale * Math.min(window.devicePixelRatio || 1, 2);
    this.w = w;
    this.k = k;
    this.c.width = Math.max(1, Math.round(w * k));
    this.c.height = Math.round(h * k);
  };
  Wave.prototype.draw = function (dt, progress) {
    var ctx = this.ctx;
    var w = this.w;
    if (!w) return;
    var animating = P.playing && !this.drag;
    // 振幅 500 ms 线性过渡；相位每 2 秒走完一圈
    var targetAmp = animating ? 6 * PX : 0;
    var step = (6 * PX) / 0.5 * dt;
    this.amp = this.amp < targetAmp ? Math.min(targetAmp, this.amp + step) : Math.max(targetAmp, this.amp - step);
    if (animating) this.phase = (this.phase + (Math.PI * 2 * dt) / 2) % (Math.PI * 2);
    var freq = 0.08 / PX;
    var cy = 24;
    var seg = M.clamp(Math.ceil(w / (6 * PX)), 48, 180);
    var sw = w / seg;
    var px = progress * w;
    ctx.setTransform(this.k, 0, 0, this.k, 0, 0);
    ctx.clearRect(0, 0, w, 48);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    var amp = this.amp;
    var phase = this.phase;
    function path() {
      ctx.beginPath();
      ctx.moveTo(0, cy + Math.sin(phase) * amp);
      for (var i = 1; i <= seg; i++) {
        var x = i * sw;
        ctx.lineTo(x, cy + Math.sin(x * freq + phase) * amp);
      }
    }
    path();
    ctx.strokeStyle = INACTIVE;
    ctx.lineWidth = 4 * PX;
    ctx.stroke();
    ctx.save();
    ctx.beginPath();
    ctx.rect(0, 0, px, 48);
    ctx.clip();
    path();
    ctx.strokeStyle = PRIMARY;
    ctx.lineWidth = 6 * PX;
    ctx.stroke();
    ctx.restore();
    ctx.beginPath();
    ctx.arc(px, cy + Math.sin(px * freq + phase) * amp, 16 * PX, 0, Math.PI * 2);
    ctx.fillStyle = PRIMARY;
    ctx.fill();
  };

  /* ---------- 一台手机 ---------- */
  function Phone(device) {
    this.device = device;
    this.ph = $('.ph', device);
    this.visible = false;
    this.waves = $$('[data-ph-wave]', this.ph).map(function (c) { return new Wave(c); });
    this.nows = $$('[data-ph-now]', this.ph);
    this.durs = $$('[data-ph-dur]', this.ph);
    this.toggles = $$('[data-ph-toggle]', this.ph);
    this.lastSec = -1;
    var clrc = $('[data-ph-clrc]', this.ph);
    var plrc = $('[data-ph-lrc]', this.ph);
    this.cover = clrc && NP.CoverLyrics ? new NP.CoverLyrics(clrc, P.seek) : null;
    this.page = plrc && NP.PageLyrics ? new NP.PageLyrics(plrc, P.seek) : null;
    var fluid = $('.ph__fluid', this.ph);
    this.fluid = fluid && NP.FluidView ? new NP.FluidView(fluid, PALETTE) : null;
    if (this.fluid && !this.fluid.ok) fluid.style.background = 'radial-gradient(70% 40% at 95% 45%, #7b719a, transparent), linear-gradient(170deg, #292641, #3c3459 55%, #3c3554)';
    this.bind();
    var self = this;
    NP.whenVisible(device, function () { self.visible = true; self.onShow(); }, function () { self.visible = false; self.onHide(); }, '80px 0px');
  }
  Phone.prototype.bind = function () {
    var ph = this.ph;
    var self = this;
    this.toggles.forEach(function (b) { b.addEventListener('click', P.toggle); });
    $$('[data-ph-shuffle]', ph).forEach(function (b) {
      b.addEventListener('click', function () { P.shuffle = !P.shuffle; emit(); });
    });
    $$('[data-ph-repeat]', ph).forEach(function (b) {
      b.addEventListener('click', function () { P.repeat = (P.repeat + 1) % 3; if (audio) audio.loop = false; emit(); });
    });
    var chip = $('[data-ph-trchip]', ph);
    if (chip) {
      chip.addEventListener('click', function () {
        var on = !chip.classList.contains('is-on');
        chip.classList.toggle('is-on', on);
        chip.setAttribute('aria-pressed', on ? 'true' : 'false');
        ph.classList.toggle('no-trans', !on);
        if (self.page) self.page.remeasureFor(520);
      });
    }
  };
  Phone.prototype.onShow = function () {
    if (this.fluid) this.fluid.visible = true;
    this.resize();
    if (this.cover) this.cover.dirty = true;
    if (this.page) this.page.dirty = true;
    this.snap = true;
    if (this.cover || this.page) P.startPreview();
  };
  Phone.prototype.onHide = function () {
    var any = phones.some(function (p) { return p.visible && (p.cover || p.page); });
    if (!any) P.stopPreview();
  };
  Phone.prototype.resize = function () {
    var s = parseFloat(getComputedStyle(this.device).getPropertyValue('--s')) || 0.74;
    this.waves.forEach(function (w) { w.resize(s); });
  };
  Phone.prototype.sync = function () {
    var ph = this.ph;
    this.toggles.forEach(function (b) {
      var u = b.querySelector('use');
      var href = P.playing ? '#i-pause' : '#i-play-o';
      if (u && u.getAttribute('href') !== href) {
        u.setAttribute('href', href);
        var svg = b.querySelector('svg');
        svg.classList.remove('ph-icon-swap');
        void svg.getBoundingClientRect();
        svg.classList.add('ph-icon-swap');
      }
      b.setAttribute('aria-label', P.playing ? NP.L('暂停', 'Pause') : NP.L('播放', 'Play'));
    });
    $$('[data-ph-shuffle]', ph).forEach(function (b) { b.classList.toggle('is-acc', P.shuffle); });
    $$('[data-ph-repeat]', ph).forEach(function (b) {
      b.classList.toggle('is-acc', !!P.repeat);
      var u = b.querySelector('use');
      var href = P.repeat === 2 ? '#i-repeat-one' : '#i-repeat';
      if (u && u.getAttribute('href') !== href) {
        u.setAttribute('href', href);
        var svg = b.querySelector('svg');
        svg.classList.remove('ph-icon-swap');
        void svg.getBoundingClientRect();
        svg.classList.add('ph-icon-swap');
      }
    });
    $$('[data-ph-like]', ph).forEach(function (b) {
      b.classList.toggle('is-liked', P.liked);
      var u = b.querySelector('use');
      if (u) u.setAttribute('href', P.liked ? '#i-heart' : '#i-heart-o');
    });
    var d = fmt(P.dur);
    this.durs.forEach(function (el) { el.textContent = d; });
    var info = $('.pc-info', ph);
    if (info) info.innerHTML = 'RAW<span>·</span>48 kHz' + (Math.abs(P.speed - 1) > 0.001 ? '<span>·</span>' + P.speed.toFixed(2) + 'x' : '');
  };
  Phone.prototype.frame = function (dt) {
    if (!this.visible) return;
    var t = P.t;
    var snap = this.snap || P.snap;
    this.snap = false;
    var sec = Math.floor(t);
    if (sec !== this.lastSec) {
      this.lastSec = sec;
      var txt = fmt(t);
      this.nows.forEach(function (el) { el.textContent = txt; });
    }
    var prog = M.clamp(t / P.dur);
    for (var i = 0; i < this.waves.length; i++) this.waves[i].draw(dt, prog);
    if (this.cover) this.cover.update(t, dt, snap);
    if (this.page) this.page.update(t, dt, snap);
    if (this.fluid) this.fluid.render(dt);
  };

  /* 《星轨》封面取色后送进着色器的五个色点（中 / 左 / 右 / 左上 / 右上），按真机截图反推校准 */
  var PALETTE = ['#534b75', '#231f44', '#9189ad', '#2a2742', '#5c5475'].map(NP.color.hexToRgb);

  /* ---------- 音效与倍速面板 ---------- */
  var PRESETS = [
    ['Flat', [0, 0, 0, 0, 0]], ['Acoustic', [3, 2, 1, 2, 3]], ['Bass Booster', [7, 5, 2, -1, -3]], ['Bass Reducer', [-7, -5, -2, 0, 1]],
    ['Classical', [4, 2, -1, 3, 5]], ['Club', [5, 3, 0, 3, 4]], ['Dance', [6, 4, 1, 4, 5]], ['Deep', [8, 5, 1, 0, 2]],
    ['Electronic', [5, 2, -1, 4, 6]], ['Folk', [1, 3, 0, 4, 5]], ['Hip Hop', [7, 4, 1, 2, 4]], ['Jazz', [3, 2, 1, 3, 4]],
    ['Latin', [4, 3, 1, 4, 5]], ['Lounge', [2, 1, 1, 3, 4]], ['Piano', [2, 1, 0, 3, 4]], ['Pop', [-1, 3, 5, 3, 0]],
    ['R&B', [4, 3, 2, 4, 3]], ['Rock', [6, 3, -1, 3, 6]], ['Small Speakers', [4, 1, -2, 4, 7]], ['Spoken Word', [-4, -2, 4, 5, 2]],
    ['Treble Booster', [-3, -1, 2, 5, 8]], ['Treble Reducer', [1, 0, -2, -5, -8]], ['Vocal Booster', [-2, 1, 5, 5, 1]],
  ];
  var BAND_LABELS = ['60Hz', '230Hz', '910Hz', '3.6kHz', '14kHz'];

  function dragSlider(el, onValue) {
    function at(e) {
      var r = el.getBoundingClientRect();
      return M.clamp((e.clientX - r.left) / r.width);
    }
    var down = false;
    el.addEventListener('pointerdown', function (e) { down = true; NP.capture(el, e); onValue(at(e)); });
    el.addEventListener('pointermove', function (e) { if (down) onValue(at(e)); });
    el.addEventListener('pointerup', function () { down = false; });
    el.addEventListener('pointercancel', function () { down = false; });
  }

  NP.sound.presets = PRESETS;
  NP.sound.bands = BAND_LABELS;
  NP.sound.dragSlider = dragSlider;

  /* ---------- 首页：种子色 / 明暗 / UI 缩放 ---------- */
  function initHome() {
    var ph = $('#phHome');
    var lab = $('#lab');
    if (!ph || !lab) return;
    var state = { seed: '#d2bcfd', mode: 'dark', ui: 1 };
    function hsl(h, s, l) { return 'hsl(' + h.toFixed(1) + ' ' + s + '% ' + l + '%)'; }
    function paint() {
      var h = NP.color.rgbToHsl(NP.color.hexToRgb(state.seed))[0];
      var dark = state.mode === 'dark';
      var v = dark
        ? { bg: hsl(h, 14, 8), on: hsl(h, 16, 90), onVar: hsl(h, 10, 80), primary: hsl(h, 86, 86), mini: 'hsl(' + h.toFixed(1) + ' 15% 30% / .5)', nav: 'hsl(' + h.toFixed(1) + ' 5% 23% / .86)' }
        : { bg: hsl(h, 60, 98), on: hsl(h, 14, 11), onVar: hsl(h, 8, 30), primary: hsl(h, 42, 40), mini: 'hsl(' + h.toFixed(1) + ' 45% 86% / .62)', nav: 'hsl(' + h.toFixed(1) + ' 24% 94% / .88)' };
      ph.style.setProperty('--h-bg', v.bg);
      ph.style.setProperty('--h-on', v.on);
      ph.style.setProperty('--h-on-var', v.onVar);
      ph.style.setProperty('--h-primary', v.primary);
      ph.style.setProperty('--h-mini', v.mini);
      ph.style.setProperty('--h-nav', v.nav);
      ph.style.setProperty('--ui', state.ui);
      ph.classList.toggle('is-light', !dark);
    }
    function radio(group, attr, key) {
      var btns = $$('[' + attr + ']', lab);
      btns.forEach(function (b) {
        b.addEventListener('click', function () {
          state[key] = b.getAttribute(attr);
          btns.forEach(function (o) {
            var on = o === b;
            o.classList.toggle('is-on', on);
            o.setAttribute('aria-checked', on ? 'true' : 'false');
          });
          paint();
        });
      });
    }
    radio(lab, 'data-seed', 'seed');
    radio(lab, 'data-mode', 'mode');
    var range = $('#labScale');
    var out = $('#labScaleOut');
    if (range) {
      var upd = function () {
        state.ui = parseInt(range.value, 10) / 100;
        range.style.setProperty('--v', ((range.value - range.min) / (range.max - range.min)) * 100 + '%');
        if (out) out.textContent = state.ui.toFixed(2) + '×';
        paint();
      };
      range.addEventListener('input', upd);
      upd();
    }
    paint();
  }

  /* ---------- 机身尺寸：按视口高度与栏宽缩放 ---------- */
  function sizeDevices(vp) {
    $$('.device').forEach(function (d) {
      var col = d.closest('.phone-col') || d.parentElement;
      var colW = col ? col.clientWidth : 360;
      var byH = (vp.h - (vp.w > 980 ? 150 : 120)) / 914;
      var byW = (colW - 18) / 411;
      var s = M.clamp(Math.min(byH, byW, 0.8), 0.46, 0.8);
      d.style.setProperty('--s', s.toFixed(4));
      var fig = d.closest('.phone-fig');
      if (fig) {
        fig.style.setProperty('--dev-w', (411 * s + 18).toFixed(1) + 'px');
        fig.style.setProperty('--dev-h', (914 * s + 18).toFixed(1) + 'px');
      }
    });
    phones.forEach(function (p) { p.resize(); });
  }

  /* ---------- 静音预览提示 ---------- */
  function initHints() {
    $$('#devicePlay, #deviceLyrics').forEach(function (dev) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'ph-hint hand';
      var L = NP.L;
      var muted = L('静音预览中 · 点这里或播放键出声', 'Muted preview · tap here or play for sound');
      b.innerHTML = '<svg class="i" aria-hidden="true"><use href="#i-headphones"/></svg><span>' + muted + '</span>';
      b.addEventListener('click', function () { if (P.playing && P.audible) P.pause(); else P.play(); });
      dev.insertAdjacentElement('afterend', b);
      P.on(function () {
        var txt = P.audible ? L('正在播放《星轨》 · 再点暂停', 'Now playing “星轨” · tap to pause') : P.playing ? muted : L('已暂停 · 点这里继续听', 'Paused · tap to keep listening');
        var span = b.querySelector('span');
        if (span.textContent !== txt) { if (NP.swapText) NP.swapText(span, txt); else span.textContent = txt; }
        b.classList.toggle('is-live', P.audible);
      });
    });
  }

  var phones = [];
  NP.phones = {
    init: function () {
      if (NP.phoneUI) NP.phoneUI.prepare();
      phones = $$('.device').map(function (d) { return new Phone(d); });
      NP.phones.list = phones;
      if (NP.phoneUI) NP.phoneUI.init(phones);
      initHome();
      initHints();
      P.on(function () { phones.forEach(function (p) { p.sync(); }); });
      NP.onResize(sizeDevices);
      sizeDevices(NP.vp);
      NP.addFrame(function (dt, t) {
        NP.stepMusic(dt, t, P.playing ? songEnergy(P.t) : null, P.playing);
        for (var i = 0; i < phones.length; i++) phones[i].frame(dt);
        P.snap = false;
      }, 5);
      if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(function () { phones.forEach(function (p) { if (p.cover) p.cover.dirty = true; if (p.page) p.page.dirty = true; }); });
      }
    },
  };
})();
