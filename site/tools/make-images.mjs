#!/usr/bin/env node
// 重新生成分享图（需要先运行 tools/serve.mjs，并安装 puppeteer-core）
//   npm i -D puppeteer-core
//   node tools/make-images.mjs [站点地址，默认 http://localhost:6099/] [Chrome 路径]
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

import { createRequire } from 'node:module';

let puppeteer;
try {
  const req = createRequire(process.env.PUPPETEER_FROM ? join(process.env.PUPPETEER_FROM, 'noop.js') : import.meta.url);
  puppeteer = req('puppeteer-core');
} catch {
  console.error('需要 puppeteer-core：npm i -D puppeteer-core（或用 PUPPETEER_FROM 指向已安装的目录）');
  process.exit(1);
}

const root = fileURLToPath(new URL('..', import.meta.url));
const url = process.argv[2] || 'http://localhost:6099/';
const chrome = process.argv[3] || process.env.CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const out = (p) => join(root, 'assets/img', p);

const browser = await puppeteer.launch({
  executablePath: chrome,
  headless: true,
  args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist', '--hide-scrollbars', '--force-color-profile=srgb'],
});
const page = await browser.newPage();

// 分享图：开场画面去掉导航、路线图与次要信息，揭示动画直接停在终态
await page.setViewport({ width: 1200, height: 630, deviceScaleFactor: 1 });
await page.goto(url, { waitUntil: 'networkidle2' });
await page.addStyleTag({
  content: [
    'html{scrollbar-gutter:auto!important}',
    '.nav,.route,.hero__scroll,.signals,.hero .small{display:none!important}',
    '.hero{padding:58px 0 0!important}',
    '.js [data-reveal]{opacity:1!important;transform:none!important;transition:none!important}',
    '.draw path,.ul__mark{stroke-dashoffset:0!important;transition:none!important}',
    '.display{font-size:76px!important}',
  ].join(''),
});
await new Promise((r) => setTimeout(r, 2600));
await page.screenshot({ path: out('og-image.jpg'), type: 'jpeg', quality: 86 });

await browser.close();
console.log('已生成 og-image.jpg（图标由 tools/make-icons.py 从 App 图标生成）');
