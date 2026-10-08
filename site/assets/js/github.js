/* NeriPlayer site · github —— 最新版本、各 ABI 安装包、星标与累计下载（带本地缓存与降级） */
(function () {
  'use strict';

  var NP = window.NP;
  var $ = NP.$;
  var $$ = NP.$$;
  var REPO = 'cwuom/NeriPlayer';
  var API = 'https://api.github.com/repos/' + REPO;
  var RELEASES = 'https://github.com/' + REPO + '/releases';

  function cached(key, ttlMin, url, map) {
    var k = 'np-site:' + key;
    try {
      var raw = localStorage.getItem(k);
      if (raw) {
        var hit = JSON.parse(raw);
        if (Date.now() - hit.t < ttlMin * 60000) return Promise.resolve(hit.v);
      }
    } catch (e) { /* 隐私模式下 localStorage 不可用 */ }
    var ctrl = 'AbortController' in window ? new AbortController() : null;
    var timer = ctrl ? setTimeout(function () { ctrl.abort(); }, 12000) : 0;
    return fetch(url, { headers: { Accept: 'application/vnd.github+json' }, signal: ctrl ? ctrl.signal : undefined })
      .then(function (r) {
        clearTimeout(timer);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (v) {
        if (map) v = map(v);
        try { localStorage.setItem(k, JSON.stringify({ t: Date.now(), v: v })); } catch (e) { /* 配额已满时忽略 */ }
        return v;
      });
  }

  function fmtCount(n) {
    if (n >= 10000 && !NP.en) return (n / 10000).toFixed(1).replace(/\.0$/, '') + ' 万';
    if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
  }
  function fmtSize(b) { return (b / 1048576).toFixed(1) + ' MB'; }
  function fmtDate(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return '—';
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
  }
  function setAll(name, text) { $$('[data-gh="' + name + '"]').forEach(function (el) { el.textContent = text; }); }

  function abiOf(name) {
    var m = /-(arm64-v8a|armeabi-v7a|x86_64|x86)\.apk$/i.exec(name);
    if (m) return m[1].toLowerCase();
    return /\.apk$/i.test(name) ? 'universal' : null;
  }

  var release = null;

  function currentAbi() {
    var el = $('input[name="abi"]:checked');
    return el ? el.value : 'arm64-v8a';
  }

  function applyAbi() {
    var btn = $('#dlBtn');
    var size = $('#dlSize');
    var sha = $('#dlSha');
    if (!btn) return;
    var abi = currentAbi();
    btn.setAttribute('data-abi', abi);
    var asset = release && (release.assets[abi] || release.assets.universal);
    if (asset) {
      btn.href = asset.url;
      btn.removeAttribute('target');
      size.textContent = abi + ' · ' + fmtSize(asset.size);
      if (asset.sha) {
        sha.hidden = false;
        var code = sha.querySelector('code');
        code.textContent = asset.sha.slice(0, 24) + '…';
        code.title = asset.sha;
      } else {
        sha.hidden = true;
      }
    } else {
      btn.href = release ? release.url : RELEASES + '/latest';
      btn.target = '_blank';
      size.textContent = release ? NP.L('前往发布页', 'Open release page') : 'GitHub Releases';
      sha.hidden = true;
    }
  }

  function renderChanges(body, url) {
    var list = $('#dlChanges');
    if (!list) return;
    var lines = (body || '').split(/\r?\n/);
    var items = [];
    lines.forEach(function (l) {
      var m = /^\*\s+(.+?)\s+by\s+@[\w-]+\s+in\s+(https:\/\/\S+)/.exec(l);
      if (m && items.length < 5) items.push({ text: m[1], href: m[2] });
    });
    if (!items.length) {
      lines.forEach(function (l) {
        var m = /^-\s+[0-9a-f]{7,}\s+(.+)$/.exec(l);
        if (m && items.length < 5) items.push({ text: m[1] });
      });
    }
    list.innerHTML = '';
    items.forEach(function (it) {
      var li = document.createElement('li');
      if (it.href) {
        var a = document.createElement('a');
        a.href = it.href;
        a.target = '_blank';
        a.rel = 'noopener';
        a.textContent = it.text;
        li.appendChild(a);
      } else {
        li.textContent = it.text;
      }
      list.appendChild(li);
    });
    var more = document.createElement('li');
    more.className = 'changes__empty';
    more.innerHTML = '<a href="' + url + '" target="_blank" rel="noopener">' + NP.L('查看完整更新日志 ↗', 'Full changelog ↗') + '</a>';
    list.appendChild(more);
  }

  function failRelease() {
    var list = $('#dlChanges');
    if (list) list.innerHTML = '<li class="changes__empty">' + NP.L('暂时连不上 GitHub API，', 'GitHub API is unreachable right now. ') + '<a href="' + RELEASES + '" target="_blank" rel="noopener">' + NP.L('直接前往 Releases ↗', 'Go to Releases ↗') + '</a></li>';
    applyAbi();
  }

  function guessAbi() {
    var ua = navigator.userAgent || '';
    if (!/Android/i.test(ua)) return;
    var pick = function (abi) {
      var input = $('input[name="abi"][value="' + abi + '"]');
      if (input) { input.checked = true; applyAbi(); }
    };
    if (/armv7|armeabi/i.test(ua)) pick('armeabi-v7a');
    else if (/x86_64/i.test(ua)) pick('x86_64');
    else if (/i686|x86/i.test(ua)) pick('x86');
    if (navigator.userAgentData && navigator.userAgentData.getHighEntropyValues) {
      navigator.userAgentData.getHighEntropyValues(['architecture', 'bitness']).then(function (v) {
        if (v.architecture === 'arm' && v.bitness === '32') pick('armeabi-v7a');
        else if (v.architecture === 'x86') pick(v.bitness === '32' ? 'x86' : 'x86_64');
      }).catch(function () {});
    }
  }

  NP.github = {
    init: function () {
      $$('input[name="abi"]').forEach(function (r) { r.addEventListener('change', applyAbi); });
      guessAbi();

      cached('repo', 360, API).then(function (repo) {
        if (repo && typeof repo.stargazers_count === 'number') setAll('stars', fmtCount(repo.stargazers_count));
        if (repo && typeof repo.forks_count === 'number') setAll('forks', fmtCount(repo.forks_count));
      }).catch(function () {});

      cached('latest', 30, API + '/releases/latest').then(function (r) {
        var assets = {};
        (r.assets || []).forEach(function (a) {
          var abi = abiOf(a.name);
          if (abi) assets[abi] = { url: a.browser_download_url, size: a.size, sha: (a.digest || '').replace(/^sha256:/, ''), dl: a.download_count };
        });
        release = { tag: r.tag_name, url: r.html_url, date: r.published_at, assets: assets };
        setAll('tag', r.tag_name || NP.L('最新版本', 'Latest'));
        var rev = /-([0-9a-f]{7,})/i.exec(r.tag_name || '');
        setAll('rev', rev ? rev[1].slice(0, 7) : (r.tag_name || '—'));
        setAll('date', fmtDate(r.published_at));
        var dev = $('#devBtn b');
        if (dev && r.tag_name) dev.textContent = r.tag_name;
        renderChanges(r.body, r.html_url);
        applyAbi();
      }).catch(failRelease);

      // 发布列表较大，只在接近终点站时才去取
      var asked = false;
      NP.whenVisible($('#download'), function () {
        if (asked) return;
        asked = true;
        cached('releases', 360, API + '/releases?per_page=100', function (list) {
          if (!Array.isArray(list)) return null;
          var total = 0;
          list.forEach(function (rel) { (rel.assets || []).forEach(function (a) { total += a.download_count || 0; }); });
          return { count: list.length, total: total };
        }).then(function (s) {
          if (!s) return;
          var more = s.count >= 100 ? '+' : '';
          setAll('count', s.count + more);
          setAll('downloads', fmtCount(s.total) + more);
        }).catch(function () {});
      }, null, '1400px 0px');
    },
  };
})();
