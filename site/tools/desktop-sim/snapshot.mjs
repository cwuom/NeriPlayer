import puppeteer from 'puppeteer-core';
import { writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

// node snapshot.mjs <lang zh|en> <outDir> <NeriPlayer-Desktop 目录>
//   导出桌面版各界面渲染后的 DOM、html 上的主题变量与全部样式规则。桌面版需以 src/__site_mock.ts（即 site-mock.ts）启动在 1477 端口。
//   首页 / 探索 / 音乐库里能跳转的元素会挨个点一遍，记下去向并标成 data-dsim-go，目标页面也一起导出；设置按分区逐个导出内容区。
const [, , lang = 'zh', out = 'dsim', DESK = 'E:/Project/_np_ref/desktop'] = process.argv;
const EN = lang === 'en';
mkdirSync(out, { recursive: true });
const W = 1280, H = 800;
const browser = await puppeteer.launch({
  executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  headless: true,
  args: ['--hide-scrollbars', '--force-color-profile=srgb', `--lang=${EN ? 'en-US' : 'zh-CN'}`],
});
const page = await browser.newPage();
await page.setViewport({ width: W, height: H, deviceScaleFactor: 1 });
await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }]);
await page.setRequestInterception(true);
page.on('request', (req) => {
  const m = /^https:\/\/covers\.neri\.invalid\/__covers\/([\w-]+\.jpg)/.exec(req.url());
  if (!m) { req.continue(); return; }
  req.respond({ status: 200, contentType: 'image/jpeg', body: readFileSync(join(DESK, 'public', '__covers', m[1])) });
});
await page.evaluateOnNewDocument((lang) => {
  localStorage.setItem('locale', lang === 'en' ? 'en' : 'zh-CN');
  if (!localStorage.getItem('np-snap-dark')) localStorage.setItem('theme-mode', 'light');
  Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
}, lang);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await page.goto(`http://localhost:1477/?lang=${lang}`, { waitUntil: 'networkidle2', timeout: 60000 });
await sleep(2500);
const app = (fn, ...a) => page.evaluate(fn, ...a);
const path = () => app(() => document.querySelector('#app').__vue_app__.config.globalProperties.$router.currentRoute.value.fullPath);
const go = async (p, wait = 1800) => { await app((p) => document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(p), p); await sleep(wait); };
const startPlayback = () => app(async () => {
  const player = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player');
  const q = window.__SITE_QUEUE;
  player.playAll(q, q[0].id);
  await new Promise((r) => setTimeout(r, 600));
  await player.play(q[0], 'local', 30600);
});
await startPlayback();
await sleep(800);

const snap = () => app(() => {
  const clone = document.querySelector('#app').cloneNode(true);
  // 画布内容导不出来，留空壳由网站脚本重画
  clone.querySelectorAll('script').forEach((s) => s.remove());
  return clone.innerHTML;
});
const views = {};

/* ---------- 外壳与播放页 ---------- */
await go('/'); await page.mouse.move(5, 400); await sleep(500);
views.home = await snap();
await page.keyboard.down('Control'); await page.keyboard.press('KeyP'); await page.keyboard.up('Control');
await sleep(2600);
await page.mouse.move(30, H - 30);
await sleep(600);
views.np = await snap();
const hyper = await app(() => {
  const c = document.querySelector('canvas.hyper-bg');
  const inst = c && c.__vueParentComponent;
  return inst ? JSON.parse(JSON.stringify(inst.props)) : null;
});
await page.keyboard.press('Escape');
await sleep(1200);

/* ---------- 可跳转的页面：先拍下来，再逐个点出去看去向 ---------- */
// 歌曲行点了是播放，分类标签是页内切换（单独导出成变体），这些不当作跳转候选
const EXCLUDE = '.track-item, .feed-song, .daily-card, .tab-chip, .local-category-chip, input, textarea, .new-playlist-row, .back-btn, .play-all-btn, .hero-icon-btn';
const mark = () => app((ex) => {
  document.querySelectorAll('[data-dsim-c]').forEach((e) => e.removeAttribute('data-dsim-c'));
  const els = [...document.querySelectorAll('main.content *')].filter((el) => {
    if (getComputedStyle(el).cursor !== 'pointer') return false;
    if (el.parentElement && getComputedStyle(el.parentElement).cursor === 'pointer') return false;
    // 多选、刷新、通知、更多菜单只改页内状态，点了会让后面的序号对不上
    if (/^(checklist|refresh|notifications|more_vert|search)$/.test(el.textContent.trim())) return false;
    return !el.closest(ex);
  });
  els.forEach((e, i) => e.setAttribute('data-dsim-c', String(i)));
  return els.length;
}, EXCLUDE);
const clickText = (sel, re) => app(async (sel, src) => {
  const re = new RegExp(src);
  const el = [...document.querySelectorAll(sel)].find((e) => re.test(e.textContent.trim()));
  if (el) el.click();
  await new Promise((r) => setTimeout(r, 700));
  return !!el;
}, sel, re);
// 音乐库的页内变体：每次都从「本地 · 歌单」重新点过去，避免 keep-alive 留下上一次的状态
const LIB = {
  library: [],
  'library-artists': [['.local-category-chip', EN ? '^.{0,24}Artists?$' : '歌手$']],
  'library-files': [['.local-category-chip', EN ? '^.{0,24}(Local )?[Ff]iles$' : '本地文件$']],
  'library-fav': [['.tab-chip', EN ? '^favorite' : '收藏$']],
  'library-dl': [['.tab-chip', EN ? '^download' : '下载$']],
};
const STATES = [{ key: 'explore', route: '/explore' }]
  .concat(Object.entries(LIB).map(([key, steps]) => ({ key, route: '/library', steps })));
