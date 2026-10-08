/* NeriPlayer site · gl —— 手机里的流体背景：App 着色器 hyper_background_effect.glsl 的 WebGL 移植 */
(function () {
  'use strict';

  var NP = window.NP;
  var M = NP.math;

  var VS = 'attribute vec2 aPos;\nvoid main(){ gl_Position = vec4(aPos, 0.0, 1.0); }';
  var FS = [
    'uniform vec2 uResolution;',
    'uniform float uAnimTime;',
    'uniform vec4 uBound;',
    'uniform vec3 uPoints[5];',
    'uniform vec4 uColors[5];',
    'uniform float uSaturateOffset;',
    'uniform float uLightOffset;',
    'uniform float uBeatEase;',
    'uniform float uMotionEase;',
    'uniform float uZoom;',
    'uniform float uColorPulse;',
    'uniform vec2 uGlobalMotion;',
    'vec3 rgb2hsv(vec3 c){',
    '  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);',
    '  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));',
    '  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));',
    '  float d = q.x - min(q.w, q.y);',
    '  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1.0e-10)), d / (q.x + 1.0e-10), q.x);',
    '}',
    'vec3 hsv2rgb(vec3 c){',
    '  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);',
    '  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);',
    '  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);',
    '}',
    'float gradientNoise(in vec2 uv){ return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715)))); }',
    'void main(){',
    '  vec2 fragCoord = gl_FragCoord.xy;',
    '  vec2 vUv = fragCoord / uResolution;',
    '  vec2 uv = vUv;',
    '  vec2 center = vec2(0.5);',
    '  uv = (uv - center) / uZoom + center;',
    '  float beatWave = sin((vUv.y + uAnimTime * 0.12) * 6.2832) * cos((vUv.x - uAnimTime * 0.10) * 6.2832);',
    '  float radialPulse = sin((distance(vUv, center) * 5.5 - uAnimTime * 0.18) * 6.2832);',
    '  float ribbonWave = sin(((vUv.x * 1.7 + vUv.y * 2.3) - uAnimTime * 0.12) * 6.2832);',
    '  uv += uBeatEase * 0.0110 * vec2(beatWave, -beatWave);',
    '  uv += uBeatEase * 0.0085 * normalize(vUv - center + vec2(1e-4)) * radialPulse;',
    '  uv += uMotionEase * 0.0062 * vec2(ribbonWave, -ribbonWave * 0.75);',
    '  uv += uGlobalMotion;',
    '  uv.xy -= uBound.xy;',
    '  uv.xy /= uBound.zw;',
    '  vec4 colorAccum = vec4(0.0);',
    '  float weightSum = 0.0;',
    '  for (int i = 0; i < 5; i++){',
    '    vec4 pointColor = uColors[i];',
    '    pointColor.rgb *= pointColor.a;',
    '    vec2 delta = uv - uPoints[i].xy;',
    '    float radiusSq = max(uPoints[i].z * uPoints[i].z, 1e-4);',
    '    float weight = 1.0 / (1.0 + dot(delta, delta) / radiusSq * 9.3);',
    '    weight *= weight;',
    '    colorAccum += pointColor * weight;',
    '    weightSum += weight;',
    '  }',
    '  vec4 color = colorAccum / max(weightSum, 1e-5);',
    '  color.rgb /= max(color.a, 1e-5);',
    '  vec3 hsv = rgb2hsv(color.rgb);',
    '  float colorPulse = uColorPulse;',
    '  float boostedSaturation = hsv.y * (1.16 + 0.06 * colorPulse) + 0.030 * colorPulse * uSaturateOffset;',
    '  float darkSaturationLimit = mix(0.66, 0.82, smoothstep(0.34, 0.74, hsv.z));',
    '  hsv.y = clamp(min(boostedSaturation, darkSaturationLimit), 0.0, 1.0);',
    '  hsv.z = clamp((hsv.z - 0.5) * (1.26 + 0.06 * colorPulse) + 0.5 + 0.018 * colorPulse, 0.0, 1.0);',
    '  color.rgb = hsv2rgb(hsv);',
    '  color.rgb += 0.010 * colorPulse * uLightOffset;',
    '  color.rgb *= mix(0.68, 1.10, smoothstep(0.10, 0.92, vUv.y));',
    '  color.rgb = clamp(color.rgb + (gradientNoise(fragCoord.xy) - 0.5) * (5.0 / 255.0), 0.0, 1.0);',
    '  gl_FragColor = vec4(color.rgb, 1.0);',
    '}',
  ].join('\n');

  /* BgEffectPainter.setPhoneDark 的五个色点 */
  var POINTS = [[0.52, 0.48, 0.9], [0.14, 0.32, 0.72], [0.92, 0.28, 0.76], [0.24, 0.88, 0.78], [0.86, 0.86, 0.82]];

  /* 模拟音乐的电平与节拍：播放时跟随真实音频的能量，未播放时安静下来 */
  var music = (NP.music = { level: 0, beat: 0, levelEase: 0, beatEase: 0, motionEase: 0, zoom: 1, colorPulse: 0 });
  var smoothLevel = 0;
  var smoothBeat = 0;
  function ss(a, b, v) { var x = M.clamp((v - a) / (b - a)); return x * x * (3 - 2 * x); }
  NP.stepMusic = function (dt, t, energy, playing) {
    var bpm = 92;
    var phase = (t * bpm) / 60;
    var frac = phase - Math.floor(phase);
    var tb = playing ? (energy != null ? energy.beat : Math.exp(-frac * 5.5) * (Math.floor(phase) % 4 === 0 ? 1 : 0.62) * 0.9) : 0;
    var tl = playing ? (energy != null ? energy.level : M.clamp(0.48 + 0.2 * Math.sin(t * 0.7))) : 0.08;
    smoothLevel = M.damp(smoothLevel, tl, tl > smoothLevel ? 7.7 : 2.8, dt);
    smoothBeat = M.damp(smoothBeat, tb, tb > smoothBeat ? 37 : 7.7, dt);
    music.levelEase = ss(0.04, 0.82, smoothLevel);
    music.beatEase = ss(0.03, 0.62, smoothBeat);
    music.motionEase = M.clamp(0.42 * music.levelEase + 0.82 * music.beatEase);
    music.zoom = 1 + 0.024 * music.levelEase + 0.105 * music.beatEase;
    music.colorPulse = M.clamp(0.68 * music.levelEase + 0.32 * music.beatEase);
  };

  function FluidView(canvas, palette) {
    this.canvas = canvas;
    this.ok = false;
    this.visible = false;
    this.time = 3 + Math.random() * 4;
    this.points = new Float32Array(15);
    this.colors = new Float32Array(20);
    this.palette = palette;
    var attrs = { alpha: false, antialias: false, depth: false, stencil: false, preserveDrawingBuffer: false };
    var gl = null;
    try { gl = canvas.getContext('webgl', attrs) || canvas.getContext('experimental-webgl', attrs); } catch (e) { gl = null; }
    if (!gl) return;
    var hp = gl.getShaderPrecisionFormat && gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT);
    var prec = hp && hp.precision > 0 ? 'precision highp float;\n' : 'precision mediump float;\n';
    function sh(type, src) {
      var s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) { console.warn('[NeriPlayer] shader', gl.getShaderInfoLog(s)); return null; }
      return s;
    }
    var v = sh(gl.VERTEX_SHADER, VS);
    var f = sh(gl.FRAGMENT_SHADER, prec + FS);
    if (!v || !f) return;
    var p = gl.createProgram();
    gl.attachShader(p, v);
    gl.attachShader(p, f);
    gl.bindAttribLocation(p, 0, 'aPos');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) return;
    gl.useProgram(p);
    var buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    var cache = {};
    this.u = function (name) { if (!(name in cache)) cache[name] = gl.getUniformLocation(p, name); return cache[name]; };
    this.gl = gl;
    // 竖屏取色区域与 calcAnimationBound 一致：色块集中在上方约七成
    gl.uniform4f(this.u('uBound'), 0, 0.3, 1, 0.7);
    gl.uniform1f(this.u('uSaturateOffset'), 0.24);
    gl.uniform1f(this.u('uLightOffset'), -0.06);
    var w = 411 * 0.5;
    var h = 914 * 0.5;
    canvas.width = Math.round(w * Math.min(NP.vp.dpr || 1, 1.5));
    canvas.height = Math.round(h * Math.min(NP.vp.dpr || 1, 1.5));
    gl.viewport(0, 0, canvas.width, canvas.height);
    this.ok = true;
    var self = this;
    NP.whenVisible(canvas.closest('.device') || canvas, function () { self.visible = true; }, function () { self.visible = false; }, '120px 0px');
  }

  FluidView.prototype.render = function (dt) {
    if (!this.ok || !this.visible) return;
    var gl = this.gl;
    var u = this.u;
    var m = music;
    this.time += NP.env.reduced ? dt * 0.1 : dt;
    var t = this.time % 62.831852;
    var offset = 0.1 + 0.022 * m.levelEase + 0.108 * m.beatEase;
    var radiusMulti = 1 + 0.045 * m.levelEase + 0.22 * m.beatEase;
    for (var i = 0; i < 5; i++) {
      var x = POINTS[i][0];
      var y = POINTS[i][1];
      x += Math.sin(t + y) * offset;
      y += Math.cos(t + x) * offset;
      var px = x - 0.5 + 1e-4;
      var py = y - 0.5 + 1e-4;
      var len = Math.sqrt(px * px + py * py);
      var push = len > 0 ? (m.beatEase * 0.118) / len : 0;
      this.points[i * 3] = x + px * push;
      this.points[i * 3 + 1] = y + py * push;
      this.points[i * 3 + 2] = POINTS[i][2] * radiusMulti;
      this.colors[i * 4] = this.palette[i][0];
      this.colors[i * 4 + 1] = this.palette[i][1];
      this.colors[i * 4 + 2] = this.palette[i][2];
      this.colors[i * 4 + 3] = 1;
    }
    gl.uniform2f(u('uResolution'), this.canvas.width, this.canvas.height);
    gl.uniform1f(u('uAnimTime'), t);
    gl.uniform3fv(u('uPoints[0]'), this.points);
    gl.uniform4fv(u('uColors[0]'), this.colors);
    gl.uniform1f(u('uBeatEase'), m.beatEase);
    gl.uniform1f(u('uMotionEase'), m.motionEase);
    gl.uniform1f(u('uZoom'), m.zoom);
    gl.uniform1f(u('uColorPulse'), m.colorPulse);
    gl.uniform2f(u('uGlobalMotion'), m.motionEase * 0.006 * Math.sin(t * 1.9), m.motionEase * 0.006 * Math.cos(t * 1.6));
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  };

  NP.FluidView = FluidView;
})();
