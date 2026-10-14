// 桌面端仿真：把 snapshot.mjs 导出的真实 DOM 与样式整理成网站素材
// 用法：node tools/desktop-sim/build.mjs <snapshot 目录> <NeriPlayer-Desktop 仓库目录>
//   输出 assets/desktop/sim.css、assets/desktop/sim-{zh,en}.html，以及 assets/fonts 下的 MiSans 与 Material Symbols 子集
// 依赖 puppeteer-core（PUPPETEER_FROM 指向其所在目录）、python fonttools + brotli
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const [, , SNAP = join(ROOT, 'tools', 'desktop-sim', '.snap'), DESK = ''] = process.argv;
const req = createRequire(process.env.PUPPETEER_FROM ? join(process.env.PUPPETEER_FROM, 'noop.js') : import.meta.url);
const puppeteer = req('puppeteer-core');
const CHROME = [process.env.CHROME, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', '/usr/bin/google-chrome', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'].find((p) => p && existsSync(p));
const OUT = join(ROOT, 'assets', 'desktop');
const FONTS = join(ROOT, 'assets', 'fonts');
mkdirSync(OUT, { recursive: true });

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true });
const page = await browser.newPage();
await page.setContent('<!doctype html><html><head></head><body></body></html>');

/* ---------- 拆分：外壳 + 各页面 + 设置各分区 + 播放页 ---------- */
const parts = {};
const texts = [];
const icons = new Set();
const usedIcons = new Set();
for (const lang of ['zh', 'en']) {
  const data = JSON.parse(readFileSync(join(SNAP, `views-${lang}.json`), 'utf8'));
  const meta = JSON.parse(readFileSync(join(SNAP, `meta-${lang}.json`), 'utf8'));
  const res = await page.evaluate((data) => {
    const views = data.views;
    const box = document.createElement('div');
    const clean = (root) => {
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_COMMENT);
      const dead = [];
      while (walker.nextNode()) dead.push(walker.currentNode);
      dead.forEach((n) => n.remove());
      root.querySelectorAll('img[src]').forEach((img) => {
        const s = img.getAttribute('src');
        const cover = /\/__covers\/([\w-]+\.jpg)/.exec(s);
        if (cover) img.setAttribute('src', '{{C}}' + cover[1]);
        else if (s === '/app-icon.png') img.setAttribute('src', '{{I}}');
        img.setAttribute('draggable', 'false');
      });
      root.querySelectorAll('.amll-lyric-player').forEach((p) => { p.innerHTML = ''; p.removeAttribute('style'); });
      root.querySelectorAll('[style*="view-transition-name"]').forEach((e) => e.style.removeProperty('view-transition-name'));
      return root;
    };
    const parse = (html) => { box.innerHTML = html; return clean(box); };
    const out = {};
    let r = parse(views.home);
    const shell = r.querySelector('.app-layout').cloneNode(true);
    shell.querySelector('main.content').innerHTML = '';
    shell.querySelectorAll('.now-playing').forEach((n) => n.remove());
    out.shell = shell.outerHTML;
    out['page-home'] = parse(views.home).querySelector('main.content').innerHTML;
    for (const [k, html] of Object.entries(data.pages)) out['page-' + k] = parse(html).querySelector('main.content').innerHTML;
    out['page-settings'] = parse(data.frame).innerHTML;
    for (const id of data.sections) out['set-' + id] = parse(data.settings[id]).innerHTML;
    r = parse(views.np);
    out.np = r.querySelector('.now-playing').outerHTML;
    out['tb-np'] = r.querySelector('header.title-bar').outerHTML;
    const all = document.createElement('div');
    all.innerHTML = Object.values(out).join('');
    const icons = [...all.querySelectorAll('.material-symbols-rounded')].map((e) => e.textContent.trim()).filter(Boolean);
    return { out, text: all.textContent, icons };
  }, data);
  // 平台图标（/icons/ic_*.svg）是桌面版 public 里的文件：改成 {{K}} 前缀，用到的拷进 assets/img/desktop/icons
  for (const [k, v] of Object.entries(res.out)) {
    res.out[k] = v.replace(/\/icons\/([\w.-]+\.svg)/g, (m, name) => { usedIcons.add(name); return '{{K}}' + name; });
  }
  parts[lang] = { html: res.out, meta };
  texts.push(res.text);
  res.icons.forEach((i) => icons.add(i));
}

