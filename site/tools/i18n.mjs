// 英文版生成：中文 index.html 是唯一的源，英文按「翻译单元」覆盖后输出 en/index.html
// 用法：
//   node tools/i18n.mjs extract   抽取翻译单元，更新 i18n/en.txt（保留已有译文，新增条目留空，并列出失效条目）
//   node tools/i18n.mjs build     按 i18n/en.txt 生成 en/index.html；缺译的条目会列出并保留中文
// 翻译单元：只含行内元素的最内层块（innerHTML，svg / img 以 {{n}} 占位）、混排块里的裸文本、含中文的属性
// 依赖 puppeteer-core 与本机 Chrome；puppeteer-core 不在本仓库时，用 PUPPETEER_FROM 指向其所在目录
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = join(ROOT, 'index.html');
const DICT = join(ROOT, 'i18n', 'en.txt');
const OUT = join(ROOT, 'en', 'index.html');
const req = createRequire(process.env.PUPPETEER_FROM ? join(process.env.PUPPETEER_FROM, 'noop.js') : import.meta.url);
const puppeteer = req('puppeteer-core');
const CHROME = [
  process.env.CHROME,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].find((p) => p && existsSync(p));

const key = (s) => createHash('sha1').update(s).digest('hex').slice(0, 10);

/* i18n/en.txt：每条以 "@key" 开头，"# " 开头的行是中文原文注释，其余行为译文（可跨行，行内 HTML 原样书写） */
function readDict() {
  const map = new Map();
  if (!existsSync(DICT)) return map;
  const text = readFileSync(DICT, 'utf8').replace(/\r\n/g, '\n');
  for (const block of text.split(/^@(?=[0-9a-f]{10}\b)/m).slice(1)) {
    const k = block.slice(0, 10);
    const body = block.slice(10).split('\n').slice(1).filter((l) => !l.startsWith('# ')).join('\n').trim();
    map.set(k, body);
  }
  return map;
}

/* 在页面里执行：收集或替换翻译单元 */
function walk(mode, dict) {
  const CJK = /[\u3400-\u9fff\uf900-\ufaff\u3000-\u303f\uff00-\uffef]/;
  const INLINE = new Set(['A', 'ABBR', 'B', 'BDI', 'BR', 'CODE', 'DATA', 'EM', 'I', 'IMG', 'KBD', 'MARK', 'Q', 'RP', 'RT', 'RUBY', 'S', 'SAMP', 'SMALL', 'SPAN', 'STRONG', 'SUB', 'SUP', 'TIME', 'U', 'VAR', 'WBR', 'svg']);
  const ATTRS = ['alt', 'aria-label', 'title', 'placeholder', 'content', 'data-label-dark', 'data-label-light'];
  const units = [];
  const seen = new Set();
  const norm = (s) => s.replace(/\s+/g, ' ').trim();
  const push = (kind, zh, ctx) => { units.push({ kind, zh, ctx }); };

  function inlineOnly(el) {
    for (const c of el.children) {
      if (!INLINE.has(c.tagName)) return false;
      if (c.tagName !== 'svg' && !inlineOnly(c)) return false;
    }
    return true;
  }
  /* svg / img 换成 {{n}}，保证译文里不用重写装饰性标记 */
  function pack(el) {
    const parts = [];
    const clone = el.cloneNode(true);
    clone.querySelectorAll('svg, img').forEach((n) => {
      if (n.parentElement && n.parentElement.closest('svg')) return;
      const i = parts.length;
      parts.push(n.outerHTML);
      n.replaceWith(document.createTextNode('\u0000' + i + '\u0000'));
    });
    const html = clone.innerHTML.replace(/\u0000(\d+)\u0000/g, '{{$1}}');
    return { html: el.closest('pre') ? html.trim() : norm(html), parts };
  }
  function unpack(html, parts) { return html.replace(/\{\{(\d+)\}\}/g, (m, i) => parts[+i] || ''); }
  function ctxOf(el) { return el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/)[0] : '') + (el.id ? '#' + el.id : ''); }

  function attrs(el) {
    for (const a of ATTRS) {
      const v = el.getAttribute(a);
      if (!v || !CJK.test(v)) continue;
      const zh = norm(v);
      if (mode === 'extract') push('attr', zh, ctxOf(el) + '[' + a + ']');
      else { const t = dict[zh]; if (t != null) el.setAttribute(a, t); else dict.__miss.push(zh); }
    }
  }
  function visit(el) {
    if (/^(SCRIPT|STYLE|TEMPLATE|NOSCRIPT)$/.test(el.tagName)) return;
    attrs(el);
    if (el.tagName === 'svg') { el.querySelectorAll('*').forEach(attrs); el.querySelectorAll('text, title').forEach(unitEl); return; }
    if (!CJK.test(el.textContent)) { el.querySelectorAll('*').forEach(attrs); return; }
    if (inlineOnly(el) && el.tagName !== 'HTML' && el.tagName !== 'BODY' && el.tagName !== 'HEAD') {
      el.querySelectorAll('*').forEach(attrs);
      unitEl(el);
      return;
    }
    for (const n of [...el.childNodes]) {
      if (n.nodeType === 3 && CJK.test(n.nodeValue)) {
        const zh = norm(n.nodeValue);
        if (mode === 'extract') push('text', zh, ctxOf(el) + ' > #text');
        else {
          const t = dict[zh];
          if (t != null) { const lead = /^\s*/.exec(n.nodeValue)[0]; const tail = /\s*$/.exec(n.nodeValue)[0]; n.nodeValue = lead + t + tail; } else dict.__miss.push(zh);
        }
      } else if (n.nodeType === 1) visit(n);
    }
  }
  function unitEl(el) {
    const { html, parts } = pack(el);
    if (!CJK.test(html)) return;
    if (mode === 'extract') push('html', html, ctxOf(el));
    else { const t = dict[html]; if (t != null) el.innerHTML = unpack(t, parts); else dict.__miss.push(html); }
  }

  visit(document.documentElement);
  return mode === 'extract' ? units.filter((u) => { const s = u.zh; if (seen.has(s)) return false; seen.add(s); return true; }) : dict.__miss;
}

