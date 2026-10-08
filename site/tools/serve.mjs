#!/usr/bin/env node
// 本地实时预览：零依赖静态服务器 + 文件变动自动刷新（CSS 变动热替换、不丢滚动位置）
// 用法：node tools/serve.mjs [端口，默认 6099]
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { watch, readdirSync, readFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { extname, join, normalize, relative, sep } from 'node:path';
import { networkInterfaces } from 'node:os';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const port = Number(process.argv[2] || process.env.PORT || 6099);

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.woff2': 'font/woff2',
  '.mp3': 'audio/mpeg',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
};

const LIVE = `<script>(function(){var es=new EventSource('/__live');es.onmessage=function(e){if(e.data==='css'){document.querySelectorAll('link[rel="stylesheet"]').forEach(function(l){var u=new URL(l.href);u.searchParams.set('v',Date.now());l.href=u.href;});}else{location.reload();}};})();</script>`;

const clients = new Set();

function send(res, code, body, type) {
  res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
}

async function serveFile(res, file, code = 200, range = null) {
  const ext = extname(file).toLowerCase();
  let body = await readFile(file);
  if (ext === '.html') body = Buffer.from(body.toString('utf8').replace(/<\/body>/i, `${LIVE}</body>`));
  const type = TYPES[ext] || 'application/octet-stream';
  // 音频拖动进度依赖 Range 请求，与 GitHub Pages 的行为保持一致
  const m = code === 200 && range ? /^bytes=(\d*)-(\d*)$/.exec(range) : null;
  if (m && (m[1] || m[2])) {
    const size = body.length;
    let start = m[1] ? Number(m[1]) : size - Number(m[2]);
    let end = m[1] && m[2] ? Number(m[2]) : size - 1;
    start = Math.max(0, start);
    end = Math.min(end, size - 1);
    if (start > end) {
      res.writeHead(416, { 'Content-Range': `bytes */${size}` });
      return res.end();
    }
    res.writeHead(206, { 'Content-Type': type, 'Cache-Control': 'no-store', 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1 });
    return res.end(body.subarray(start, end + 1));
  }
  res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store', 'Accept-Ranges': 'bytes' });
  res.end(body);
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/__live') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
    res.write('retry: 800\n\n');
    clients.add(res);
    req.on('close', () => clients.delete(res));
    return;
  }
  let path = decodeURIComponent(url.pathname);
  if (path.endsWith('/')) path += 'index.html';
  const file = normalize(join(root, path));
  if (relative(root, file).startsWith('..')) return send(res, 403, 'Forbidden', 'text/plain');
  try {
    const s = await stat(file);
    if (s.isDirectory()) return serveFile(res, join(file, 'index.html'));
    return await serveFile(res, file, 200, req.headers.range);
  } catch {
    try {
      return await serveFile(res, join(root, '404.html'), 404);
    } catch {
      return send(res, 404, 'Not Found', 'text/plain');
    }
  }
});

// Windows 的递归监听会把读取、杀毒扫描、索引这类元数据变动也报成 change，
// 所以只看站点会发布的文件，并且内容哈希真的变了才通知浏览器
const SITE = /^(index\.html|404\.html|site\.webmanifest|robots\.txt|en[\\/].+|assets[\\/].+)$/i;
const hashes = new Map();
const digest = (buf) => createHash('sha1').update(buf).digest('hex');
(function seed(dir) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    const rel = relative(root, p);
    if (e.isDirectory()) {
      if (rel === 'assets' || rel === 'en' || rel.startsWith('assets' + sep) || rel.startsWith('en' + sep)) seed(p);
    } else if (SITE.test(rel)) {
      try { hashes.set(rel, digest(readFileSync(p))); } catch { /* 文件被占用时等下一次事件 */ }
    }
  }
})(root);

let timer = null;
let pending = 'css';
const changed = new Set();
watch(root, { recursive: true }, (_event, name) => {
  if (!name) return;
  const n = String(name);
  if (!SITE.test(n)) return;
  let next;
  try {
    const p = join(root, n);
    if (!statSync(p).isFile()) return;
    next = digest(readFileSync(p));
  } catch {
    next = null;
  }
  if (hashes.get(n) === next) return;
  if (next === null) hashes.delete(n);
  else hashes.set(n, next);
  if (!/\.css$/i.test(n)) pending = 'reload';
  changed.add(n);
  clearTimeout(timer);
  timer = setTimeout(() => {
    for (const c of clients) c.write(`data: ${pending}\n\n`);
    console.log(`[${new Date().toLocaleTimeString()}] ${pending === 'css' ? '样式热替换' : '页面刷新'}：${[...changed].join('、')}`);
    pending = 'css';
    changed.clear();
  }, 160);
});

server.listen(port, '0.0.0.0', () => {
  const lan = Object.values(networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => `http://${i.address}:${port}/`);
  console.log(`NeriPlayer 官网预览已启动`);
  console.log(`  本机：   http://localhost:${port}/`);
  lan.forEach((u) => console.log(`  局域网： ${u}`));
  console.log('文件改动会自动刷新浏览器，按 Ctrl+C 停止。');
});
