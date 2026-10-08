/* NeriPlayer site · phone-ui —— 播放页的交互仿真：更多选项、Dock 面板、睡眠定时器、音效与倍速、主控位置、封面 / 歌词页切换、首页迷你播放器 */
(function () {
  'use strict';

  var NP = window.NP;
  var M = NP.math;
  var $ = NP.$;
  var $$ = NP.$$;
  var L = NP.L;
  var P = NP.player;

  /* 真机「本地文件」播放列表的顺序，《星轨》在第 10 首 */
  var QUEUE = [
    ['goodnight-train', '晚安，列车', 'Neri Line'],
    ['paper-planets', 'Paper Planets', 'Velvet Orbit'],
    ['daylight-route', '白昼航线', 'Sora Atlas'],
    ['polaris', 'Polaris', 'Northbound'],
    ['cat-soda', '猫与汽水', 'Soda Cat'],
    ['two-am', '02:00 AM', 'Lumen Drift feat. Soda Cat'],
    ['seaside-signal', '海边的信号', 'Glass Harbor'],
    ['low-orbit', 'Low Orbit', 'Mellow Circuit'],
    ['rain-radio', '雨后电台', 'Paper Satellite'],
    ['startrail', '星轨', 'Neri Line'],
  ];
  var CUR = 9;
  var coverBase = 'assets/img/covers/';
  var favCount = 0;
  var sleep = { mode: null, end: 0 };
  var fxHinted = false;

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }
  function ic(id) { return '<svg aria-hidden="true"><use href="#' + id + '"/></svg>'; }
  function cover(k) { return coverBase + k + '.jpg'; }
  function songs(n) { return NP.en ? n + (n === 1 ? ' song' : ' songs') : n + ' 首'; }
  function mmss(ms) {
    var s = Math.max(0, Math.ceil(ms / 1000));
    var m = Math.floor(s / 60);
    s %= 60;
    return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }

  /* ---------- 水波纹（M3 state layer） ---------- */
  var RIPPLE = '.ph button, .ph [data-rpl]';
  function ripple(e) {
    if (e.button > 0) return;
    var t = e.target.closest && e.target.closest(RIPPLE);
    if (!t || t.disabled || t.closest('.xslider')) return;
    if (!t._rpl) {
      t._rpl = true;
      var cs = getComputedStyle(t);
      if (cs.position === 'static') t.style.position = 'relative';
      if (cs.overflow === 'visible') t.style.overflow = 'hidden';
    }
    var r = t.getBoundingClientRect();
    var k = t.offsetWidth ? r.width / t.offsetWidth : 1;
    var x = (e.clientX - r.left) / k;
    var y = (e.clientY - r.top) / k;
    var w = t.offsetWidth;
    var h = t.offsetHeight;
    var d = 2 * Math.sqrt(Math.pow(Math.max(x, w - x), 2) + Math.pow(Math.max(y, h - y), 2));
    var s = el('span', 'ph-rpl');
    s.style.cssText = 'width:' + d + 'px;height:' + d + 'px;left:' + (x - d / 2) + 'px;top:' + (y - d / 2) + 'px';
    t.appendChild(s);
    function up() {
      document.removeEventListener('pointerup', up);
      document.removeEventListener('pointercancel', up);
      s.classList.add('is-up');
      setTimeout(function () { s.remove(); }, 520);
    }
    document.addEventListener('pointerup', up);
    document.addEventListener('pointercancel', up);
  }

  /* ---------- Snackbar ---------- */
  function snack(ph, msg) {
    if (!ph._snack) {
      ph._snack = el('div', 'ph-snack');
      ph._snack.setAttribute('role', 'status');
      ph.appendChild(ph._snack);
    }
    var s = ph._snack;
    clearTimeout(s._t);
    if (s.classList.contains('is-on')) {
      s.classList.remove('is-on');
      setTimeout(function () { s.textContent = msg; s.classList.add('is-on'); }, 180);
    } else {
      s.textContent = msg;
      void s.offsetWidth;
      s.classList.add('is-on');
    }
    s._t = setTimeout(function () { s.classList.remove('is-on'); }, 2800);
  }

  /* ---------- 面板层：底部面板与对话框共用一层遮罩 ---------- */
  function Layers(ph) {
    var self = this;
    this.ph = ph;
    this.open = null;
    this.scrim = $('[data-ph-scrim]', ph);
    if (!this.scrim) {
      this.scrim = el('div', 'sheet-scrim');
      this.scrim.setAttribute('data-ph-scrim', '');
      ph.insertBefore(this.scrim, $('.ph__gesture', ph));
    }
    this.scrim.addEventListener('click', function () { self.hide(true); });
  }
  Layers.prototype.show = function (layer, manual) {
    if (this.open === layer) return;
    var prev = this.open;
    if (prev) {
      prev.el.classList.remove('is-open');
      if (prev.onHide) prev.onHide();
    }
    this.open = layer;
    this.manual = !!manual;
    var self = this;
    setTimeout(function () {
      if (self.open !== layer) return;
      layer.el.classList.add('is-open');
      self.ph.classList.add('is-sheet');
      self.ph.classList.toggle('is-dialog', !!layer.dialog);
      if (layer.onShow) layer.onShow();
    }, prev ? 160 : 0);
  };
  Layers.prototype.hide = function (byUser) {
    var l = this.open;
    if (!l) return;
    this.open = null;
    this.manual = false;
    if (byUser) this.dismissed = l;
    l.el.classList.remove('is-open');
    this.ph.classList.remove('is-sheet', 'is-dialog');
    if (l.onHide) l.onHide();
  };

  function makeSheet(ph, cls, inner) {
    var s = el('div', 'msheet msheet--auto ' + cls, '<i class="msheet__grab"></i><div class="msheet__body">' + inner + '</div>');
    ph.insertBefore(s, $('.ph__gesture', ph));
    return s;
  }
  function makeDialog(ph, cls, inner) {
    var d = el('div', 'mdialog ' + cls, inner);
    d.setAttribute('role', 'dialog');
    ph.insertBefore(d, $('.ph__gesture', ph));
    return d;
  }

  /* 按住拖动条往下拉可以关掉面板 */
  function dragToDismiss(sheet, layers) {
    var grab = $('.msheet__grab', sheet);
    if (!grab) return;
    var y0 = 0;
    var dy = 0;
    var k = 1;
    var t0 = 0;
    var down = false;
    grab.addEventListener('pointerdown', function (e) {
      down = true;
      y0 = e.clientY;
      dy = 0;
      t0 = performance.now();
      var r = sheet.getBoundingClientRect();
      k = sheet.offsetHeight ? r.height / sheet.offsetHeight : 1;
      NP.capture(grab, e);
      sheet.classList.add('is-drag');
    });
    grab.addEventListener('pointermove', function (e) {
      if (!down) return;
      dy = Math.max(0, (e.clientY - y0) / k);
      sheet.style.translate = '0 ' + dy + 'px';
    });
    function end() {
      if (!down) return;
      down = false;
      sheet.classList.remove('is-drag');
      sheet.style.translate = '';
      var v = dy / Math.max(1, performance.now() - t0);
      if (dy > 110 || v > 0.6) layers.hide(true);
    }
    grab.addEventListener('pointerup', end);
    grab.addEventListener('pointercancel', end);
  }

  /* ---------- 音效与倍速（多台手机共享同一份状态） ---------- */
  var SPEC = {
    speed: { min: 0.1, max: 4, step: 0.05, fmt: function (v) { return v.toFixed(2) + 'x'; }, get: function () { return P.speed; }, set: function (v) { P.setSpeed(v); } },
    pitch: { min: 0.25, max: 2, step: 0.05, fmt: function (v) { return v.toFixed(2) + 'x'; }, get: function () { return P.pitch; }, set: function (v) { P.setPitch(v); } },
    loud: { min: 0, max: 15, step: 0.5, fmt: function (v) { return '+' + v.toFixed(1) + ' dB'; }, get: function () { return NP.sound.state.loud; }, set: function (v) { NP.sound.state.loud = v; NP.sound.apply(); } },
  };
  var fxInst = [];
  function fxHint(ph) {
    if (fxHinted || NP.sound.live()) return;
    fxHinted = true;
    snack(ph, L('按下播放键出声，就能听到效果', 'Press play to hear the change'));
  }
  function setFx(key, v, ph) {
    var spec = SPEC[key];
    v = M.clamp(Math.round(v / spec.step) * spec.step, spec.min, spec.max);
    spec.set(v);
    paintFx();
    fxHint(ph);
  }
  function applyPreset(i, ph) {
    var st = NP.sound.state;
    st.levels = NP.sound.presets[i][1].slice();
    st.preset = i;
    NP.sound.apply();
    paintFx();
    if (ph) fxHint(ph);
  }
  function paintFx() {
    var st = NP.sound.state;
    fxInst.forEach(function (inst) {
      Object.keys(inst.cards).forEach(function (key) {
        var c = inst.cards[key];
        var v = SPEC[key].get();
        c.xs.style.setProperty('--v', ((v - SPEC[key].min) / (SPEC[key].max - SPEC[key].min)).toFixed(4));
        c.val.textContent = SPEC[key].fmt(v);
        c.chips.forEach(function (b) { b.classList.toggle('is-on', Math.abs(parseFloat(b.getAttribute('data-v')) - v) < 1e-3); });
      });
      inst.presetEls.forEach(function (b, k) { b.classList.toggle('is-on', k === st.preset); });
      inst.bandEls.forEach(function (b, i) {
        var db = st.levels[i];
        b.xs.style.setProperty('--v', ((db + 15) / 30).toFixed(4));
        b.val.textContent = (db < 0 ? '-' : '+') + Math.abs(db).toFixed(1) + ' dB';
      });
      if (inst.sw) {
        inst.sw.classList.toggle('is-on', st.eqOn);
        inst.sw.setAttribute('aria-checked', st.eqOn ? 'true' : 'false');
        inst.eqCard.classList.toggle('is-off', !st.eqOn);
      }
    });
  }
  function initFx(ph, sheet) {
    var drag = NP.sound.dragSlider;
    var inst = { cards: {}, presetEls: [], bandEls: [], sw: null, eqCard: null };
    $$('[data-ms]', sheet).forEach(function (card) {
      var key = card.getAttribute('data-ms');
      if (!SPEC[key]) return;
      var c = { val: $('[data-ms-val]', card), xs: $('[data-xs]', card), chips: $$('[data-ms-chips] button', card) };
      inst.cards[key] = c;
      c.chips.forEach(function (b) { b.addEventListener('click', function () { setFx(key, parseFloat(b.getAttribute('data-v')), ph); }); });
      drag(c.xs, function (p) { setFx(key, SPEC[key].min + p * (SPEC[key].max - SPEC[key].min), ph); });
    });
    var eqCard = $('[data-ms="eq"]', sheet);
    var chipsBox = $('[data-eq-chips]', sheet);
    var bandsBox = $('[data-eq-bands]', sheet);
    if (eqCard && chipsBox && bandsBox) {
      inst.eqCard = eqCard;
      inst.presetEls = NP.sound.presets.map(function (p, i) {
        var b = el('button');
        b.type = 'button';
        b.textContent = p[0];
        b.addEventListener('click', function () { applyPreset(i, ph); });
        chipsBox.appendChild(b);
        return b;
      });
      inst.bandEls = NP.sound.bands.map(function (label, i) {
        var wrap = el('div', 'ms-band', '<p class="ms-band__row"><span>' + label + '</span><span data-v></span></p><div class="xslider xslider--steps"><i class="xs-on"></i><i class="xs-thumb"></i><i class="xs-off"></i></div>');
        bandsBox.appendChild(wrap);
        var xs = $('.xslider', wrap);
        drag(xs, function (p) {
          var st = NP.sound.state;
          st.levels[i] = Math.round((p * 30 - 15) * 10) / 10;
          st.preset = -1;
          NP.sound.apply();
          paintFx();
          fxHint(ph);
        });
        return { xs: xs, val: $('[data-v]', wrap) };
      });
      var sw = $('.m3switch', eqCard);
      if (sw) {
        inst.sw = sw;
        sw.setAttribute('role', 'switch');
        sw.setAttribute('aria-hidden', 'false');
        sw.setAttribute('aria-label', L('均衡器', 'Equalizer'));
        sw.tabIndex = 0;
        var flip = function () {
          NP.sound.state.eqOn = !NP.sound.state.eqOn;
          NP.sound.apply();
          paintFx();
          fxHint(ph);
        };
        sw.addEventListener('click', flip);
        sw.addEventListener('keydown', function (e) { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); flip(); } });
      }
    }
    fxInst.push(inst);
  }

  /* ---------- 一台手机的播放页 ---------- */
  function PlayerUI(ph, phone) {
    var self = this;
    this.ph = ph;
    this.phone = phone;
    this.fig = ph.closest('.phone-fig');
    this.layers = new Layers(ph);
    var lay = this.layers;

    var fxEl = $('[data-ph-msheet="fx"]', ph);
    this.fx = fxEl ? { el: fxEl, onShow: function () { var sc = $('[data-ph-sheet-scroll]', fxEl); if (sc && !self.keepFxScroll) sc.scrollTop = 0; } } : null;
    if (fxEl) { initFx(ph, fxEl); dragToDismiss(fxEl, lay); }

    this.buildMore();
    this.buildQueue();
    this.buildVolume();
    this.buildAdd();
    this.buildSleep();

    function on(sel, fn) { $$(sel, ph).forEach(function (b) { b.addEventListener('click', function (e) { fn.call(b, e); }); }); }
    on('[data-ph-more]', function () { lay.show(self.more, true); });
    on('[data-ph-queue]', function () { lay.show(self.queue, true); });
    on('[data-ph-timer]', function () { lay.show(self.sleep, true); });
    on('[data-ph-volume]', function () { lay.show(self.volume, true); });
    on('[data-ph-add]', function () { lay.show(self.add, true); });
    on('[data-ph-golyrics]', function () { self.setPage(!ph.classList.contains('is-lyrics')); });
    on('[data-ph-collapse]', function () {
      ph.classList.remove('is-nudge');
      void ph.offsetWidth;
      ph.classList.add('is-nudge');
      snack(ph, L('收起后会回到首页，在底部的迷你播放器里继续播放', 'Collapsing returns to Home, where the mini player keeps playing'));
    });
    on('[data-ph-like]', function () {
      P.liked = !P.liked;
      favCount = P.liked ? 1 : 0;
      P.emit();
      var svg = this.querySelector('svg');
      svg.classList.remove('ph-pop');
      void svg.getBoundingClientRect();
      svg.classList.add('ph-pop');
      snack(ph, P.liked ? L('已收藏', 'Favorited') : L('已取消收藏', 'Unfavorited'));
    });
    on('[data-ph-prev]', function () { snack(ph, noAudio(QUEUE[CUR - 1])); });
    on('[data-ph-next]', function () {
      if (P.repeat === 1) snack(ph, noAudio(QUEUE[0]));
    });

    this.bindSwipe();
    P.on(function () { self.sync(); });
  }
  function noAudio(q) {
    return L('网页演示只带了《星轨》的音频，暂时切不到「' + q[1] + '」', 'Only “星轨” ships with audio here, so “' + q[1] + '” can’t play');
  }
  PlayerUI.prototype.sync = function () {
    var ph = this.ph;
    $$('[data-ph-timer]', ph).forEach(function (b) {
      b.classList.toggle('is-acc', !!sleep.mode);
      var u = b.querySelector('use');
      if (u) u.setAttribute('href', sleep.mode ? '#i-timer-f' : '#i-timer');
    });
    if (this.queueCur) {
      var u = $('use', this.queueCur);
      if (u) u.setAttribute('href', P.playing && P.audible ? '#i-pause' : '#i-play-o');
    }
    if (this.addCount) this.addCount.textContent = songs(favCount);
    if (this.volXs) this.volXs.style.setProperty('--v', P.volume.toFixed(4));
  };

  PlayerUI.prototype.setPage = function (lyrics) {
    var ph = this.ph;
    if (ph.classList.contains('is-lyrics') === lyrics) return;
    ph.classList.toggle('is-lyrics', lyrics);
    if (this.fig) this.fig.classList.toggle('is-alt', lyrics !== (ph.getAttribute('data-home') === 'lyrics'));
    var ph2 = this.phone;
    if (ph2 && ph2.page) ph2.page.dirty = true;
    if (ph2 && ph2.cover) ph2.cover.dirty = true;
  };
  PlayerUI.prototype.bindSwipe = function () {
    var self = this;
    var ph = this.ph;
    var x0 = 0;
    var y0 = 0;
    var active = false;
    var moved = false;
    ph.addEventListener('pointerdown', function (e) {
      if (ph.classList.contains('is-sheet') || e.target.closest('button, canvas, .xslider, .lrc-c, .lrc-l')) { active = false; return; }
      active = true;
      moved = false;
      x0 = e.clientX;
      y0 = e.clientY;
    });
    ph.addEventListener('pointermove', function (e) {
      if (!active) return;
      var dx = e.clientX - x0;
      if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(e.clientY - y0) * 1.4) moved = true;
    });
    ph.addEventListener('pointerup', function (e) {
      if (!active || !moved) { active = false; return; }
      active = false;
      var r = ph.getBoundingClientRect();
      var k = r.width / ph.offsetWidth;
      var dx = (e.clientX - x0) / k;
      if (dx < -56 && !ph.classList.contains('is-lyrics')) self.setPage(true);
      else if (dx > 56 && ph.classList.contains('is-lyrics')) self.setPage(false);
    });
  };

  PlayerUI.prototype.buildMore = function () {
    var self = this;
    var ph = this.ph;
    var items = [
      ['i-info', L('获取歌曲信息', 'Get Song Info'), null, 'na'],
      ['i-edit', L('编辑歌曲信息', 'Edit Song Info'), null, 'na'],
      ['i-tune', L('音效与倍速', 'Audio Effects & Speed'), L('速度、音调和均衡器预设', 'Speed, pitch, and equalizer presets'), 'fx'],
      ['i-info', L('歌曲信息详情', 'Song Details'), null, 'na'],
      ['i-timer', L('调整歌词行为', 'Adjust Lyrics Behavior'), null, 'na'],
      ['i-fontsize', L('歌词字体大小', 'Lyrics Font Size'), L('歌词 100% · 翻译 100%', 'Lyrics 100% · Translation 100%'), 'na'],
      ['i-share', L('分享', 'Share'), null, 'share'],
      ['i-chart', L('播放统计', 'Playback Stats'), null, 'stats'],
      ['i-headphones', L('一起听', 'Listen Together'), null, 'together'],
    ];
    var html = '<ul class="sh-menu">' + items.map(function (it, i) {
      return '<li><button type="button" class="sh-menu__row" data-i="' + i + '">' + ic(it[0]) + '<span><b>' + it[1] + '</b>' + (it[2] ? '<small>' + it[2] + '</small>' : '') + '</span></button></li>';
    }).join('') + '</ul>';
    var s = makeSheet(ph, 'msheet--more', html);
    this.more = { el: s };
    dragToDismiss(s, this.layers);
    $$('.sh-menu__row', s).forEach(function (b) {
      b.addEventListener('click', function () {
        var it = items[+b.getAttribute('data-i')];
        var act = it[3];
        if (act === 'fx' && self.fx) { self.layers.show(self.fx, true); return; }
        if (act === 'share') {
          var text = L('分享 Neri Line 的单曲《星轨》', 'Neri Line – “星轨”') + ' · NeriPlayer ' + location.href.split('#')[0];
          var done = function () { snack(ph, L('分享文案已复制', 'Share text copied')); };
          if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(text).then(done, done);
          else done();
          self.layers.hide();
          return;
        }
        if (act === 'stats' || act === 'together') {
          self.layers.hide();
          var seg = $('#sync .seg');
          var target = act === 'together' ? $('#together') : seg ? seg.closest('.sheet') : $('#sync');
          snack(ph, act === 'together' ? L('带你去看「一起听」', 'Taking you to Listen Together') : L('带你去看「播放统计」', 'Taking you to Playback Stats'));
          setTimeout(function () { if (target) NP.scrollTo(NP.absTop(target) - 90); }, 650);
          return;
        }
        snack(ph, L('网页演示里没有接入「' + it[1] + '」', '“' + it[1] + '” isn’t wired up in this web demo'));
      });
    });
  };

  PlayerUI.prototype.buildQueue = function () {
    var self = this;
    var ph = this.ph;
    var rows = QUEUE.map(function (q, i) {
      var cur = i === CUR;
      return '<li class="sh-q' + (cur ? ' is-cur' : '') + '"><button type="button" class="sh-q__row" data-i="' + i + '"><i>' + (i + 1) + '</i><img src="' + cover(q[0]) + '" alt="" width="96" height="96" loading="lazy"><span><b>' + q[1] + '</b><small>' + q[2] + '</small></span>' + (cur ? '<em class="sh-q__now">' + ic('i-play-o') + '</em>' : '') + '</button><button type="button" class="pbtn sh-q__more" aria-label="' + L('更多', 'More') + '">' + ic('i-more') + '</button></li>';
    }).join('');
    var html = '<div class="sh-q__head"><p><b>' + L('播放列表', 'Play Queue') + '</b><small>' + songs(QUEUE.length) + '</small></p><button type="button" class="sh-q__chip" data-q-jump>' + ic('i-play-o') + L('第 ' + (CUR + 1) + ' 首', 'No. ' + (CUR + 1)) + '</button></div><ol class="sh-q__list" data-q-list>' + rows + '</ol><button type="button" class="sh-q__fab" aria-label="' + L('播放列表快捷操作', 'Queue Quick Actions') + '">' + ic('i-more') + '</button>';
    var s = makeSheet(ph, 'msheet--queue', html);
    var list = $('[data-q-list]', s);
    this.queueCur = $('.sh-q__now', s);
    function jump(smooth) {
      var row = $('.is-cur', list);
      if (!row) return;
      list.scrollTo({ top: row.offsetTop - list.clientHeight + row.offsetHeight + 24, behavior: smooth ? 'smooth' : 'auto' });
    }
    this.queue = { el: s, onShow: function () { jump(false); } };
    dragToDismiss(s, this.layers);
    $('[data-q-jump]', s).addEventListener('click', function () { jump(true); });
    $$('.sh-q__row', s).forEach(function (b) {
      b.addEventListener('click', function () {
        var i = +b.getAttribute('data-i');
        if (i === CUR) { if (!(P.playing && P.audible)) P.play(); return; }
        snack(ph, noAudio(QUEUE[i]));
      });
    });
    $$('.sh-q__more, .sh-q__fab', s).forEach(function (b) {
      b.addEventListener('click', function () { snack(ph, L('网页演示里没有接入队列操作', 'Queue actions aren’t wired up in this web demo')); });
    });
  };

  PlayerUI.prototype.buildVolume = function () {
    var html = '<p class="sh-vol__title">' + L('手机扬声器', 'Phone Speaker') + '</p><div class="sh-vol__row">' + ic('i-speaker') + '<div class="xslider sh-vol__xs" style="--v:1"><i class="xs-on"></i><i class="xs-thumb"></i><i class="xs-off"></i></div></div>';
    var s = makeSheet(this.ph, 'msheet--vol', html);
    this.volume = { el: s };
    this.volXs = $('.xslider', s);
    dragToDismiss(s, this.layers);
    NP.sound.dragSlider(this.volXs, function (p) { P.setVolume(p); });
  };

  PlayerUI.prototype.buildAdd = function () {
    var self = this;
    var ph = this.ph;
    var html = '<ul class="sh-add"><li><button type="button" class="sh-add__row"><b>' + L('我喜欢的音乐', 'My Favorite Music') + '</b><small data-add-count>' + songs(favCount) + '</small></button></li></ul>';
    var s = makeSheet(ph, 'msheet--add', html);
    this.add = { el: s };
    this.addCount = $('[data-add-count]', s);
    dragToDismiss(s, this.layers);
    $('.sh-add__row', s).addEventListener('click', function () {
      var had = P.liked;
      P.liked = true;
      favCount = 1;
      P.emit();
      self.layers.hide();
      snack(ph, had ? L('《星轨》已经在「我喜欢的音乐」里了', '“星轨” is already in My Favorite Music') : L('已添加到「我喜欢的音乐」', 'Added to My Favorite Music'));
    });
  };

  PlayerUI.prototype.buildSleep = function () {
    var self = this;
    var ph = this.ph;
    var minutes = 30;
    var html = '<div class="mdialog__card">' +
      '<span class="mdialog__icon">' + ic('i-timer-f') + '</span>' +
      '<p class="mdialog__title">' + L('睡眠定时器', 'Sleep Timer') + '</p>' +
      '<div class="mdialog__body">' +
      '<div class="sl-run" data-sl-run><small>' + L('定时器运行中', 'Timer Running') + '</small><b data-sl-left>30:00</b></div>' +
      '<p class="sl-label">' + L('倒计时', 'Countdown') + '</p>' +
      '<p class="sl-min" data-sl-min></p>' +
      '<div class="xslider xslider--steps sl-xs" data-sl-xs><i class="xs-on"></i><i class="xs-thumb"></i><i class="xs-off"></i></div>' +
      '<button type="button" class="sl-btn" data-sl="count">' + L('开始倒计时', 'Start Countdown') + '</button>' +
      '<p class="sl-label">' + L('其他模式', 'Other Modes') + '</p>' +
      '<button type="button" class="sl-btn" data-sl="current">' + ic('i-next') + L('播放完当前歌曲后停止', 'Stop after current song') + '</button>' +
      '<button type="button" class="sl-btn" data-sl="playlist">' + ic('i-playlist-play') + L('播放完播放列表后停止', 'Stop after playlist') + '</button>' +
      '</div>' +
      '<p class="mdialog__actions"><button type="button" class="mdialog__text" data-sl="cancel">' + L('取消定时器', 'Cancel Timer') + '</button><button type="button" class="mdialog__text" data-sl="close">' + L('关闭', 'Close') + '</button></p>' +
      '</div>';
    var d = makeDialog(ph, 'mdialog--sleep', html);
    var xs = $('[data-sl-xs]', d);
    var minEl = $('[data-sl-min]', d);
    var run = $('[data-sl-run]', d);
    var left = $('[data-sl-left]', d);
    var cancel = $('[data-sl="cancel"]', d);
    function paint() {
      xs.style.setProperty('--v', ((minutes - 5) / 115).toFixed(4));
      minEl.textContent = NP.en ? minutes + ' minutes' : minutes + ' 分钟';
      var active = !!sleep.mode;
      run.hidden = !active;
      cancel.hidden = !active;
      if (active) {
        left.textContent = sleep.mode === 'count' ? mmss(sleep.end - Date.now()) : sleep.mode === 'current' ? L('播放完当前歌曲后停止', 'Stop after current song') : L('播放完播放列表后停止', 'Stop after playlist');
        left.classList.toggle('is-text', sleep.mode !== 'count');
      }
    }
    NP.sound.dragSlider(xs, function (p) { minutes = Math.round((5 + p * 115) / 5) * 5; paint(); });
    $$('[data-sl]', d).forEach(function (b) {
      b.addEventListener('click', function () {
        var k = b.getAttribute('data-sl');
        if (k === 'count') { sleep.mode = 'count'; sleep.end = Date.now() + minutes * 60000; }
        else if (k === 'current' || k === 'playlist') { sleep.mode = k; }
        else if (k === 'cancel') { sleep.mode = null; }
        self.layers.hide();
        P.emit();
        if (k === 'count') snack(ph, L(minutes + ' 分钟后停止播放', 'Playback stops in ' + minutes + ' minutes'));
        else if (k === 'current') snack(ph, L('播放完当前歌曲后停止', 'Stops after the current song'));
        else if (k === 'playlist') snack(ph, L('播放完播放列表后停止', 'Stops after the playlist'));
      });
    });
    this.sleep = { el: d, dialog: true, onShow: paint, paint: paint };
  };

  /* 睡眠定时器真的会停：倒计时到点暂停；「播完当前 / 播完列表」在《星轨》放完时停（它是列表最后一首） */
  function tickSleep(uis) {
    if (!sleep.mode) return;
    var stop = false;
    if (sleep.mode === 'count') stop = Date.now() >= sleep.end;
    else stop = P.playing && P.t >= P.dur - 0.3;
    uis.forEach(function (u) { if (u.layers.open === u.sleep) u.sleep.paint(); });
    if (!stop) return;
    sleep.mode = null;
    if (P.playing) P.pause();
    uis.forEach(function (u) { snack(u.ph, L('睡眠定时器已结束，播放已暂停', 'Sleep timer finished, playback paused')); });
    P.emit();
  }

  /* ---------- 主控位置：中下 / 底部 / 底部 + 进度 ---------- */
  function initPlacement(uis) {
    var lab = $('#placeLab');
    if (!lab) return;
    var btns = $$('[data-place]', lab);
    btns.forEach(function (b) {
      b.addEventListener('click', function () {
        var v = b.getAttribute('data-place');
        btns.forEach(function (o) {
          var on = o === b;
          o.classList.toggle('is-on', on);
          o.setAttribute('aria-checked', on ? 'true' : 'false');
        });
        uis.forEach(function (u) {
          u.ph.setAttribute('data-place', v);
          if (u.fig) u.fig.setAttribute('data-place', v);
          if (u.phone && u.phone.cover) u.phone.cover.dirty = true;
        });
        var play = uis[0];
        if (play && play.ph.classList.contains('is-lyrics')) play.setPage(false);
      });
    });
  }

  /* ---------- 首页迷你播放器：左右滑切歌，带阻尼回弹 ---------- */
  function initMini() {
    var home = $('#phHome');
    var mini = home && $('.hm-mini', home);
    if (!mini) return;
    var row = $('.hm-mini__row', mini);
    var img = $('img', row);
    var title = $('.hm-mini__id b', row);
    var artist = $('.hm-mini__id span', row);
    var btn = $('[data-hm-play]', mini);
    var idx = CUR;
    var localPlaying = false;
    var PEAK = 52;
    var THRESH = 72;
    function resisted(d) { return d === 0 ? 0 : (d > 0 ? 1 : -1) * PEAK * (1 - Math.exp(-Math.abs(d) / PEAK)); }
    function set(off, anim) {
      var r = Math.min(1, Math.abs(off) / PEAK);
      row.style.transition = anim ? 'translate ' + anim + 'ms cubic-bezier(.4, 0, .2, 1), scale ' + anim + 'ms cubic-bezier(.4, 0, .2, 1)' : 'none';
      row.style.translate = off.toFixed(2) + 'px 0';
      row.style.scale = (1 - r * 0.025).toFixed(4);
    }
    function show(i) {
      idx = (i + QUEUE.length) % QUEUE.length;
      var q = QUEUE[idx];
      img.src = cover(q[0]);
      title.textContent = q[1];
      artist.textContent = q[2];
      paintBtn();
    }
    function playing() { return idx === CUR ? P.playing && P.audible : localPlaying; }
    function paintBtn() {
      var u = $('use', btn);
      var href = playing() ? '#i-pause' : '#i-play-o';
      if (u.getAttribute('href') !== href) u.setAttribute('href', href);
      btn.setAttribute('aria-label', playing() ? L('暂停', 'Pause') : L('播放', 'Play'));
    }
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (idx === CUR) P.toggle();
      else { localPlaying = !localPlaying; paintBtn(); }
    });
    var x0 = 0;
    var dist = 0;
    var down = false;
    var k = 1;
    mini.addEventListener('pointerdown', function (e) {
      if (e.target.closest('[data-hm-play]')) return;
      down = true;
      dist = 0;
      x0 = e.clientX;
      var r = mini.getBoundingClientRect();
      k = mini.offsetWidth ? r.width / mini.offsetWidth : 1;
      NP.capture(mini, e);
    });
    mini.addEventListener('pointermove', function (e) {
      if (!down) return;
      dist = (e.clientX - x0) / k;
      set(resisted(dist), 0);
    });
    function end() {
      if (!down) return;
      down = false;
      if (Math.abs(dist) >= THRESH) {
        var dir = dist < 0 ? -1 : 1;
        set(dir * PEAK, 120);
        setTimeout(function () { set(0, 180); }, 120);
        setTimeout(function () { localPlaying = true; show(idx - dir); }, 300);
      } else set(0, 160);
    }
    mini.addEventListener('pointerup', end);
    mini.addEventListener('pointercancel', end);
    P.on(paintBtn);
    $$('.hm-nav span', home).forEach(function (s) {
      if (s.classList.contains('is-on')) return;
      s.addEventListener('click', function () { snack(home, L('网页演示只还原了首页', 'This web demo only recreates Home')); });
    });
  }

  NP.phoneUI = {
    /* 两台手机各自补齐封面页与歌词页、音效面板与遮罩，之后 Phone 会把它们一起绑定 */
    prepare: function () {
      var play = $('#phPlay');
      var lyr = $('#phLyrics');
      var img = play && $('.pc-art img', play);
      if (img) coverBase = img.getAttribute('src').replace(/[^/]*$/, '');
      if (!play || !lyr) return;
      var lp = $('.pg-lyrics', lyr);
      var cp = $('.pg-cover', play);
      var gesture = $('.ph__gesture', play);
      play.insertBefore(lp.cloneNode(true), $('.sheet-scrim', play) || gesture);
      lyr.insertBefore(cp.cloneNode(true), lp);
      var fx = $('[data-ph-msheet="fx"]', play);
      var scrim = $('[data-ph-scrim]', play);
      var lg = $('.ph__gesture', lyr);
      if (scrim) lyr.insertBefore(scrim.cloneNode(true), lg);
      if (fx) lyr.insertBefore(fx.cloneNode(true), lg);
      play.setAttribute('data-home', 'cover');
      lyr.setAttribute('data-home', 'lyrics');
      lyr.classList.add('is-lyrics');
      $$('#phPlay, #phLyrics').forEach(function (ph) { ph.setAttribute('data-place', 'lower'); });
    },
    init: function (phones) {
      document.addEventListener('pointerdown', ripple, true);
      var uis = [];
      phones.forEach(function (p) {
        var ph = p.ph;
        if (!ph || !ph.hasAttribute('data-home')) return;
        uis.push(new PlayerUI(ph, p));
      });
      applyPreset(17);
      paintFx();
      initPlacement(uis);
      initMini();
      NP.addFrame(function () { tickSleep(uis); }, 7);

      var play = uis[0];
      if (play && play.fx) {
        var api = {
          open: function () {
            if (play.layers.open || play.layers.dismissed === play.fx) return;
            if (play.ph.classList.contains('is-lyrics')) play.setPage(false);
            play.layers.show(play.fx, false);
          },
          close: function () { if (play.layers.open === play.fx && !play.layers.manual) play.layers.hide(); },
          isOpen: function () { return !!play.layers.open; },
          scrollTo: function (y) { var sc = $('[data-ph-sheet-scroll]', play.fx.el); if (sc) sc.scrollTo({ top: y, behavior: NP.env.reduced ? 'auto' : 'smooth' }); },
        };
        Object.defineProperty(api, 'manual', { get: function () { return play.layers.manual; }, set: function () {} });
        NP.phones.sheet = api;
      }
    },
  };
})();
