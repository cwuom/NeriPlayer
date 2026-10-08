// 收集页面上每种网页字体实际渲染的字符，写入 tools/glyphs.json，供 subset-fonts.py 子集化
// 用法：先 `node tools/serve.mjs`，再 `node tools/collect-glyphs.mjs [http://localhost:6099]`
// 依赖 puppeteer-core 与本机 Chrome；puppeteer-core 不在本仓库时，可用 PUPPETEER_FROM 指向其所在目录
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const req = createRequire(process.env.PUPPETEER_FROM ? join(process.env.PUPPETEER_FROM, 'noop.js') : import.meta.url);
const puppeteer = req('puppeteer-core');
const base = (process.argv[2] || 'http://localhost:6099').replace(/\/$/, '');
const CHROME = [
  process.env.CHROME,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].find((p) => p && existsSync(p));

const buckets = { serif: new Set(), serifItalic: new Set(), hand: new Set(), mono: new Set(), sans: new Set() };
const add = (key, text) => { for (const ch of text) buckets[key].add(ch); };

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true });
for (const path of ['/', '/404.html', '/en/', '/en/__missing__']) {
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  await page.goto(base + path, { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise((r) => setTimeout(r, 1500));
  const got = await page.evaluate(() => {
    const out = { serif: '', serifItalic: '', hand: '', mono: '', sans: '' };
    const pick = (ff) => (/^"?Fraunces/.test(ff) ? 'serif' : /Caveat/.test(ff) ? 'hand' : /Geist Mono/.test(ff) ? 'mono' : /^"?Geist/.test(ff) ? 'sans' : null);
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = walker.nextNode())) {
      const el = n.parentElement;
      if (!el || /^(SCRIPT|STYLE)$/.test(el.tagName)) continue;
      const cs = getComputedStyle(el);
      const key = pick(cs.fontFamily);
      if (key) out[key] += n.data;
      if (key === 'serif' && cs.fontStyle === 'italic') out.serifItalic += n.data;
    }
    for (const el of document.querySelectorAll('*')) {
      for (const pseudo of ['::before', '::after']) {
        const cs = getComputedStyle(el, pseudo);
        const c = cs.content;
        if (c && c !== 'none' && c !== 'normal' && /^".*"$/.test(c)) {
          const key = pick(cs.fontFamily);
          if (key) out[key] += c.slice(1, -1);
        }
      }
    }
    return out;
  });
  for (const k of Object.keys(buckets)) add(k, got[k] || '');
  await page.close();
}
await browser.close();

// 脚本里动态写入的手写体文案（提示、开发者模式等）
for (const f of readdirSync(join(ROOT, 'assets/js'))) {
  const src = readFileSync(join(ROOT, 'assets/js', f), 'utf8');
  for (const m of src.matchAll(/'([^'\n]*[\u3000-\u9fff\uff00-\uffef][^'\n]*)'/g)) add('hand', m[1]);
}

const json = Object.fromEntries(Object.entries(buckets).map(([k, v]) => [k, [...v].sort().join('')]));
writeFileSync(join(ROOT, 'tools/glyphs.json'), JSON.stringify(json, null, 1) + '\n');
console.log(Object.entries(json).map(([k, v]) => `${k}: ${[...v].length}`).join('  '));
