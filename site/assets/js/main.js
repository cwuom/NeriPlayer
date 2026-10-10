/* NeriPlayer site · main —— 启动顺序、导航、路线图与锚点跳转 */
(function () {
  'use strict';

  var NP = window.NP;
  var M = NP.math;
  var $ = NP.$;
  var $$ = NP.$$;

  function navH() { var n = $('#nav'); return n ? n.offsetHeight : 64; }
  function targetY(id) {
    if (id === 'top' || !id) return 0;
    var el = document.getElementById(id);
    if (!el) return null;
    var extra = el.classList.contains('station') ? 10 : 24;
    return NP.absTop(el) - navH() - extra;
  }

  /* ---------- 导航与路线图 ---------- */
  var nav = {
    stops: [],
    idx: -1,
    hidden: false,
    solid: false,
    init: function () {
      this.el = $('#nav');
      this.route = $('#route');
      this.ink = $('#routeInk');
      this.cat = $('#routeCat');
      this.items = $$('.route__stops li');
      this.links = $$('.nav__links a');
      this.inkLen = this.ink && this.ink.getTotalLength ? this.ink.getTotalLength() : 0;
      document.addEventListener('click', function (e) {
        var a = e.target.closest && e.target.closest('a[href^="#"]');
        if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return;
        var id = (a.getAttribute('href') || '').slice(1);
        if (id === 'main') return;
        var y = targetY(id);
        if (y === null) return;
        e.preventDefault();
        menu.close();
        NP.scrollTo(y);
        if (history.replaceState) history.replaceState(null, '', id === 'top' ? location.pathname + location.search : '#' + id);
      });
      this.measure();
    },
    measure: function () {
      this.stops = $$('[data-station]').map(function (el) {
        return { i: +el.getAttribute('data-station'), y: NP.absTop(el), id: el.id };
      }).sort(function (a, b) { return a.i - b.i; });
      this.railH = this.route ? this.route.clientHeight : 0;
      this.idx = -1;
    },
    update: function () {
      var s = this.stops;
      if (!s.length) return;
      var sy = NP.scroll.y;
      var y = sy + NP.vp.h * 0.4;
      var i = 0;
      while (i < s.length - 1 && s[i + 1].y <= y) i++;
      if (i !== this.idx) {
        this.idx = i;
        // 路线图上没有站务室，FAQ 时停在终点
        var stop = Math.min(i, this.items.length - 1);
        this.items.forEach(function (li, k) {
          li.classList.toggle('is-on', k === stop);
          li.classList.toggle('is-passed', k < stop);
        });
        var href = '#' + s[i].id;
        this.links.forEach(function (a) { a.classList.toggle('is-on', a.getAttribute('href') === href); });
      }
      // 路线进度按站计：当前站 + 到下一站的比例，与等距排布的站点对齐
      var last = this.items.length - 1;
      var next = s[Math.min(i + 1, s.length - 1)];
      var frac = next.y > s[i].y ? M.clamp((y - s[i].y) / (next.y - s[i].y)) : 0;
      var pos = last > 0 ? M.clamp((i + frac) / last) : 0;
      if (this.ink) this.ink.style.strokeDashoffset = (1 - pos).toFixed(4);
      if (this.cat && this.railH && this.inkLen) {
        var pt = this.ink.getPointAtLength(this.inkLen * pos);
        var ty = (pt.y / 600) * this.railH;
        this.cat.style.transform = 'translate3d(' + (pt.x - 12).toFixed(1) + 'px,' + ty.toFixed(1) + 'px,0)';
      }
      var solid = sy > 24;
      if (solid !== this.solid) { this.solid = solid; this.el.classList.toggle('is-solid', solid); }
      var vy = NP.scroll.vy;
      var hide = sy > NP.vp.h * 0.9 && vy > 60 && !menu.isOpen;
      var show = vy < -40 || sy < NP.vp.h * 0.5;
      if (hide && !this.hidden) { this.hidden = true; this.el.classList.add('is-hidden'); }
      else if (show && this.hidden) { this.hidden = false; this.el.classList.remove('is-hidden'); }
    },
  };

  /* ---------- 移动端目录 ---------- */
  var menu = {
    isOpen: false,
    init: function () {
      this.el = $('#menu');
      this.btn = $('#burger');
      if (!this.el || !this.btn) return;
      var self = this;
      $$('.menu__list a').forEach(function (a, k) { a.style.setProperty('--k', k); });
      this.btn.addEventListener('click', function () { self.isOpen ? self.close() : self.open(); });
      document.addEventListener('keydown', function (e) { if (e.key === 'Escape') self.close(); });
    },
    open: function () {
      this.isOpen = true;
      this.el.hidden = false;
      void this.el.offsetWidth;
      this.el.classList.add('is-open');
      this.btn.setAttribute('aria-expanded', 'true');
      this.btn.setAttribute('aria-label', NP.L('关闭目录', 'Close menu'));
      NP.lockScroll(true);
      $('#nav').classList.remove('is-hidden');
    },
    close: function () {
      if (!this.isOpen) return;
      var el = this.el;
      this.isOpen = false;
      el.classList.remove('is-open');
      this.btn.setAttribute('aria-expanded', 'false');
      this.btn.setAttribute('aria-label', NP.L('打开目录', 'Open menu'));
      NP.lockScroll(false);
      setTimeout(function () { if (!menu.isOpen) el.hidden = true; }, 450);
    },
  };

  /* ---------- 启动 ---------- */
  function relayout() { nav.measure(); }

  function boot() {
    NP.measure(true);
    NP.motion.initTheme();
    NP.phones.init();
    NP.demos.init();
    nav.init();
    menu.init();
    NP.smoothScroll.init();
    NP.motion.init();
    NP.github.init();
    NP.onResize(relayout);

    if (window.ResizeObserver) {
      var t = 0;
      var lastH = document.documentElement.scrollHeight;
      new ResizeObserver(function () {
        var h = document.documentElement.scrollHeight;
        if (Math.abs(h - lastH) < 2) return;
        lastH = h;
        clearTimeout(t);
        t = setTimeout(relayout, 120);
      }).observe($('#main'));
    }
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(relayout);
    window.addEventListener('load', relayout);

    NP.addFrame(function (dt) { NP.smoothScroll.update(dt); }, 0);
    NP.addFrame(function (dt) { NP.scroll.update(dt); }, 1);
    NP.addFrame(function () { nav.update(); }, 6);

    var hash = location.hash.replace('#', '');
    if (hash) {
      setTimeout(function () {
        relayout();
        var y = targetY(hash);
        if (y !== null) NP.scrollTo(y, true);
      }, 60);
    }

    NP.start();
    NP.booted = true;
    document.documentElement.classList.add('np-ready');
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
