#!/usr/bin/env node
// 从原版 Logo（assets/img/logo.svg）生成站点用到的品牌素材：
//   assets/img/emblem.svg       夜色版徽记（圆弧 + 音理 + 小黑猫）
//   assets/img/favicon.svg      带底色的方形图标
//   assets/img/logo-night.svg   夜色版完整 Logo（含文字标）
// 并把内联 SVG sprite 与开场文字标注入 index.html / 404.html 的标记区间。
// 用法：node tools/build-brand.mjs
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const logo = readFileSync(join(root, 'assets/img/logo.svg'), 'utf8');
const d = [...logo.matchAll(/<path\b[^>]*?\sd="([^"]+)"/g)].map((m) => m[1].trim().replace(/\s+/g, ' '));
if (d.length !== 15) throw new Error(`logo.svg 应包含 15 条路径，实际 ${d.length} 条`);

// 原文件路径顺序：圆弧、头像、蝴蝶结、i 的点、N、P、l、e、a、e、r、i、y、r、小猫
const [arc, head, bow, iDot, N, P, l, e1, a, e2, r1, iStem, y, r2, cat] = d;
const letters = [[N], [e1], [r1], [iDot, iStem], [P], [l], [a], [y], [e2], [r2]];

const LAV = '#c9a8fb';
const VB_EMBLEM = '164 131 695 546';
const VB_WORD = '218 680 589 128';
const VB_CAT = '606 480 186 186';

// 小猫眼睛在原图里是镂空的，垫一层金色椭圆让它在夜里“亮”起来
const eyes = '<ellipse cx="645.6" cy="572" rx="7.4" ry="9.2"/><ellipse cx="706.3" cy="578" rx="8.4" ry="10.2"/>';
const catLayer = (themable) =>
  themable
    ? `<g class="np-eyes" style="fill:var(--np-eye,#ffd58a)">${eyes}</g><path style="fill:var(--np-cat,#29243f);stroke:var(--np-cat-line,${LAV});stroke-width:var(--np-cat-stroke,5px);stroke-linejoin:round" d="${cat}"/>`
    : `<g fill="#ffd58a">${eyes}</g><path fill="#29243f" stroke="${LAV}" stroke-width="5" stroke-linejoin="round" d="${cat}"/>`;

const paths = (list) => list.map((p) => `<path d="${p}"/>`).join('');

const sprite = [
  '<svg class="sprite" aria-hidden="true" focusable="false" xmlns="http://www.w3.org/2000/svg">',
  `<symbol id="np-arc" viewBox="${VB_EMBLEM}"><path d="${arc}"/></symbol>`,
  `<symbol id="np-emblem" viewBox="${VB_EMBLEM}"><g style="fill:var(--np-logo,${LAV})">${paths([arc, head, bow])}</g>${catLayer(true)}</symbol>`,
  `<symbol id="np-cat" viewBox="${VB_CAT}">${catLayer(true)}</symbol>`,
  // 分层符号：开场的蜡笔画需要给圆弧、头发、蝴蝶结、小猫分别上色
  `<symbol id="np-p-arc" viewBox="${VB_EMBLEM}"><path d="${arc}"/></symbol>`,
  `<symbol id="np-p-head" viewBox="${VB_EMBLEM}"><path d="${head}"/></symbol>`,
  `<symbol id="np-p-bow" viewBox="${VB_EMBLEM}"><path d="${bow}"/></symbol>`,
  `<symbol id="np-p-cat" viewBox="${VB_EMBLEM}"><path d="${cat}"/></symbol>`,
  `<symbol id="np-p-eyes" viewBox="${VB_EMBLEM}">${eyes}</symbol>`,
  `<symbol id="np-wordmark" viewBox="${VB_WORD}"><g style="fill:var(--np-logo,${LAV})">${paths(letters.flat())}</g></symbol>`,
  '</svg>',
].join('\n');

const wordmark = [
  `<svg class="wordmark" viewBox="${VB_WORD}" aria-hidden="true" focusable="false">`,
  '<defs><linearGradient id="wm-fill" gradientUnits="userSpaceOnUse" x1="218" y1="690" x2="807" y2="800">',
  '<stop offset="0" stop-color="#f6efff"/><stop offset=".48" stop-color="#dcc6ff"/><stop offset="1" stop-color="#ffb8dc"/>',
  '</linearGradient></defs>',
  `<g fill="url(#wm-fill)">${letters.map((ps, i) => `<g class="wm-l" style="--i:${i}">${paths(ps)}</g>`).join('')}</g>`,
  '</svg>',
].join('\n');

const svgFile = (viewBox, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}">${body}</svg>\n`;

writeFileSync(
  join(root, 'assets/img/emblem.svg'),
  svgFile(VB_EMBLEM, `<g fill="${LAV}">${paths([arc, head, bow])}</g>${catLayer(false)}`),
);
writeFileSync(
  join(root, 'assets/img/favicon.svg'),
  svgFile(
    '111.5 4 800 800',
    `<rect x="111.5" y="4" width="800" height="800" rx="190" fill="#16123a"/><g fill="${LAV}">${paths([arc, head, bow])}</g>${catLayer(false)}`,
  ),
);
writeFileSync(
  join(root, 'assets/img/logo-night.svg'),
  svgFile('150 120 724 700', `<g fill="${LAV}">${paths([arc, head, bow, ...letters.flat()])}</g>${catLayer(false)}`),
);

function inject(file, marker, html) {
  const path = join(root, file);
  if (!existsSync(path)) return false;
  const src = readFileSync(path, 'utf8');
  const re = new RegExp(`(<!-- @${marker} -->)[\\s\\S]*?(<!-- /@${marker} -->)`);
  if (!re.test(src)) return false;
  writeFileSync(path, src.replace(re, (_, open, close) => `${open}\n${html}\n${close}`));
  return true;
}

for (const file of ['index.html', '404.html']) {
  const s = inject(file, 'sprite', sprite);
  const w = inject(file, 'wordmark', wordmark);
  console.log(`${file}: sprite ${s ? '✓' : '-'}  wordmark ${w ? '✓' : '-'}`);
}
console.log('brand assets written to assets/img/');