/* ---------- 样式：只留用得上的规则，改写成 Shadow DOM 可用 ---------- */
const css = JSON.parse(readFileSync(join(SNAP, 'css-zh.json'), 'utf8'));
// 判断规则是否用得上时，页面按真实的祖先结构包起来，设置分区放回设置页框架里
const inContent = (h) => '<div class="app-layout"><main class="content">' + h + '</main></div>';
const allHtml = ['zh', 'en'].map((lang) => Object.entries(parts[lang].html).map(([k, v]) => {
  if (k.startsWith('page-')) return inContent(v);
  if (k.startsWith('set-')) return inContent(parts[lang].html['page-settings'].replace(/(<main[^>]*class="[^"]*settings-content[^"]*"[^>]*>)/, '$1' + v));
  return v;
}).join('')).join('');
const kept = await page.evaluate((rules, allHtml) => {
  document.documentElement.className = 'light-theme dark-theme platform-windows platform-mac window-fullscreen theme-ripple-active';
  document.body.innerHTML = '<div id="app">' + allHtml + '</div>';
  const strip = (sel) => sel.replace(/::?[a-zA-Z-]+(\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\))?/g, '').replace(/[>+~]\s*$/, '').trim();
  // AMLL 的歌词行由网站脚本重建，CSS Modules 规则（形如 ._lyricLine_1rfgi_1）一律保留
  const used = (selText) => /\._[A-Za-z]+_[a-z0-9]{5}_\d+/.test(selText) || selText.split(/,(?![^(]*\))/).some((s) => {
    const t = strip(s);
    if (!t) return true;
    try { return !!document.querySelector(t); } catch (e) { return true; }
  });
  const style = document.createElement('style');
  document.head.appendChild(style);
  const keep = [];
  const visit = (rule) => {
    if (rule instanceof CSSStyleRule) return used(rule.selectorText) ? rule.cssText : null;
    if (rule instanceof CSSFontFaceRule) return null;
    if (rule instanceof CSSKeyframesRule) return rule.cssText;
    if (rule instanceof CSSMediaRule || rule instanceof CSSSupportsRule) {
      const inner = [...rule.cssRules].map(visit).filter(Boolean);
      return inner.length ? '@' + (rule instanceof CSSMediaRule ? 'media ' + rule.conditionText : 'supports ' + rule.conditionText) + ' {' + inner.join('\n') + '}' : null;
    }
    if (rule.cssText.startsWith('@property')) return rule.cssText;
    return null;
  };
  for (const text of rules) {
    if (/view-transition|::-webkit-scrollbar|@font-face/.test(text) && !/^@media|^@supports/.test(text)) continue;
    style.textContent = text;
    const r = style.sheet && style.sheet.cssRules[0];
    if (!r) continue;
    const v = visit(r);
    if (v) keep.push(v);
  }
  return keep;
}, css, allHtml);

