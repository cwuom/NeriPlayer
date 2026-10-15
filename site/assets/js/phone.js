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

  /* 信号链与 App 的原生 DSP 同序：音源 → 移调 → 前级 → 十段均衡 → 低音 / 高音 / 润色 → 声场宽度 → 混响 → 压缩 → 防破音 → 音量 */
  var EQ_FREQS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
  var fx = null;
  /* 混响：逐行照搬原生 ReverbStage（neri_dsp_space_stages.cpp）——
     (L+R)·0.015 → 160 Hz 一阶高通 → 预延迟 → 8 路带阻尼的梳状滤波并联 → 4 路全通串联，左右声道调谐差 23 个样本。
     它是线性时不变的，所以把同一套算法跑一遍冲激得到脉冲响应，交给 ConvolverNode，结果与原生逐样本处理一致。 */
  var COMB = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
  var ALLPASS = [556, 441, 341, 225];
  var SPREAD = 23;
  function reverbIR(ctx, room, damping, eco) {
    var sr = ctx.sampleRate;
    var scaled = function (n) { return Math.max(1, Math.round(n * sr / 44100)); };
    var nc = eco ? 4 : 8;
    var na = eco ? 2 : 4;
    var feedback = 0.7 + 0.28 * room;
    var damp = damping * 0.4;
    var hp = 1 - Math.exp(-2 * Math.PI * 160 / sr);
    // 衰减到 -70 dB 大约要绕最长的梳状环多少圈；上限 8 秒，末尾 0.3 秒淡出
    var loops = Math.log(Math.pow(10, -70 / 20)) / Math.log(Math.max(0.5, feedback * (1 - damp * 0.5)));
    var len = Math.min(Math.round(sr * 8), Math.round(loops * scaled(COMB[7] + SPREAD) + sr * 0.1));
    var buf = ctx.createBuffer(2, len, sr);
    // 输入：节点前面先把立体声混成 (L+R)/2，这里乘 0.03 就等于原生的 (L+R)·0.015；省电模式湿声 ×1.41
    var inGain = 0.03 * (eco ? 1.41 : 1);
    for (var ch = 0; ch < 2; ch++) {
      var off = ch ? SPREAD : 0;
      var combs = [];
      var aps = [];
      for (var c = 0; c < nc; c++) combs.push({ b: new Float32Array(scaled(COMB[c] + off)), i: 0, f: 0 });
      for (var a = 0; a < na; a++) aps.push({ b: new Float32Array(scaled(ALLPASS[a] + off)), i: 0 });
      var out = buf.getChannelData(ch);
      var low = 0;
      for (var n = 0; n < len; n++) {
        var x = n === 0 ? inGain : 0;
        low += hp * (x - low);
        var input = x - low;
        var y = 0;
        for (var k = 0; k < nc; k++) {
          var cb = combs[k];
          var d = cb.b[cb.i];
          cb.f = d * (1 - damp) + cb.f * damp;
          cb.b[cb.i] = input + cb.f * feedback;
          if (++cb.i === cb.b.length) cb.i = 0;
          y += d;
        }
        for (var m = 0; m < na; m++) {
          var ap = aps[m];
          var dd = ap.b[ap.i];
          ap.b[ap.i] = y + dd * 0.5;
          if (++ap.i === ap.b.length) ap.i = 0;
          y = dd - y;
        }
        out[n] = y;
      }
      var fade = Math.min(len, Math.round(sr * 0.3));
      for (var t = 0; t < fade; t++) out[len - 1 - t] *= t / fade;
    }
    return buf;
  }
  /* 从磁盘直接打开时，本地 mp3 对 Web Audio 是跨源的（只会输出静音）：
     第一次播放前改用包成 .js 的同一首歌，转成同源的 blob: 地址再接进音频链 */
  var IS_FILE = location.protocol === 'file:';
  var local = IS_FILE ? 'idle' : 'ready';
  var localWait = [];
  function prepareLocalAudio(then) {
    if (local === 'ready' || local === 'failed') { then(); return; }
    localWait.push(then);
    if (local === 'loading') return;
    local = 'loading';
    var done = function (ok) {
      local = ok ? 'ready' : 'failed';
      var q = localWait;
      localWait = [];
      q.forEach(function (fn) { fn(); });
    };
    var s = document.createElement('script');
    s.src = (audio.getAttribute('src') || '').replace(/\.mp3(\?.*)?$/, '.js');
    s.onload = function () {
      try {
        var b64 = window.NP_AUDIO_B64;
        var bin = atob(b64);
        var bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        audio.src = URL.createObjectURL(new Blob([bytes], { type: 'audio/mpeg' }));
        window.NP_AUDIO_B64 = null;
        done(true);
      } catch (e) { done(false); }
    };
    s.onerror = function () { done(false); };
    document.head.appendChild(s);
  }
  function setupAudioGraph() {
    if (fx !== null || !(/^https?:$/.test(location.protocol) || (IS_FILE && local === 'ready'))) return;
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) { fx = false; return; }
    try {
      var ctx = new AC();
      var gain = function () { return ctx.createGain(); };
      var biquad = function (type, f, q) {
        var b = ctx.createBiquadFilter();
        b.type = type;
        b.frequency.value = f;
        if (q) b.Q.value = q;
        return b;
      };
      var src = ctx.createMediaElementSource(audio);
      var pre = gain();
      var bands = EQ_FREQS.map(function (f, i) { return biquad(i === 0 ? 'lowshelf' : i === 9 ? 'highshelf' : 'peaking', f, 1.41); });
      var peq = [];
      for (var pi = 0; pi < 10; pi++) peq.push(biquad('peaking', 1000, 1));
      var tone = {
        vb: biquad('lowshelf', 150),
        bass: biquad('lowshelf', 105),
        treble: biquad('highshelf', 7500),
        body: biquad('lowshelf', 240),
        soft: biquad('highshelf', 5200),
        air: biquad('highshelf', 11000),
        vocal: biquad('peaking', 2400, 0.9),
        lcBass: biquad('lowshelf', 120),
        lcTreble: biquad('highshelf', 10000),
      };
      var split = ctx.createChannelSplitter(2);
      var merge = ctx.createChannelMerger(2);
      var ms = { ll: gain(), lr: gain(), rl: gain(), rr: gain() };
      split.connect(ms.ll, 0); split.connect(ms.lr, 0);
      split.connect(ms.rl, 1); split.connect(ms.rr, 1);
      ms.ll.connect(merge, 0, 0); ms.rl.connect(merge, 0, 0);
      ms.lr.connect(merge, 0, 1); ms.rr.connect(merge, 0, 1);
      var dry = gain();
      var wet = gain();
      wet.gain.value = 0;
      var revIn = gain();
      revIn.channelCount = 1;
      revIn.channelCountMode = 'explicit';
      revIn.channelInterpretation = 'speakers';
      var preDelay = ctx.createDelay(0.2);
      var convs = [0, 1].map(function () {
        var cv = ctx.createConvolver();
        cv.normalize = false;
        var g = gain();
        g.gain.value = 0;
        preDelay.connect(cv);
        cv.connect(g);
        g.connect(wet);
        return { cv: cv, g: g };
      });
      var sum = gain();
      /* 扬声器优化：照原生 SpeakerStage —— 四阶高通护低频 → 谐波补低音 → 2.8 kHz / 8 kHz 提清晰度 → 侧声道扩展 → 1.2 kHz 分两段压缩提响度 */
      var matrix = function () {
        var sp = ctx.createChannelSplitter(2);
        var mg = ctx.createChannelMerger(2);
        var g = { sp: sp, mg: mg, ll: gain(), lr: gain(), rl: gain(), rr: gain() };
        sp.connect(g.ll, 0); sp.connect(g.lr, 0);
        sp.connect(g.rl, 1); sp.connect(g.rr, 1);
        g.ll.connect(mg, 0, 0); g.rl.connect(mg, 0, 0);
        g.lr.connect(mg, 0, 1); g.rr.connect(mg, 0, 1);
        g.lr.gain.value = g.rl.gain.value = 0;
        return g;
      };
      /* 原生按包络把低频归一化到 ±3 再整形、最后乘回包络；这里按典型低频幅度 1/6 换算：输入 ×2 落进曲线的 ±1，对应归一化后的 ±3 */
      var shaper = ctx.createWaveShaper();
      var curve = new Float32Array(1025);
      for (var ci = 0; ci < curve.length; ci++) {
        var xn = (ci / 512 - 1) * 3;
        curve[ci] = (0.7 * Math.tanh(1.6 * xn) + 0.3 * xn * xn) / 6;
      }
      shaper.curve = curve;
      shaper.oversample = '2x';
      var hMono = gain();
      hMono.channelCount = 1;
      hMono.channelCountMode = 'explicit';
      hMono.channelInterpretation = 'speakers';
      var band = function (type) {
        var c = ctx.createDynamicsCompressor();
        c.threshold.value = 0; c.ratio.value = 1; c.knee.value = 6; c.attack.value = 0.004; c.release.value = 0.14;
        return { a: biquad(type, 1200, 0.7071), b: biquad(type, 1200, 0.7071), c: c, g: gain() };
      };
      var spk = {
        hpA: biquad('highpass', 10, 0.5412), hpB: biquad('highpass', 10, 1.3066),
        hLpA: biquad('lowpass', 252, 0.707), hLpB: biquad('lowpass', 252, 0.707), hPre: gain(), hShape: shaper,
        hHp: biquad('highpass', 302, 0.707), hLp: biquad('lowpass', 1512, 0.707), hAmt: gain(), mix: gain(),
        clPeak: biquad('peaking', 2800, 0.8), clShelf: biquad('highshelf', 8000, 0.707),
        ex: matrix(), low: band('lowpass'), high: band('highpass'), out: gain(),
      };
      spk.hPre.gain.value = 2;
      spk.hAmt.gain.value = 0;
      sum.connect(spk.hpA);
      spk.hpA.connect(spk.hpB);
      spk.hpB.connect(spk.mix);
      spk.hpB.connect(hMono);
      [hMono, spk.hLpA, spk.hLpB, spk.hPre, spk.hShape, spk.hHp, spk.hLp, spk.hAmt, spk.mix].reduce(function (a, b) { a.connect(b); return b; });
      spk.mix.connect(spk.clPeak);
      spk.clPeak.connect(spk.clShelf);
      spk.clShelf.connect(spk.ex.sp);
      [spk.low, spk.high].forEach(function (b) {
        spk.ex.mg.connect(b.a); b.a.connect(b.b); b.b.connect(b.c); b.c.connect(b.g); b.g.connect(spk.out);
      });
      /* 外放试听（只在网页里）：把输出再过一遍小喇叭的频响，戴耳机也能听出手机外放的样子 */
      var sim = {
        inp: gain(), dry: gain(), wet: gain(), m: matrix(),
        hpA: biquad('highpass', 520, 0.5412), hpB: biquad('highpass', 520, 1.3066), res: biquad('peaking', 1100, 1.1), body: biquad('peaking', 3200, 1.4),
        lp: biquad('lowpass', 11000, 0.707), drive: ctx.createWaveShaper(), lvl: gain(),
      };
      var sc = new Float32Array(1025);
      for (var si = 0; si < sc.length; si++) { var sx = si / 512 - 1; sc[si] = Math.tanh(1.8 * sx) / Math.tanh(1.8); }
      sim.drive.curve = sc;
      sim.wet.gain.value = 0;
      sim.inp.connect(sim.dry);
      sim.inp.connect(sim.m.sp);
      [sim.m.mg, sim.hpA, sim.hpB, sim.res, sim.body, sim.lp, sim.drive, sim.lvl, sim.wet].reduce(function (a, b) { a.connect(b); return b; });
      var comp = ctx.createDynamicsCompressor();
      var makeup = gain();
      var limiter = ctx.createDynamicsCompressor();
      limiter.threshold.value = -1.5;
      limiter.knee.value = 0;
      limiter.ratio.value = 20;
      limiter.attack.value = 0.002;
      limiter.release.value = 0.12;
      var vol = gain();
      /* 监视用的抽头：处理前 / 处理后（左右分开），只读不改声音 */
      var head = gain();
      var an = function () { var a = ctx.createAnalyser(); a.fftSize = 4096; a.smoothingTimeConstant = 0.72; return a; };
      var anPre = an();
      var anPost = an();
      var anL = ctx.createAnalyser();
      var anR = ctx.createAnalyser();
      anL.fftSize = anR.fftSize = 1024;
      var tapSplit = ctx.createChannelSplitter(2);
      src.connect(head);
      head.connect(pre);
      head.connect(anPre);
      var node = pre;
      bands.concat(peq, [tone.vb, tone.bass, tone.treble, tone.lcBass, tone.lcTreble, tone.body, tone.soft, tone.air, tone.vocal]).forEach(function (b) { node.connect(b); node = b; });
      node.connect(split);
      merge.connect(dry);
      merge.connect(revIn);
      revIn.connect(preDelay);
      dry.connect(sum);
      wet.connect(sum);
      spk.out.connect(comp);
      comp.connect(makeup);
      makeup.connect(limiter);
      limiter.connect(vol);
      vol.connect(sim.inp);
      sim.dry.connect(ctx.destination);
      sim.wet.connect(ctx.destination);
      limiter.connect(anPost);
      limiter.connect(tapSplit);
      tapSplit.connect(anL, 0);
      tapSplit.connect(anR, 1);
      fx = { ctx: ctx, src: src, head: head, anPre: anPre, anPost: anPost, anL: anL, anR: anR, pre: pre, bands: bands, peq: peq, tone: tone, ms: ms, wet: wet, preDelay: preDelay, convs: convs, cur: -1, irKey: '', spk: spk, sim: sim, comp: comp, makeup: makeup, limiter: limiter, vol: vol, pitch: null };
      if (ctx.audioWorklet && SCRIPT_BASE) {
        ctx.audioWorklet.addModule(SCRIPT_BASE + 'pitch-worklet.js').then(function () {
          var node = new AudioWorkletNode(ctx, 'neri-pitch', { outputChannelCount: [2] });
          src.disconnect();
          src.connect(node);
          node.connect(head);
          fx.pitch = node;
          applyFx();
        }).catch(function () { /* 不支持 AudioWorklet 时只保留倍速 */ });
      }
      applyFx();
    } catch (e) {
      fx = false;
    }
  }
  /* 各分区可单独关闭而保留数值；总开关关闭或全部为中性时整条链等同原声 */
  /* 默认值与分区划分同 AudioEffectsModels.kt / AudioEffectsSoundSections.kt */
  var DEFAULTS = {
    eq: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], preamp: 0, peqOn: false, peq: [],
    bass: 0, bassFreq: 100, treble: 0, trebleFreq: 8000, virtualBass: 0, vbFreq: 100,
    warmth: 0, clarity: 0, clarityFreq: 5000, vocal: 0, vocalRemoval: 0, loudComp: 0,
    width: 1, monoBass: 0, mono: false, swap: false, crossfeed: 0, surround: 0,
    reverb: 0, reverbRoom: 0.5, reverbDamping: 0.5, reverbPreDelay: 10,
    compOn: false, compThr: -18, compRatio: 2, compAttack: 10, compRelease: 150, compKnee: 6, compMakeup: 0,
    outGain: 0, limiter: true, ceiling: -1, release: 80,
    spkSize: 'phone', spkProt: 0, spkBass: 0.5, spkLoud: 0.4, spkClarity: 0.3, spkExpand: 0.3,
  };
  var SECTION_KEYS = {
    equalizer: ['eq', 'preamp', 'peqOn', 'peq'],
    tone: ['bass', 'bassFreq', 'treble', 'trebleFreq', 'virtualBass', 'vbFreq', 'warmth', 'clarity', 'clarityFreq', 'vocal', 'vocalRemoval', 'loudComp'],
    space: ['width', 'monoBass', 'mono', 'swap', 'crossfeed', 'surround', 'reverb', 'reverbRoom', 'reverbDamping', 'reverbPreDelay'],
    dynamics: ['compOn', 'compThr', 'compRatio', 'compAttack', 'compRelease', 'compKnee', 'compMakeup', 'outGain', 'limiter', 'ceiling', 'release'],
    speaker: ['spkSize', 'spkProt', 'spkBass', 'spkLoud', 'spkClarity', 'spkExpand'],
  };
  /* 扬声器优化：低频保护低于 40 Hz 时按扬声器类型自动取值（SpeakerSize.protectionHz） */
  var SPK_HZ = { phone: 180, tablet: 120, large: 70 };
  function spkProtHz(s) { s = s || sound; return s.spkProt >= 40 ? s.spkProt : SPK_HZ[s.spkSize] || 180; }
  function copyVal(v) { return Array.isArray(v) ? v.map(function (x) { return typeof x === 'object' ? Object.assign({}, x) : x; }) : v; }
  var sound = {
    on: true,
    preset: 'flat',
    sec: { equalizer: true, tone: true, space: true, dynamics: true, speaker: false, speed: true },
    autoHeadroom: true,
    linked: true,
  };
  Object.keys(DEFAULTS).forEach(function (k) { sound[k] = copyVal(DEFAULTS[k]); });
  /* 「动态压缩」滑块是一个强度值，换算方式同 AudioEffectsNormalization.kt */
  function compAmount() { return sound.compOn ? M.clamp((-sound.compThr - 10) / 26) : 0; }
  function setCompAmount(amount, into) {
    var s = into || sound;
    var a = M.clamp(amount || 0);
    if (a <= 0) { s.compOn = false; return; }
    var thr = -10 - 26 * a;
    var ratio = 1.5 + 4.5 * a;
    s.compOn = true;
    s.compThr = thr;
    s.compRatio = ratio;
    s.compAttack = 12 - 6 * a;
    s.compRelease = 180;
    s.compKnee = 8;
    s.compMakeup = 0.5 * -thr * (1 - 1 / ratio);
  }
  function sectionDefault(name) {
    return SECTION_KEYS[name].every(function (k) {
      var a = sound[k], b = DEFAULTS[k];
      if (Array.isArray(b)) return a.length === b.length && a.every(function (v, i) { return typeof v === 'object' ? false : Math.abs(v - b[i]) < 1e-4; });
      return typeof b === 'number' ? Math.abs(a - b) < 1e-4 : a === b;
    });
  }
  /* 扬声器分区恢复默认只重置调节（SpeakerOptimizerSettings.resetTuning），开关保持原样 */
  function resetSectionKeys(name) {
    SECTION_KEYS[name].forEach(function (k) { sound[k] = copyVal(DEFAULTS[k]); });
    if (name !== 'speaker') sound.sec[name] = true;
  }
  var PEQ_TYPE = { peak: 'peaking', low_shelf: 'lowshelf', high_shelf: 'highshelf', low_pass: 'lowpass', high_pass: 'highpass', notch: 'notch', band_pass: 'bandpass' };
  function sectionOn(name) { return sound.on && sound.sec[name]; }
  function applyFx() {
    if (!fx) return;
    var t = fx.ctx.currentTime;
    var ramp = function (param, v) { param.setTargetAtTime(v, t, 0.03); };
    var eq = sectionOn('equalizer');
    for (var i = 0; i < 10; i++) ramp(fx.bands[i].gain, eq ? sound.eq[i] : 0);
    ramp(fx.pre.gain, Math.pow(10, (eq ? sound.preamp : 0) / 20));
    for (var p = 0; p < 10; p++) {
      var band = eq && sound.peqOn && sound.peq[p] && sound.peq[p].on !== false ? sound.peq[p] : null;
      var f = fx.peq[p];
      var type = band ? PEQ_TYPE[band.type] || 'peaking' : 'peaking';
      if (f.type !== type) f.type = type;
      ramp(f.frequency, band ? band.f : 1000);
      ramp(f.Q, band ? band.q : 1);
      ramp(f.gain, band ? band.g : 0);
    }
    var tn = sectionOn('tone');
    ramp(fx.tone.vb.frequency, sound.vbFreq * 1.4);
    ramp(fx.tone.vb.gain, tn ? sound.virtualBass * 6 : 0);
    ramp(fx.tone.bass.frequency, sound.bassFreq);
    ramp(fx.tone.bass.gain, tn ? sound.bass : 0);
    ramp(fx.tone.treble.frequency, sound.trebleFreq);
    ramp(fx.tone.treble.gain, tn ? sound.treble : 0);
    var lc = loudnessDb();
    ramp(fx.tone.lcBass.gain, lc.bass);
    ramp(fx.tone.lcTreble.gain, lc.treble);
    ramp(fx.tone.air.frequency, sound.clarityFreq * 2);
    ramp(fx.tone.body.gain, tn ? sound.warmth * 3 : 0);
    ramp(fx.tone.soft.gain, tn ? -sound.warmth * 4 : 0);
    ramp(fx.tone.air.gain, tn ? sound.clarity * 5 : 0);
    ramp(fx.tone.vocal.gain, tn ? sound.vocal * 5 : 0);
    var sp = sectionOn('space');
    var w = sp ? (sound.mono ? 0 : sound.width * (1 + sound.surround * 0.5) * (1 - sound.crossfeed * 0.35)) : 1;
    var m = tn ? 1 - sound.vocalRemoval : 1;
    var same = (m + w) / 2;
    var cross = (m - w) / 2;
    var sw = sp && sound.swap;
    ramp(fx.ms.ll.gain, sw ? cross : same);
    ramp(fx.ms.rr.gain, sw ? cross : same);
    ramp(fx.ms.lr.gain, sw ? same : cross);
    ramp(fx.ms.rl.gain, sw ? same : cross);
    var rev = sp ? sound.reverb : 0;
    ramp(fx.wet.gain, 1.5 * rev);
    var pd = Math.round(fx.ctx.sampleRate * Math.min(120, sound.reverbPreDelay) / 1000);
    ramp(fx.preDelay.delayTime, Math.max(0, pd - 1) / fx.ctx.sampleRate);
    if (rev > 0) updateReverb();
    applySpeaker(ramp);
    var dyn = sectionOn('dynamics');
    var cOn = dyn && sound.compOn;
    ramp(fx.comp.threshold, cOn ? sound.compThr : 0);
    ramp(fx.comp.ratio, cOn ? sound.compRatio : 1);
    ramp(fx.comp.knee, cOn ? sound.compKnee : 0);
    fx.comp.attack.setTargetAtTime(sound.compAttack / 1000, t, 0.03);
    fx.comp.release.setTargetAtTime(sound.compRelease / 1000, t, 0.03);
    /* 浏览器的压缩节点自带补偿增益，这里只叠加用户相对默认补偿的改动 */
    var auto = 0.5 * -sound.compThr * (1 - 1 / sound.compRatio);
    ramp(fx.makeup.gain, cOn ? Math.pow(10, (sound.compMakeup - auto) / 20) : 1);
    var lim = sound.on && sound.limiter;
    ramp(fx.limiter.threshold, lim ? Math.min(-0.1, sound.ceiling) : 0);
    ramp(fx.limiter.ratio, lim ? 20 : 1);
    fx.limiter.release.setTargetAtTime(sound.release / 1000, t, 0.03);
    ramp(fx.vol.gain, P.volume * Math.pow(10, (dyn ? sound.outGain : 0) / 20));
    if (fx.pitch) fx.pitch.parameters.get('ratio').setValueAtTime(speedOn() && !sound.linked ? P.pitch : 1, t);
  }
  /* 响度补偿（桌面版 loudnessCompensationDb）：音量调低多少，就按比例补回低频和高频；音量 100% 时不起作用 */
  function loudnessDb() {
    var a = sectionOn('tone') ? M.clamp(sound.loudComp || 0) : 0;
    if (a <= 0) return { bass: 0, treble: 0 };
    var att = Math.min(40, Math.max(0, -20 * Math.log10(M.clamp(P.volume, 0.001, 1))));
    return { bass: a * Math.min(10, att * 0.3), treble: a * Math.min(4, att * 0.1) };
  }
  /* 参数换算同 SpeakerStage::setTargets / speakerBandSettings；网页里的输出就当作手机扬声器，开了就生效 */
  function speakerBand(loud, boost) {
    var thr = -8 - 22 * loud;
    var ratio = 2.5 + boost * loud;
    return { thr: thr, ratio: ratio, makeup: 0.55 * -thr * (1 - 1 / ratio) };
  }
  function applySpeaker(ramp) {
    var s = fx.spk;
    var on = sectionOn('speaker');
    var hz = spkProtHz();
    ramp(s.hpA.frequency, on ? hz : 10);
    ramp(s.hpB.frequency, on ? hz : 10);
    var hf = M.clamp(Math.max(hz, 60) * 1.4, 70, 260);
    ramp(s.hLpA.frequency, hf);
    ramp(s.hLpB.frequency, hf);
    ramp(s.hHp.frequency, hf * 1.2);
    ramp(s.hLp.frequency, hf * 6);
    ramp(s.hAmt.gain, on ? sound.spkBass * 1.6 : 0);
    ramp(s.clPeak.gain, on ? 4 * sound.spkClarity : 0);
    ramp(s.clShelf.gain, on ? 3 * sound.spkClarity : 0);
    var k = 1 + 0.6 * (on ? sound.spkExpand : 0);
    ramp(s.ex.ll.gain, (1 + k) / 2);
    ramp(s.ex.rr.gain, (1 + k) / 2);
    ramp(s.ex.lr.gain, (1 - k) / 2);
    ramp(s.ex.rl.gain, (1 - k) / 2);
    var loud = on ? sound.spkLoud : 0;
    [[s.low, 1.5], [s.high, 2.5]].forEach(function (x) {
      var b = x[0];
      var c = speakerBand(loud, x[1]);
      ramp(b.c.threshold, loud > 0 ? c.thr : 0);
      ramp(b.c.ratio, loud > 0 ? c.ratio : 1);
      /* 浏览器的压缩节点自带补偿增益（约 0.5 × 压缩量），这里补齐到原生的 0.55 × */
      ramp(b.g.gain, loud > 0 ? Math.pow(10, (c.makeup - 0.5 * -c.thr * (1 - 1 / c.ratio)) / 20) : 1);
    });
    applyPreview(ramp);
  }
  /* 外放试听的小喇叭模型：按扬声器类型换口径（越小低频越早滚降、声像越窄），推得越响越容易发毛 */
  var SIM = {
    phone: { hp: 520, res: 1100, resG: 5, body: 3200, bodyG: 3, lp: 11000, width: 0.3, lvl: 1.5 },
    tablet: { hp: 300, res: 900, resG: 3.5, body: 3000, bodyG: 2, lp: 14000, width: 0.65, lvl: 1.3 },
    large: { hp: 95, res: 160, resG: 2, body: 2600, bodyG: 1, lp: 18000, width: 1, lvl: 1.05 },
  };
  var preview = { on: false };
  function applyPreview(ramp) {
    var m = fx.sim;
    var p = SIM[sound.spkSize] || SIM.phone;
    var on = preview.on;
    ramp(m.dry.gain, on ? 0 : 1);
    ramp(m.wet.gain, on ? 1 : 0);
    ramp(m.hpA.frequency, p.hp);
    ramp(m.hpB.frequency, p.hp);
    ramp(m.res.frequency, p.res);
    ramp(m.res.gain, p.resG);
    ramp(m.body.frequency, p.body);
    ramp(m.body.gain, p.bodyG);
    ramp(m.lp.frequency, p.lp);
    ramp(m.lvl.gain, p.lvl);
    ramp(m.m.ll.gain, (1 + p.width) / 2);
    ramp(m.m.rr.gain, (1 + p.width) / 2);
    ramp(m.m.lr.gain, (1 - p.width) / 2);
    ramp(m.m.rl.gain, (1 - p.width) / 2);
  }
  /* 房间大小 / 明亮度 / 处理模式变了才重算脉冲响应；两路卷积交叉淡变，拖滑块时不会咔哒 */
  function updateReverb() {
    var eco = !!(NP.dspState && NP.dspState.local && NP.dspState.local.mode === 'eco');
    var key = sound.reverbRoom.toFixed(2) + '|' + sound.reverbDamping.toFixed(2) + '|' + (eco ? 1 : 0);
    if (key === fx.irKey) return;
    fx.irKey = key;
    clearTimeout(fx.irT);
    fx.irT = setTimeout(function () {
      var next = fx.cur === 0 ? 1 : 0;
      var slot = fx.convs[next];
      try { slot.cv.buffer = reverbIR(fx.ctx, sound.reverbRoom, sound.reverbDamping, eco); } catch (e) { return; }
      var t = fx.ctx.currentTime;
      slot.g.gain.cancelScheduledValues(t);
      slot.g.gain.setTargetAtTime(1, t, 0.04);
      if (fx.cur >= 0) { var old = fx.convs[fx.cur].g.gain; old.cancelScheduledValues(t); old.setTargetAtTime(0, t, 0.04); }
      fx.cur = next;
    }, fx.cur < 0 ? 0 : 140);
  }
  function speedOn() { return sectionOn('speed'); }
  function rate() { return speedOn() ? P.speed : 1; }
  function applyRate() {
    if (!audio) return;
    var keep = !(speedOn() && sound.linked);
    if ('preservesPitch' in audio) audio.preservesPitch = keep;
    else if ('webkitPreservesPitch' in audio) audio.webkitPreservesPitch = keep;
    audio.playbackRate = rate();
  }
  NP.sound = {
    state: sound, apply: function () { applyFx(); applyRate(); emit(); }, live: function () { return !!fx && P.audible; }, freqs: EQ_FREQS, rate: rate,
    defaults: DEFAULTS, sections: SECTION_KEYS, sectionDefault: sectionDefault, resetSection: resetSectionKeys,
    compAmount: compAmount, setCompAmount: setCompAmount, spkProtHz: spkProtHz, spkSizes: SPK_HZ, loudnessDb: loudnessDb,
    preview: function () { return preview.on; },
    setPreview: function (on) { preview.on = !!on; if (fx) applyPreview(function (p, v) { p.setTargetAtTime(v, fx.ctx.currentTime, 0.03); }); emit(); },
    /* 效果监视器：有真实音频链时给出分析节点，否则返回 null，由页面用模型画 */
    limiterReduction: function () { return fx ? fx.limiter.reduction : 0; },
    taps: function () { return fx && P.audible ? { ctx: fx.ctx, pre: fx.anPre, post: fx.anPost, l: fx.anL, r: fx.anR, comp: fx.comp } : null; },
  };

  P.play = function () {
    if (!audio) return;
    if (local === 'idle' || local === 'loading') { P.playing = true; emit(); prepareLocalAudio(function () { if (P.playing) P.play(); }); return; }
    setupAudioGraph();
    if (fx && fx.ctx.state === 'suspended') fx.ctx.resume();
    P.pending = P.t;
    if (audio.readyState === 0) audio.load();
    else { try { audio.currentTime = P.t; P.pending = null; } catch (e) { /* 元数据未就绪时在 loadedmetadata 里补 */ } }
    applyRate();
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
    applyRate();
    applyFx();
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
      P.t += dt * rate();
      if (Math.abs(at - P.t) > 0.12) P.t = at;
    } else if (!P.audible) {
      P.t += dt * rate();
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
    var r = rate();
    var spec = 'RAW<span>·</span>48 kHz' + (Math.abs(r - 1) > 0.001 ? '<span>·</span>' + r.toFixed(2) + 'x' : '');
    if (info && info.innerHTML !== spec) info.innerHTML = spec;
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

  /* ---------- 内置音效：逐项照搬 AudioEffectsPresets.kt 的 29 种预设（风格 / 音色 / 场景 / 设备） ---------- */
  var L = NP.L;
  function pre(id, cat, zh, en, s) { s = s || {}; s.id = id; s.cat = cat; s.name = L(zh, en); return s; }
  var PRESETS = [
    pre('flat', 'style', '原声', 'Original'),
    pre('pop', 'style', '流行', 'Pop', { eq: [0, 1, 2, 1, 0, -0.5, 1, 2, 2.5, 2], vocal: 0.2 }),
    pre('rock', 'style', '摇滚', 'Rock', { eq: [3, 3, 2, 0, -1, -0.5, 1, 2.5, 3, 3], warmth: 0.15 }),
    pre('classical', 'style', '古典', 'Classical', { eq: [1, 1, 0.5, 0, 0, 0, -0.5, 0.5, 1.5, 2], width: 1.1, reverb: 0.12, reverbRoom: 0.75, reverbDamping: 0.45, reverbPreDelay: 20 }),
    pre('jazz', 'style', '爵士', 'Jazz', { eq: [1.5, 1, 1, 1.5, 0, -0.5, 0.5, 1.5, 2, 1.5], warmth: 0.25 }),
    pre('electronic', 'style', '电子', 'Electronic', { eq: [4, 3.5, 2, 0, -1, 0, 1, 2, 3, 3], virtualBass: 0.25, width: 1.15 }),
    pre('hip_hop', 'style', '嘻哈', 'Hip-hop', { eq: [4.5, 4, 2.5, 1, -0.5, -0.5, 1, 1, 1.5, 1], virtualBass: 0.3 }),
    pre('rnb', 'style', 'R&B', 'R&B', { eq: [3, 3, 2, 1, -0.5, 0, 1, 1.5, 1.5, 1], warmth: 0.2 }),
    pre('folk', 'style', '民谣', 'Folk', { eq: [0.5, 1, 1.5, 1, 0, 0, 1, 2, 2, 1.5], warmth: 0.15 }),
    pre('metal', 'style', '金属', 'Metal', { eq: [3, 2.5, 1, -1, -2, -1, 1, 3, 3.5, 3] }),
    pre('acg', 'style', '二次元', 'Anime & games', { eq: [1, 1, 0.5, 0, 0, 0.5, 1.5, 2.5, 3, 2.5], vocal: 0.3, clarity: 0.2 }),
    pre('piano', 'style', '钢琴', 'Piano', { eq: [0, 0, 0.5, 1, 0.5, 0, 0.5, 1, 1.5, 1], reverb: 0.08 }),
    pre('vocal_boost', 'tone', '人声增强', 'Vocal boost', { eq: [-2, -1.5, -1, 0, 1, 2.5, 3, 2.5, 1, 0], vocal: 0.4 }),
    pre('bass_boost', 'tone', '低音增强', 'Bass boost', { eq: [2, 2, 1, 0, 0, 0, 0, 0, 0, 0], bass: 6, virtualBass: 0.2 }),
    pre('deep_bass', 'tone', '超重低音', 'Deep bass', { eq: [5, 4, 2, 0, -0.5, 0, 0, 0, 0, 0], bass: 6, bassFreq: 80, virtualBass: 0.5, monoBass: 120 }),
    pre('treble_boost', 'tone', '高音增强', 'Treble boost', { treble: 5, trebleFreq: 6000, clarity: 0.2 }),
    pre('warm', 'tone', '温暖黑胶', 'Warm vinyl', { warmth: 0.6, treble: -3, trebleFreq: 9000, crossfeed: 0.1 }),
    pre('live', 'scene', '现场演唱会', 'Live concert', { width: 1.3, surround: 0.2, reverb: 0.35, reverbRoom: 0.85, reverbDamping: 0.4, reverbPreDelay: 25 }),
    pre('concert_hall', 'scene', '音乐厅', 'Concert hall', { width: 1.2, reverb: 0.4, reverbRoom: 0.95, reverbDamping: 0.3, reverbPreDelay: 40 }),
    pre('cinema', 'scene', '影院', 'Cinema', { bass: 4, virtualBass: 0.3, surround: 0.6, reverb: 0.15, reverbRoom: 0.7, comp: 0.25 }),
    pre('surround_3d', 'scene', '3D 环绕', '3D surround', { surround: 0.8, width: 1.5 }),
    pre('karaoke', 'scene', 'K 歌伴奏', 'Karaoke', { vocalRemoval: 0.9, reverb: 0.25, reverbRoom: 0.6 }),
    pre('night', 'scene', '深夜模式', 'Night mode', { bass: -2, treble: -1, comp: 0.7 }),
    pre('spoken', 'scene', '有声书 / 播客', 'Podcasts & audiobooks', { eq: [-6, -4, -2, 0, 1, 2, 3, 2, 0, -1], mono: true, vocal: 0.5, comp: 0.5 }),
    pre('loudness_max', 'scene', '响度最大化', 'Maximum loudness', { ceiling: -0.5, comp: 0.85 }),
    pre('headphone_hifi', 'device', '耳机 Hi-Fi', 'Headphone Hi-Fi', { eq: [1, 0.5, 0, 0, 0, 0, 0.5, 1, 1, 0.5], crossfeed: 0.35, clarity: 0.1 }),
    pre('studio', 'device', '录音棚监听', 'Studio monitor', { crossfeed: 0.3, reverb: 0.05, reverbRoom: 0.25 }),
    pre('car', 'device', '车载', 'In the car', { bass: 3, treble: 2, vocal: 0.2, comp: 0.3 }),
    pre('small_speaker', 'device', '小音箱', 'Small speaker', { eq: [0, 0, 1, 1, 0, 0, 1, 2, 2, 1], virtualBass: 0.5, vbFreq: 140, monoBass: 150, comp: 0.35 }),
  ];
  /* 内置预设整套替换音色（四个分区重新打开），耳机校正属于设备补偿，切换时保留 */
  function applyPresetSound(id) {
    var p = PRESETS.filter(function (x) { return x.id === id; })[0];
    if (!p) return;
    Object.keys(DEFAULTS).forEach(function (k) {
      if (k === 'peq' || k === 'peqOn') return;
      sound[k] = copyVal(p[k] != null ? p[k] : DEFAULTS[k]);
    });
    if (p.comp) setCompAmount(p.comp);
    ['equalizer', 'tone', 'space', 'dynamics'].forEach(function (s) { sound.sec[s] = true; });
    sound.preset = id;
    sound.on = true;
    NP.sound.apply();
  }

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
  NP.sound.usePreset = applyPresetSound;
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
    $$('#devicePlay, #deviceLyrics, #deviceDsp').forEach(function (dev) {
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
  function waitFonts(devices) {
    if (!document.fonts || !document.fonts.load) return;
    var zh = (document.documentElement.lang || '').indexOf('zh') === 0;
    var faces = ['400 14px "NP Roboto"', '500 14px "NP Roboto"', '14px "NP Droid Mono"'];
    var probe = zh ? 'Aa0 音效设置' : 'Aa0';
    var pending = Promise.all(faces.map(function (f) { return document.fonts.load(f, probe); }));
    var done = false;
    var open = function () { if (done) return; done = true; devices.forEach(function (d) { d.classList.remove('is-fontwait'); }); };
    devices.forEach(function (d) { d.classList.add('is-fontwait'); });
    pending.then(open, open);
    setTimeout(open, 2500);
  }
  NP.phones = {
    init: function () {
      if (NP.phoneUI) NP.phoneUI.prepare();
      waitFonts($$('.device'));
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
