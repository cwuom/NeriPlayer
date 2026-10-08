/* NeriPlayer site · core —— 命名空间、数学与颜色工具、视口、平滑滚动、揭示动画 */
(function () {
  'use strict';

  var NP = (window.NP = window.NP || {});

  /* ---------- 数学 ---------- */
  function clamp(v, a, b) {
    if (a === undefined) a = 0;
    if (b === undefined) b = 1;
    return v < a ? a : v > b ? b : v;
  }
  function lerp(a, b, t) { return a + (b - a) * t; }
  function damp(a, b, lambda, dt) { return lerp(a, b, 1 - Math.exp(-lambda * dt)); }
  function seg(p, a, b) { return clamp((p - a) / (b - a)); }
  function smooth(t) { return t * t * (3 - 2 * t); }
  var ease = {
    smooth: smooth,
    inOut: function (t) { return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; },
    out: function (t) { return 1 - Math.pow(1 - t, 3); },
    in: function (t) { return t * t * t; },
    in2: function (t) { return t * t; },
    outExpo: function (t) { return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t); },
    inOutSine: function (t) { return -(Math.cos(Math.PI * t) - 1) / 2; },
    outBack: function (t) { var c = 1.5; return 1 + (c + 1) * Math.pow(t - 1, 3) + c * Math.pow(t - 1, 2); },
  };
  NP.math = { clamp: clamp, lerp: lerp, damp: damp, seg: seg, smooth: smooth, ease: ease };

  /* ---------- 颜色 ---------- */
  function hslToRgb(h, s, l) {
    h = (((h % 360) + 360) % 360) / 360;
    s /= 100;
    l /= 100;
    if (s === 0) return [l, l, l];
    var q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    var p = 2 * l - q;
    function hue(t) {
      if (t < 0) t += 1;
      if (t > 1) t -= 1;
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    }
    return [hue(h + 1 / 3), hue(h), hue(h - 1 / 3)];
  }
  function hexToRgb(hex) {
    var n = parseInt(hex.replace('#', ''), 16);
    return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
  }
  function rgbToHex(c) {
    return '#' + c.map(function (v) {
      var s = Math.round(clamp(v) * 255).toString(16);
      return s.length < 2 ? '0' + s : s;
    }).join('');
  }
  function rgbToHsl(c) {
    var r = c[0], g = c[1], b = c[2];
    var max = Math.max(r, g, b), min = Math.min(r, g, b);
    var h = 0, s = 0, l = (max + min) / 2;
    if (max !== min) {
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
      else if (max === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h *= 60;
    }
    return [h, s * 100, l * 100];
  }
  function palette(list) { return list.map(function (c) { return hslToRgb(c[0], c[1], c[2]); }); }
  function mixPalette(a, b, t, out) {
    out = out || a.map(function () { return [0, 0, 0]; });
    for (var i = 0; i < a.length; i++) {
      out[i][0] = lerp(a[i][0], b[i][0], t);
      out[i][1] = lerp(a[i][1], b[i][1], t);
      out[i][2] = lerp(a[i][2], b[i][2], t);
    }
    return out;
  }
  NP.color = { hslToRgb: hslToRgb, hexToRgb: hexToRgb, rgbToHex: rgbToHex, rgbToHsl: rgbToHsl, palette: palette, mixPalette: mixPalette };

  /* ---------- DOM ---------- */
  NP.$ = function (s, r) { return (r || document).querySelector(s); };
  NP.$$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  NP.absTop = function (el) { return el.getBoundingClientRect().top + window.scrollY; };

  /* ---------- 环境 ---------- */
  function mq(q) { return window.matchMedia ? window.matchMedia(q).matches : false; }
  NP.env = {
    reduced: mq('(prefers-reduced-motion: reduce)'),
    coarse: mq('(hover: none), (pointer: coarse)'),
  };
  NP.en = /^en\b/i.test(document.documentElement.lang);
  NP.L = function (zh, en) { return NP.en ? en : zh; };
  NP.capture = function (el, e) { try { el.setPointerCapture(e.pointerId); } catch (err) { /* 指针已经抬起 */ } };

  /* ---------- 视口 ---------- */
  var vp = (NP.vp = { w: 0, h: 0, dpr: 1, mobile: false });
  var resizeFns = [];
  var lastW = 0;
  var lastH = 0;
  NP.onResize = function (fn) { resizeFns.push(fn); };
  NP.measure = function (force) {
    var w = document.documentElement.clientWidth || window.innerWidth;
    var h = window.innerHeight;
    // 移动端地址栏伸缩只改变高度，小幅变化忽略，避免吸顶场景跳动
    if (!force && w === lastW && Math.abs(h - lastH) < 140) return;
    lastW = w;
    lastH = h;
    vp.w = w;
    vp.h = h;
    vp.dpr = Math.min(window.devicePixelRatio || 1, 2);
    vp.mobile = w <= 900;
    document.documentElement.style.setProperty('--vh', h + 'px');
    for (var i = 0; i < resizeFns.length; i++) resizeFns[i](vp);
  };
  var resizeTimer = 0;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { NP.measure(false); }, 120);
  });
  window.addEventListener('orientationchange', function () {
    setTimeout(function () { NP.measure(true); }, 320);
  });

  /* ---------- 滚动状态与平滑滚动 ---------- */
  var scroll = (NP.scroll = { y: window.scrollY, vy: 0, max: 0 });
  var ss = {
    on: false,
    cur: window.scrollY,
    target: window.scrollY,
    active: false,
    lastSet: -1,
    tween: null,
    locked: false,
  };

  function maxScroll() {
    return Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
  }

  function canScrollInside(el, dy) {
    for (; el && el !== document.body && el !== document.documentElement; el = el.parentElement) {
      if (el.nodeType !== 1) continue;
      var oy = getComputedStyle(el).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight + 1) {
        if ((dy < 0 && el.scrollTop > 0) || (dy > 0 && el.scrollTop + el.clientHeight < el.scrollHeight - 1)) return true;
      }
    }
    return false;
  }

  function stopTween() { ss.tween = null; }

  function onWheel(e) {
    if (ss.locked) { e.preventDefault(); return; }
    if (e.ctrlKey || e.defaultPrevented) return;
    if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
    if (canScrollInside(e.target, e.deltaY)) return;
    e.preventDefault();
    var d = e.deltaY;
    if (e.deltaMode === 1) d *= 36;
    else if (e.deltaMode === 2) d *= window.innerHeight * 0.9;
    if (!ss.active) ss.cur = ss.target = window.scrollY;
    stopTween();
    ss.target = clamp(ss.target + d, 0, maxScroll());
    ss.active = true;
  }

  function onKey(e) {
    if (ss.locked || e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
    var t = e.target;
    if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT|BUTTON|SUMMARY|A)$/.test(t.tagName))) {
      if (e.key === ' ' || t.tagName !== 'A') return;
    }
    var h = window.innerHeight;
    var d = null;
    switch (e.key) {
      case 'ArrowDown': d = 110; break;
      case 'ArrowUp': d = -110; break;
      case 'PageDown': d = h * 0.88; break;
      case 'PageUp': d = -h * 0.88; break;
      case ' ': d = (e.shiftKey ? -1 : 1) * h * 0.88; break;
      case 'Home': e.preventDefault(); NP.scrollTo(0); return;
      case 'End': e.preventDefault(); NP.scrollTo(maxScroll()); return;
      default: return;
    }
    e.preventDefault();
    if (!ss.active) ss.cur = ss.target = window.scrollY;
    stopTween();
    ss.target = clamp(ss.target + d, 0, maxScroll());
    ss.active = true;
  }

  window.addEventListener('scroll', function () {
    var y = window.scrollY;
    if (ss.active || ss.tween) {
      // 用户拖动了滚动条或浏览器自身发生滚动，交还控制权
      if (Math.abs(y - ss.lastSet) > 3) {
        ss.active = false;
        stopTween();
        ss.cur = ss.target = y;
      }
    } else {
      ss.cur = ss.target = y;
    }
  }, { passive: true });

  ['touchstart', 'pointerdown'].forEach(function (type) {
    window.addEventListener(type, function (e) {
      if (type === 'pointerdown' && e.pointerType === 'mouse') return;
      if (ss.tween) { stopTween(); ss.active = false; }
    }, { passive: true });
  });

  ss.update = function (dt) {
    if (ss.tween) {
      var tw = ss.tween;
      tw.t = Math.min(1, tw.t + dt / tw.dur);
      ss.cur = lerp(tw.from, tw.to, ease.inOut(tw.t));
      if (tw.t >= 1) { ss.tween = null; ss.active = false; }
    } else if (ss.active) {
      ss.cur = damp(ss.cur, ss.target, 9, dt);
      if (Math.abs(ss.target - ss.cur) < 0.4) { ss.cur = ss.target; ss.active = false; }
    } else {
      return;
    }
    ss.lastSet = ss.cur;
    window.scrollTo(0, ss.cur);
  };

  NP.scrollTo = function (y, instant) {
    y = clamp(y, 0, maxScroll());
    if (instant || NP.env.reduced) {
      stopTween();
      ss.active = false;
      ss.cur = ss.target = y;
      window.scrollTo(0, y);
      return;
    }
    var from = window.scrollY;
    var dist = Math.abs(y - from);
    if (dist < 2) return;
    ss.tween = { from: from, to: y, t: 0, dur: clamp(0.7 + dist / 9000, 0.7, 2.1) };
    ss.target = y;
    ss.active = true;
  };

  NP.lockScroll = function (on) {
    ss.locked = !!on;
    document.documentElement.classList.toggle('is-locked', !!on);
  };

  NP.smoothScroll = {
    init: function () {
      ss.cur = ss.target = window.scrollY;
      if (NP.env.reduced || NP.env.coarse) return;
      ss.on = true;
      window.addEventListener('wheel', onWheel, { passive: false });
      window.addEventListener('keydown', onKey);
    },
    update: ss.update,
  };

  var lastY = window.scrollY;
  scroll.update = function (dt) {
    var y = window.scrollY;
    scroll.y = y;
    scroll.max = maxScroll();
    var v = dt > 0 ? (y - lastY) / dt : 0;
    scroll.vy = damp(scroll.vy, clamp(v, -30000, 30000), 7, dt);
    lastY = y;
  };

  /* ---------- 滚动揭示 ---------- */
  NP.reveal = function () {
    var els = NP.$$('[data-reveal]');
    if (!('IntersectionObserver' in window)) {
      els.forEach(function (el) { el.classList.add('is-in'); });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          en.target.classList.add('is-in');
          io.unobserve(en.target);
        }
      });
    }, { rootMargin: '0px 0px -10% 0px', threshold: 0.08 });
    els.forEach(function (el) { io.observe(el); });
  };

  /* 进入视口时回调（演示动画按需启动） */
  NP.whenVisible = function (el, onEnter, onLeave, margin) {
    if (!el) return;
    if (!('IntersectionObserver' in window)) { onEnter(); return; }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) onEnter();
        else if (onLeave) onLeave();
      });
    }, { rootMargin: margin || '0px' });
    io.observe(el);
  };

  /* ---------- Toast ---------- */
  NP.toast = function (msg, ms) {
    var t = NP.$('#toast');
    if (!t) return;
    t.textContent = msg;
    t.classList.add('is-on');
    clearTimeout(t._h);
    t._h = setTimeout(function () { t.classList.remove('is-on'); }, ms || 2200);
  };

  /* ---------- 主循环 ---------- */
  var tasks = [];
  NP.time = 0;
  NP.addFrame = function (fn, order) {
    tasks.push({ fn: fn, order: order || 0 });
    tasks.sort(function (a, b) { return a.order - b.order; });
  };
  var last = 0;
  var slowFor = 0;
  NP.perf = { ema: 1 / 60, onSlow: null };
  function frame(now) {
    var dt = last ? Math.min(0.05, (now - last) / 1000) : 1 / 60;
    last = now;
    NP.time += dt;
    NP.perf.ema = lerp(NP.perf.ema, dt, 0.05);
    if (NP.perf.ema > 1 / 40) slowFor += dt; else slowFor = Math.max(0, slowFor - dt * 0.5);
    if (slowFor > 1.6 && NP.perf.onSlow) { slowFor = 0; NP.perf.onSlow(); }
    for (var i = 0; i < tasks.length; i++) tasks[i].fn(dt, NP.time);
    requestAnimationFrame(frame);
  }
  NP.start = function () { requestAnimationFrame(frame); };
  document.addEventListener('visibilitychange', function () { last = 0; });
})();
