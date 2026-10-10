/* NeriPlayer site · lyrics —— 《星轨》逐字歌词，以及封面页 / 歌词页两种同步歌词视图 */
(function () {
  'use strict';

  var NP = window.NP;
  var M = NP.math;

  /* [行开始, 行结束, 逐字文本, 第 2…n 个字的开始时间, 译文]，时间轴取自 NeriPlayer PV 的 TTML */
  var RAW = [[7.5,11.016,["把","喜","欢","的","歌 ","留","在","掌","心"],[7.734,7.969,8.203,8.438,9.375,9.609,9.844,10.078],"Keep the songs you love close at hand"],[11.25,14.531,["让","歌","词 ","随","声","音","展","开"],[11.484,11.719,12.422,12.656,12.891,13.125,13.594],"And let the lyrics unfold with the sound"],[15,16.641,["音","理 ","音","理！"],[15.352,15.703,15.938],"Neri, Neri!"],[18.75,21.562,["无","论","从","哪","里","来","的","歌"],[18.984,19.219,19.453,19.688,19.922,20.156,20.391],"Wherever the song may come from"],[22.5,25.781,["换","一","条","轨 ","也","不","会","停"],[22.734,22.969,23.203,23.906,24.141,24.375,24.609],"Switch the track, and it never stops"],[26.25,29.766,["每","个","字 ","都","落","在","节","拍","上"],[26.484,26.719,27.422,27.656,27.891,28.125,28.594,29.062],"Every word lands right on the beat"],[30,33.516,["原","文","与","译","文 ","一","同","亮","起"],[30.234,30.469,30.703,30.938,31.641,31.875,32.109,32.578],"Original and translation light up as one"],[33.75,37.266,["每","一","个","采","样 ","原","样","抵","达"],[33.984,34.219,34.453,34.688,35.625,35.859,36.094,36.328],"Every sample arrives untouched"],[37.5,40.312,["不","多","一","分 ","也","不","少","一","分"],[37.734,37.969,38.203,38.672,38.906,39.141,39.375,39.609],"Nothing added, nothing lost"],[41.25,44.531,["连","接","断","开 ","歌","还","在","继","续"],[41.484,41.719,41.953,42.656,42.891,43.125,43.359,43.594],"The signal drops, the song plays on"],[45,48.281,["回","来","的","路 ","一","直","都","在"],[45.234,45.469,45.703,46.406,46.641,46.875,47.109],"The way back is always there"],[48.75,52.031,["隔","着","很","远 ","听","同","一","首","歌"],[48.984,49.219,49.453,50.156,50.391,50.625,50.859,51.094],"Miles apart, on the very same song"],[52.5,55.781,["同","一","句","歌","词 ","同","一","秒"],[52.734,52.969,53.203,53.438,54.375,54.609,54.844],"Same line, same second"],[60,61.875,["音","理 ","音","理"],[60.352,60.703,60.938],"Neri, Neri"]];

  var LINES = RAW.map(function (r) {
    var starts = [r[0]].concat(r[3]);
    return {
      t: r[0],
      e: r[1],
      tr: r[4],
      words: r[2].map(function (w, i) { return { text: w, s: starts[i], e: i + 1 < starts.length ? starts[i + 1] : r[1] }; }),
    };
  });
  NP.LYRICS = LINES;

  function indexAt(t) {
    var idx = -1;
    for (var i = 0; i < LINES.length; i++) {
      if (LINES[i].t <= t) idx = i;
      else break;
    }
    return idx;
  }
  NP.lyricIndexAt = indexAt;

  function buildLine(cls, line, i, onSeek) {
    var el = document.createElement('div');
    el.className = cls;
    var main = document.createElement('p');
    main.className = cls + '__main';
    var spans = line.words.map(function (w) {
      var s = document.createElement('span');
      s.className = 'w';
      s.textContent = w.text;
      main.appendChild(s);
      return s;
    });
    var sub = document.createElement('p');
    sub.className = cls + '__sub';
    sub.textContent = line.tr;
    el.appendChild(main);
    el.appendChild(sub);
    el.addEventListener('click', function () { onSeek(line.t); });
    return { el: el, main: main, sub: sub, spans: spans, line: line, idx: i, y: 0, s: 1, mainH: 0, subH: 0, h: 0, lastP: [] };
  }

  function paintWords(item, t) {
    var words = item.line.words;
    for (var k = 0; k < words.length; k++) {
      var w = words[k];
      var p = M.clamp((t - w.s) / Math.max(0.05, w.e - w.s));
      p = Math.round(p * 50) / 50;
      if (item.lastP[k] !== p) {
        item.lastP[k] = p;
        item.spans[k].style.setProperty('--p', p);
      }
    }
  }

  /* ---------- 封面页：居中，当前行逐字点亮，译文只跟在当前行下面 ---------- */
  function CoverLyrics(root, onSeek) {
    this.root = root;
    this.items = LINES.map(function (l, i) { return buildLine('lrc-c', l, i, onSeek); });
    var frag = document.createDocumentFragment();
    this.items.forEach(function (it) { frag.appendChild(it.el); });
    root.appendChild(frag);
    this.cur = -2;
    this.offset = null;
    this.dirty = true;
  }
  CoverLyrics.prototype.measure = function () {
    this.items.forEach(function (it) {
      it.mainH = it.main.offsetHeight || 21;
      it.subH = it.sub.offsetHeight || 16;
    });
    this.dirty = false;
  };
  CoverLyrics.prototype.update = function (t, dt, snap) {
    if (this.dirty) this.measure();
    var idx = indexAt(t);
    var items = this.items;
    if (idx !== this.cur) {
      for (var i = 0; i < items.length; i++) {
        var on = i === idx;
        items[i].el.classList.toggle('is-on', on);
        items[i].el.classList.toggle('is-far', Math.abs(i - Math.max(idx, 0)) > 1);
        if (!on) { items[i].lastP = []; }
      }
      this.cur = idx;
    }
    var gap = 18;
    var y = 0;
    var anchorY = 0;
    var anchorH = 0;
    var focus = Math.max(idx, 0);
    for (var j = 0; j < items.length; j++) {
      var it = items[j];
      var expand = j === idx ? 1 : 0;
      it.ex = snap || it.ex == null ? expand : M.damp(it.ex, expand, 14, dt);
      it.h = it.mainH + it.ex * (it.subH + 6);
      it.ty = y;
      if (j === focus) { anchorY = y; anchorH = it.mainH + (j === idx ? it.subH + 6 : 0); }
      y += it.h + gap;
    }
    var target = this.root.clientHeight / 2 - (anchorY + anchorH / 2);
    this.offset = this.offset == null || snap ? target : M.damp(this.offset, target, 9, dt);
    for (var k = 0; k < items.length; k++) {
      var c = items[k];
      var s = k === idx ? 1.025 : 0.985;
      c.s = snap ? s : M.damp(c.s, s, 16, dt);
      var py = c.ty + this.offset;
      if (py < -120 || py > this.root.clientHeight + 40) {
        if (!c.hidden) { c.el.style.visibility = 'hidden'; c.hidden = true; }
        continue;
      }
      if (c.hidden) { c.el.style.visibility = ''; c.hidden = false; }
      c.el.style.transform = 'translate3d(0,' + py.toFixed(2) + 'px,0) scale(' + c.s.toFixed(4) + ')';
    }
    if (idx >= 0) paintWords(items[idx], t);
  };

  /* ---------- 歌词页：左对齐，每行都带译文，远处的行逐级缩小、变淡、模糊 ---------- */
  function PageLyrics(root, onSeek) {
    this.root = root;
    this.items = LINES.map(function (l, i) { return buildLine('lrc-l', l, i, onSeek); });
    var dots = document.createElement('div');
    dots.className = 'lrc-l lrc-l--dots';
    dots.innerHTML = '<div class="lrc-dots"><i></i><i></i><i></i></div>';
    this.dots = { el: dots, dotEls: dots.querySelectorAll('i'), y: 0, s: 1, h: 0, f: 1 };
    var frag = document.createDocumentFragment();
    frag.appendChild(dots);
    this.items.forEach(function (it) { frag.appendChild(it.el); });
    root.appendChild(frag);
    this.cur = -2;
    this.dirty = true;
    this.anchor = 128;
  }
  PageLyrics.prototype.measure = function () {
    this.items.forEach(function (it) { it.h = it.el.offsetHeight || 46; });
    this.dots.h = this.dots.el.offsetHeight || 18;
    this.dirty = false;
  };
  PageLyrics.prototype.remeasureFor = function (ms) {
    this.measureUntil = performance.now() + ms;
    this.dirty = true;
  };
  PageLyrics.prototype.update = function (t, dt, snap) {
    if (this.dirty || performance.now() < (this.measureUntil || 0)) this.measure();
    var first = LINES[0].t;
    var idx = indexAt(t);
    var items = this.items;
    var dots = this.dots;
    var intro = idx < 0;
    dots.f = snap ? (intro ? 1 : 0) : M.damp(dots.f, intro ? 1 : 0, 10, dt);
    if (idx !== this.cur) {
      for (var i = 0; i < items.length; i++) {
        items[i].el.classList.toggle('is-on', i === idx);
        if (i !== idx) items[i].lastP = [];
      }
      this.cur = idx;
    }
    var gap = 18;
    var dotsH = (dots.h + gap) * dots.f;
    var y = dotsH;
    var focus = Math.max(idx, 0);
    var focusY = 0;
    for (var j = 0; j < items.length; j++) {
      items[j].ty = y;
      if (j === focus) focusY = y;
      y += items[j].h + gap;
    }
    var base = intro ? this.anchor - 0 : this.anchor - focusY;
    var vh = this.root.clientHeight;
    for (var k = 0; k < items.length; k++) {
      var it = items[k];
      var d = intro ? k + 1 : Math.abs(k - idx);
      var below = intro || k > idx;
      var target = base + it.ty;
      // 下方的行稍晚跟上，形成一层层递进的滚动
      var lambda = below ? Math.max(4.2, 11 - d * 1.3) : 11;
      it.y = snap || it.y == null ? target : M.damp(it.y, target, lambda, dt);
      var s = d === 0 ? 1 : 0.95;
      it.s = snap ? s : M.damp(it.s, s, 10, dt);
      var blur = d === 0 ? 0 : Math.min(3.2, 0.55 + 0.75 * (d - 1));
      var alpha = d === 0 ? 1 : Math.max(0.2, 0.42 - 0.045 * (d - 1));
      it.bl = snap || it.bl == null ? blur : M.damp(it.bl, blur, 8, dt);
      it.al = snap || it.al == null ? alpha : M.damp(it.al, alpha, 8, dt);
      if (it.y < -160 || it.y > vh + 60) {
        if (!it.hidden) { it.el.style.visibility = 'hidden'; it.hidden = true; }
        continue;
      }
      if (it.hidden) { it.el.style.visibility = ''; it.hidden = false; }
      it.el.style.transform = 'translate3d(0,' + it.y.toFixed(2) + 'px,0) scale(' + it.s.toFixed(4) + ')';
      it.el.style.opacity = it.al.toFixed(3);
      var b = it.bl < 0.15 ? 'none' : 'blur(' + it.bl.toFixed(2) + 'px)';
      if (it.lastBlur !== b) { it.el.style.filter = b; it.lastBlur = b; }
    }
    var dy = (intro ? this.anchor : base) + 0;
    dots.el.style.transform = 'translate3d(0,' + dy.toFixed(2) + 'px,0) scale(' + (0.6 + 0.4 * dots.f).toFixed(3) + ')';
    dots.el.style.opacity = dots.f.toFixed(3);
    if (intro) {
      var prog = M.clamp(t / first) * 3;
      for (var q = 0; q < 3; q++) dots.dotEls[q].style.setProperty('--f', M.clamp(prog - q).toFixed(3));
    }
    if (idx >= 0) paintWords(items[idx], t);
  };

  NP.CoverLyrics = CoverLyrics;
  NP.PageLyrics = PageLyrics;
})();
