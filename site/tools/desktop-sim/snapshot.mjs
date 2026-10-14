import puppeteer from 'puppeteer-core';
import { writeFileSync, mkdirSync } from 'node:fs';

// node dsnap.mjs <lang zh|en> <outDir>  —— 导出桌面版各界面渲染后的 DOM、html 上的主题变量与全部样式规则
const [, , lang = 'zh', out = 'dsim'] = process.argv;
mkdirSync(out, { recursive: true });
const W = 1280, H = 800;
const browser = await puppeteer.launch({
  executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  headless: true,
  args: ['--hide-scrollbars', '--force-color-profile=srgb', `--lang=${lang === 'en' ? 'en-US' : 'zh-CN'}`],
});
const page = await browser.newPage();
await page.setViewport({ width: W, height: H, deviceScaleFactor: 1 });
await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }]);
await page.evaluateOnNewDocument((lang) => {
  localStorage.setItem('locale', lang === 'en' ? 'en' : 'zh-CN');
  if (!localStorage.getItem('np-snap-dark')) localStorage.setItem('theme-mode', 'light');
  Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
}, lang);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await page.goto(`http://localhost:1477/?lang=${lang}`, { waitUntil: 'networkidle2', timeout: 60000 });
await sleep(2500);
const app = (fn, ...a) => page.evaluate(fn, ...a);
const go = async (p) => { await app((p) => document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(p), p); await sleep(1800); };

await app(async () => {
  const pinia = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia;
  const player = pinia._s.get('player');
  const q = window.__SITE_QUEUE;
  player.playAll(q, q[0].id);
  await new Promise((r) => setTimeout(r, 600));
  await player.play(q[0], 'local', 30600);
});
await sleep(800);

const snap = () => app(() => {
  const root = document.querySelector('#app');
  const clone = root.cloneNode(true);
  // 画布内容导不出来，留空壳由网站脚本重画
  clone.querySelectorAll('script').forEach((s) => s.remove());
  return clone.innerHTML;
});
const views = {};
async function clickSettings(re) {
  await app((src) => {
    const re = new RegExp(src);
    const el = [...document.querySelectorAll('.settings-view *')].find((e) => e.children.length === 0 && re.test(e.textContent.trim()));
    (el?.closest('[role="button"],button,a,li,div[tabindex]') ?? el)?.click();
  }, re);
  await sleep(1500);
}
await go('/'); await page.mouse.move(5, 400); await sleep(500); views.home = await snap();
await go('/explore'); views.explore = await snap();
await go('/library'); views.library = await snap();
await go('/settings'); await clickSettings(lang === 'en' ? '^(Audio effects|Audio Effects)' : '^音效与倍速');
await app(() => {
  const player = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player');
  player.updateAudioEffectsProfile({ enabled: true });
  player.setAudioEffectsPreset('acg');
});
await sleep(900);
views.fx = await snap();
await clickSettings(lang === 'en' ? '^Keyboard shortcuts' : '^键盘快捷键'); views.shortcuts = await snap();
await clickSettings(lang === 'en' ? '^Appearance' : '^外观'); views.appearance = await snap();
await go('/');
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
await app(async () => {
  const player = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player');
  const q = window.__SITE_QUEUE;
  player.playAll(q, q[0].id);
  await new Promise((r) => setTimeout(r, 600));
  await player.play(q[0], 'local', 30600);
});
await sleep(2500);
await app(() => localStorage.removeItem('np-snap-dark'));
const darkVars = await app(() => document.documentElement.style.cssText);
const darkClass = await app(() => document.documentElement.className);


writeFileSync(`${out}/views-${lang}.json`, JSON.stringify(views));
writeFileSync(`${out}/meta-${lang}.json`, JSON.stringify({ lightVars, darkVars, htmlClass, darkClass, hyper }));
writeFileSync(`${out}/css-${lang}.json`, JSON.stringify(css));
console.log(Object.entries(views).map(([k, v]) => k + ':' + Math.round(v.length / 1024) + 'k').join(' '), 'css rules', css.length);
await browser.close();
