/* NeriPlayer site · desktop-sim —— 桌面端 1:1 仿真：界面是桌面版前端导出的真实 DOM 与样式，交互在这里接回去 */
(function () {
  'use strict';

  var NP = window.NP;
  var M = NP.math;
  var $ = NP.$;
  var $$ = NP.$$;
  var L = NP.L;
  var P = NP.player;
  var reduced = NP.env.reduced;
  var BASE = ((document.currentScript && document.currentScript.src) || '').replace(/js\/[^/]*$/, '');
  var W = 1280;
  var H = 800;

  /* AMLL 的 CSS Modules 类名（随桌面版构建固定） */
  var A = {
    wrap: '_lyricLineWrapper_1rfgi_1', line: '_lyricLine_1rfgi_1', active: '_active_1rfgi_265',
    main: '_lyricMainLine_1rfgi_111', word: '_emphasizeWrapper_1rfgi_193', sub: '_lyricSubLine_1rfgi_281', playing: '_playing_1rfgi_533',
  };

  var EXTRA = [
    ':host { contain: strict; }',
    /* 窗口整体缩放过，content-visibility: auto 会把曲目行误判成在屏幕外而不画 */
    '.dsim-app * { content-visibility: visible !important; }',
    '.dsim-app, .dsim-app .app-layout { width: 1280px; height: 800px; }',
    /* 路由切换：与桌面版 .fade 过渡一致 */
    'main.content > * { transition: opacity 300ms cubic-bezier(0,0,0,1), transform 300ms cubic-bezier(0,0,0,1); }',
    'main.content.dsim-leave > * { opacity: 0; transform: scale(.99); transition: opacity 100ms cubic-bezier(.3,0,1,1), transform 100ms cubic-bezier(.3,0,1,1); }',
    'main.content.dsim-enter > * { opacity: 0; transform: translateY(8px) scale(.98); transition: none; }',
    '.settings-content > * { transition: opacity 240ms cubic-bezier(0,0,0,1), transform 300ms cubic-bezier(0,0,0,1); }',
    '.settings-content.dsim-leave > * { opacity: 0; transition: opacity 90ms linear; }',
    '.settings-content.dsim-enter > * { opacity: 0; transform: translateY(6px); transition: none; }',
    /* 播放页：上滑覆盖，标题栏两套交叉淡入 */
    '.now-playing { transition: transform 460ms cubic-bezier(.22,1,.36,1), visibility 0s; }',
    ':host(:not(.np-open)) .now-playing { transform: translateY(100%); visibility: hidden; transition: transform 400ms cubic-bezier(.3,0,.8,.15), visibility 0s 400ms; }',
    '.title-bar.tb-np-mode { opacity: 0; pointer-events: none; transition: opacity 240ms cubic-bezier(.2,0,0,1); }',
    '.title-bar.tb-np-mode * { pointer-events: none !important; }',
    ':host(.np-open) .title-bar.tb-np-mode { opacity: 1; transition: opacity 320ms cubic-bezier(.2,0,0,1) 300ms; }',
    ':host(.np-open) .title-bar.tb-np-mode .tb-np-btn, :host(.np-open) .title-bar.tb-np-mode .tb-ctrl, :host(.np-open) .title-bar.tb-np-mode .tb-traffic-safe-area * { pointer-events: auto !important; }',
    '.title-bar:not(.tb-np-mode) { transition: opacity 520ms cubic-bezier(.2,0,0,1); }',
    ':host(.np-open) .title-bar:not(.tb-np-mode) { opacity: 0; pointer-events: none; }',
    ':host(.np-open) .title-bar:not(.tb-np-mode) * { pointer-events: none !important; }',
    '.np-underlay-scrim { transition: opacity 400ms cubic-bezier(.2,0,0,1); }',
    /* macOS：系统红绿灯在 x=20、垂直居中；应用让出 92px 安全区 */
    '.tb-traffic-safe-area { position: relative; width: 92px; flex: 0 0 auto; height: 100%; pointer-events: auto; display: none; align-items: center; gap: 8px; padding-left: 20px; box-sizing: border-box; }',
    ':host(.platform-mac) .tb-traffic-safe-area { display: flex; }',
    ':host(.platform-mac) .tb-controls { display: none !important; }',
    ':host(.platform-mac) .tb-left-slot .tb-brand { padding-left: 0; }',
    '.tl { position: relative; width: 12px; height: 12px; border-radius: 50%; box-shadow: inset 0 0 0 .5px rgba(0,0,0,.18); cursor: pointer; }',
    '.tl::after { content: ""; position: absolute; inset: 0; opacity: 0; transition: opacity .12s; background: center / 8px 8px no-repeat; }',
    '.tb-traffic-safe-area:hover .tl::after { opacity: .7; }',
    '.tl--close { background: #ff5f57; } .tl--min { background: #febc2e; } .tl--max { background: #28c840; }',
    '.tl--close::after { background-image: url("data:image/svg+xml,%3Csvg xmlns=%27http://www.w3.org/2000/svg%27 viewBox=%270 0 8 8%27%3E%3Cpath d=%27M1.5 1.5l5 5M6.5 1.5l-5 5%27 stroke=%27%23000%27 stroke-width=%271.2%27/%3E%3C/svg%3E"); }',
    '.tl--min::after { background-image: url("data:image/svg+xml,%3Csvg xmlns=%27http://www.w3.org/2000/svg%27 viewBox=%270 0 8 8%27%3E%3Cpath d=%27M1 4h6%27 stroke=%27%23000%27 stroke-width=%271.2%27/%3E%3C/svg%3E"); }',
    '.tl--max::after { background-image: url("data:image/svg+xml,%3Csvg xmlns=%27http://www.w3.org/2000/svg%27 viewBox=%270 0 8 8%27%3E%3Cpath d=%27M2 6V2.5L5.5 6zM6 2v3.5L2.5 2z%27 fill=%27%23000%27/%3E%3C/svg%3E"); }',
    ':host(.is-blur) .tl { background: #d3d1d6; }',
    /* 窗口最小化 / 关到托盘 */
    '.dsim-app { transition: transform 420ms cubic-bezier(.3,0,.8,.15), opacity 300ms; transform-origin: 50% 110%; }',
    ':host(.is-min) .dsim-app { transform: scale(.4) translateY(40%); opacity: 0; }',
    ':host(.is-restore) .dsim-app { transition: transform 520ms cubic-bezier(.05,.7,.1,1), opacity 300ms; }',
    /* 歌词：网站接手 AMLL 的行布局与逐字推进 */
    '.amll-lyric-player .' + A.wrap + ' { position: absolute; left: 0; top: 0; width: 100%; will-change: transform, filter; cursor: pointer; }',
    '.amll-lyric-player .' + A.line + ' { transition: transform .6s cubic-bezier(.2,0,0,1); transform-origin: left center; }',
    '.amll-lyric-player.dsim-no-sub .' + A.sub + ' { display: none; }',
    /* 网站提示 */
    '.dsim-toast { position: absolute; left: 50%; bottom: 96px; z-index: 3000; max-width: 640px; padding: 12px 18px; border-radius: 8px; background: var(--md-inverse-surface); color: var(--md-inverse-on-surface); font: 14px/1.45 var(--font-family-default, MiSans, sans-serif); box-shadow: 0 6px 18px rgba(0,0,0,.25); opacity: 0; transform: translate(-50%, 12px); pointer-events: none; transition: opacity .25s, transform .35s cubic-bezier(.05,.7,.1,1); }',
    '.dsim-toast.is-on { opacity: 1; transform: translate(-50%, 0); }',
    ':host(.np-open) .dsim-toast { bottom: 40px; }',
    /* 主题圆形扩散 */
    '.dsim-ripple { position: absolute; inset: 0; z-index: 4000; pointer-events: none; }',
    'input, button { -webkit-tap-highlight-color: transparent; }',
  ].join('\n');

  function el(html) { var d = document.createElement('div'); d.innerHTML = html; return d.firstElementChild; }
  function setText(node, t) { if (node && node.textContent !== t) node.textContent = t; }
  function icon(span, name) { if (span && span.textContent !== name) span.textContent = name; }
  function mmss(s) { s = Math.max(0, Math.floor(s)); return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2); }

  /* ---------- 桌面版的频响曲线（model.ts 的 audioEffectsFrequencyResponse） ---------- */
  var FREQS = [31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
  function biquad(type, f, gain, q) {
    var w = 2 * Math.PI * Math.min(f, 23900) / 48000;
    var c = Math.cos(w);
    var alpha = Math.sin(w) / (2 * Math.max(0.1, Math.min(24, q)));
    var a = Math.pow(10, gain / 40);
    var root = 2 * Math.sqrt(a) * alpha;
    var k;
    if (type === 'peak') k = [1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a];
    else if (type === 'low') k = [a * (a + 1 - (a - 1) * c + root), 2 * a * (a - 1 - (a + 1) * c), a * (a + 1 - (a - 1) * c - root), a + 1 + (a - 1) * c + root, -2 * (a - 1 + (a + 1) * c), a + 1 + (a - 1) * c - root];
    else k = [a * (a + 1 + (a - 1) * c + root), -2 * a * (a - 1 + (a + 1) * c), a * (a + 1 + (a - 1) * c - root), a + 1 - (a - 1) * c + root, 2 * (a - 1 - (a + 1) * c), a + 1 - (a - 1) * c - root];
    return { b0: k[0] / k[3], b1: k[1] / k[3], b2: k[2] / k[3], a1: k[4] / k[3], a2: k[5] / k[3] };
  }
  function magDb(f, hz) {
    var w = 2 * Math.PI * hz / 48000;
    var rn = f.b0 + f.b1 * Math.cos(w) + f.b2 * Math.cos(2 * w);
    var im = -f.b1 * Math.sin(w) - f.b2 * Math.sin(2 * w);
    var rd = 1 + f.a1 * Math.cos(w) + f.a2 * Math.cos(2 * w);
    var id = -f.a1 * Math.sin(w) - f.a2 * Math.sin(2 * w);
    var n = rn * rn + im * im;
    var d = rd * rd + id * id;
    return n <= 0 || d <= 0 ? -120 : 10 * Math.log10(n / d);
  }
  function response(S) {
    var fs = [];
    var on = S.on;
    if (on && S.sec.equalizer) S.eq.forEach(function (g, i) { if (g) fs.push(biquad('peak', FREQS[i], g, 1.41)); });
    if (on && S.sec.tone && S.bass) fs.push(biquad('low', S.bassFreq || 100, S.bass, 0.707));
    if (on && S.sec.tone && S.treble) fs.push(biquad('high', S.trebleFreq || 8000, S.treble, 0.707));
    var lc = NP.sound.loudnessDb ? NP.sound.loudnessDb() : null;
    if (lc && lc.bass) fs.push(biquad('low', 120, lc.bass, 0.707));
    if (lc && lc.treble) fs.push(biquad('high', 10000, lc.treble, 0.707));
    var pre = on && S.sec.equalizer ? S.preamp : 0;
    var pts = [];
    for (var i = 0; i < 96; i++) {
      var hz = 20 * Math.pow(1000, i / 95);
      var g = pre;
      for (var j = 0; j < fs.length; j++) g += magDb(fs[j], hz);
      pts.push([hz, g]);
    }
    return pts;
  }

  /* ---------- 播放页背景：桌面版 hyperBackground.frag ---------- */
  var VS = 'attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}';
  var FS = 'precision highp float;uniform vec2 u_resolution;uniform float u_time;uniform float u_musicLevel;uniform float u_beat;uniform vec4 u_color0;uniform vec4 u_color1;uniform vec4 u_color2;uniform vec4 u_color3;uniform vec4 u_color4;uniform float u_darkMode;uniform float u_lightOffset;uniform float u_saturateOffset;' +
    'vec3 rgb2hsv(vec3 c){vec4 K=vec4(0.,-1./3.,2./3.,-1.);vec4 p=mix(vec4(c.bg,K.wz),vec4(c.gb,K.xy),step(c.b,c.g));vec4 q=mix(vec4(p.xyw,c.r),vec4(c.r,p.yzx),step(p.x,c.r));float d=q.x-min(q.w,q.y);float e=1.0e-10;return vec3(abs(q.z+(q.w-q.y)/(6.*d+e)),d/(q.x+e),q.x);}' +
    'vec3 hsv2rgb(vec3 c){vec4 K=vec4(1.,2./3.,1./3.,3.);vec3 p=abs(fract(c.xxx+K.xyz)*6.-K.www);return c.z*mix(K.xxx,clamp(p-K.xxx,0.,1.),c.y);}' +
    'float gn(vec2 uv){return fract(52.9829189*fract(dot(uv,vec2(0.06711056,0.00583715))));}' +
    'void main(){vec2 vUv=gl_FragCoord.xy/u_resolution;vec2 uv=vUv;float le=clamp(u_musicLevel,0.,1.);float be=clamp(u_beat,0.,1.);float me=clamp(.42*le+.82*be,0.,1.);float cp=clamp(.68*le+.32*be,0.,1.);float zoom=1.+.024*le+.105*be;vec2 ce=vec2(.5);uv=(uv-ce)/zoom+ce;' +
    'float bw=sin((vUv.y+u_time*.12)*6.2832)*cos((vUv.x-u_time*.10)*6.2832);float rp=sin((distance(vUv,ce)*5.5-u_time*.18)*6.2832);float rw=sin(((vUv.x*1.7+vUv.y*2.3)-u_time*.12)*6.2832);uv+=be*.011*vec2(bw,-bw);uv+=be*.0085*normalize(vUv-ce+vec2(1e-4))*rp;uv+=me*.0062*vec2(rw,-rw*.75);uv+=me*.006*vec2(sin(u_time*1.9),cos(u_time*1.6));' +
    'vec3 pts[5];if(u_darkMode>.5){pts[0]=vec3(.52,.48,.90);pts[1]=vec3(.14,.32,.72);pts[2]=vec3(.92,.28,.76);pts[3]=vec3(.24,.88,.78);pts[4]=vec3(.86,.86,.82);}else{pts[0]=vec3(.52,.46,.92);pts[1]=vec3(.14,.32,.74);pts[2]=vec3(.92,.30,.76);pts[3]=vec3(.26,.88,.80);pts[4]=vec3(.84,.86,.84);}' +
    'vec4 cs[5];cs[0]=u_color0;cs[1]=u_color1;cs[2]=u_color2;cs[3]=u_color3;cs[4]=u_color4;float po=.1+.022*le+.108*be;float pr=1.+.045*le+.22*be;vec4 acc=vec4(0.);float ws=0.;' +
    'for(int i=0;i<5;i++){vec4 pc=cs[i];pc.rgb*=pc.a;float x=pts[i].x;float y=pts[i].y;float r=pts[i].z*pr;x+=sin(u_time+y)*po;y+=cos(u_time+x)*po;float px=x-.5+1e-4;float py=y-.5+1e-4;float pl=sqrt(px*px+py*py);float ps=pl>0.?be*.118/pl:0.;vec2 pt=vec2(x+px*ps,y+py*ps);vec2 dl=uv-pt;float rs=max(r*r,1e-4);float w=1./(1.+dot(dl,dl)/rs*9.3);w*=w;acc+=pc*w;ws+=w;}' +
    'vec4 col=acc/max(ws,1e-5);col.rgb/=max(col.a,1e-5);vec3 hsv=rgb2hsv(col.rgb);float bs=hsv.y*(1.16+.06*cp)+.03*cp*u_saturateOffset;float dl2=mix(.66,.82,smoothstep(.34,.74,hsv.z));hsv.y=clamp(min(bs,dl2),0.,1.);hsv.z=clamp((hsv.z-.5)*(1.26+.06*cp)+.5+.018*cp,0.,1.);col.rgb=hsv2rgb(hsv);col.rgb+=.01*cp*u_lightOffset;col.rgb*=mix(.68,1.1,smoothstep(.1,.92,vUv.y));col.rgb=clamp(col.rgb,0.,1.);col.a=clamp(col.a,0.,1.);float di=(gn(gl_FragCoord.xy)-.5)*(5./255.);col.rgb=clamp(col.rgb+di,0.,1.);gl_FragColor=vec4(col.rgb*col.a,col.a);}';
  function Hyper(canvas, props) {
    this.ok = false;
    var gl = null;
    try { gl = canvas.getContext('webgl', { alpha: true, premultipliedAlpha: true, antialias: false, depth: false }); } catch (e) { gl = null; }
    if (!gl) return;
    var sh = function (t, s) { var x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x); return gl.getShaderParameter(x, gl.COMPILE_STATUS) ? x : null; };
    var v = sh(gl.VERTEX_SHADER, VS);
    var f = sh(gl.FRAGMENT_SHADER, FS);
    if (!v || !f) return;
    var p = gl.createProgram();
    gl.attachShader(p, v);
    gl.attachShader(p, f);
    gl.bindAttribLocation(p, 0, 'p');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) return;
    gl.useProgram(p);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    var u = {};
    ['u_resolution', 'u_time', 'u_musicLevel', 'u_beat', 'u_color0', 'u_color1', 'u_color2', 'u_color3', 'u_color4', 'u_darkMode', 'u_lightOffset', 'u_saturateOffset'].forEach(function (n) { u[n] = gl.getUniformLocation(p, n); });
    canvas.width = 640;
    canvas.height = 400;
    gl.viewport(0, 0, 640, 400);
    var cs = props && props.colors || [[0.06, 0.06, 0.18, 1], [0.05, 0.05, 0.2, 1], [0.08, 0.08, 0.28, 1], [0.15, 0.14, 0.25, 1], [0.06, 0.06, 0.19, 1]];
    for (var i = 0; i < 5; i++) gl.uniform4fv(u['u_color' + i], cs[i]);
    gl.uniform1f(u.u_darkMode, props && props.isDark === false ? 0 : 1);
    gl.uniform1f(u.u_lightOffset, props ? props.lightOffset || 0 : 0);
    gl.uniform1f(u.u_saturateOffset, props ? props.saturateOffset || 0 : 0);
    gl.uniform2f(u.u_resolution, 640, 400);
    this.gl = gl;
    this.u = u;
    this.t = 2 + Math.random() * 4;
    this.level = 0;
    this.beat = 0;
    this.ok = true;
  }
  Hyper.prototype.render = function (dt, playing, t) {
    if (!this.ok) return;
    var beatLen = 60 / 128;
    var ph = t / beatLen;
    var pulse = Math.exp(-(ph - Math.floor(ph)) * 6) * (Math.floor(ph) % 4 === 0 ? 1 : 0.6);
    var lvlT = playing ? (t < 7.5 ? 0.34 : t < 30 ? 0.62 : t < 56 ? 0.8 : 0.42) : 0;
    var beatT = playing ? pulse * lvlT * 0.9 : 0;
    var rate = function (k) { return 1 - Math.exp(-k * dt); };
    this.level += (lvlT - this.level) * rate(lvlT > this.level ? 6 : 2.4);
    this.beat += (beatT - this.beat) * rate(beatT > this.beat ? 18 : 5);
    this.t += dt;
    var gl = this.gl;
    gl.uniform1f(this.u.u_time, this.t);
    gl.uniform1f(this.u.u_musicLevel, this.level);
    gl.uniform1f(this.u.u_beat, this.beat);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  };

  /* ---------- 仿真本体 ---------- */
  /* opts：frame 决定缩放宽度的容器；mini 为示意桌面里的小窗口（开在播放页，关闭 / 最小化 / 提示交给外层） */
  function Sim(host, opts) {
    opts = opts || {};
    this.host = host;
    this.opts = opts;
    this.mini = !!opts.mini;
    this.frame = opts.frame || host.closest('.dwin');
    this.route = 'home';
    this.setting = 'appearance';
    this.np = false;
    this.os = opts.os || 'win';
    this.theme = document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
    this.listeners = [];
  }
  var source = null;
  /* 直接从磁盘打开（file://）时浏览器不让 fetch，改读同一份内容包成的 .js（tools/file-fallback.mjs 生成） */
  function viaScript(lang) {
    return new Promise(function (resolve, reject) {
      var have = function () { return window.NP_SIM_SRC && window.NP_SIM_SRC[lang]; };
      if (have()) { resolve(have()); return; }
      var s = document.createElement('script');
      s.src = BASE + 'desktop/sim-' + lang + '.js';
      s.onload = function () { if (have()) resolve(have()); else reject(new Error('empty')); };
      s.onerror = reject;
      document.head.appendChild(s);
    });
  }
  function fetchSource() {
    if (source) return source;
    var lang = NP.en ? 'en' : 'zh';
    var get = location.protocol === 'file:' ? viaScript(lang) : fetch(BASE + 'desktop/sim-' + lang + '.html').then(function (r) { if (!r.ok) throw new Error(r.status); return r.text(); }).catch(function () { return viaScript(lang); });
    source = get.then(function (text) {
      text = text.split('{{C}}').join(BASE + 'img/covers/').split('{{I}}').join(BASE + 'img/desktop/app-icon.png').split('{{K}}').join(BASE + 'img/desktop/icons/');
      var box = document.createElement('div');
      box.innerHTML = text;
      var parts = {};
      $$('template[data-part]', box).forEach(function (t) { parts[t.getAttribute('data-part')] = t.innerHTML; });
      return { parts: parts, meta: JSON.parse($('script[data-part="meta"]', box).textContent) };
    });
    source.catch(function () { source = null; });
    return source;
  }
  Sim.prototype.load = function () {
    var self = this;
    if (this.loading) return;
    this.loading = true;
    fetchSource().then(function (src) {
      self.parts = src.parts;
      self.meta = src.meta;
      self.mount();
    }).catch(function () { self.loading = false; self.frame.classList.add('is-fallback'); });
  };
  Sim.prototype.mount = function () {
    var self = this;
    var root = this.host.attachShadow({ mode: 'open' });
    this.root = root;
    var link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = BASE + 'desktop/sim.css';
    var style = document.createElement('style');
    style.textContent = EXTRA;
    root.appendChild(link);
    root.appendChild(style);
    var done = false;
    var go = function () {
      if (done) return;
      done = true;
      self.build();
      // 字体就绪后再揭开，避免先显示回退字体
      var reveal = function () { requestAnimationFrame(function () { self.frame.classList.add('is-live'); }); };
      if (document.fonts && document.fonts.load) {
        Promise.all(['400 14px MiSans', '700 14px MiSans', '24px "Material Symbols Rounded"', '850 30px MiSans'].map(function (f) { return document.fonts.load(f, f.indexOf('Material') > 0 ? 'home' : '音理 Neri'); }))
          .then(reveal, reveal);
        setTimeout(reveal, 2500);
      } else reveal();
    };
    link.onload = go;
    link.onerror = function () { self.frame.classList.add('is-fallback'); };
  };
  Sim.prototype.applyTheme = function (animate, origin) {
    var self = this;
    var vars = this.meta[this.theme];
    var cls = 'dsim ' + this.theme + '-theme platform-' + (this.os === 'mac' ? 'mac' : this.os === 'linux' ? 'linux' : 'windows') + (this.np ? ' np-open' : '');
    var commit = function () {
      self.host.style.cssText = vars;
      self.host.className = cls;
      self.layout();
    };
    if (!animate || reduced || !this.host.animate) { commit(); return; }
    // 桌面版：圆形扩散切换主题。这里先拍一层旧画面的纯色遮罩，再从点击处展开
    var ripple = document.createElement('div');
    ripple.className = 'dsim-ripple';
    ripple.style.background = getComputedStyle(this.host).getPropertyValue('--md-background') || '';
    this.root.appendChild(ripple);
    commit();
    var r = Math.hypot(Math.max(origin.x, W - origin.x), Math.max(origin.y, H - origin.y));
    var a = ripple.animate({ clipPath: ['circle(' + r + 'px at ' + origin.x + 'px ' + origin.y + 'px)', 'circle(0px at ' + origin.x + 'px ' + origin.y + 'px)'] }, { duration: 500, easing: 'cubic-bezier(0.2, 0, 0, 1)' });
    a.onfinish = function () { ripple.remove(); };
  };
  Sim.prototype.layout = function () {
    var w = this.frame.clientWidth;
    if (!w) return;
    this.k = w / W;
    this.host.style.transform = 'scale(' + this.k.toFixed(5) + ')';
  };
  Sim.prototype.q = function (sel, r) { return (r || this.root).querySelector(sel); };
  Sim.prototype.qa = function (sel, r) { return Array.prototype.slice.call((r || this.root).querySelectorAll(sel)); };

  Sim.prototype.build = function () {
    var self = this;
    var root = this.root;
    var app = document.createElement('div');
    app.id = 'app';
    app.className = 'dsim-app';
    app.innerHTML = this.parts.shell;
    root.appendChild(app);
    this.app = app;
    var toast = document.createElement('div');
    toast.className = 'dsim-toast';
    toast.setAttribute('role', 'status');
    root.appendChild(toast);
    this.toastEl = toast;
    var layout = this.q('.app-layout');
    layout.insertAdjacentHTML('beforeend', this.parts.np);
    layout.insertAdjacentHTML('beforeend', this.parts['tb-np']);
    this.qa('.title-bar').forEach(function (tb) {
      tb.classList.remove('tb-transitioning', 'tb-opening', 'tb-closing');
      tb.insertAdjacentHTML('afterbegin', '<div class="tb-traffic-safe-area"><i class="tl tl--close" data-act="close"></i><i class="tl tl--min" data-act="min"></i><i class="tl tl--max" data-act="max"></i></div>');
    });
    this.content = this.q('main.content');
    if (this.mini) { this.np = true; this.host.classList.add('np-open'); }
    this.applyTheme(false);
    this.setRoute('home', true);
    this.bindShell();
    this.bindNP();
    this.bindWheel();
    P.on(function () { self.sync(); });
    NP.addFrame(function (dt) { self.tick(dt); }, 9);
    NP.onResize(function () { self.layout(); });
    new MutationObserver(function () {
      var t = document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
      if (t !== self.theme) { self.theme = t; self.applyTheme(false); }
    }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    NP.whenVisible(this.frame, function () { self.visible = true; P.startPreview(); }, function () { self.visible = false; }, '40px 0px');
    this.ready = true;
    this.listeners.forEach(function (fn) { fn(self.np ? 'np' : self.route === 'settings' ? self.setting : self.route); });
    this.sync();
  };

  Sim.prototype.toast = function (msg) {
    if (this.opts.toast) { this.opts.toast(msg); return; }
    var t = this.toastEl;
    clearTimeout(t._h);
    var show = function () { t.textContent = msg; t.classList.add('is-on'); t._h = setTimeout(function () { t.classList.remove('is-on'); }, 2600); };
    if (t.classList.contains('is-on')) { t.classList.remove('is-on'); t._h = setTimeout(show, 220); } else show();
  };
  Sim.prototype.na = function (what) { this.toast(what ? L('网页演示里没有接入「' + what + '」', '“' + what + '” isn’t wired up in this web demo') : L('这里是网页演示，这一项没有接入', 'This part isn’t wired up in the web demo')); };
  Sim.prototype.onView = function (fn) { this.listeners.push(fn); };
  /* 侧边栏归属：歌单、歌手、统计这些详情页都挂在音乐库下面（路由 meta.section） */
  function sectionOf(r) { return r === 'home' || r === 'explore' || r === 'settings' ? r : 'library'; }
  Sim.prototype.view = function () {
    if (this.np) return 'np';
    if (this.route === 'settings') return this.setting;
    return /^r-playlist/.test(this.route) ? 'playlist' : sectionOf(this.route);
  };
  Sim.prototype.emitView = function () {
    var v = this.view();
    this.listeners.forEach(function (fn) { fn(v); });
  };

  /* 路由：换掉 main.content，过渡同桌面版；进详情页时记下来处，返回键按记录退回 */
  Sim.prototype.navigate = function (r) {
    if (r === this.route) return;
    this.hist = (this.hist || []).concat([this.route]).slice(-20);
    this.setRoute(r, false, true);
  };
  Sim.prototype.back = function () {
    var prev = (this.hist || []).pop();
    this.setRoute(prev || 'library', false, true);
  };
  Sim.prototype.setRoute = function (r, instant, keepHist) {
    var self = this;
    if (!keepHist) this.hist = [];
    var page = r;
    var html = this.parts['page-' + page];
    if (!html) return;
    var c = this.content;
    this.route = r;
    var navIdx = { home: 0, explore: 1, library: 2, settings: 3 }[sectionOf(r)];
    this.qa('.side-nav .nav-item').forEach(function (it, i) {
      var on = i === navIdx;
      it.classList.toggle('active', on);
      var ic = it.querySelector('.material-symbols-rounded');
      if (ic) ic.classList.toggle('filled', on);
      if (on) it.setAttribute('aria-current', 'page'); else it.removeAttribute('aria-current');
    });
    var swap = function () {
      c.innerHTML = html;
      c.scrollTop = 0;
      if (page === 'settings') self.fillSettings();
      self.bindPage(page);
      if (!instant && !reduced) {
        c.classList.remove('dsim-leave');
        c.classList.add('dsim-enter');
        void c.offsetWidth;
        c.classList.remove('dsim-enter');
      }
      self.sync();
    };
    if (instant || reduced) swap();
    else { c.classList.add('dsim-leave'); clearTimeout(this._route); this._route = setTimeout(swap, 100); }
    this.emitView();
  };
  /* 设置：框架只导出一份，21 个分区的内容区各自一份（set-<id>），切换时只换内容区 */
  var SET_ALIAS = { fx: 'audio_effects' };
  Sim.prototype.markNav = function () {
    var key = this.setting;
    this.qa('.settings-nav-item[data-dsim-set]', this.content).forEach(function (it) {
      var on = it.getAttribute('data-dsim-set') === key;
      it.classList.toggle('active', on);
      if (on) it.setAttribute('aria-current', 'page'); else it.removeAttribute('aria-current');
    });
  };
  Sim.prototype.fillSettings = function () {
    var cur = this.q('.settings-content', this.content);
    if (cur) cur.innerHTML = this.parts['set-' + this.setting] || '';
    this.markNav();
  };
  Sim.prototype.setSetting = function (key) {
    var self = this;
    key = SET_ALIAS[key] || key;
    if (!this.parts['set-' + key]) return;
    if (this.route !== 'settings') { this.setting = key; this.setRoute('settings'); return; }
    if (key === this.setting) return;
    this.setting = key;
    var cur = this.q('.settings-content', this.content);
    this.markNav();
    cur.classList.add('dsim-leave');
    setTimeout(function () {
      cur.innerHTML = self.parts['set-' + key];
      cur.scrollTop = 0;
      cur.classList.remove('dsim-leave');
      cur.classList.add('dsim-enter');
      void cur.offsetWidth;
      cur.classList.remove('dsim-enter');
      self.bindSettingsContent(key);
      self.sync();
    }, reduced ? 0 : 90);
    this.emitView();
  };

  Sim.prototype.bindShell = function () {
    var self = this;
    this.qa('.side-nav .nav-item').forEach(function (it, i) {
      it.addEventListener('click', function () { self.setRoute(['home', 'explore', 'library', 'settings'][i]); });
    });
    var mp = this.q('.mini-player');
    this.mp = {
      root: mp, fill: this.q('.progress-fill', mp), thumb: this.q('.progress-thumb-track', mp), time: this.q('.mp-time', mp),
      play: this.q('.mp-play-btn', mp), cover: this.q('.mp-cover', mp),
    };
    this.mp.play.addEventListener('click', function (e) { e.stopPropagation(); P.toggle(); });
    var track = this.q('.progress-track', mp);
    var seeking = false;
    var seekAt = function (e) { var r = track.getBoundingClientRect(); P.seek(M.clamp((e.clientX - r.left) / r.width) * P.dur); };
    track.addEventListener('pointerdown', function (e) { e.stopPropagation(); seeking = true; NP.capture(track, e); seekAt(e); });
    track.addEventListener('pointermove', function (e) { if (seeking) seekAt(e); });
    track.addEventListener('pointerup', function () { seeking = false; });
    this.qa('.mp-center .mp-ctrl-btn', mp).forEach(function (b, i) {
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        if (i === 0 || i === 4) self.toggleMode(i === 0 ? 'shuffle' : 'repeat');
        else self.toast(L('网页演示只带了《星轨》的音频，切歌就先不演示了', 'Only “星轨” ships with audio here, so skipping is off'));
      });
    });
    this.qa('.mp-right .mp-tool-btn', mp).forEach(function (b) {
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        var name = (b.querySelector('.material-symbols-rounded') || {}).textContent;
        if (name === 'keyboard_arrow_up') self.openNP(true);
        else if (name === 'lyrics') {
          var sc = NP.desktop && NP.desktop.scene;
          if (sc) { sc.setOpen(!sc.st.open); b.setAttribute('aria-pressed', sc.st.open ? 'true' : 'false'); b.classList.toggle('active', sc.st.open); }
          self.toast(L('桌面歌词在下面那块示意桌面里，可以拖动和锁定', 'Desktop lyrics live in the mock desktop below — drag or lock them there'));
        } else self.na(b.getAttribute('title'));
      });
    });
    this.q('.mp-body', mp).addEventListener('click', function (e) { if (!e.target.closest('button')) self.openNP(true); });
    this.qa('.title-bar').forEach(function (tb) {
      var ctrls = tb.querySelectorAll('.tb-ctrl');
      if (ctrls[0]) ctrls[0].addEventListener('click', function () { self.minimize(); });
      if (ctrls[1]) ctrls[1].addEventListener('click', function () { self.toggleMax(tb); });
      if (ctrls[2]) ctrls[2].addEventListener('click', function () { self.closeToTray(); });
      tb.querySelectorAll('.tl').forEach(function (t) {
        t.addEventListener('click', function () { var a = t.getAttribute('data-act'); if (a === 'min') self.minimize(); else if (a === 'close') self.closeToTray(); else self.toast(L('网页里的窗口就不全屏了', 'The web demo stays windowed')); });
      });
    });
  };
  Sim.prototype.minimize = function () {
    var h = this.host;
    if (this.opts.onMinimize) { this.opts.onMinimize(); return; }
    if (reduced) { this.toast(L('已最小化到任务栏', 'Minimized')); return; }
    h.classList.add('is-min');
    var self = this;
    setTimeout(function () { h.classList.add('is-restore'); h.classList.remove('is-min'); setTimeout(function () { h.classList.remove('is-restore'); }, 560); self.toast(L('最小化后从任务栏点回来，播放不受影响', 'Restored from the taskbar — playback never stopped')); }, 900);
  };
  Sim.prototype.closeToTray = function () {
    var h = this.host;
    var self = this;
    if (this.opts.onClose) { this.opts.onClose(); return; }
    h.classList.add('is-min');
    setTimeout(function () { h.classList.add('is-restore'); h.classList.remove('is-min'); setTimeout(function () { h.classList.remove('is-restore'); }, 560); self.toast(L('开启「关闭到托盘」后，关掉主窗口音乐也不会停；从托盘菜单可以叫回来', 'With “close to tray” on, closing the window keeps the music going; bring it back from the tray menu')); }, 1100);
  };
  Sim.prototype.toggleMax = function (tb) {
    this.maxed = !this.maxed;
    var self = this;
    this.qa('.title-bar .tb-ctrl:nth-child(2) svg').forEach(function (svg) {
      svg.style.transition = 'opacity 120ms, transform 160ms cubic-bezier(.05,.7,.1,1)';
      svg.style.opacity = '0';
      svg.style.transform = 'scale(.7)';
      setTimeout(function () {
        svg.innerHTML = self.maxed ? '<path d="M4.5 3V2.9A1.4 1.4 0 0 1 5.9 1.5h3.2A1.4 1.4 0 0 1 10.5 2.9v3.2A1.4 1.4 0 0 1 9.1 7.5H9"></path><rect x="1.5" y="4" width="6.5" height="6.5" rx="1.4"></rect>' : '<rect x="2.5" y="2.5" width="7" height="7" rx="1.6"></rect>';
        svg.style.opacity = '1';
        svg.style.transform = 'none';
      }, 130);
    });
    this.toast(this.maxed ? L('已最大化（网页里窗口大小不变）', 'Maximized (the web demo keeps its size)') : L('已还原', 'Restored'));
  };
  Sim.prototype.toggleMode = function (m) {
    if (m === 'shuffle') { P.shuffle = !P.shuffle; this.toast(P.shuffle ? L('随机播放：开', 'Shuffle on') : L('随机播放：关', 'Shuffle off')); }
    else { P.repeat = (P.repeat + 1) % 3; this.toast([L('循环：关闭', 'Repeat off'), L('列表循环', 'Repeat all'), L('单曲循环', 'Repeat one')][P.repeat]); }
    P.emit();
  };

  /* 各页面里的可点元素：快照时点过一遍的跳转元素带 data-dsim-go，其余按种类接上 */
  var LIB_TABS = ['library', 'library-fav', 'library-dl'];
  var LIB_CATS = ['library', 'library-artists', 'library-files'];
  function isStartrail(el) { return /星轨|Startrail/.test(el.textContent || ''); }
  Sim.prototype.playRow = function (row) {
    if (isStartrail(row)) { P.seek(0); if (!P.audible) P.play(); this.toast(L('开始播放《星轨》', 'Playing “Startrail”')); }
    else this.toast(L('网页演示只带了《星轨》的音频，其他曲目点了不会出声', 'Only “星轨” ships with audio here, so other tracks stay silent'));
  };
  /* 列表里「正在播放」的那一行（亮起 + 均衡条）只留给《星轨》，和底部播放栏一致 */
  Sim.prototype.markPlaying = function (c) {
    var bars = null;
    this.qa('.track-item', c).forEach(function (row, i) {
      var idx = row.querySelector('.track-index');
      var eq = idx && idx.querySelector('.equalizer-bars');
      if (eq && !bars) bars = eq.outerHTML;
      var star = /^local:startrail\b/.test(row.getAttribute('data-track-key') || '');
      if (!star && row.classList.contains('active')) {
        row.classList.remove('active');
        if (eq) idx.innerHTML = '<span class="index-num">' + (i + 1) + '</span>';
      }
      row._star = star;
    });
    if (!bars) bars = '<div class="equalizer-bars"><span class="bar"></span><span class="bar"></span><span class="bar"></span></div>';
    this.qa('.track-item', c).forEach(function (row) {
      if (!row._star) return;
      var idx = row.querySelector('.track-index');
      var num = idx && idx.querySelector('.index-num');
      if (!row.classList.contains('active') && num && idx.className === 'track-index') { row.classList.add('active'); idx.innerHTML = bars; }
    });
  };
  Sim.prototype.bindPage = function (page) {
    var self = this;
    var c = this.content;
    this.markPlaying(c);
    if (page === 'settings') {
      this.qa('.settings-nav-item[data-dsim-set]', c).forEach(function (b) {
        b.addEventListener('click', function () { self.setSetting(b.getAttribute('data-dsim-set')); });
      });
      this.qa('.settings-search input', c).forEach(function (i) { i.addEventListener('keydown', function (e) { e.stopPropagation(); }); });
      this.bindSettingsContent(this.setting);
      return;
    }
    var bound = [];
    var on = function (el, fn) { bound.push(el); el.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); fn(e); }); };
    this.qa('[data-dsim-go]', c).forEach(function (b) { on(b, function () { self.navigate(b.getAttribute('data-dsim-go')); }); });
    this.qa('.back-btn', c).forEach(function (b) { on(b, function () { self.back(); }); });
    this.qa('.feed-song, .track-item, .daily-card', c).forEach(function (b) { on(b, function () { self.playRow(b); }); });
    this.qa('.play-all-btn', c).forEach(function (b) {
      on(b, function () {
        var rows = self.qa('.track-item', c);
        var star = rows.filter(isStartrail)[0];
        if (star) self.playRow(star); else self.toast(L('这个歌单里没有《星轨》，网页演示只带了这一首的音频', 'This playlist doesn’t include “星轨”, the only track with audio here'));
      });
    });
    if (/^library/.test(page)) {
      this.qa('.tab-chip', c).forEach(function (b, i) {
        on(b, function () { if (LIB_TABS[i]) self.navigate(LIB_TABS[i]); else self.na(b.textContent.trim().replace(/^[a-z_]+/, '')); });
      });
      this.qa('.local-category-chip', c).forEach(function (b, i) { on(b, function () { if (LIB_CATS[i]) self.navigate(LIB_CATS[i]); }); });
    }
    var form = this.q('.home-search', c);
    if (form) form.addEventListener('submit', function (e) { e.preventDefault(); self.setRoute('explore'); });
    this.qa('input[type="text"], input[type="search"]', c).forEach(function (i) { i.addEventListener('keydown', function (e) { e.stopPropagation(); }); });
    this.bindChips(c);
    this.qa('button, [role="button"], .playlist-item, .continue-card, .feed-playlist', c).forEach(function (b) {
      if (bound.indexOf(b) >= 0 || b._chip || b.closest('[data-dsim-go], .track-item, .feed-song')) return;
      b.addEventListener('click', function (e) { e.preventDefault(); self.na(b.getAttribute('title') || b.getAttribute('aria-label') || b.textContent.trim().replace(/^[a-z_]+(?=[^a-z_])/, '').slice(0, 16)); });
    });
  };
  /* 一组里只有一个亮着的按钮（分段选择、筛选标签）：点哪个亮哪个，只改外观 */
  Sim.prototype.bindChips = function (root) {
    var groups = [];
    this.qa('button.active, button.selected, button[aria-pressed="true"], button[aria-checked="true"]', root).forEach(function (b) {
      var p = b.parentElement;
      if (!p || groups.indexOf(p) >= 0 || b.closest('.settings-nav, .side-nav')) return;
      var sib = Array.prototype.filter.call(p.children, function (x) { return x.tagName === 'BUTTON'; });
      if (sib.length < 2) return;
      groups.push(p);
      var cls = b.classList.contains('selected') ? 'selected' : 'active';
      sib.forEach(function (x) {
        x._chip = true;
        x.addEventListener('click', function () {
          sib.forEach(function (y) {
            var me = y === x;
            y.classList.toggle(cls, me);
            if (y.hasAttribute('aria-pressed')) y.setAttribute('aria-pressed', me ? 'true' : 'false');
            if (y.hasAttribute('aria-checked')) y.setAttribute('aria-checked', me ? 'true' : 'false');
          });
        });
      });
    });
  };
  /* 设置分区里的通用控件：开关与滑块是原生 input，折叠标题收放下面的内容，分段按钮切换高亮，其余给提示 */
  Sim.prototype.bindSettingsContent = function (key) {
    var self = this;
    var box = this.q('.settings-content', this.content);
    if (!box) return;
    this.qa('.section-label.clickable', box).forEach(function (h) {
      var body = h.nextElementSibling;
      var arrow = h.querySelector('.section-arrow');
      if (!body) return;
      h.addEventListener('click', function () {
        var open = !(arrow ? arrow.classList.contains('expanded') : body.style.display !== 'none');
        if (arrow) arrow.classList.toggle('expanded', open);
        if (reduced || !body.animate) { body.style.display = open ? '' : 'none'; return; }
        body.style.display = '';
        body.style.overflow = 'hidden';
        var hgt = body.scrollHeight;
        var a = body.animate(open ? [{ height: '0px', opacity: 0 }, { height: hgt + 'px', opacity: 1 }] : [{ height: hgt + 'px', opacity: 1 }, { height: '0px', opacity: 0 }], { duration: open ? 300 : 220, easing: 'cubic-bezier(0.2, 0, 0, 1)' });
        a.onfinish = function () { body.style.overflow = ''; if (!open) body.style.display = 'none'; };
      });
    });
    this.qa('input[type="range"]', box).forEach(function (i) { i.addEventListener('pointerdown', function (e) { e.stopPropagation(); }); });
    this.qa('input[type="text"], input[type="search"], input[type="number"], textarea', box).forEach(function (i) { i.addEventListener('keydown', function (e) { e.stopPropagation(); }); });
    if (key === 'appearance') {
      this.qa('button', box).forEach(function (b) {
        var ic = b.querySelector('.material-symbols-rounded');
        var name = ic ? ic.textContent.trim() : '';
        if (name !== 'dark_mode' && name !== 'light_mode' && name !== 'brightness_auto' && name !== 'contrast') return;
        b._theme = true;
        b.addEventListener('click', function (e) {
          var mode = name === 'dark_mode' ? 'dark' : name === 'light_mode' ? 'light' : (document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light');
          var r = self.host.getBoundingClientRect();
          var o = { x: (e.clientX - r.left) / self.k, y: (e.clientY - r.top) / self.k };
          if (mode !== self.theme) { self.theme = mode; self.applyTheme(true, o); }
        });
      });
    } else if (key === 'audio_effects') {
      this.bindFx(box);
    } else if (key === 'shortcuts') {
      this.qa('.shortcut-settings button', box).forEach(function (b) { b._fx = true; b.addEventListener('click', function () { self.toast(L('网页里不录制快捷键；在演示桌面下方可以试试应用内快捷键', 'Shortcut recording is off here — try the in-app shortcuts below the mock desktop')); }); });
      if (this.os === 'mac') this.qa('kbd, .shortcut-key, .key-cap', box).forEach(function (k) { if (/^Ctrl$/.test(k.textContent.trim())) k.textContent = '⌘'; });
    }
    this.bindChips(box);
    this.qa('button, .setting-card[style*="pointer"]', box).forEach(function (b) {
      if (b._chip || b._theme || b._fx || b.closest('.section-label')) return;
      b.addEventListener('click', function (e) {
        e.preventDefault();
        var t = b.getAttribute('aria-label') || b.getAttribute('title') || (b.querySelector('.setting-title') || b).textContent.trim().replace(/^[a-z_]+(?=[^a-z_])/, '');
        self.na(t.slice(0, 20));
      });
    });
  };

  /* 音效与倍速：与手机里那页共享同一份 DSP 状态。滑块与开关按桌面版 AudioEffectsSettings.vue 里的先后顺序对应 */
  var S_ = function () { return NP.sound.state; };
  /* 听感调节（listening.ts）：滑块位置与参数之间用同一对 strength / position 换算 */
  function strength(v) { var m = Math.abs(v) / 100; return (v < 0 ? -1 : 1) * m * (0.4 + 0.6 * m); }
  function position(v) { var m = Math.min(1, Math.abs(v)); return (v < 0 ? -1 : 1) * (Math.sqrt(0.16 + 2.4 * m) - 0.4) / 1.2 * 100; }
  function protect(S) { S.sec.dynamics = true; S.limiter = true; S.ceiling = Math.min(-0.5, S.ceiling); }
  function tune(field, scale, neutral, sec) {
    return {
      get: function (S) { return S.sec[sec] === false ? 0 : position((S[field] - neutral) / scale); },
      set: function (S, v) {
        if (S.sec[sec] === false) NP.sound.resetSection(sec);
        S.sec[sec] = true;
        S[field] = neutral + scale * strength(v);
        if (field === 'width') S.mono = false;
        if (field === 'vocal' && v > 0) S.vocalRemoval = 0;
        protect(S);
      },
      listen: true,
    };
  }
  function key(k, sec) { return { get: function (S) { return S[k]; }, set: function (S, v) { S[k] = v; if (sec && S.sec[sec] === false) S.sec[sec] = true; }, sec: sec }; }
  function eqk(i) { return { get: function (S) { return S.eq[i]; }, set: function (S, v) { S.eq[i] = v; S.sec.equalizer = true; } }; }
  function spk(k) { return { get: function (S) { return S[k]; }, set: function (S, v) { S[k] = v; }, speaker: true }; }
  var FX_RANGES = [
    tune('bass', 12, 0, 'tone'), tune('vocal', 1, 0, 'tone'), tune('treble', 12, 0, 'tone'), tune('warmth', 1, 0, 'tone'), tune('width', 1, 1, 'space'),
    key('preamp', 'equalizer'), eqk(0), eqk(1), eqk(2), eqk(3), eqk(4), eqk(5), eqk(6), eqk(7), eqk(8), eqk(9),
    key('bass', 'tone'), key('bassFreq', 'tone'), key('treble', 'tone'), key('trebleFreq', 'tone'), key('loudComp', 'tone'),
    key('virtualBass', 'tone'), key('vbFreq', 'tone'), key('warmth', 'tone'), key('clarity', 'tone'), key('clarityFreq', 'tone'), key('vocal', 'tone'), key('vocalRemoval', 'tone'),
    key('width', 'space'), { get: function (S) { return S.monoBass; }, set: function (S, v) { S.monoBass = v < 40 ? 0 : v; } }, key('crossfeed', 'space'), key('surround', 'space'),
    key('reverb', 'space'), key('reverbRoom', 'space'), key('reverbDamping', 'space'), key('reverbPreDelay', 'space'),
    key('compThr', 'dynamics'), key('compRatio', 'dynamics'), key('compAttack', 'dynamics'), key('compRelease', 'dynamics'), key('compKnee', 'dynamics'), key('compMakeup', 'dynamics'),
    key('outGain', 'dynamics'), key('ceiling', 'dynamics'), key('release', 'dynamics'),
    { get: function (S) { return S.spkProt; }, set: function (S, v) { S.spkProt = v < 40 ? 0 : v; }, speaker: true }, spk('spkBass'), spk('spkLoud'), spk('spkClarity'), spk('spkExpand'),
    { get: function () { return P.speed; }, set: function (S, v) { P.setSpeed(v); S.sec.speed = true; if (S.linked) P.setPitch(M.clamp(v, 0.25, 2)); }, rate: true },
    { get: function () { return P.pitch; }, set: function (S, v) { P.setPitch(v); S.sec.speed = true; }, rate: true },
    null,
  ];
  var FX_CHECKS = [
    { get: function (S) { return S.on; }, set: function (S, v) { S.on = v; } },
    { get: function (S) { return S.sec.equalizer !== false; }, set: function (S, v) { S.sec.equalizer = v; } },
    { get: function (S) { return S.peqOn; }, set: function (S, v) { S.peqOn = v; } },
    { get: function (S) { return S.sec.tone !== false; }, set: function (S, v) { S.sec.tone = v; } },
    { get: function (S) { return S.sec.space !== false; }, set: function (S, v) { S.sec.space = v; } },
    { get: function (S) { return S.mono; }, set: function (S, v) { S.mono = v; } },
    { get: function (S) { return S.swap; }, set: function (S, v) { S.swap = v; } },
    { get: function (S) { return S.sec.dynamics !== false; }, set: function (S, v) { S.sec.dynamics = v; } },
    { get: function (S) { return S.compOn; }, set: function (S, v) { S.compOn = v; } },
    { get: function (S) { return S.limiter; }, set: function (S, v) { S.limiter = v; } },
    { get: function (S) { return !!S.sec.speaker; }, set: function (S, v) { S.sec.speaker = v; if (v) S.on = true; } },
    { get: function (S) { return S.linked; }, set: function (S, v) { S.linked = v; P.setPitch(v ? M.clamp(P.speed, 0.25, 2) : 1); } },
    null,
    { get: function (S) { return S.autoHeadroom; }, set: function (S, v) { S.autoHeadroom = v; } },
  ];
  var SPK_SIZES = ['phone', 'tablet', 'large'];
  /* 显示格式照桌面版：单位跟着初始文字走，小数位按步进，dB 正值带 + 号 */
  function fxFormat(inp, disp) {
    var t0 = disp ? disp.textContent.trim() : '';
    var unit = (/[a-zA-Z%×x]+\s*$/.exec(t0) || [''])[0].trim();
    var sp = /\d\s+[a-zA-Z%]/.test(t0) ? ' ' : '';
    var step = parseFloat(inp.step) || 1;
    var dec = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
    var pct = unit === '%' && parseFloat(inp.max) <= 1;
    var word = t0 && !/\d/.test(t0) ? t0 : '';
    return function (v) {
      if (word && Math.abs(v) < 0.5) return word;
      if (pct) return Math.round(v * 100) + sp + '%';
      if (unit === 'x' || unit === '×') return v.toFixed(2) + unit;
      var s = v.toFixed(dec);
      if (unit === 'dB' && v > 0) s = '+' + s;
      if (!unit && word) s = (v > 0 ? '+' : '') + Math.round(v);
      return s + (unit ? sp + unit : '');
    };
  }
  Sim.prototype.bindFx = function (box) {
    var self = this;
    var changed = function () {
      NP.sound.apply();
      if (NP.fxPage) NP.fxPage.paint();
      if (NP.dsp && NP.dsp.page) NP.dsp.page.paint();
      if (NP.dspChain) NP.dspChain();
      self.syncFx();
    };
    this.fx = { box: box, ranges: [], checks: [], path: this.q('.dsp-frequency-graph svg path', box), graph: this.q('.dsp-frequency-graph svg', box), changed: changed };
    this.qa('input[type="range"]', box).forEach(function (inp, i) {
      var spec = FX_RANGES[i];
      var ctl = inp.closest('.dsp-number-control');
      var disp = ctl && ctl.querySelector('.editable-range-display');
      var range = ctl && ctl.querySelector('.dsp-number-range');
      var fmt = fxFormat(inp, disp);
      var entry = { inp: inp, spec: spec, disp: disp, range: range, fmt: fmt, ctl: ctl };
      self.fx.ranges.push(entry);
      inp.addEventListener('input', function () {
        var v = parseFloat(inp.value);
        self.paintRange(entry, v);
        if (!spec) return;
        var S = S_();
        spec.set(S, v);
        if (!spec.speaker && !spec.rate) { S.preset = 'custom'; S.on = true; }
        changed();
      });
      inp.addEventListener('pointerdown', function (e) { e.stopPropagation(); });
      if (disp) { disp._fx = true; disp.addEventListener('click', function () { self.toast(L('网页里直接拖滑块就好，数值输入框没有接入', 'Drag the slider here — typing a value isn’t wired up')); }); }
    });
    this.qa('input[type="checkbox"]', box).forEach(function (inp, i) {
      var spec = FX_CHECKS[i];
      self.fx.checks.push({ inp: inp, spec: spec });
      inp.addEventListener('change', function () {
        if (!spec) { self.toast(L('网页里只有一个输出设备，按设备记忆就先不演示了', 'There’s only one output here, so per-device memory stays off')); inp.checked = false; return; }
        spec.set(S_(), inp.checked);
        changed();
      });
    });
    this.qa('.effects-preset-card', box).forEach(function (b) {
      b._fx = true;
      var name = (b.querySelector('strong') || b).textContent.trim();
      b.addEventListener('click', function () {
        var p = (NP.sound.presets || []).filter(function (x) { return x.name === name; })[0];
        if (!p) { self.na(name); return; }
        NP.sound.usePreset(p.id);
        changed();
      });
    });
    var master = this.q('.effects-master', box);
    var ab = master && this.qa('button', master).filter(function (b) { return !b.closest('label'); })[0];
    if (ab) {
      ab._fx = true;
      this.fx.ab = ab;
      ab.addEventListener('click', function () { self.fx.abOff = !self.fx.abOff; S_().on = !self.fx.abOff; changed(); });
    }
    this.qa('details', box).forEach(function (d) {
      var sum = d.querySelector('summary');
      var spkSec = /扬声器|Speaker/i.test(sum ? sum.textContent : '');
      Array.prototype.forEach.call(d.querySelectorAll('summary button'), function (b) {
        b._fx = true;
        b.addEventListener('click', function (e) {
          e.preventDefault();
          e.stopPropagation();
          var sec = ['equalizer', null, 'tone', 'space', 'dynamics', 'speaker', 'speed'][self.qa('details', box).indexOf(d)];
          if (sec === 'speed') { P.setSpeed(1); P.setPitch(1); }
          else if (sec) NP.sound.resetSection(sec);
          changed();
        });
      });
      if (spkSec) {
        var sizes = Array.prototype.filter.call(d.querySelectorAll('button'), function (b) { return !b.closest('summary') && !b.classList.contains('editable-range-display'); });
        sizes.forEach(function (b, i) {
          b._fx = true;
          b._chip = true;
          b.addEventListener('click', function () { if (SPK_SIZES[i]) { S_().spkSize = SPK_SIZES[i]; changed(); } });
        });
        self.fx.sizes = sizes;
      }
    });
    this.syncFx();
  };
  Sim.prototype.paintRange = function (r, v) {
    if (r.disp) setText(r.disp, r.fmt(v));
    if (!r.range) return;
    var lo = parseFloat(r.inp.min);
    var hi = parseFloat(r.inp.max);
    var p = M.clamp((v - lo) / (hi - lo));
    var z = lo < 0 && hi > 0 ? M.clamp(-lo / (hi - lo)) : 0;
    var st = r.range.style;
    st.setProperty('--thumb', (p * 100).toFixed(2) + '%');
    st.setProperty('--fill-from', (Math.min(p, z) * 100).toFixed(2) + '%');
    st.setProperty('--fill-to', (Math.max(p, z) * 100).toFixed(2) + '%');
  };
  Sim.prototype.syncFx = function () {
    var f = this.fx;
    if (!f || !f.box.isConnected) return;
    var self = this;
    var S = S_();
    f.ranges.forEach(function (r) {
      if (!r.spec || document.activeElement === r.inp || (self.root && self.root.activeElement === r.inp)) return;
      var v = r.spec.get(S);
      if (v == null || isNaN(v)) return;
      if (Math.abs(parseFloat(r.inp.value) - v) > 1e-6) r.inp.value = v;
      self.paintRange(r, parseFloat(r.inp.value));
    });
    f.checks.forEach(function (c) { if (c.spec) { var on = !!c.spec.get(S); if (c.inp.checked !== on) c.inp.checked = on; } });
    if (f.sizes) f.sizes.forEach(function (b, i) { b.classList.toggle('active', SPK_SIZES[i] === S.spkSize); });
    this.qa('.effects-preset-card', f.box).forEach(function (b) {
      var name = (b.querySelector('strong') || b).textContent.trim();
      var p = (NP.sound.presets || []).filter(function (x) { return x.name === name; })[0];
      var on = !!p && S.on && p.id === S.preset;
      b.classList.toggle('active', on);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    if (f.path) {
      var pts = response(S);
      var max = 0;
      pts.forEach(function (p) { max = Math.max(max, Math.abs(p[1])); });
      var limit = Math.max(12, Math.min(60, Math.ceil(max / 6) * 6));
      var x = function (hz) { return 42 + Math.log10(hz / 20) / 3 * 518; };
      var y = function (g) { return 94 - Math.max(-limit, Math.min(limit, g)) / limit * 72; };
      f.path.setAttribute('d', pts.map(function (p, i) { return (i ? 'L' : 'M') + x(p[0]).toFixed(2) + ',' + y(p[1]).toFixed(2); }).join(' '));
      var gs = f.graph.querySelectorAll('g');
      [-limit, 0, limit].forEach(function (g, i) {
        var grp = gs[i];
        if (!grp) return;
        var ln = grp.querySelector('line');
        var tx = grp.querySelector('text');
        ln.setAttribute('y1', y(g));
        ln.setAttribute('y2', y(g));
        tx.setAttribute('y', y(g) + 4);
        setText(tx, g + ' dB');
      });
    }
  };

  /* 播放页 */
  Sim.prototype.bindNP = function () {
    var self = this;
    var npEl = this.q('.now-playing');
    this.npEl = npEl;
    var tbNp = this.q('.title-bar.tb-np-mode');
    var collapse = this.q('.tb-np-collapse', tbNp);
    if (collapse) collapse.addEventListener('click', function () { self.openNP(false); });
    var more = this.q('.tb-np-more', tbNp);
    if (more) more.addEventListener('click', function () { self.na(more.getAttribute('title')); });
    var ctrls = this.qa('.np-controls button', npEl);
    ctrls.forEach(function (b, i) {
      b.addEventListener('click', function () {
        if (i === 2) P.toggle();
        else if (i === 0 || i === 4) self.toggleMode(i === 0 ? 'shuffle' : 'repeat');
        else self.toast(L('网页演示只带了《星轨》的音频，切歌就先不演示了', 'Only “星轨” ships with audio here, so skipping is off'));
      });
    });
    this.npPlay = ctrls[2];
    this.npShuffle = ctrls[0];
    this.npRepeat = ctrls[4];
    this.qa('.np-toolbar button', npEl).forEach(function (b) {
      b.addEventListener('click', function () {
        var name = (b.querySelector('.material-symbols-rounded') || {}).textContent;
        if (name === 'favorite') { P.liked = !P.liked; P.emit(); self.toast(P.liked ? L('已添加到我喜欢的音乐', 'Added to Liked Songs') : L('已从我喜欢的音乐移除', 'Removed from Liked Songs')); }
        else if (name === 'tune') { self.openNP(false); self.setSetting('fx'); }
        else self.na(b.getAttribute('title'));
      });
    });
    this.wave = {
      box: this.q('.waveform-container', npEl), on: this.q('.wave-active', npEl), off: this.q('.wave-inactive', npEl),
      track: this.q('.thumb-track', npEl), thumb: this.q('.thumb', npEl), phase: 0, amp: 0, drag: false, dragP: 0,
    };
    var wv = this.wave;
    var at = function (e) { var r = wv.box.getBoundingClientRect(); return M.clamp((e.clientX - r.left) / r.width); };
    wv.box.addEventListener('pointerdown', function (e) { e.preventDefault(); wv.drag = true; wv.dragP = at(e); NP.capture(wv.box, e); });
    wv.box.addEventListener('pointermove', function (e) { if (wv.drag) wv.dragP = at(e); });
    wv.box.addEventListener('pointerup', function (e) { if (!wv.drag) return; wv.drag = false; P.seek(at(e) * P.dur); });
    var times = this.qa('.np-time span', npEl);
    this.npTimes = times;
    var canvas = this.q('canvas.hyper-bg', npEl);
    this.hyper = canvas ? new Hyper(canvas, this.meta.hyper) : null;
    var tr = this.q('.lyrics-display-toggle', npEl);
    var player = this.q('.amll-lyric-player', npEl);
    if (tr) tr.addEventListener('click', function () { var on = !tr.classList.contains('active'); tr.classList.toggle('active', on); player.classList.toggle('dsim-no-sub', !on); self.lyr.dirty = true; });
    this.buildLyrics(player);
    if (!this.mini) document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && self.np && self.visible) self.openNP(false); });
  };
  Sim.prototype.openNP = function (on) {
    if (this.np === on) return;
    this.np = on;
    this.host.classList.toggle('np-open', on);
    this.host.classList.toggle('dsim', true);
    var scrim = this.q('.np-underlay-scrim');
    if (scrim) scrim.classList.toggle('np-underlay-scrim--visible', on);
    if (on) { this.lyr.snap = true; this.lyr.dirty = true; }
    this.emitView();
  };

  /* AMLL 式歌词：行按测得的高度排布，当前行对齐在 35% 处，其余行按距离加模糊，弹簧跟随且由近及远错开 */
  Sim.prototype.buildLyrics = function (player) {
    var self = this;
    var LY = NP.LYRICS || [];
    player.classList.add(A.playing);
    var lines = LY.map(function (l, i) {
      var wrap = document.createElement('div');
      wrap.className = A.wrap;
      var line = document.createElement('div');
      line.className = A.line;
      var main = document.createElement('div');
      main.className = A.main;
      var words = l.words.map(function (w) {
        var text = w.text;
        var trail = / $/.test(text);
        var outer = document.createElement('span');
        outer.className = A.word;
        var inner = document.createElement('span');
        inner.textContent = text.replace(/ $/, '');
        outer.appendChild(inner);
        main.appendChild(outer);
        if (trail) main.appendChild(document.createTextNode(' '));
        return { el: inner, s: w.s, e: w.e, w: 0, f: 0 };
      });
      var sub = document.createElement('div');
      sub.className = A.sub;
      sub.textContent = l.tr;
      var sub2 = document.createElement('div');
      sub2.className = A.sub;
      line.appendChild(main);
      line.appendChild(sub);
      line.appendChild(sub2);
      wrap.appendChild(line);
      player.appendChild(wrap);
      wrap.addEventListener('click', function () { P.seek(l.t); });
      return { wrap: wrap, line: line, words: words, y: 0, vy: 0, blur: 5, h: 0, idx: i, act: null };
    });
    this.lyr = { player: player, lines: lines, cur: -2, dirty: true, snap: true };
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(function () { self.lyr.dirty = true; });
  };
  Sim.prototype.measureLyrics = function () {
    var ly = this.lyr;
    var fs = parseFloat(getComputedStyle(ly.player).fontSize) || 30;
    ly.lines.forEach(function (ln) {
      ln.h = ln.wrap.offsetHeight;
      ln.words.forEach(function (w) {
        w.w = Math.max(1, w.el.offsetWidth);
        w.f = fs * 0.62;
        var total = 2 * w.w + w.f;
        var size = total / w.w * 100;
        var b = w.w / total * 100;
        var d = (w.w + w.f) / total * 100;
        w.el.style.maskImage = w.el.style.webkitMaskImage = 'linear-gradient(to right,rgba(0,0,0,var(--bright-mask-alpha, 1.0)) ' + b + '%,rgba(0,0,0,var(--dark-mask-alpha, 1.0)) ' + d + '%)';
        w.el.style.maskRepeat = w.el.style.webkitMaskRepeat = 'no-repeat';
        w.el.style.maskSize = w.el.style.webkitMaskSize = size + '% 100%';
        w.lastP = -1;
      });
    });
    ly.boxH = ly.player.clientHeight || 600;
    ly.dirty = false;
  };
  Sim.prototype.tickLyrics = function (dt, t) {
    var ly = this.lyr;
    if (!ly) return;
    if (ly.dirty) this.measureLyrics();
    var idx = NP.lyricIndexAt(t);
    var lines = ly.lines;
    var a = Math.max(0, idx);
    if (idx !== ly.cur) {
      var prev = ly.cur;
      ly.cur = idx;
      lines.forEach(function (ln, i) {
        var on = i === idx;
        if (ln.act === on) return;
        ln.act = on;
        ln.line.classList.toggle(A.active, on);
        ln.line.firstChild.classList.toggle(A.active, on);
        ln.line.style.transform = on ? 'scale(1)' : 'scale(0.97)';
        ln.line.style.setProperty('--bright-mask-alpha', on ? '1.000' : '0.200');
        ln.line.style.setProperty('--dark-mask-alpha', on ? '0.400' : '0.200');
      });
      ln_delay(lines, prev, idx);
    }
    var anchor = ly.boxH * 0.35;
    var y = anchor;
    for (var i = a - 1; i >= 0; i--) { y -= lines[i].h; lines[i].target = y; }
    y = anchor;
    for (var j = a; j < lines.length; j++) { lines[j].target = y; y += lines[j].h; }
    var k = 1 - Math.exp(-dt * 9);
    lines.forEach(function (ln, i) {
      var d = i - idx;
      var blurT = idx < 0 ? 5 : d === 0 ? 0 : d === 1 ? 3 : d === -1 || d === 2 ? 4.5 : 5;
      if (ly.snap) { ln.y = ln.target; ln.blur = blurT; ln.delay = 0; }
      else if (ln.delay > 0) ln.delay -= dt;
      else { ln.y += (ln.target - ln.y) * k; ln.blur += (blurT - ln.blur) * k; }
      var visible = ln.y > -ln.h - 40 && ln.y < ly.boxH + 40;
      if (!visible && ln.hidden) return;
      ln.hidden = !visible;
      var tf = 'translateY(' + ln.y.toFixed(1) + 'px)';
      if (ln.tf !== tf) { ln.tf = tf; ln.wrap.style.transform = tf; }
      var bf = 'blur(' + ln.blur.toFixed(1) + 'px)';
      if (ln.bf !== bf) { ln.bf = bf; ln.wrap.style.filter = bf; }
      ln.wrap.style.visibility = visible ? '' : 'hidden';
    });
    ly.snap = false;
    lines.forEach(function (ln, i) {
      if (Math.abs(i - idx) > 1 && ln.wordsDone === (i < idx)) return;
      ln.words.forEach(function (w) {
        var p = M.clamp((t - w.s) / Math.max(0.05, w.e - w.s));
        p = Math.round(p * 100) / 100;
        if (p === w.lastP) return;
        w.lastP = p;
        w.el.style.maskPosition = w.el.style.webkitMaskPosition = (-(1 - p) * (w.w + w.f)).toFixed(1) + 'px 0';
      });
      ln.wordsDone = i < idx;
    });
  };
  function ln_delay(lines, prev, idx) {
    // 往下推进时下方的行晚一点出发，像一条被拉动的链
    lines.forEach(function (ln, i) { ln.delay = idx > prev ? Math.max(0, (i - idx)) * 0.045 : 0; });
  }

  Sim.prototype.tick = function (dt) {
    if (!this.ready || !this.visible) return;
    var t = P.t;
    var prog = M.clamp(t / P.dur);
    var mp = this.mp;
    if (mp) {
      mp.fill.style.transform = 'scaleX(' + prog.toFixed(5) + ')';
      mp.thumb.style.transform = 'translateX(' + (prog * 100).toFixed(3) + '%)';
      var sec = Math.floor(t);
      if (sec !== this._sec) { this._sec = sec; setText(mp.time, mmss(t) + ' / ' + mmss(P.dur)); if (this.npTimes[0]) { setText(this.npTimes[0], mmss(t)); setText(this.npTimes[1], mmss(P.dur)); } }
    }
    if (!this.np) return;
    var wv = this.wave;
    if (wv && wv.on) {
      var playing = P.playing && !wv.drag;
      wv.phase += dt / 2 * Math.PI * 2;
      var step = 2 / 0.5 * dt;
      wv.amp = playing ? Math.min(2, wv.amp + step) : Math.max(0, wv.amp - step);
      var p = wv.drag ? wv.dragP : prog;
      var px = p * 500;
      var path = function (a, b) {
        var yv = function (x) { return 4 + Math.sin(x * 0.15 + wv.phase) * wv.amp; };
        if (a >= b) return 'M ' + a + ' 4';
        var d = 'M ' + a.toFixed(2) + ' ' + yv(a).toFixed(3);
        for (var x = Math.floor(a / 2) * 2 + 2; x < b; x += 2) d += ' L ' + x + ' ' + yv(x).toFixed(3);
        return d + ' L ' + b.toFixed(2) + ' ' + yv(b).toFixed(3);
      };
      wv.off.setAttribute('d', path(px, 500));
      wv.on.setAttribute('d', path(0, px));
      wv.track.style.transform = 'translate3d(' + (p * 100).toFixed(3) + '%, 0, 0)';
      var off = Math.sin(px * 0.15 + wv.phase) * wv.amp;
      wv.thumb.style.transform = 'translate3d(-50%, calc(-50% + ' + off.toFixed(2) + 'px), 0)';
      wv.thumb.style.width = wv.thumb.style.height = wv.drag ? '12px' : '8px';
    }
    if (this.hyper) this.hyper.render(dt, P.playing, t);
    this.tickLyrics(dt, t);
  };

  Sim.prototype.sync = function () {
    if (!this.ready) return;
    var playing = P.playing && P.audible;
    var self = this;
    var setPlay = function (btn, cls) {
      if (!btn) return;
      var ic = btn.querySelector(cls || '.material-symbols-rounded');
      icon(ic, playing ? 'pause' : 'play_arrow');
      var t = playing ? L('暂停', 'Pause') : L('播放', 'Play');
      btn.setAttribute('title', t);
      btn.setAttribute('aria-label', t);
    };
    setPlay(this.mp && this.mp.play);
    setPlay(this.npPlay);
    if (this.mp && this.mp.cover) this.mp.cover.classList.toggle('playing', playing);
    [[this.npShuffle, P.shuffle], [this.npRepeat, !!P.repeat]].concat(this.mp ? [[this.q('.mp-center .mp-ctrl-btn'), P.shuffle], [this.qa('.mp-center .mp-ctrl-btn')[3], !!P.repeat]] : []).forEach(function (x) {
      if (!x[0]) return;
      x[0].classList.toggle('active', x[1]);
      x[0].setAttribute('aria-pressed', x[1] ? 'true' : 'false');
    });
    this.qa('.np-controls .ctrl-btn:last-child .material-symbols-rounded, .mp-center .mp-ctrl-btn:last-child .material-symbols-rounded').forEach(function (s) { icon(s, P.repeat === 2 ? 'repeat_one' : 'repeat'); });
    var fav = this.q('.fav-btn');
    if (fav) {
      fav.classList.toggle('active', P.liked);
      var fi = fav.querySelector('.material-symbols-rounded');
      if (fi) fi.classList.toggle('filled', P.liked);
    }
    if (this.route === 'settings' && this.setting === 'audio_effects') this.syncFx();
    void self;
  };

  /* 内部可滚动区域吃掉滚轮，不让整页的平滑滚动抢走 */
  Sim.prototype.bindWheel = function () {
    this.host.addEventListener('wheel', function (e) {
      var path = e.composedPath();
      for (var i = 0; i < path.length; i++) {
        var n = path[i];
        if (n === e.currentTarget) break;
        if (n.nodeType !== 1) continue;
        var oy = getComputedStyle(n).overflowY;
        if ((oy === 'auto' || oy === 'scroll') && n.scrollHeight > n.clientHeight + 1) {
          if ((e.deltaY < 0 && n.scrollTop > 0) || (e.deltaY > 0 && n.scrollTop + n.clientHeight < n.scrollHeight - 1)) { e.stopPropagation(); return; }
        }
      }
    }, { passive: true });
  };

  /* 系统：Windows / macOS / Linux 的标题栏差异 */
  Sim.prototype.setOS = function (os) {
    if (this.os === os) return;
    this.os = os;
    if (!this.ready) return;
    this.applyTheme(false);
    if (this.route === 'settings' && this.setting === 'shortcuts') {
      var box = this.q('.settings-content', this.content);
      this.qa('kbd, .shortcut-key, .key-cap', box).forEach(function (k) {
        var t = k.textContent.trim();
        if (os === 'mac' && t === 'Ctrl') k.textContent = '⌘';
        else if (os !== 'mac' && t === '⌘') k.textContent = 'Ctrl';
      });
    }
  };
  Sim.prototype.go = function (view) {
    if (!this.ready) { this.pending = view; return; }
    if (view === 'np') { this.openNP(true); return; }
    this.openNP(false);
    if (view === 'playlist') view = 'r-playlist-local-1';
    if (this.parts['set-' + (SET_ALIAS[view] || view)]) this.setSetting(view);
    else this.setRoute(view);
  };

  NP.desktopSim = {
    init: function () {
      var host = $('#deskSim');
      if (!host || !host.attachShadow || !window.fetch) { var f = host && host.closest('.dwin'); if (f) f.classList.add('is-fallback'); return; }
      var sim = new Sim(host);
      NP.desktopSim.sim = sim;
      NP.whenVisible(host.closest('.dstage') || host, function () { sim.load(); }, null, '1600px 0px');
      sim.onView(function () { if (sim.pending) { var v = sim.pending; sim.pending = null; sim.go(v); } });
      return sim;
    },
    /* 示意桌面里的主窗口：同一套界面，缩小后开在播放页 */
    mini: function (host, opts) {
      if (!host || !host.attachShadow || !window.fetch) return null;
      opts.mini = true;
      var sim = new Sim(host, opts);
      NP.whenVisible(opts.frame, function () { sim.load(); }, null, '1200px 0px');
      return sim;
    },
  };
})();
