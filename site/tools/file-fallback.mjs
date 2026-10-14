// 直接双击 index.html（file://）打开时，浏览器不许 fetch 本地文件，Web Audio 也会把本地音频当成跨源而输出静音。
// 这里把桌面版仿真的界面片段和示例曲包成 .js，页面在 file:// 下改用 <script> 读取。
// 用法：node tools/file-fallback.mjs （更新 assets/desktop/sim-*.html 或示例曲之后重新跑一次）
import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = [];
for (const lang of ['zh', 'en']) {
  const html = readFileSync(join(ROOT, `assets/desktop/sim-${lang}.html`), 'utf8');
  const js = `(window.NP_SIM_SRC || (window.NP_SIM_SRC = {}))[${JSON.stringify(lang)}] = ${JSON.stringify(html)};\n`;
  writeFileSync(join(ROOT, `assets/desktop/sim-${lang}.js`), js);
  out.push(`sim-${lang}.js ${(js.length / 1024).toFixed(0)} KB`);
}
const mp3 = readFileSync(join(ROOT, 'assets/audio/startrail.mp3'));
const audio = `window.NP_AUDIO_B64 = ${JSON.stringify(mp3.toString('base64'))};\n`;
writeFileSync(join(ROOT, 'assets/audio/startrail.js'), audio);
out.push(`startrail.js ${(audio.length / 1024).toFixed(0)} KB`);
console.log(out.join('\n'));