const HOST = ['light-theme', 'dark-theme', 'platform-windows', 'platform-mac', 'platform-linux', 'window-fullscreen', 'theme-ripple-active', 'theme-switching'];
function mapSelector(s) {
  s = s.trim();
  s = s.replace(/^:root\b/, ':host');
  s = s.replace(/^html((?:\.[\w-]+)*)/, (m, cls) => (cls ? ':host(' + cls + ')' : ':host'));
  s = s.replace(/^((?:\.(?:light-theme|dark-theme|platform-[\w-]+|window-fullscreen|theme-ripple-active|theme-switching))+)(?=[\s>+~]|$)/, ':host($1)');
  s = s.replace(/^:host\s+body\b/, ':host').replace(/^body\b/, ':host');
  s = s.replace(/#app\b/g, '.dsim-app');
  return s;
}
function mapRule(text) {
  return text.replace(/(^|\})\s*([^{}@][^{}]*)\{/g, (m, end, sel) => end + ' ' + sel.split(/,(?![^(]*\))/).map(mapSelector).join(', ') + ' {');
}
let sheet = kept.map(mapRule).join('\n');
sheet = sheet.replace(/(-?\d*\.?\d+)(d|s|l)?(vw|vh)\b/g, (m, n, p, u) => `calc(${n} * var(--dsim-${u}))`);
sheet = sheet.replace(/"Fraunces Variable"/g, 'Fraunces').replace(/"Geist Mono Variable"/g, '"Geist Mono"').replace(/"Caveat Variable"/g, 'Caveat');
sheet = ':host { --dsim-vw: 12.8px; --dsim-vh: 8px; }\n' + sheet;
writeFileSync(join(OUT, 'sim.css'), sheet);
console.log('sim.css', Math.round(sheet.length / 1024) + 'k', kept.length, 'rules');

for (const lang of ['zh', 'en']) {
  const { html, meta } = parts[lang];
  const tpl = Object.entries(html).map(([k, v]) => `<template data-part="${k}">${v}</template>`).join('\n');
  const data = { light: meta.lightVars, dark: meta.darkVars, hyper: meta.hyper };
  writeFileSync(join(OUT, `sim-${lang}.html`), tpl + '\n<script type="application/json" data-part="meta">' + JSON.stringify(data) + '</script>\n');
  console.log(`sim-${lang}.html`, Math.round((tpl.length) / 1024) + 'k');
}
await browser.close();
if (DESK && usedIcons.size) {
  const IDIR = join(ROOT, 'assets', 'img', 'desktop', 'icons');
  rmSync(IDIR, { recursive: true, force: true });
  mkdirSync(IDIR, { recursive: true });
  for (const name of usedIcons) writeFileSync(join(IDIR, name), readFileSync(join(DESK, 'public', 'icons', name)));
  console.log('icons', [...usedIcons].join(' '));
}

/* ---------- 字体子集 ---------- */
if (DESK) {
  const lyrics = readFileSync(join(ROOT, 'assets', 'js', 'lyrics.js'), 'utf8');
  const extra = '0123456789:./-+·%×xX dBkHzmsMB—…「」《》（），。！？、；：' + 'NeriPlayerStartrail星轨正在播放暂停翻译音译';
  const chars = [...new Set((texts.join('') + lyrics + extra).replace(/\s+/g, ''))].join('') + ' ';
  const tmp = join(tmpdir(), 'np-dsim-fonts-' + Date.now());
  mkdirSync(tmp, { recursive: true });
  writeFileSync(join(tmp, 'chars.txt'), chars);
  const fdir = join(DESK, 'public', 'fonts');
  const sub = (src, out, more = []) => execFileSync('python', ['-m', 'fontTools.subset', src, '--text-file=' + join(tmp, 'chars.txt'), '--flavor=woff2', '--layout-features=*', '--output-file=' + out, ...more], { stdio: 'inherit' });
  for (const [file, w] of [['MiSans-Regular.woff2', 500], ['MiSans-Medium.woff2', 600], ['MiSans-Semibold.woff2', 700], ['MiSans-Bold.woff2', 800]]) sub(join(fdir, file), join(FONTS, `misans-${w}.woff2`));
  // 400 用桌面版的 MiSans Normal 分片（CID 字体合并不了）：逐片裁剪，只留用到字的分片，按 unicode-range 声明
  const NDIR = join(FONTS, 'misans-400');
  rmSync(NDIR, { recursive: true, force: true });
  mkdirSync(NDIR, { recursive: true });
  writeFileSync(join(tmp, 'cmap.py'), [
    'import sys', 'from fontTools.ttLib import TTFont',
    'cps = sorted(TTFont(sys.argv[1]).getBestCmap().keys())',
    'print(",".join("U+%X" % c for c in cps))',
  ].join('\n'));
  const faces = [];
  readdirSync(join(fdir, 'misans-normal')).filter((f) => f.endsWith('.woff2')).forEach((f, i) => {
    const out = join(NDIR, `${i}.woff2`);
    try {
      execFileSync('python', ['-m', 'fontTools.subset', join(fdir, 'misans-normal', f), '--text-file=' + join(tmp, 'chars.txt'), '--layout-features=*', '--flavor=woff2', '--output-file=' + out], { stdio: 'ignore' });
      const range = execFileSync('python', [join(tmp, 'cmap.py'), out], { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
      if (!range) { rmSync(out); return; }
      faces.push(`@font-face { font-family: "MiSans"; src: url("../fonts/misans-400/${i}.woff2") format("woff2"); font-weight: 400; font-display: swap; unicode-range: ${range}; }`);
    } catch (e) { rmSync(out, { force: true }); }
  });
  const W = [[500, 500], [600, 600], [700, 700], [800, 900]];
  W.forEach(([file, w]) => faces.push(`@font-face { font-family: "MiSans"; src: url("../fonts/misans-${file}.woff2") format("woff2"); font-weight: ${w === 900 ? '800 900' : w}; font-display: swap; }`));
  faces.push('@font-face { font-family: "Material Symbols Rounded"; src: url("../fonts/material-symbols.woff2") format("woff2"); font-weight: 400; font-display: block; }');
  writeFileSync(join(ROOT, 'assets', 'css', 'desktop-fonts.css'), '/* 由 tools/desktop-sim/build.mjs 生成：桌面端仿真用的 MiSans 与 Material Symbols 子集 */\n' + faces.join('\n') + '\n');
  // 图标：按字体自己的连字表从界面里收集用到的图标（字形名和连字文本不一定相同，按名字猜会丢图标）
  execFileSync('python', [join(ROOT, 'tools', 'subset-icons.py'), DESK], { stdio: 'inherit' });
  console.log('fonts ok ·', icons.size, 'icons ·', chars.length, 'chars');
}
// 直接打开 index.html（file://）时用的同内容 .js
execFileSync('node', [join(ROOT, 'tools', 'file-fallback.mjs')], { stdio: 'inherit' });