async function enter(st) {
  if (st.key === 'home') { await go('/', 1200); return; }
  await go(st.route, 1200);
  if (st.route === '/library') {
    await clickText('.tab-chip', EN ? '^folder_open' : '本地$');
    await clickText('.local-category-chip', EN ? '^queue_music' : '歌单$');
    for (const [sel, re] of st.steps) await clickText(sel, re);
  }
}
const keyOf = (p) => 'r' + p.replace(/[?#].*$/, '').replace(/[^\w]+/g, '-').replace(/-$/, '');
const pages = {};
const links = {};
const routes = new Map();
async function discover(st) {
  await enter(st);
  const n = await mark();
  pages[st.key] = await snap();
  const base = await path();
  links[st.key] = {};
  for (let i = 0; i < n; i++) {
    await enter(st);
    await mark();
    await app((i) => document.querySelector(`[data-dsim-c="${i}"]`)?.click(), i);
    await sleep(900);
    const p = await path();
    if (p !== base && !p.startsWith('/settings')) {
      links[st.key][i] = keyOf(p);
      routes.set(keyOf(p), p);
    }
    await page.keyboard.press('Escape');
  }
  console.log(st.key, n, 'candidates ·', Object.keys(links[st.key]).length, 'links');
}
await discover({ key: 'home', route: '/' });
for (const st of STATES) await discover(st);
// 挨个点的时候可能点到了本地文件、换了正在播放的曲目；导出目标页之前放回《星轨》
await startPlayback();
await sleep(800);
for (const [key, p] of routes) {
  await go(p, 2000);
  pages[key] = await snap();
}
console.log('routes', [...routes.values()].join(' '));
// 候选标记换成去向
for (const k of Object.keys(pages)) {
  const map = links[k] || {};
  pages[k] = pages[k].replace(/ data-dsim-c="(\d+)"/g, (m, i) => (map[i] ? ` data-dsim-go="${map[i]}"` : ''));
}
views.home = pages.home;
delete pages.home;

/* ---------- 设置：21 个分区 ---------- */
const SECTIONS = ['accounts', 'general', 'shortcuts', 'language', 'playback', 'audio', 'audio_effects', 'quality', 'playback_sources', 'appearance', 'now_playing', 'background', 'motion', 'lyrics', 'desktop_lyrics', 'downloads', 'storage', 'network', 'backup', 'listen_together', 'about'];
await app(() => {
  const player = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player');
  player.updateAudioEffectsProfile({ enabled: true });
  player.setAudioEffectsPreset('flat');
});
const settings = {};
for (const id of SECTIONS) {
  await go('/settings?section=' + id, 1500);
  // 访问过的分区都留在 DOM 里只是隐藏，导出时只留当前这一个
  settings[id] = await app(() => {
    const c = document.querySelector('.settings-content').cloneNode(true);
    c.querySelectorAll('.settings-section-panel').forEach((p) => { if (p.style.display === 'none') p.remove(); });
    return c.innerHTML;
  });
}
await go('/settings?section=appearance', 1500);
const frame = await app((ids) => {
  const main = document.querySelector('main.content').cloneNode(true);
  main.querySelectorAll('.settings-nav .settings-nav-item').forEach((b, i) => b.setAttribute('data-dsim-set', ids[i]));
  main.querySelector('.settings-content').innerHTML = '';
  return main.innerHTML;
}, SECTIONS);

const css = await app(() => {
  const out = [];
  for (const sh of document.styleSheets) {
    let rules;
    try { rules = sh.cssRules; } catch (e) { continue; }
    for (const r of rules) out.push(r.cssText);
  }
  return out;
});
const lightVars = await app(() => document.documentElement.style.cssText);
const htmlClass = await app(() => document.documentElement.className);
await app(() => { localStorage.setItem('theme-mode', 'dark'); localStorage.setItem('np-snap-dark', '1'); });
await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
await page.reload({ waitUntil: 'networkidle2' });
await sleep(2500);
await startPlayback();
await sleep(2500);
await app(() => localStorage.removeItem('np-snap-dark'));
const darkVars = await app(() => document.documentElement.style.cssText);
const darkClass = await app(() => document.documentElement.className);

writeFileSync(`${out}/views-${lang}.json`, JSON.stringify({ views, pages, settings, frame, sections: SECTIONS }));
writeFileSync(`${out}/meta-${lang}.json`, JSON.stringify({ lightVars, darkVars, htmlClass, darkClass, hyper }));
writeFileSync(`${out}/css-${lang}.json`, JSON.stringify(css));
const kb = (s) => Math.round(s.length / 1024) + 'k';
console.log('pages', Object.keys(pages).length, kb(Object.values(pages).join('')), '· settings', kb(Object.values(settings).join('')), '· css rules', css.length);
await browser.close();
