/* NeriPlayer site · desktop —— 桌面端站台：随明暗切换的真实截图、桌面歌词 / 托盘 / 媒体会话演示、应用内快捷键 */
(function () {
  'use strict';

  var NP = window.NP;
  var M = NP.math;
  var $ = NP.$;
  var $$ = NP.$$;
  var L = NP.L;
  var P = NP.player;
  var reduced = NP.env.reduced;

  /* ---------- 截图跟着页面明暗换成对应主题 ---------- */
  var ready = {};
  function isDark() { return document.documentElement.getAttribute('data-theme') === 'dark'; }
  function themed(src, dark) { return src.replace(dark ? '-light-' : '-dark-', dark ? '-dark-' : '-light-'); }
  function swapThemed(instant) {
    var dark = isDark();
    $$('img[data-themed]').forEach(function (img) {
      var cur = img.getAttribute('src');
      var want = themed(cur, dark);
      if (cur === want) return;
      // 已预载的直接换，主题切换的圆形揭示里就是新图；没预载的叠一张淡入，避免闪成空白
      if (instant || ready[want] || !img.complete || !img.naturalWidth || reduced) { img.setAttribute('src', want); return; }
      var ghost = img.cloneNode(false);
      ghost.removeAttribute('data-themed');
      ghost.removeAttribute('loading');
      ghost.className = 'theme-ghost';
      ghost.alt = '';
      ghost.src = want;
      img.parentNode.insertBefore(ghost, img.nextSibling);
      var done = function () {
        ghost.classList.add('is-on');
        setTimeout(function () { img.setAttribute('src', want); setTimeout(function () { ghost.remove(); }, 80); }, 620);
      };
      if (ghost.decode) ghost.decode().then(done, done); else ghost.onload = done;
    });
  }
  function preloadThemed() {
    var dark = isDark();
    $$('img[data-themed]').forEach(function (img) {
      var alt = themed(img.getAttribute('src'), !dark);
      if (ready[alt] || alt === img.getAttribute('src')) return;
      var im = new Image();
      im.src = alt;
      var ok = function () { ready[alt] = true; };
      if (im.decode) im.decode().then(ok, function () {}); else im.onload = ok;
    });
  }
  function initThemed() {
    if (!$('img[data-themed]')) return;
    swapThemed(true);
    new MutationObserver(function () { swapThemed(false); }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    var asked = false;
    NP.whenVisible($('#deskStage'), function () {
      if (asked) return;
      asked = true;
      var go = function () { setTimeout(function () { if (window.requestIdleCallback) requestIdleCallback(preloadThemed, { timeout: 2000 }); else preloadThemed(); }, 1200); };
      if (document.readyState === 'complete') go(); else window.addEventListener('load', go);
    }, null, '600px 0px');
  }

  /* ---------- 桌面歌词：与 App 同一套配色 ---------- */
  var THEMES = [
    { id: 'aurora', name: L('极光', 'Aurora'), played: ['#a8f0ff', '#7c9cff'], unplayed: ['#ffffff', '#e4e9ff'], sub: '#e8ecff', stroke: '#0b1020' },
    { id: 'classic', name: L('经典', 'Classic'), played: ['#5ad1ff', '#1e88ff'], unplayed: ['#ffffff', '#ffffff'], sub: '#ffffff', stroke: '#000000' },
    { id: 'sakura', name: L('樱花', 'Sakura'), played: ['#ffd6ea', '#ff7eb6'], unplayed: ['#ffffff', '#ffeef6'], sub: '#ffe3f0', stroke: '#2a0f1c' },
    { id: 'sunset', name: L('落日', 'Sunset'), played: ['#ffe29a', '#ff8a4c'], unplayed: ['#ffffff', '#fff3e4'], sub: '#fff0dc', stroke: '#2a1408' },
    { id: 'mint', name: L('薄荷', 'Mint'), played: ['#d0fff0', '#2fd6a3'], unplayed: ['#ffffff', '#eafff7'], sub: '#e3fff5', stroke: '#062018' },
    { id: 'neon', name: L('霓虹', 'Neon'), played: ['#f7a8ff', '#7c4dff'], unplayed: ['#e3f8ff', '#b8e6fb'], sub: '#e9ddff', stroke: '#12002b' },
    { id: 'mono', name: L('素白', 'Mono'), played: ['#ffffff', '#ffffff'], unplayed: ['#a3a3a3', '#c2c2c2'], sub: '#d6d6d6', stroke: '#000000' },
  ];

  function Scene(root) {
    this.root = root;
    this.screen = $('.desk__screen', root);
    this.lyr = $('#deskLyr', root);
    this.stage = $('[data-dl-stage]', root);
    this.menubar = $('[data-dl-menubar]', root);
    this.flyout = $('[data-flyout]', root);
    this.flyProg = $('[data-flyout-prog]', root);
    this.tpop = $('[data-tpop]', root);
    this.trayBtns = $$('[data-tray]', root);
    this.toastEl = $('[data-desk-toast]', root);
    this.st = { layout: 'single', sec: 'translation', theme: 'aurora', locked: false, open: true, os: 'win', x: 0.5, y: 0.66 };
    this.idx = -2;
    this.visible = false;
    this.build();
  }
  Scene.prototype.build = function () {
    var self = this;
    var root = this.root;
    var box = $('[data-dl-themes]', root);
    THEMES.forEach(function (t, i) {
      var b = document.createElement('button');
      b.type = 'button';
      b.setAttribute('role', 'radio');
      b.className = 'dctl__sw' + (i === 0 ? ' is-on' : '');
      b.setAttribute('aria-checked', i === 0 ? 'true' : 'false');
      b.setAttribute('aria-label', t.name);
      b.title = t.name;
      b.style.setProperty('--a', t.played[0]);
      b.style.setProperty('--b', t.played[1]);
      b.innerHTML = '<i></i><span>' + t.name + '</span>';
      b.addEventListener('click', function () { self.setTheme(t.id); });
      box.appendChild(b);
    });
    this.setTheme('aurora');
    function radio(attr, key, after) {
      var btns = $$('[' + attr + ']', root);
      btns.forEach(function (b) {
        b.addEventListener('click', function () {
          var v = b.getAttribute(attr);
          btns.forEach(function (o) { var on = o === b; o.classList.toggle('is-on', on); o.setAttribute('aria-checked', on ? 'true' : 'false'); });
          if (after) after(v); else { self.st[key] = v; self.idx = -2; self.lyr.setAttribute('data-' + key, v); }
        });
      });
    }
    radio('data-dl-layout', 'layout', function (v) {
      self.st.layout = v;
      self.lyr.setAttribute('data-layout', v);
      var secSeg = $('[data-dl-sec]', root).closest('.seg');
      secSeg.classList.toggle('is-disabled', v === 'double');
      self.idx = -2;
    });
    radio('data-dl-sec', 'sec');
    radio('data-os', 'os', function (v) { self.setOS(v); });
    var lock = $('#dlLock', root);
    lock.addEventListener('change', function () { self.setLock(lock.checked); });
    this.lockInput = lock;

    $$('[data-dl-act]', root).forEach(function (b) {
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        var a = b.getAttribute('data-dl-act');
        if (a === 'toggle') { P.toggle(); self.fly(); }
        else if (a === 'prev' || a === 'next') { self.toast(L('网页演示只带了《星轨》，切歌就先不演示了', 'Only “星轨” ships with audio here, so skipping is off')); self.fly(); }
        else if (a === 'lock') { self.setLock(true); }
        else if (a === 'unlock') { self.setLock(false); }
        else if (a === 'close') { self.setOpen(false); self.toast(L('桌面歌词已关闭，可以从托盘菜单或按 D 重新打开', 'Desktop lyrics closed — reopen them from the tray menu or press D')); }
      });
    });

    this.trayBtns.forEach(function (b) { b.addEventListener('click', function (e) { e.stopPropagation(); self.setTray(!self.trayOpen); }); });
    $$('[data-tp]', this.tpop).forEach(function (b) {
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        var a = b.getAttribute('data-tp');
        if (a === 'lyrics') { self.setOpen(!self.st.open); return; }
        self.setTray(false);
        if (a === 'show') self.setApp(true);
        else if (a === 'quit') self.toast(L('这里是网页，退出就不演示啦', 'This is a web page — quitting would end the demo'));
      });
    });
    document.addEventListener('pointerdown', function (e) { if (self.trayOpen && !e.target.closest('[data-tpop], [data-tray]')) self.setTray(false); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && self.trayOpen) self.setTray(false); });

    this.app = $('[data-desk-app]', root);
    var closeApp = function () {
      self.setApp(false);
      self.toast(L('主窗口关进了托盘，音乐不会停', 'Closed to the tray — the music keeps playing'));
    };
    $('[data-desk-close]', root).addEventListener('click', closeApp);
    if (NP.desktopSim && NP.desktopSim.mini) {
      this.mini = NP.desktopSim.mini($('[data-desk-sim]', root), {
        frame: this.app,
        os: this.st.os,
        onClose: closeApp,
        onMinimize: function () { self.setApp(false); self.toast(L('最小化了，从托盘或任务栏都能叫回来', 'Minimized — bring it back from the tray or taskbar')); },
        toast: function (m) { self.toast(m); },
      });
    }

    this.bindDrag();
    this.bindLockHover();
    this.clocks = $$('[data-desk-clock]', root);
    this.tickClock();
    setInterval(function () { self.tickClock(); }, 15000);
    P.on(function () { self.sync(); });
    NP.whenVisible(this.screen, function () { self.visible = true; self.idx = -2; P.startPreview(); }, function () { self.visible = false; self.leave(); }, '60px 0px');
    NP.addFrame(function (dt) { self.frame(dt); }, 8);
    NP.onResize(function () { self.place(); });
    this.place();
  };
  Scene.prototype.leave = function () {
    var any = (NP.phones && NP.phones.list || []).some(function (p) { return p.visible && (p.cover || p.page); });
    if (!any) P.stopPreview();
  };
  Scene.prototype.setTheme = function (id) {
    var t = THEMES.filter(function (x) { return x.id === id; })[0] || THEMES[0];
    this.st.theme = t.id;
    var s = this.lyr.style;
    s.setProperty('--p1', t.played[0]);
    s.setProperty('--p2', t.played[1]);
    s.setProperty('--u1', t.unplayed[0]);
    s.setProperty('--u2', t.unplayed[1]);
    s.setProperty('--sub', t.sub);
    s.setProperty('--stroke', t.stroke);
    $$('.dctl__sw', this.root).forEach(function (b, i) {
      var on = THEMES[i].id === t.id;
      b.classList.toggle('is-on', on);
      b.setAttribute('aria-checked', on ? 'true' : 'false');
    });
  };
  Scene.prototype.setOS = function (os) {
    this.st.os = os;
    this.root.setAttribute('data-os', os);
    if (this.mini) this.mini.setOS(os);
    this.setTray(false);
    this.flyout.classList.remove('is-on');
  };
  Scene.prototype.setLock = function (on) {
    this.st.locked = on;
    this.lyr.classList.toggle('is-locked', on);
    if (this.lockInput.checked !== on) this.lockInput.checked = on;
    this.toast(on ? L('已锁定：鼠标可以穿过歌词，悬停时右侧会出现解锁按钮', 'Locked: clicks pass through; hover to reveal the unlock button') : L('已解锁，可以拖动歌词条', 'Unlocked — drag the lyrics bar around'));
  };
  Scene.prototype.setOpen = function (on) {
    this.st.open = on;
    this.lyr.classList.toggle('is-closed', !on);
    var sw = $('[data-tp="lyrics"]', this.tpop);
    sw.setAttribute('aria-checked', on ? 'true' : 'false');
    sw.classList.toggle('is-on', on);
  };
  Scene.prototype.setApp = function (on) {
    var app = this.app;
    if (!app) return;
    if (on && !app.classList.contains('is-tray')) {
      app.classList.remove('is-bump');
      void app.offsetWidth;
      app.classList.add('is-bump');
      return;
    }
    app.classList.toggle('is-tray', !on);
  };
  Scene.prototype.setTray = function (on) {
    this.trayOpen = on;
    this.tpop.classList.toggle('is-open', on);
    this.trayBtns.forEach(function (b) { b.setAttribute('aria-expanded', on ? 'true' : 'false'); b.classList.toggle('is-on', on); });
    if (on) this.flyout.classList.remove('is-on');
  };
  Scene.prototype.fly = function () {
    var f = this.flyout;
    if (this.trayOpen) return;
    f.classList.add('is-on');
    clearTimeout(this._fly);
    this._fly = setTimeout(function () { f.classList.remove('is-on'); }, 2600);
  };
  Scene.prototype.toast = function (msg) {
    var t = this.toastEl;
    clearTimeout(t._h);
    var show = function () { t.textContent = msg; t.classList.add('is-on'); t._h = setTimeout(function () { t.classList.remove('is-on'); }, 2600); };
    if (t.classList.contains('is-on')) { t.classList.remove('is-on'); t._h = setTimeout(show, 200); } else show();
  };
  Scene.prototype.tickClock = function () {
    var d = new Date();
    var hm = ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
    var date = d.getFullYear() + '/' + (d.getMonth() + 1) + '/' + d.getDate();
    this.clocks.forEach(function (c) {
      var html = c.classList.contains('tb-clock') ? '<b>' + hm + '</b><small>' + date + '</small>' : hm;
      if (c.innerHTML !== html) c.innerHTML = html;
    });
  };
  Scene.prototype.sync = function () {
    var playing = P.playing && P.audible;
    var href = playing ? '#i-pause' : '#i-play';
    $$('[data-dl-act="toggle"]', this.root).forEach(function (b) {
      var u = $('use', b);
      if (u.getAttribute('href') !== href) {
        u.setAttribute('href', href);
        var svg = $('svg', b);
        svg.classList.remove('ph-icon-swap');
        void svg.getBoundingClientRect();
        svg.classList.add('ph-icon-swap');
      }
      b.setAttribute('aria-label', playing ? L('暂停', 'Pause') : L('播放', 'Play'));
    });
    this.tpop.classList.toggle('is-playing', playing);
  };

  /* 拖动歌词条，位置按屏幕比例记，缩放窗口也不会跑出去 */
  Scene.prototype.place = function () {
    var sw = this.screen.clientWidth;
    var sh = this.screen.clientHeight;
    var w = this.lyr.offsetWidth;
    var h = this.lyr.offsetHeight;
    if (!sw || !w) return;
    var x = M.clamp(this.st.x * sw - w / 2, 6, sw - w - 6);
    var y = M.clamp(this.st.y * sh - h / 2, 30, sh - h - 50);
    this.lyr.style.translate = x.toFixed(1) + 'px ' + y.toFixed(1) + 'px';
  };
  Scene.prototype.bindDrag = function () {
    var self = this;
    var lyr = this.lyr;
    var down = false;
    var sx = 0;
    var sy = 0;
    var ox = 0;
    var oy = 0;
    lyr.addEventListener('pointerdown', function (e) {
      if (self.st.locked || e.button > 0 || e.target.closest('button')) return;
      down = true;
      sx = e.clientX;
      sy = e.clientY;
      ox = self.st.x;
      oy = self.st.y;
      NP.capture(lyr, e);
      lyr.classList.add('is-drag');
    });
    lyr.addEventListener('pointermove', function (e) {
      if (!down) return;
      var r = self.screen.getBoundingClientRect();
      self.st.x = M.clamp(ox + (e.clientX - sx) / r.width, 0.05, 0.95);
      self.st.y = M.clamp(oy + (e.clientY - sy) / r.height, 0.1, 0.9);
      self.place();
    });
    function up() { down = false; lyr.classList.remove('is-drag'); }
    lyr.addEventListener('pointerup', up);
    lyr.addEventListener('pointercancel', up);
  };
  /* 锁定后歌词条不接收指针，悬停判断交给整块屏幕 */
  Scene.prototype.bindLockHover = function () {
    var self = this;
    this.screen.addEventListener('pointermove', function (e) {
      if (!self.st.locked) return;
      var r = self.lyr.getBoundingClientRect();
      var inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
      self.lyr.classList.toggle('is-peek', inside);
    });
    this.screen.addEventListener('pointerleave', function () { self.lyr.classList.remove('is-peek'); });
  };

  /* ---------- 逐字渲染 ---------- */
  function wordsHtml(line, plain) {
    return line.words.map(function (w) {
      var t = w.text.replace(/&/g, '&amp;').replace(/</g, '&lt;');
      return plain ? t : '<span class="kw" data-t="' + t.replace(/"/g, '&quot;') + '">' + t + '</span>';
    }).join('');
  }
  function lineEl(cls, html) {
    var p = document.createElement('p');
    p.className = cls;
    p.innerHTML = html;
    return p;
  }
  Scene.prototype.render = function (idx) {
    var LY = NP.LYRICS;
    var st = this.st;
    var stage = this.stage;
    var cur = LY[idx];
    var next = LY[idx + 1];
    var prev = LY[idx - 1];
    var self = this;
    var old = $$('.dl-line', stage);
    old.forEach(function (o) { o.classList.add('is-leave'); setTimeout(function () { o.remove(); }, 160); });
    var frag = document.createDocumentFragment();
    var title = L('星轨 — Neri Line', '星轨 — Neri Line');
    this.kws = [];
    if (st.layout === 'double') {
      var even = idx < 0 ? 0 : idx % 2 === 0 ? idx : idx + 1;
      var odd = idx < 0 ? 1 : idx % 2 === 1 ? idx : idx + 1;
      var top = LY[even];
      var bot = LY[odd];
      var a = lineEl('dl-line dl-main dl-left' + (idx === even ? '' : ' is-up'), top ? wordsHtml(top, idx !== even) : title);
      var b = lineEl('dl-line dl-main dl-right' + (idx === odd ? '' : ' is-up'), bot ? wordsHtml(bot, idx !== odd) : '&nbsp;');
      frag.appendChild(a);
      frag.appendChild(b);
      this.kwLine = idx === even ? a : b;
    } else {
      if (st.layout === 'triple') frag.appendChild(lineEl('dl-line dl-adj', prev ? wordsHtml(prev, true) : '&nbsp;'));
      var main = lineEl('dl-line dl-main is-enter', cur ? wordsHtml(cur) : title);
      frag.appendChild(main);
      this.kwLine = main;
      var subText = st.sec === 'translation' ? (cur ? cur.tr : '') : st.sec === 'next' ? ((idx < 0 ? LY[0] : next) ? wordsHtml(idx < 0 ? LY[0] : next, true) : '') : '';
      if (st.sec !== 'none') frag.appendChild(lineEl('dl-line dl-sub', subText || '&nbsp;'));
      if (st.layout === 'triple') frag.appendChild(lineEl('dl-line dl-adj', (st.sec === 'next' ? LY[idx + 2] : next) ? wordsHtml(st.sec === 'next' ? LY[idx + 2] : next, true) : '&nbsp;'));
    }
    stage.appendChild(frag);
    this.kwLineData = cur;
    this.kws = this.kwLine ? $$('.kw', this.kwLine) : [];
    this.lastP = [];
    requestAnimationFrame(function () { $$('.is-enter', stage).forEach(function (e) { e.classList.remove('is-enter'); }); self.place(); });
    if (this.menubar) {
      var mt = cur ? cur.words.map(function (w) { return w.text; }).join('') : L('星轨 · Neri Line', '星轨 · Neri Line');
      if (NP.swapText) NP.swapText(this.menubar, mt); else this.menubar.textContent = mt;
    }
  };
  Scene.prototype.frame = function () {
    if (!this.visible || !NP.LYRICS) return;
    var t = P.t;
    var idx = NP.lyricIndexAt(t);
    if (idx !== this.idx) { this.idx = idx; this.render(idx); }
    var line = this.kwLineData;
    if (line && this.kws.length) {
      for (var k = 0; k < line.words.length && k < this.kws.length; k++) {
        var w = line.words[k];
        var p = M.clamp((t - w.s) / Math.max(0.05, w.e - w.s));
        p = Math.round(p * 60) / 60;
        if (this.lastP[k] !== p) { this.lastP[k] = p; this.kws[k].style.setProperty('--p', p); }
      }
    }
    if (this.flyProg) this.flyProg.style.scale = M.clamp(t / P.dur).toFixed(4) + ' 1';
  };

  /* ---------- 应用内快捷键：聚焦演示区时才接管按键 ---------- */
  function initKeys(scene) {
    var box = $('#deskKeys');
    if (!box) return;
    var hint = $('[data-keys-hint]', box);
    var idle = L('点这里，然后按键盘试试', 'Click here, then try the keyboard');
    var live = L('正在接管键盘 · 点别处或按 Tab 离开', 'Listening to the keyboard · click elsewhere or press Tab to leave');
    box.addEventListener('focus', function () { NP.swapText(hint, live); box.classList.add('is-live'); });
    box.addEventListener('blur', function () { NP.swapText(hint, idle); box.classList.remove('is-live'); });
    var muted = null;
    function hit(k) {
      var li = $('[data-k="' + k + '"]', box);
      if (!li) return;
      li.classList.remove('is-hit');
      void li.offsetWidth;
      li.classList.add('is-hit');
    }
    box.addEventListener('keydown', function (e) {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      var k = e.key;
      var msg = null;
      if (k === ' ' || k === 'Spacebar') { P.toggle(); hit('space'); scene.fly(); msg = P.audible ? L('播放', 'Play') : L('暂停', 'Pause'); }
      else if (k === 'ArrowLeft' || k === 'ArrowRight') {
        var d = (k === 'ArrowLeft' ? -1 : 1) * (e.shiftKey ? 30 : 5);
        P.seek(P.t + d);
        hit(e.shiftKey ? 'shift' : 'left');
        scene.fly();
        msg = (d < 0 ? L('快退 ', 'Back ') : L('快进 ', 'Forward ')) + Math.abs(d) + L(' 秒', ' s');
      } else if (k === 'ArrowUp' || k === 'ArrowDown') {
        P.setVolume(Math.round((P.volume + (k === 'ArrowUp' ? 0.1 : -0.1)) * 10) / 10);
        hit('up');
        muted = null;
        msg = L('音量 ', 'Volume ') + Math.round(P.volume * 100) + '%';
      } else if (k === 'm' || k === 'M') {
        if (muted == null) { muted = P.volume; P.setVolume(0); msg = L('已静音', 'Muted'); } else { P.setVolume(muted || 1); muted = null; msg = L('取消静音', 'Unmuted'); }
        hit('m');
      } else if (k === 'l' || k === 'L') {
        P.liked = !P.liked;
        P.emit();
        hit('l');
        msg = P.liked ? L('已收藏《星轨》', 'Liked “星轨”') : L('已取消收藏', 'Removed from liked');
      } else if (k === 'd' || k === 'D') {
        scene.setOpen(!scene.st.open);
        hit('d');
        msg = scene.st.open ? L('打开桌面歌词', 'Desktop lyrics on') : L('关闭桌面歌词', 'Desktop lyrics off');
      } else return;
      e.preventDefault();
      if (msg) scene.toast(msg);
    });
  }

  /* ---------- 仿真窗口的导览：标签 ↔ 仿真里的页面，图钉与图例跟着走 ---------- */
  var VIEWS = ['home', 'np', 'library', 'fx', 'shortcuts'];
  function initStage(sim) {
    var stage = $('#deskStage');
    if (!stage) return;
    var tabs = $$('[role="tab"]', stage);
    var pins = $$('.dpins', stage);
    var legends = $$('.tablegend', stage);
    var panel = $('#deskScreen', stage);
    var cur = 0;
    function show(i) {
      tabs.forEach(function (t, k) {
        var on = k === i;
        t.classList.toggle('is-on', on);
        t.setAttribute('aria-selected', on ? 'true' : 'false');
        t.tabIndex = on || (i < 0 && k === cur) ? 0 : -1;
      });
      pins.forEach(function (p, k) { p.classList.toggle('is-on', k === i); });
      if (i < 0) return;
      cur = i;
      panel.setAttribute('aria-labelledby', tabs[i].id);
      legends.forEach(function (l, k) {
        if (k !== i) { l.classList.remove('is-on'); return; }
        if (l.classList.contains('is-on')) return;
        l.classList.remove('is-in');
        l.classList.add('is-on');
        void l.offsetWidth;
        l.classList.add('is-in');
      });
    }
    tabs.forEach(function (t, k) {
      t.addEventListener('click', function () { show(k); if (sim) sim.go(VIEWS[k]); });
      t.addEventListener('keydown', function (e) {
        var d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
        if (!d) return;
        e.preventDefault();
        var n = (cur + d + tabs.length) % tabs.length;
        tabs[n].focus();
        tabs[n].click();
      });
    });
    legends.forEach(function (l, k) {
      $$('li', l).forEach(function (li, j) {
        var pin = pins[k] && $$('.tabpin', pins[k])[j];
        if (!pin) return;
        li.addEventListener('pointerenter', function () { li.classList.add('is-hot'); pin.classList.add('is-hot'); });
        li.addEventListener('pointerleave', function () { li.classList.remove('is-hot'); pin.classList.remove('is-hot'); });
      });
    });
    if (sim) sim.onView(function (v) { show(VIEWS.indexOf(v)); });
  }

  /* 窗口与示意桌面共用一个系统选择，默认跟访客的系统走 */
  function guessOS() {
    var plat = ((navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || navigator.userAgent || '').toLowerCase();
    if (/mac|iphone|ipad/.test(plat)) return 'mac';
    if (/linux|x11|cros/.test(plat) && !/android/.test(plat)) return 'linux';
    return 'win';
  }
  function initOS(sim, scene) {
    var btns = $$('[data-sim-os]');
    function set(os) {
      btns.forEach(function (b) { var on = b.getAttribute('data-sim-os') === os; b.classList.toggle('is-on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
      if (sim) sim.setOS(os);
      if (scene && scene.st.os !== os) {
        scene.setOS(os);
        $$('.desk__os [data-os]', scene.root).forEach(function (b) { var on = b.getAttribute('data-os') === os; b.classList.toggle('is-on', on); b.setAttribute('aria-checked', on ? 'true' : 'false'); });
      }
    }
    btns.forEach(function (b) { b.addEventListener('click', function () { set(b.getAttribute('data-sim-os')); }); });
    if (scene) $$('.desk__os [data-os]', scene.root).forEach(function (b) { b.addEventListener('click', function () { set(b.getAttribute('data-os')); }); });
    var os = guessOS();
    set(os);
    // 能力表：标出访客自己的系统（手机上不标）
    var plat = $('#deskPlat');
    if (plat && !/Android|iPhone|iPad|iPod/i.test(navigator.userAgent || '')) {
      $$('[data-col="' + os + '"]', plat).forEach(function (c) { c.classList.add('is-you'); });
      var you = $('[data-plat-you]', plat);
      if (you) { $('[data-plat-you-name]', you).textContent = { win: 'Windows', mac: 'macOS', linux: 'Linux' }[os]; you.hidden = false; }
    }
  }

  NP.desktop = {
    init: function () {
      initThemed();
      var sim = NP.desktopSim ? NP.desktopSim.init() : null;
      initStage(sim);
      var root = $('#deskScene');
      var scene = root && NP.LYRICS ? new Scene(root) : null;
      if (scene) { initKeys(scene); NP.desktop.scene = scene; }
      initOS(sim, scene);
    },
  };
})();
