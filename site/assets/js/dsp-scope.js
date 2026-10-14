/* NeriPlayer site · dsp-scope —— 效果监视器：处理前后频谱、声场、输出电平与单核占用。
   播放出声时读 Web Audio 的真实分析节点；没出声时用一段“像音乐”的模型频谱，乘上和 App 同一套滤波器的频响，
   所以随手拧一下也能马上看到变化。 */
(function () {
  'use strict';

  var NP = window.NP;
  var M = NP.math;
  var $ = NP.$;
  var $$ = NP.$$;
  var L = NP.L;
  var P = NP.player;
  var reduced = NP.env.reduced;

  var BINS = 120;
  var F_LO = 20;
  var F_HI = 20000;
  var FREQ = [];
  for (var i = 0; i < BINS; i++) FREQ.push(F_LO * Math.pow(F_HI / F_LO, i / (BINS - 1)));
  var LOG2 = Math.log(2);
  function oct(f, ref) { return Math.log(f / ref) / LOG2; }

  /* ---------- 运行状态：哪些环节在工作、单核占用、防破音 ---------- */
  /* 每个环节在 48 kHz 立体声下大致占一颗大核的百分比（均衡模式）；省电 / 高音质按 App 的说明缩放 */
  var COST = { base: 0.035, pre: 0.004, eq: 0.055, peqBand: 0.009, tone: 0.018, bass: 0.07, color: 0.05, stereo: 0.016, space: 0.06, reverb: 0.36, spk: 0.14, dyn: 0.05, lim: 0.028 };
  var MODE = { eco: { all: 0.74, reverb: 0.52, dyn: 0.7, dither: 0 }, balanced: { all: 1, reverb: 1, dyn: 1, dither: 0 }, high: { all: 1.28, reverb: 1.18, dyn: 1.2, dither: 0.024 } };

  function stages(S, D) {
    var on = function (sec) { return D.secActive(sec); };
    var neu = D.neutral();
    var live = S.on && !neu;
    var peqLive = S.peqOn && S.peq.some(function (b) { return b.on !== false && (!(b.type === 'peak' || b.type === 'low_shelf' || b.type === 'high_shelf') || Math.abs(b.g) > 1e-4); });
    var st = {
      pre: on('equalizer') && !!S.preamp,
      eq: on('equalizer') && (S.eq.some(function (v) { return v !== 0; }) || peqLive),
      tone: on('tone') && !!(S.bass || S.treble),
      bass: on('tone') && !!S.virtualBass,
      color: on('tone') && !!(S.warmth || S.clarity || S.vocal),
      stereo: (on('space') && (Math.abs(S.width - 1) > 1e-3 || S.mono || S.swap || !!S.monoBass || !!S.crossfeed)) || (on('tone') && !!S.vocalRemoval),
      space: on('space') && !!S.surround,
      reverb: on('space') && !!S.reverb,
      spk: S.on && !!S.sec.speaker,
      dyn: on('dynamics') && !!S.compOn,
      lim: true,
    };
    Object.keys(st).forEach(function (k) { st[k] = live && !!st[k]; });
    st.peqBands = live && on('equalizer') && peqLive ? S.peq.filter(function (b) { return b.on !== false; }).length : 0;
    return st;
  }
  function loadOf(st, mode) {
    var m = MODE[mode] || MODE.balanced;
    var sum = 0;
    if (!st.lim) return 0;
    sum += COST.base + COST.lim;
    ['pre', 'eq', 'tone', 'bass', 'color', 'stereo', 'space', 'spk'].forEach(function (k) { if (st[k]) sum += COST[k]; });
    sum += st.peqBands * COST.peqBand;
    sum *= m.all;
    if (st.reverb) sum += COST.reverb * m.reverb;
    if (st.dyn) sum += COST.dyn * m.dyn;
    return sum + m.dither;
  }
  function fmtLoad(p) { return p < 0.1 ? '<0.1%' : p.toFixed(1) + '%'; }
  function fmtRate(hz) { var k = hz / 1000; return Math.abs(k - Math.round(k)) < 0.05 ? Math.round(k) + ' kHz' : k.toFixed(1) + ' kHz'; }

  /* ---------- 模型：一段像音乐的频谱（低频鼓点、中频人声、高频逐渐滚降） ---------- */
  var BEAT = 60 / 128;
  function musicDb(f, t, e) {
    var o = oct(f, 80);
    var base = -26 + 7 * Math.exp(-o * o / 1.6) - 3.3 * Math.max(0, oct(f, 320)) - 14 * Math.max(0, oct(f, 15000)) - 9 * Math.max(0, oct(40, f));
    var lf = Math.log(f);
    var wob = 2.6 * Math.sin(t * 1.3 + lf * 2.1) * Math.sin(t * 0.47 + lf * 0.83) + 1.4 * Math.sin(t * 3.1 + lf * 5.3);
    var ph = (t / BEAT) % 4;
    var kick = Math.exp(-(ph % 1) * 7) * 8 * Math.exp(-Math.pow(oct(f, 62), 2) / 0.5);
    var snare = (Math.floor(ph) % 2 === 1 ? Math.exp(-(ph % 1) * 9) : 0) * 5 * Math.exp(-Math.pow(oct(f, 2400), 2) / 2.2);
    var vox = 3 * Math.exp(-Math.pow(oct(f, 900), 2) / 1.4) * (0.6 + 0.4 * Math.sin(t * 0.9));
    return base + (wob + kick + snare + vox) * e - (1 - e) * 10;
  }

  /* 理论频响：均衡 / 参数均衡 / 音调 + 虚拟低音、润色、扬声器与输出增益，滤波器参数与手机音频链一致 */
  function curve(S, D) {
    var bq = D.biquad;
    var on = function (sec) { return D.secActive(sec); };
    var fs = [];
    var flat = 0;
    if (on('equalizer')) {
      S.eq.forEach(function (g, i) { if (g) fs.push(bq('peak', D.freqs[i], g, 1.41)); });
      if (S.peqOn) S.peq.forEach(function (b) { if (b.on !== false) fs.push(bq(b.type, b.f, b.g, b.q)); });
      flat += S.preamp;
    }
    if (on('tone')) {
      if (S.bass) fs.push(bq('low_shelf', S.bassFreq, S.bass, 0.707));
      if (S.treble) fs.push(bq('high_shelf', S.trebleFreq, S.treble, 0.707));
      if (S.virtualBass) fs.push(bq('low_shelf', S.vbFreq * 1.4, S.virtualBass * 6, 0.707));
      if (S.warmth) { fs.push(bq('low_shelf', 240, S.warmth * 3, 0.707)); fs.push(bq('high_shelf', 5200, -S.warmth * 4, 0.707)); }
      if (S.clarity) fs.push(bq('high_shelf', Math.min(20000, S.clarityFreq * 2), S.clarity * 5, 0.707));
      if (S.vocal) fs.push(bq('peak', 2400, S.vocal * 5, 0.9));
    }
    var lo = D.local;
    if (S.on && S.sec.speaker) {
      var fc = lo.spkProt > 1 ? lo.spkProt : 180;
      fs.push(bq('high_pass', fc, 0, 0.707));
      fs.push(bq('peak', fc * 1.6, lo.spkBass * 6, 1));
      fs.push(bq('high_shelf', 4000, lo.spkClarity * 3, 0.707));
      flat += lo.spkLoud * 4;
    }
    if (on('dynamics')) flat += S.outGain;
    return FREQ.map(function (hz) {
      var g = flat;
      for (var j = 0; j < fs.length; j++) g += D.mag(fs[j], hz);
      return Math.max(-60, g);
    });
  }
  function pitchFactor(S) {
    if (!S.sec.speed) return 1;
    return S.linked ? P.speed : P.pitch;
  }

  /* ---------- 监视器 ---------- */
  function Scope(fig, page) {
    var self = this;
    this.fig = fig;
    this.page = page;
    this.S = NP.sound.state;
    this.D = NP.dspState;
    this.spec = $('[data-scope-spec]', fig);
    this.field = $('[data-scope-field]', fig);
    this.srcEl = $('[data-scope-src]', fig);
    this.readEl = $('[data-scope-read]', fig);
    this.widthEl = $('[data-scope-width]', fig);
    this.grEl = $('[data-scope-gr]', fig);
    this.lvl = { l: $('[data-scope-lvl="l"] i', fig), r: $('[data-scope-lvl="r"] i', fig) };
    this.ceilEl = $('[data-scope-ceil]', fig);
    this.nodes = $$('[data-node]', fig);
    this.stateEl = $('[data-dsp-state]', fig);
    this.dry = new Float32Array(BINS).fill(-90);
    this.wet = new Float32Array(BINS).fill(-90);
    this.curveTarget = new Float32Array(BINS);
    this.curveShown = new Float32Array(BINS);
    this.meter = { l: -60, r: -60, gr: 0 };
    this.t = 0;
    this.energy = 0;
    this.hover = -1;
    this.visible = false;
    this.rt = { load: '<0.1%', rate: null, limDb: 0, limUntil: 0, loadV: 0, jitter: 1 };
    NP.dspRuntime = this.rt;
    this.colors();
    this.resize();
    if (window.ResizeObserver) new ResizeObserver(function () { self.resize(); }).observe(fig);
    else NP.onResize(function () { self.resize(); });
    new MutationObserver(function () { self.colors(); }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    NP.whenVisible(fig, function () { self.visible = true; }, function () { self.visible = false; }, '80px 0px');
    NP.addFrame(function (dt) { self.frame(dt); }, 9);
    this.bindHover();
    this.bindAB();
    NP.dspChain = function () { self.chain(); self.recurve(); };
    this.recurve(true);
    this.chain();
    this.sample();
    setInterval(function () { if (!document.hidden) self.sample(); }, 1000);
    P.on(function () { self.recurve(); self.chain(); });
  }

  Scope.prototype.colors = function () {
    var cs = getComputedStyle(this.fig);
    var v = function (n, d) { return (cs.getPropertyValue(n) || '').trim() || d; };
    this.c = { ink: v('--ink', '#1f1b16'), ink3: v('--ink-3', '#8a8175'), rule: v('--rule', 'rgba(0,0,0,.1)'), acc: v('--acc', '#d4537e'), card: v('--card', '#fff'), paper: v('--paper', '#faf7f2') };
  };
  Scope.prototype.resize = function () {
    var dpr = Math.min(2, window.devicePixelRatio || 1);
    var changed = false;
    [this.spec, this.field].forEach(function (cv) {
      if (!cv) return;
      var w = Math.max(1, Math.round(cv.clientWidth * dpr));
      var h = Math.max(1, Math.round(cv.clientHeight * dpr));
      if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; changed = true; }
    });
    this.dpr = dpr;
    if (!changed) return;
    this.fieldClear = true;
    // 改尺寸会清空画布，马上补画一帧，避免闪一下空白
    if (this.c && this.curveShown) { this.drawSpec(); this.drawField(0, null, !this.S.on); }
  };

  /* 每秒刷新一次，和 App 读取引擎统计的节奏一样 */
  Scope.prototype.sample = function () {
    var S = this.S;
    var D = this.D;
    var rt = this.rt;
    var st = stages(S, D);
    this.st = st;
    var flowing = P.playing && P.audible;
    var taps = NP.sound.taps();
    rt.rate = flowing && st.lim ? fmtRate(taps ? taps.ctx.sampleRate : 48000) : null;
    rt.jitter = M.lerp(rt.jitter, 0.86 + Math.random() * 0.3, 0.6);
    rt.loadV = flowing ? loadOf(st, D.local.mode) * rt.jitter : 0;
    rt.load = fmtLoad(rt.loadV);
    var now = performance.now();
    if (rt.peakDb > 0.5 && st.lim) { rt.limDb = Math.round(rt.peakDb * 10) / 10; rt.limUntil = now + 4000; }
    else if (now > rt.limUntil) rt.limDb = 0;
    rt.peakDb = 0;
    if (this.page) this.page.paint();
  };

  Scope.prototype.recurve = function (snap) {
    var c = curve(this.S, this.D);
    for (var i = 0; i < BINS; i++) { this.curveTarget[i] = c[i]; if (snap) this.curveShown[i] = c[i]; }
    this.st = stages(this.S, this.D);
  };

  Scope.prototype.chain = function () {
    var S = this.S;
    var st = this.st || stages(S, this.D);
    var neu = this.D.neutral();
    this.nodes.forEach(function (n) { n.classList.toggle('is-on', !!st[n.getAttribute('data-node')]); });
    this.fig.classList.toggle('is-bypass', !S.on || neu);
    var t = !S.on ? L('总开关关闭：整条链路旁路，系统可以用硬件低功耗播放', 'Master switch off: the whole chain is bypassed for low-power hardware playback')
      : neu ? L('全部为中性：自动跳过处理，不额外耗电', 'Everything is neutral: processing is skipped, no extra power')
      : L('正在处理 · 亮着的环节正在生效', 'Processing · lit stages are active');
    if (this.stateEl && this.stateEl.textContent !== t) NP.swapText(this.stateEl, t);
    var live = !!NP.sound.taps();
    var src = live ? L('实时分析 · 处理前后各取一路', 'Live analysis · tapped before and after the chain') : L('模型频谱 · 按播放后换成实时分析', 'Modeled spectrum · switches to live analysis once you press play');
    if (this.srcEl && this.srcEl.textContent !== src) NP.swapText(this.srcEl, src);
  };

  /* 频谱：真实分析节点按对数频段取平均功率；没有就用模型 */
  var buf = null;
  function bands(an, out) {
    var n = an.frequencyBinCount;
    if (!buf || buf.length !== n) buf = new Float32Array(n);
    an.getFloatFrequencyData(buf);
    var nyq = an.context.sampleRate / 2;
    for (var b = 0; b < BINS; b++) {
      var f0 = b ? Math.sqrt(FREQ[b - 1] * FREQ[b]) : F_LO;
      var f1 = b < BINS - 1 ? Math.sqrt(FREQ[b] * FREQ[b + 1]) : F_HI;
      var i0 = Math.max(1, Math.floor(f0 / nyq * n));
      var i1 = Math.min(n - 1, Math.max(i0, Math.ceil(f1 / nyq * n)));
      if (i1 - i0 < 2) {
        // 低频段比一个 FFT 格还窄：按中心频率在相邻两格之间插值，免得画成台阶
        var x = Math.max(1, FREQ[b] / nyq * n);
        var xi = Math.min(n - 2, Math.floor(x));
        out[b] = buf[xi] + (buf[xi + 1] - buf[xi]) * (x - xi);
        continue;
      }
      var p = 0;
      for (var k = i0; k <= i1; k++) p += Math.pow(10, buf[k] / 10);
      out[b] = 10 * Math.log10(p / (i1 - i0 + 1) + 1e-12);
    }
  }
  var tmpDry = new Float32Array(BINS);
  var tmpWet = new Float32Array(BINS);

  Scope.prototype.frame = function (dt) {
    if (!this.visible) return;
    var S = this.S;
    var D = this.D;
    var k = 1 - Math.exp(-dt * 10);
    for (var c = 0; c < BINS; c++) this.curveShown[c] += (this.curveTarget[c] - this.curveShown[c]) * k;
    var taps = NP.sound.taps();
    var playing = P.playing;
    this.energy += ((playing ? 1 : 0.35) - this.energy) * (1 - Math.exp(-dt * 2.5));
    this.t = playing ? P.t : this.t + dt * 0.35;
    var bypass = !S.on || D.neutral();
    if (taps) {
      bands(taps.pre, tmpDry);
      bands(taps.post, tmpWet);
    } else {
      var pf = pitchFactor(S);
      var comp = D.secActive('dynamics') && S.compOn ? NP.sound.compAmount() : 0;
      for (var b = 0; b < BINS; b++) {
        var dry = musicDb(FREQ[b], this.t, this.energy);
        tmpDry[b] = dry;
        var src = pf === 1 ? dry : musicDb(FREQ[b] / pf, this.t, this.energy);
        var wet = src + (bypass ? 0 : this.curveShown[b]);
        if (comp && !bypass) wet = wet + (-24 - wet) * 0.28 * comp + comp * 3;
        tmpWet[b] = wet;
      }
    }
    var up = 1 - Math.exp(-dt * 24);
    var down = 1 - Math.exp(-dt * 7);
    for (var j = 0; j < BINS; j++) {
      var a = tmpDry[j];
      var w = tmpWet[j];
      this.dry[j] += (a - this.dry[j]) * (a > this.dry[j] ? up : down);
      this.wet[j] += (w - this.wet[j]) * (w > this.wet[j] ? up : down);
    }
    this.drawSpec();
    this.drawField(dt, taps, bypass);
    this.drawMeter(dt, taps, bypass);
  };

  Scope.prototype.drawSpec = function () {
    var cv = this.spec;
    var g = cv.getContext('2d');
    var W = cv.width;
    var H = cv.height;
    var d = this.dpr;
    var c = this.c;
    var padL = 34 * d;
    var padR = 34 * d;
    var padT = 42 * d;
    var padB = 22 * d;
    var pw = W - padL - padR;
    var ph = H - padT - padB;
    var X = function (i) { return padL + i / (BINS - 1) * pw; };
    var Y = function (db) { return padT + (1 - M.clamp((db + 84) / 78)) * ph; };
    var CY = function (db) { return padT + ph / 2 - M.clamp(db, -18, 18) / 18 * ph * 0.46; };
    g.clearRect(0, 0, W, H);
    g.font = 500 * 1 + ' ' + 10 * d + 'px "Geist Mono", ui-monospace, monospace';
    g.textBaseline = 'middle';
    // 网格
    g.lineWidth = 1 * d;
    g.strokeStyle = c.rule;
    g.fillStyle = c.ink3;
    g.textAlign = 'center';
    [50, 100, 200, 500, 1000, 2000, 5000, 10000].forEach(function (f) {
      var x = padL + Math.log(f / F_LO) / Math.log(F_HI / F_LO) * pw;
      g.beginPath(); g.moveTo(x, padT); g.lineTo(x, padT + ph); g.stroke();
      g.fillText(f >= 1000 ? f / 1000 + 'k' : String(f), x, H - padB / 2);
    });
    g.textAlign = 'right';
    [0, -24, -48, -72].forEach(function (db) { var y = Y(db); g.fillText(db + '', padL - 6 * d, y); });
    g.textAlign = 'left';
    [12, 0, -12].forEach(function (db) { g.fillText((db > 0 ? '+' : '') + db, W - padR + 6 * d, CY(db)); });
    // 原声：浅色填充
    var self = this;
    var area = function (arr, fill, stroke, lw) {
      g.beginPath();
      g.moveTo(X(0), padT + ph);
      for (var i = 0; i < BINS; i++) g.lineTo(X(i), Y(arr[i]));
      g.lineTo(X(BINS - 1), padT + ph);
      g.closePath();
      if (fill) { g.fillStyle = fill; g.fill(); }
      if (stroke) {
        g.beginPath();
        for (var k = 0; k < BINS; k++) { if (k) g.lineTo(X(k), Y(arr[k])); else g.moveTo(X(k), Y(arr[k])); }
        g.strokeStyle = stroke;
        g.lineWidth = lw * d;
        g.stroke();
      }
    };
    g.globalAlpha = 1;
    area(this.dry, mix(c.ink, 0.07), mix(c.ink, 0.32), 1.2);
    var grad = g.createLinearGradient(0, padT, 0, padT + ph);
    grad.addColorStop(0, mix(c.acc, 0.36));
    grad.addColorStop(1, mix(c.acc, 0.04));
    area(this.wet, grad, c.acc, 1.8);
    // 理论频响：虚线 + 零线
    g.setLineDash([2 * d, 3 * d]);
    g.strokeStyle = mix(c.ink, 0.28);
    g.lineWidth = 1 * d;
    g.beginPath(); g.moveTo(padL, CY(0)); g.lineTo(padL + pw, CY(0)); g.stroke();
    g.setLineDash([6 * d, 4 * d]);
    g.strokeStyle = c.ink;
    g.lineWidth = 1.6 * d;
    g.beginPath();
    for (var i = 0; i < BINS; i++) { var y = CY(this.curveShown[i]); if (i) g.lineTo(X(i), y); else g.moveTo(X(i), y); }
    g.stroke();
    g.setLineDash([]);
    // 指针读数
    if (this.hover >= 0) {
      var hx = X(this.hover);
      g.strokeStyle = mix(c.ink, 0.5);
      g.lineWidth = 1 * d;
      g.beginPath(); g.moveTo(hx, padT); g.lineTo(hx, padT + ph); g.stroke();
      g.fillStyle = c.ink;
      g.beginPath(); g.arc(hx, CY(this.curveShown[this.hover]), 3.5 * d, 0, Math.PI * 2); g.fill();
    }
    this.read();
    void self;
  };
  function mix(col, a) {
    var m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(col);
    if (!m) return col;
    var h = m[1].length === 3 ? m[1].replace(/./g, '$&$&') : m[1];
    return 'rgba(' + parseInt(h.slice(0, 2), 16) + ',' + parseInt(h.slice(2, 4), 16) + ',' + parseInt(h.slice(4, 6), 16) + ',' + a + ')';
  }
  Scope.prototype.read = function () {
    var D = this.D;
    var t;
    var cs = this.curveShown;
    var at = function (hz) { var i = Math.round(Math.log(hz / F_LO) / Math.log(F_HI / F_LO) * (BINS - 1)); return cs[M.clamp(i, 0, BINS - 1)]; };
    if (this.hover >= 0) t = D.fHz(FREQ[this.hover]) + ' · ' + D.fDb(cs[this.hover]);
    else t = L('低频 ', 'Lows ') + D.fDb(at(60)) + L(' · 中频 ', ' · Mids ') + D.fDb(at(1000)) + L(' · 高频 ', ' · Highs ') + D.fDb(at(10000));
    if (t !== this._read) { this._read = t; this.readEl.textContent = t; }
  };
  Scope.prototype.bindHover = function () {
    var self = this;
    var cv = this.spec;
    var at = function (e) {
      var r = cv.getBoundingClientRect();
      var x = (e.clientX - r.left - 34) / Math.max(1, r.width - 68);
      return x < 0 || x > 1 ? -1 : Math.round(x * (BINS - 1));
    };
    cv.addEventListener('pointermove', function (e) { self.hover = at(e); });
    cv.addEventListener('pointerdown', function (e) { self.hover = at(e); });
    cv.addEventListener('pointerleave', function () { self.hover = -1; });
  };

  /* 声场：处理后的左右声道画成 M/S 点云——竖线是单声道，越宽越开阔，左右互换会镜像 */
  var fl = null;
  var fr = null;
  Scope.prototype.drawField = function (dt, taps, bypass) {
    var cv = this.field;
    if (!cv) return;
    var g = cv.getContext('2d');
    var W = cv.width;
    var H = cv.height;
    var c = this.c;
    var S = this.S;
    var D = this.D;
    var cx = W / 2;
    var cy = H / 2;
    var R = Math.min(W, H) * 0.44;
    g.globalCompositeOperation = 'destination-out';
    g.fillStyle = 'rgba(0,0,0,' + (this.fieldClear ? 1 : M.clamp(dt * 9, 0.08, 0.6)) + ')';
    g.fillRect(0, 0, W, H);
    g.globalCompositeOperation = 'source-over';
    this.fieldClear = false;
    g.strokeStyle = c.rule;
    g.lineWidth = this.dpr;
    g.beginPath(); g.moveTo(cx, cy - R); g.lineTo(cx, cy + R); g.moveTo(cx - R, cy); g.lineTo(cx + R, cy); g.stroke();
    g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.stroke();
    g.fillStyle = c.ink3;
    g.font = 9 * this.dpr + 'px "Geist Mono", ui-monospace, monospace';
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText('L', cx - R * 0.74, cy - R * 0.74);
    g.fillText('R', cx + R * 0.74, cy - R * 0.74);
    var sp = D.secActive('space') && !bypass;
    var mono = sp && S.mono;
    var width = mono ? 0 : sp ? S.width * (1 + S.surround * 0.5) * (1 - S.crossfeed * 0.35) : 1;
    var swap = sp && S.swap;
    var rev = sp ? S.reverb : 0;
    var label = mono ? L('单声道', 'Mono') : Math.round(width * 100) + '%' + (swap ? L(' · 左右互换', ' · swapped') : '');
    if (label !== this._wl) { this._wl = label; this.widthEl.textContent = label; }
    g.fillStyle = mix(c.acc, 0.55);
    var n = 0;
    if (taps) {
      var len = taps.l.fftSize;
      if (!fl || fl.length !== len) { fl = new Float32Array(len); fr = new Float32Array(len); }
      taps.l.getFloatTimeDomainData(fl);
      taps.r.getFloatTimeDomainData(fr);
      for (var i = 0; i < len; i += 2) {
        var m = (fl[i] + fr[i]) * 0.7071;
        var s = (fr[i] - fl[i]) * 0.7071;
        g.fillRect(cx + s * R * 1.6 - this.dpr, cy - m * R * 1.6 - this.dpr, 2 * this.dpr, 2 * this.dpr);
        n++;
      }
    } else {
      var amp = 0.35 + 0.45 * this.energy;
      var t = this.t;
      for (var j = 0; j < 160; j++) {
        var ph = t * 6 + j * 0.37;
        var mid = (Math.sin(ph * 1.3) * 0.6 + Math.sin(ph * 3.7 + 1) * 0.3) * amp;
        var side = (Math.sin(ph * 2.3 + j) * 0.5 + (Math.random() - 0.5) * 0.5) * amp * 0.42 * width;
        var diff = rev ? (Math.random() - 0.5) * rev * 0.9 * amp : 0;
        var dm = rev ? (Math.random() - 0.5) * rev * 0.9 * amp : 0;
        var x = (side + diff) * (swap ? -1 : 1);
        g.fillRect(cx + x * R - this.dpr, cy - (mid + dm) * R - this.dpr, 2 * this.dpr, 2 * this.dpr);
      }
    }
    void n;
  };

  /* 输出电平：左右峰值、防破音上限与压缩量 */
  Scope.prototype.drawMeter = function (dt, taps, bypass) {
    var S = this.S;
    var D = this.D;
    var mt = this.meter;
    var rt = this.rt;
    var l;
    var r;
    var gr = 0;
    if (taps) {
      var pk = function (an) {
        var len = an.fftSize;
        if (!fl || fl.length !== len) { fl = new Float32Array(len); fr = new Float32Array(len); }
        an.getFloatTimeDomainData(fl);
        var p = 0;
        for (var i = 0; i < len; i++) p = Math.max(p, Math.abs(fl[i]));
        return 20 * Math.log10(p + 1e-6);
      };
      l = pk(taps.l);
      r = pk(taps.r);
      gr = taps.comp ? taps.comp.reduction : 0;
      var lim = NP.sound.limiterReduction ? NP.sound.limiterReduction() : 0;
      rt.peakDb = Math.max(rt.peakDb || 0, -lim);
    } else {
      var boost = 0;
      for (var b = 0; b < BINS; b += 6) boost = Math.max(boost, this.curveShown[b]);
      var e = this.energy;
      var ph = (this.t / BEAT) % 1;
      var hit = Math.exp(-ph * 6);
      var base = -13 + (bypass ? 0 : boost * 0.8) + hit * 6 * e - (1 - e) * 16;
      var comp = !bypass && D.secActive('dynamics') && S.compOn;
      if (comp) {
        var over = Math.max(0, base - S.compThr);
        gr = -over * (1 - 1 / S.compRatio);
        base += gr + Math.max(0, S.compMakeup - 0.5 * -S.compThr * (1 - 1 / S.compRatio));
      }
      if (!bypass && S.limiter && base > S.ceiling) { rt.peakDb = Math.max(rt.peakDb || 0, base - S.ceiling); base = S.ceiling; }
      l = base + Math.sin(this.t * 7.3) * 1.2;
      r = base + Math.sin(this.t * 6.1 + 1) * 1.2;
      if (!P.playing) { l -= 6; r -= 6; }
    }
    var up = 1 - Math.exp(-dt * 30);
    var down = 1 - Math.exp(-dt * 4);
    mt.l += (l - mt.l) * (l > mt.l ? up : down);
    mt.r += (r - mt.r) * (r > mt.r ? up : down);
    mt.gr += (gr - mt.gr) * (1 - Math.exp(-dt * 8));
    var pos = function (db) { return M.clamp((db + 48) / 48); };
    this.lvl.l.style.transform = 'scaleY(' + pos(mt.l).toFixed(4) + ')';
    this.lvl.r.style.transform = 'scaleY(' + pos(mt.r).toFixed(4) + ')';
    var ceilOn = S.on && S.limiter && !bypass;
    this.ceilEl.style.bottom = (pos(S.ceiling) * 100).toFixed(2) + '%';
    this.ceilEl.classList.toggle('is-on', ceilOn);
    var gt = 'GR ' + (Math.abs(mt.gr) < 0.05 ? '0.0' : mt.gr.toFixed(1)) + ' dB';
    if (gt !== this._gr) { this._gr = gt; this.grEl.textContent = gt; }
  };

  /* A/B：按住时临时关掉总开关听原声，松手恢复 */
  Scope.prototype.bindAB = function () {
    var self = this;
    var b = $('[data-scope-ab]', this.fig);
    if (!b) return;
    var held = false;
    var was = true;
    var label = $('span', b);
    var idle = label.textContent;
    var down = function (e) {
      if (held) return;
      if (e && e.pointerId != null) NP.capture(b, e);
      held = true;
      was = self.S.on;
      if (!P.audible) P.play();
      self.S.on = false;
      b.classList.add('is-held');
      b.setAttribute('aria-pressed', 'true');
      NP.swapText(label, L('正在听原声', 'Hearing the original'));
      self.changed();
    };
    var up = function () {
      if (!held) return;
      held = false;
      self.S.on = was;
      b.classList.remove('is-held');
      b.setAttribute('aria-pressed', 'false');
      NP.swapText(label, idle);
      self.changed();
    };
    b.addEventListener('pointerdown', down);
    b.addEventListener('pointerup', up);
    b.addEventListener('pointercancel', up);
    b.addEventListener('lostpointercapture', up);
    b.addEventListener('keydown', function (e) { if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) { e.preventDefault(); down(); } });
    b.addEventListener('keyup', function (e) { if (e.key === ' ' || e.key === 'Enter') up(); });
    b.addEventListener('blur', up);
  };
  Scope.prototype.changed = function () {
    NP.sound.apply();
    this.recurve();
    this.chain();
    if (this.page) this.page.paint();
  };

  NP.dspScope = {
    init: function (page) {
      var fig = $('#dspChain');
      if (!fig || !fig.querySelector('[data-scope-spec]')) return;
      NP.dspScope.scope = new Scope(fig, page);
      if (reduced) NP.dspScope.scope.energy = 0.35;
    },
  };
})();