const cmd = process.argv[2];
if (!['extract', 'build'].includes(cmd)) { console.log('usage: node tools/i18n.mjs extract|build'); process.exit(1); }

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true });
const page = await browser.newPage();
await page.setJavaScriptEnabled(false);
await page.goto(pathToFileURL(SRC).href, { waitUntil: 'domcontentloaded' });

if (cmd === 'extract') {
  const units = await page.evaluate(walk, 'extract', null);
  const old = readDict();
  const out = [];
  const live = new Set();
  let fresh = 0;
  for (const u of units) {
    const k = key(u.zh);
    live.add(k);
    const t = old.get(k);
    if (!t) fresh++;
    out.push('@' + k + '  ' + u.kind + '  ' + u.ctx + '\n# ' + u.zh.split('\n').join('\n# ') + '\n' + (t || '') + '\n');
  }
  const stale = [...old.keys()].filter((k) => !live.has(k));
  mkdirSync(dirname(DICT), { recursive: true });
  writeFileSync(DICT, out.join('\n'));
  console.log(`${units.length} units · ${fresh} untranslated · ${stale.length} stale dropped${stale.length ? ': ' + stale.join(' ') : ''}`);
} else {
  const old = readDict();
  const units = await page.evaluate(walk, 'extract', null);
  const dict = {};
  for (const u of units) { const t = old.get(key(u.zh)); if (t) dict[u.zh] = t; }
  dict.__miss = [];
  const enShots = (p) => existsSync(join(ROOT, p));
  const miss = await page.evaluate((d) => {
    const m = (0, eval)('(' + d.fn + ')')('build', d.dict);
    document.querySelectorAll('img[src*="-zh."]').forEach((img) => { if (d.swap.includes(img.getAttribute('src'))) img.setAttribute('src', img.getAttribute('src').replace('-zh.', '-en.')); });
    const doc = document.documentElement;
    doc.lang = 'en';
    /* 相对路径整体上移一级；语言链接互换 */
    const fix = (v) => {
      if (!v || /^(#|[a-z]+:|\/|data:)/i.test(v)) return v;
      const r = ('../' + v).replace(/^\.\.\/\.\/$/, '../').replace(/^\.\.\/en\/?$/, './');
      return r;
    };
    document.querySelectorAll('[src], [href], link[href], meta[property="og:image"], meta[property="og:url"]').forEach((el) => {
      for (const a of ['src', 'href', 'content']) {
        if (!el.hasAttribute(a)) continue;
        if (a === 'content' && !/^og:(image|url)$/.test(el.getAttribute('property') || '')) continue;
        if (a === 'href' && el.closest('svg') ) continue;
        el.setAttribute(a, fix(el.getAttribute(a)));
      }
    });
    const og = document.querySelector('meta[property="og:locale"]');
    if (og) og.setAttribute('content', 'en_US');
    const lang = document.querySelector('.nav__lang');
    if (lang) { lang.setAttribute('href', '../'); lang.setAttribute('hreflang', 'zh-CN'); lang.setAttribute('lang', 'zh-CN'); lang.setAttribute('aria-label', '中文版'); lang.textContent = '中文'; }
    return m;
  }, { fn: walk.toString(), dict, swap: await page.evaluate(() => [...document.querySelectorAll('img[src*="-zh."]')].map((i) => i.getAttribute('src'))).then((l) => l.filter((s) => enShots(s.replace('-zh.', '-en.')))) });
  const html = '<!doctype html>\n' + (await page.evaluate(() => document.documentElement.outerHTML)) + '\n';
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, html);
  const uniq = [...new Set(miss)];
  console.log(`en/index.html written · ${uniq.length} untranslated`);
  if (uniq.length) console.log(uniq.slice(0, 40).map((s) => '  - ' + s.slice(0, 90)).join('\n'));
}
await browser.close();
