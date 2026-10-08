/* NeriPlayer site · demos —— 各站台里的小演示 */
(function () {
  'use strict';

  var NP = window.NP;
  var M = NP.math;
  var $ = NP.$;
  var $$ = NP.$$;
  var SVGNS = 'http://www.w3.org/2000/svg';

  function wide() { return NP.vp.w > 980; }

  /* ---------- 01 播放：分步讲解驱动手机状态 ---------- */
  function initSteps() {
    var steps = $$('#play .pstep');
    var anns = $$('#play .ann[data-ann]');
    if (!steps.length) return;
    var cur = -1;
    function apply(i) {
      if (i === cur) return;
      cur = i;
      steps.forEach(function (s, k) { s.classList.toggle('is-on', !wide() || k === i); });
      anns.forEach(function (a) { a.classList.toggle('is-on', a.getAttribute('data-ann') === String(i)); });
      var sheet = NP.phones && NP.phones.sheet;
      if (!sheet || !wide()) return;
      if (i === 3) { sheet.open(); sheet.scrollTo(0); }
      else if (!sheet.manual) sheet.close();
    }
    function check() {
      if (!wide()) { apply(-1); return; }
      var line = NP.vp.h * 0.52;
      var idx = 0;
      for (var i = 0; i < steps.length; i++) if (steps[i].getBoundingClientRect().top < line) idx = i;
      apply(idx);
    }
    window.addEventListener('scroll', check, { passive: true });
    NP.onResize(function () { cur = -2; check(); });
    check();
  }

  /* ---------- 03 多源：索引高亮 ---------- */
  function initSrcIndex() {
    var nav = $('#srcIndex');
    if (!nav) return;
    var links = $$('a', nav);
    var sheets = links.map(function (a) { return $(a.getAttribute('href')); });
    links.forEach(function (a, i) {
      var acc = sheets[i] && sheets[i].style.getPropertyValue('--acc');
      if (acc) a.parentElement.style.setProperty('--c', acc);
    });
    var cur = -1;
    function check() {
      var line = NP.vp.h * 0.4;
      var idx = 0;
      for (var i = 0; i < sheets.length; i++) if (sheets[i] && sheets[i].getBoundingClientRect().top < line) idx = i;
      if (idx === cur) return;
      cur = idx;
      links.forEach(function (a, k) { a.parentElement.classList.toggle('is-on', k === idx); });
    }
    window.addEventListener('scroll', check, { passive: true });
    check();
  }

  /* ---------- 链接识别：打字机 ---------- */
  function initLinkTyping() {
    var el = $('#linkTyping');
    if (!el) return;
    var out = $('.linkbox__out', el.closest('.linkbox'));
    var L = NP.L;
    var samples = [
      { text: L('【哔哩哔哩】夜行列车 Lo-fi 合集 P3 https://b23.tv/…', '[bilibili] Night Train Lo-fi Collection P3 https://b23.tv/…'), tags: [L('Bilibili 合集', 'Bilibili collection'), L('分 P 3', 'Part 3'), 'season_id'] },
      { text: L('分享 Neri Line 的单曲《星轨》 https://music.163.com/song?id=…', 'Neri Line – “星轨” on NetEase Cloud Music https://music.163.com/song?id=…'), tags: [L('网易云 · 单曲', 'NetEase · Song'), 'song id', L('播放页', 'Now Playing')] },
      { text: 'https://music.youtube.com/playlist?list=…', tags: ['YouTube Music', L('歌单', 'Playlist'), 'list'] },
      { text: L('快来听我的收藏夹 https://space.bilibili.com/…/favlist?fid=…', 'Come listen to my favorites https://space.bilibili.com/…/favlist?fid=…'), tags: [L('Bilibili 收藏夹', 'Bilibili favorites'), 'fid', L('歌单详情', 'Playlist details')] },
    ];
    var k = 0;
    var running = false;
    var timer = 0;
    function setTags(tags) {
      out.innerHTML = '';
      tags.forEach(function (t, i) {
        var s = document.createElement('span');
        s.className = 'tag';
        s.textContent = t;
        s.style.animation = 'logIn .45s ' + i * 0.08 + 's both';
        out.appendChild(s);
      });
    }
    function type() {
      if (!running) return;
      if (out.children.length && !out.classList.contains('is-out')) {
        out.classList.add('is-out');
        timer = setTimeout(type, 380);
        return;
      }
      out.classList.remove('is-out');
      var sm = samples[k % samples.length];
      var chars = Array.from(sm.text);
      var n = 0;
      out.innerHTML = '';
      (function next() {
        if (!running) return;
        n++;
        el.textContent = chars.slice(0, n).join('');
        if (n < chars.length) { timer = setTimeout(next, 28 + Math.random() * 46); return; }
        timer = setTimeout(function () {
          setTags(sm.tags);
          k++;
          timer = setTimeout(type, 2600);
        }, 360);
      })();
    }
    if (NP.env.reduced) { setTags(samples[0].tags); return; }
    NP.whenVisible(el, function () { if (!running) { running = true; type(); } }, function () { running = false; clearTimeout(timer); });
  }

  /* ---------- 04 音质：信号链 ---------- */
  function initChain() {
    var chain = $('#chain');
    if (!chain) return;
    var base = $('#wireBase');
    var flow = $('#wireFlow');
    var bypass = $('#wireBypass');
    var nodes = $$('.chain__node', chain);
    var sw = $('#bitPerfect');
    var out = $('#gainOut');
    function center(n) {
      var r = $('.chain__box', n).getBoundingClientRect();
      var c = chain.getBoundingClientRect();
      var ty = n.classList.contains('chain__node--fx') && chain.classList.contains('is-bp') ? 18 : 0;
      return { x: r.left - c.left + r.width / 2, y: r.top - c.top + r.height / 2 - ty, top: r.top - c.top - ty, w: r.width };
    }
    function seg(a, b) {
      if (Math.abs(a.y - b.y) < 4) return ' L' + b.x.toFixed(1) + ' ' + b.y.toFixed(1);
      var my = (a.y + b.y) / 2;
      return ' C' + a.x.toFixed(1) + ' ' + my.toFixed(1) + ' ' + b.x.toFixed(1) + ' ' + my.toFixed(1) + ' ' + b.x.toFixed(1) + ' ' + b.y.toFixed(1);
    }
    function draw() {
      var pts = nodes.map(center);
      var d = 'M' + pts[0].x.toFixed(1) + ' ' + pts[0].y.toFixed(1);
      for (var i = 1; i < pts.length; i++) d += seg(pts[i - 1], pts[i]);
      base.setAttribute('d', d);
      var bp = chain.classList.contains('is-bp');
      if (bp && Math.abs(pts[1].y - pts[3].y) < 4) {
        var lift = pts[1].y - Math.max(46, pts[2].top - pts[1].top + 46);
        var arc = ' C' + pts[1].x.toFixed(1) + ' ' + lift.toFixed(1) + ' ' + pts[3].x.toFixed(1) + ' ' + lift.toFixed(1) + ' ' + pts[3].x.toFixed(1) + ' ' + pts[3].y.toFixed(1);
        bypass.setAttribute('d', 'M' + pts[1].x.toFixed(1) + ' ' + pts[1].y.toFixed(1) + arc);
        var f = 'M' + pts[0].x.toFixed(1) + ' ' + pts[0].y.toFixed(1) + seg(pts[0], pts[1]) + arc;
        for (var j = 4; j < pts.length; j++) f += seg(pts[j - 1], pts[j]);
        flow.setAttribute('d', f);
      } else {
        bypass.setAttribute('d', bp ? 'M' + pts[1].x + ' ' + pts[1].y + seg(pts[1], pts[3]) : '');
        flow.setAttribute('d', d);
      }
    }
    function setBp() {
      var on = sw && sw.checked;
      chain.classList.add('is-swap');
      chain.classList.toggle('is-bp', !!on);
      if (out) NP.swapText(out, on ? NP.L('软件增益 0.0 dB · 绕过音效链，音量交给 DAC', 'Software gain 0.0 dB · effects bypassed, volume handed to the DAC') : NP.L('软件增益 −3.2 dB · 经过音效链', 'Software gain −3.2 dB · through the effects chain'));
      setTimeout(draw, 20);
      setTimeout(function () { draw(); chain.classList.remove('is-swap'); }, 650);
    }
    if (sw) sw.addEventListener('change', setBp);
    NP.onResize(draw);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(draw);
    setBp();
    // 信号依次点亮每一个节点
    var k = 0;
    var timer = 0;
    function pulse() {
      nodes.forEach(function (n) { n.classList.remove('is-lit'); });
      var n = nodes[k % nodes.length];
      if (n.classList.contains('chain__node--fx') && chain.classList.contains('is-bp')) { k++; n = nodes[k % nodes.length]; }
      n.classList.add('is-lit');
      k++;
      timer = setTimeout(pulse, k % nodes.length === 0 ? 1100 : 520);
    }
    NP.whenVisible(chain, function () { draw(); clearTimeout(timer); pulse(); }, function () { clearTimeout(timer); });
  }

  /* ---------- 05 一起听：三个人的房间 ---------- */
  function initRoom() {
    var room = $('#room');
    if (!room) return;
    var stage = $('.room__stage', room);
    var svg = $('#roomWires');
    var hub = $('#roomHub');
    var log = $('#roomLog');
    var members = $$('.member', room).map(function (el) {
      return { el: el, bar: $('.member__bar > i', el), use: $('.member__st use', el), p: 0, rate: 1, playing: false, joined: false };
    });
    var lines = [];
    var dots = [];
    function layout() {
      svg.innerHTML = '';
      var s = stage.getBoundingClientRect();
      var h = hub.getBoundingClientRect();
      var hc = { x: h.left - s.left + h.width / 2, y: h.top - s.top + h.height / 2 };
      lines = members.map(function (m) {
        var r = m.el.getBoundingClientRect();
        var c = { x: r.left - s.left + r.width / 2, y: r.top - s.top + r.height / 2 };
        var ln = document.createElementNS(SVGNS, 'line');
        ln.setAttribute('x1', hc.x); ln.setAttribute('y1', hc.y);
        ln.setAttribute('x2', c.x); ln.setAttribute('y2', c.y);
        svg.appendChild(ln);
        return { a: hc, b: c };
      });
      dots.forEach(function (d) { svg.appendChild(d.el); });
    }
    function addLog(txt) {
      var li = document.createElement('li');
      var now = new Date();
      li.textContent = ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2) + ':' + ('0' + now.getSeconds()).slice(-2) + '  ' + txt;
      log.appendChild(li);
      var live = $$('li:not(.is-out)', log);
      if (live.length > 5) {
        var old = live[0];
        old.classList.add('is-out');
        setTimeout(function () { if (old.parentNode) old.parentNode.removeChild(old); }, 460);
      }
      hub.classList.add('is-ping');
      setTimeout(function () { hub.classList.remove('is-ping'); }, 380);
    }
    function send(i, back) {
      var c = document.createElementNS(SVGNS, 'circle');
      c.setAttribute('r', 4);
      svg.appendChild(c);
      dots.push({ el: c, i: i, t: 0, back: !!back });
    }
    function broadcast() { for (var i = 0; i < members.length; i++) send(i); }
    function flash(m) { m.el.classList.add('is-flash'); setTimeout(function () { m.el.classList.remove('is-flash'); }, 700); }
    function setPlay(m, on) { m.playing = on; if (m.use) m.use.setAttribute('href', on ? '#i-play' : '#i-pause'); }

    var L = NP.L;
    var script = [
      [0, function () { members.forEach(function (m) { m.p = 0; m.rate = 1; setPlay(m, false); m.el.classList.remove('is-drift'); m.el.style.opacity = '.45'; }); members[0].el.style.opacity = '1'; addLog(L('音理 创建房间 NR7K2Q', 'Neri opened room NR7K2Q')); }],
      [1.2, function () { members[1].el.style.opacity = '1'; send(1); flash(members[1]); addLog(L('诺瓦 加入 · 时钟偏移 −21 ms', 'Noir joined · clock offset −21 ms')); }],
      [2.4, function () { members[2].el.style.opacity = '1'; send(2); flash(members[2]); addLog(L('狩叶 加入 · 时钟偏移 +34 ms', 'Karuha joined · clock offset +34 ms')); }],
      [3.8, function () { send(0, true); members.forEach(function (m) { setPlay(m, true); }); broadcast(); addLog(L('音理 播放《星轨》→ 全员 00:00', 'Neri plays “星轨” → everyone at 00:00')); }],
      [6.4, function () { members[2].rate = 1.09; }],
      [8.2, function () { var m = members[2]; m.el.classList.add('is-drift'); addLog(L('狩叶 漂移 312 ms，超过阈值', 'Karuha drifted 312 ms, past the threshold')); }],
      [9.0, function () { var m = members[2]; m.p = members[0].p; m.rate = 1; m.el.classList.remove('is-drift'); send(2); flash(m); addLog(L('→ 已校正到房主进度', '→ snapped back to the host')); }],
      [11.2, function () { send(0, true); members.forEach(function (m) { m.p = 0.66; }); broadcast(); addLog(L('音理 拖动到 00:48 · 同一句歌词，同一秒', 'Neri seeks to 00:48 · same line, same second')); }],
      [14.4, function () { send(0, true); members.forEach(function (m) { setPlay(m, false); }); broadcast(); addLog(L('音理 暂停 · 全员暂停', 'Neri pauses · everyone pauses')); }],
      [17.0, null],
    ];
    var t = 0;
    var si = 0;
    var visible = false;
    NP.whenVisible(room, function () { visible = true; layout(); }, function () { visible = false; });
    NP.onResize(function () { if (visible) layout(); });
    NP.addFrame(function (dt) {
      if (!visible) return;
      t += dt;
      while (si < script.length && t >= script[si][0]) {
        if (script[si][1]) script[si][1]();
        else { t = 0; si = -1; }
        si++;
      }
      members.forEach(function (m) {
        if (m.playing) m.p = Math.min(1, m.p + (dt / 72) * 2.2 * m.rate);
        m.bar.style.transform = 'scaleX(' + m.p.toFixed(4) + ')';
      });
      for (var k = dots.length - 1; k >= 0; k--) {
        var d = dots[k];
        d.t += dt / 0.6;
        var ln = lines[d.i];
        if (d.t >= 1 || !ln) { d.el.remove(); dots.splice(k, 1); continue; }
        var e = M.ease.inOut(d.t);
        var a = d.back ? ln.b : ln.a;
        var b = d.back ? ln.a : ln.b;
        d.el.setAttribute('cx', M.lerp(a.x, b.x, e).toFixed(1));
        d.el.setAttribute('cy', M.lerp(a.y, b.y, e).toFixed(1));
      }
    });
  }

  /* ---------- 06 本地：脱机开关 ---------- */
  function initOffline() {
    var sw = $('#netSwitch');
    var card = $('#offlineDemo');
    if (!sw || !card) return;
    function upd() { card.classList.toggle('is-offline', !sw.checked); }
    sw.addEventListener('change', upd);
    upd();
  }

  /* ---------- 下载与断点续传 ---------- */
  function initDownloads() {
    var card = $('#dlDemo');
    if (!card) return;
    var items = $$('.dl', card).map(function (el, i) {
      return { el: el, bar: $('.dl__bar > i', el), st: $('.dl__st', el), p: 0, speed: [0.16, 0.11, 0.075][i] || 0.1, wait: 0, done: false, hls: /HLS/.test(el.textContent) };
    });
    var visible = false;
    var rest = 0;
    var list = $('.dl-list', card);
    function reset() { items.forEach(function (it) { it.p = 0; it.done = false; it.wait = 0; it.paused = false; it.el.classList.remove('is-done', 'is-wait'); }); }
    NP.whenVisible(card, function () { visible = true; }, function () { visible = false; });
    NP.addFrame(function (dt) {
      if (!visible || card.resetting) return;
      var all = true;
      items.forEach(function (it, i) {
        if (it.done) return;
        all = false;
        if (i === 1 && !it.paused && it.p > 0.46) { it.paused = true; it.wait = 2.2; it.el.classList.add('is-wait'); }
        if (it.wait > 0) {
          it.wait -= dt;
          it.st.textContent = it.wait > 0.7 ? NP.L('网络中断 · 等待', 'Offline · waiting') : NP.L('续传 Range: ', 'Resume Range: ') + Math.round(it.p * 9.6) + '.' + Math.round(it.p * 90) % 10 + ' MB-';
          if (it.wait <= 0) it.el.classList.remove('is-wait');
          return;
        }
        it.p = Math.min(1, it.p + it.speed * dt * (0.75 + 0.5 * Math.random()));
        if (it.p >= 1) { it.done = true; it.el.classList.add('is-done'); it.st.textContent = NP.L('已完成 ✓', 'Done ✓'); }
        else it.st.textContent = it.hls ? NP.L('分片 ', 'Segment ') + Math.ceil(it.p * 40) + ' / 40' : NP.L('下载中 ', 'Downloading ') + Math.floor(it.p * 100) + '%';
        it.bar.style.transform = 'scaleX(' + it.p.toFixed(4) + ')';
      });
      if (all && !card.resetting) {
        rest += dt;
        if (rest > 2.6) {
          rest = 0;
          card.resetting = true;
          list.classList.add('is-reset');
          setTimeout(function () {
            reset();
            items.forEach(function (it) { it.bar.style.transform = 'scaleX(0)'; it.st.textContent = NP.L('排队中', 'Queued'); });
            list.classList.remove('is-reset');
            setTimeout(function () { card.resetting = false; }, 500);
          }, 480);
        }
      }
    });
  }

  /* ---------- 07 播放统计 ---------- */
  function initStats() {
    var root = $('#statsDemo');
    if (!root) return;
    var svg = $('#statChart');
    var btns = $$('.seg button', root);
    var nums = { plays: $('#statPlays'), time: $('#statTime'), songs: $('#statSongs') };
    var RANGES = [
      { n: 24, seed: 3, plays: 23, time: 1.4, songs: 17 },
      { n: 7, seed: 7, plays: 186, time: 11.2, songs: 74 },
      { n: 30, seed: 11, plays: 742, time: 46.8, songs: 213 },
      { n: 12, seed: 5, plays: 8214, time: 538, songs: 1206 },
      { n: 16, seed: 2, plays: 19630, time: 1284, songs: 2451 },
    ];
    function rnd(seed) { var x = seed; return function () { x = (x * 9301 + 49297) % 233280; return x / 233280; }; }
    var W = 600;
    var H = 200;
    var grid = document.createElementNS(SVGNS, 'g');
    grid.setAttribute('class', 'grid');
    [0.25, 0.5, 0.75, 1].forEach(function (f) {
      var l = document.createElementNS(SVGNS, 'line');
      l.setAttribute('x1', 0); l.setAttribute('x2', W);
      l.setAttribute('y1', H - H * f * 0.9); l.setAttribute('y2', H - H * f * 0.9);
      grid.appendChild(l);
    });
    svg.appendChild(grid);
    var bars = [];
    var shown = { plays: 186, time: 11.2, songs: 74 };
    var tween = null;
    function render(i) {
      var r = RANGES[i];
      var rand = rnd(r.seed);
      var vals = [];
      for (var k = 0; k < r.n; k++) {
        var wave = i === 0 ? Math.max(0.05, Math.sin(((k - 6) / 24) * Math.PI * 2) * 0.5 + 0.45) : 0.35 + 0.6 * rand();
        vals.push(M.clamp(wave * (0.7 + 0.5 * rand()), 0.06, 1));
      }
      var max = Math.max.apply(null, vals);
      var hot = vals.indexOf(max);
      while (bars.length > r.n) {
        var gone = bars.pop();
        gone.style.y = H + 'px';
        gone.style.height = '0px';
        gone.style.opacity = '0';
        setTimeout((function (g) { return function () { if (g.parentNode) g.parentNode.removeChild(g); }; })(gone), 700);
      }
      while (bars.length < r.n) {
        var rect = document.createElementNS(SVGNS, 'rect');
        rect.setAttribute('class', 'bar');
        rect.setAttribute('rx', 3);
        rect.style.y = H + 'px';
        rect.style.height = '0px';
        svg.appendChild(rect);
        bars.push(rect);
      }
      var gap = r.n > 20 ? 4 : 10;
      var bw = (W - gap * (r.n - 1)) / r.n;
      bars.forEach(function (b, k) {
        var h = vals[k] * H * 0.9;
        b.setAttribute('x', (k * (bw + gap)).toFixed(1));
        b.setAttribute('width', bw.toFixed(1));
        b.classList.toggle('is-hot', k === hot);
        requestAnimationFrame(function () {
          b.style.y = (H - h).toFixed(1) + 'px';
          b.style.height = h.toFixed(1) + 'px';
        });
      });
      tween = { from: { plays: shown.plays, time: shown.time, songs: shown.songs }, to: r, t: 0 };
    }
    function fmtN(v, dec) {
      return dec ? v.toFixed(1) : Math.round(v).toLocaleString('en-US');
    }
    NP.addFrame(function (dt) {
      if (!tween) return;
      tween.t = Math.min(1, tween.t + dt / 0.8);
      var e = M.ease.out(tween.t);
      ['plays', 'time', 'songs'].forEach(function (key) {
        shown[key] = M.lerp(tween.from[key], tween.to[key], e);
        if (nums[key]) nums[key].textContent = fmtN(shown[key], key === 'time' && tween.to[key] < 100);
      });
      if (tween.t >= 1) tween = null;
    });
    btns.forEach(function (b) {
      b.addEventListener('click', function () {
        btns.forEach(function (o) { var on = o === b; o.classList.toggle('is-on', on); o.setAttribute('aria-selected', on ? 'true' : 'false'); });
        render(parseInt(b.getAttribute('data-r'), 10));
      });
    });
    var started = false;
    NP.whenVisible(svg, function () { if (!started) { started = true; render(1); } });
  }

  /* ---------- 09 开发者模式 ---------- */
  function initDev() {
    var btn = $('#devBtn');
    var hint = $('#devHint');
    if (!btn) return;
    var n = 0;
    var timer = 0;
    btn.addEventListener('click', function () {
      var L = NP.L;
      if (btn.classList.contains('is-dev')) { NP.toast(L('已经处于开发者模式', 'Developer mode is already on')); return; }
      n++;
      clearTimeout(timer);
      timer = setTimeout(function () { if (n < 7) { n = 0; NP.swapText(hint, L('连续点击 7 次试试', 'Try tapping 7 times')); } }, 1800);
      if (n >= 7) {
        btn.classList.add('is-dev');
        NP.swapText(hint, L('你已处于开发者模式', 'You are now a developer'));
        NP.toast(L('Debug 页已出现在底栏（示意）', 'A Debug tab appeared in the nav bar (simulated)'));
      } else if (n >= 3) {
        NP.swapText(hint, L('再点 ' + (7 - n) + ' 次即可进入开发者模式', (7 - n) + ' more ' + (7 - n === 1 ? 'tap' : 'taps') + ' to developer mode'));
      }
    });
  }

  /* ---------- 复制 ---------- */
  function initCopy() {
    $$('[data-copy]').forEach(function (b) {
      b.addEventListener('click', function () {
        var src = $(b.getAttribute('data-copy'));
        if (!src) return;
        var text = src.innerText.replace(/\u00a0/g, ' ').trim();
        var label = b.querySelector('span');
        function ok() {
          if (label) { label.textContent = NP.L('已复制', 'Copied'); setTimeout(function () { label.textContent = NP.L('复制', 'Copy'); }, 1600); }
          NP.toast(NP.L('已复制到剪贴板', 'Copied to clipboard'));
        }
        if (navigator.clipboard && window.isSecureContext) {
          navigator.clipboard.writeText(text).then(ok, function () { NP.toast(NP.L('复制失败，请手动选择', 'Copy failed, please select it manually')); });
        } else {
          var ta = document.createElement('textarea');
          ta.value = text;
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.select();
          try { document.execCommand('copy'); ok(); } catch (e) { NP.toast(NP.L('复制失败，请手动选择', 'Copy failed, please select it manually')); }
          ta.remove();
        }
      });
    });
  }

  /* ---------- FAQ：展开动画 ---------- */
  function initFaq() {
    $$('.qa').forEach(function (d) {
      var sum = $('summary', d);
      var body = $('.qa__a', d);
      if (!sum || !body || !body.animate || NP.env.reduced) return;
      var anim = null;
      sum.addEventListener('click', function (e) {
        e.preventDefault();
        if (anim) anim.cancel();
        if (d.open) {
          var h = body.offsetHeight;
          anim = body.animate([{ height: h + 'px', opacity: 1 }, { height: '0px', opacity: 0 }], { duration: 280, easing: 'cubic-bezier(.4,0,.2,1)' });
          anim.onfinish = function () { d.open = false; anim = null; };
        } else {
          d.open = true;
          var full = body.offsetHeight;
          anim = body.animate([{ height: '0px', opacity: 0 }, { height: full + 'px', opacity: 1 }], { duration: 360, easing: 'cubic-bezier(.2,.8,.2,1)' });
          anim.onfinish = function () { anim = null; };
        }
      });
    });
  }

  /* ---------- 11 车票：检票打孔 ---------- */
  function initTicket() {
    var t = $('#ticket');
    var btn = $('#dlBtn');
    if (!t || !btn) return;
    btn.addEventListener('click', function () {
      t.classList.remove('is-punched');
      void t.offsetWidth;
      t.classList.add('is-punched');
    });
    $$('input[name="abi"]', t).forEach(function (r) {
      r.addEventListener('change', function () { t.classList.remove('is-punched'); });
    });
  }

  /* ---------- 09 平板：标签切换截图 ---------- */
  function initTablet() {
    var stage = $('#tabStage');
    if (!stage) return;
    var tabs = $$('[role="tab"]', stage);
    var shots = $$('.tabshot', stage);
    var legends = $$('.tablegend', stage);
    var panel = $('#tabScreen', stage);
    var cur = 0;
    var prevTimer = 0;
    var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function show(i, focus) {
      if (i === cur) return;
      var old = shots[cur];
      cur = i;
      tabs.forEach(function (t, k) {
        var on = k === i;
        t.classList.toggle('is-on', on);
        t.setAttribute('aria-selected', on ? 'true' : 'false');
        t.tabIndex = on ? 0 : -1;
      });
      if (focus) tabs[i].focus();
      panel.setAttribute('aria-labelledby', tabs[i].id);
      clearTimeout(prevTimer);
      shots.forEach(function (s) { s.classList.remove('is-prev', 'is-wipe'); });
      old.classList.remove('is-on');
      old.classList.add('is-prev');
      void shots[i].offsetWidth;
      shots[i].classList.add('is-on', 'is-wipe');
      prevTimer = setTimeout(function () {
        old.classList.remove('is-prev');
        shots[i].classList.remove('is-wipe');
      }, 1100);
      legends.forEach(function (l, k) {
        if (k !== i) { l.classList.remove('is-on'); return; }
        l.classList.remove('is-in');
        l.classList.add('is-on');
        void l.offsetWidth;
        l.classList.add('is-in');
      });
    }

    tabs.forEach(function (t, k) {
      t.addEventListener('click', function () { stop(); show(k); });
      t.addEventListener('keydown', function (e) {
        var d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
        if (!d) return;
        e.preventDefault();
        stop();
        show((cur + d + tabs.length) % tabs.length, true);
      });
    });

    legends.forEach(function (l, k) {
      $$('li', l).forEach(function (li, j) {
        var pin = $$('.tabpin', shots[k])[j];
        if (!pin) return;
        var hot = function (on) { li.classList.toggle('is-hot', on); pin.classList.toggle('is-hot', on); };
        li.addEventListener('pointerenter', function () { hot(true); });
        li.addEventListener('pointerleave', function () { hot(false); });
        pin.addEventListener('pointerenter', function () { hot(true); });
        pin.addEventListener('pointerleave', function () { hot(false); });
      });
    });

    function stop() { stage.classList.remove('is-auto'); }
    if (reduced) return;
    stage.classList.add('is-auto', 'is-paused');
    stage.addEventListener('animationend', function (e) {
      if (e.animationName !== 'tabprog' || !stage.classList.contains('is-auto')) return;
      show((cur + 1) % tabs.length);
    });
    var hover = false;
    var seen = false;
    function sync() { stage.classList.toggle('is-paused', hover || !seen || document.hidden); }
    stage.addEventListener('pointerenter', function (e) { if (e.pointerType === 'mouse') { hover = true; sync(); } });
    stage.addEventListener('pointerleave', function () { hover = false; sync(); });
    document.addEventListener('visibilitychange', sync);
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (en) { seen = en[0].isIntersecting; sync(); }, { threshold: 0.45 }).observe($('.tabdev', stage));
    } else { seen = true; sync(); }
  }

  NP.demos = {
    init: function () {
      initSteps();
      initTablet();
      initSrcIndex();
      initLinkTyping();
      initChain();
      initRoom();
      initOffline();
      initDownloads();
      initStats();
      initDev();
      initCopy();
      initFaq();
      initTicket();
    },
  };
})();
