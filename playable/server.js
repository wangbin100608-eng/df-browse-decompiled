// 理包模拟器 - 自有可游玩站点
// 静态资源本地托管；/api/* 与原站动态资源反向代理到上游（同源，绕过 CORS）
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = __dirname;
const UPSTREAM = 'dfc.lza11111.com';
const PORT = process.env.PORT || 8095;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json',
};

// 需要反向代理到上游的路径前缀（前端 SPA 依赖这些动态数据）
const PROXY_PREFIXES = ['/api/'];
// 上游单文件端点（若本地未镜像）
const PROXY_EXACT = new Set(['/health']);

function shouldProxy(pathname) {
  if (PROXY_EXACT.has(pathname)) return true;
  return PROXY_PREFIXES.some(p => pathname.startsWith(p));
}

function proxyToUpstream(req, res) {
  const headers = { ...req.headers };
  headers.host = UPSTREAM;
  delete headers['accept-encoding']; // 让 Node 层解压后统一处理
  const opts = {
    hostname: UPSTREAM,
    port: 443,
    path: req.url,
    method: req.method,
    headers,
  };
  const upstreamReq = https.request(opts, upstreamRes => {
    const outHeaders = { ...upstreamRes.headers };
    delete outHeaders['content-encoding'];
    delete outHeaders['content-length'];
    // 把 CORS 相关头去掉，浏览器视为同源，无需 CORS
    for (const k of Object.keys(outHeaders)) {
      if (k.toLowerCase().startsWith('access-control-')) delete outHeaders[k];
    }
    res.writeHead(upstreamRes.statusCode || 502, outHeaders);
    upstreamRes.on('data', c => res.write(c));
    upstreamRes.on('end', () => res.end());
  });
  upstreamReq.on('error', e => {
    res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'upstream error', detail: String(e.message) }));
  });
  if (req.method !== 'GET' && req.method !== 'HEAD') req.pipe(upstreamReq);
  else upstreamReq.end();
}

function serveStatic(req, res, pathname) {
  let rel = decodeURIComponent(pathname);
  if (rel === '/' || rel === '') rel = '/index.html';
  // SPA 路由回退：无扩展名的路径一律回 index.html
  const ext = path.extname(rel);
  let filePath = path.join(ROOT, rel);
  if (!ext || rel.endsWith('/')) filePath = path.join(ROOT, 'index.html');
  // 安全：限制在 ROOT 内
  if (!filePath.startsWith(ROOT)) {
    res.writeHead(403); res.end('Forbidden'); return;
  }
  fs.readFile(filePath, (err, data) => {
    if (err) {
      // 找不到的资源回退 index.html（SPA）
      fs.readFile(path.join(ROOT, 'index.html'), (e2, fallback) => {
        if (e2) { res.writeHead(404); res.end('Not Found'); return; }
        res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-cache' });
        res.end(fallback);
      });
      return;
    }
    const type = MIME[ext.toLowerCase()] || 'application/octet-stream';
    const headers = { 'Content-Type': type };
    // 静态资源长缓存，HTML 不缓存
    if (ext === '.html') headers['Cache-Control'] = 'no-cache';
    else headers['Cache-Control'] = 'public, max-age=86400';
    if (ext === '.wasm') headers['Cross-Origin-Opener-Policy'] = 'same-origin';
    res.writeHead(200, headers);
    res.end(data);
  });
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (shouldProxy(u.pathname)) { proxyToUpstream(req, res); return; }
  serveStatic(req, res, u.pathname);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`理包模拟器运行于 http://0.0.0.0:${PORT}`);
  console.log(`API 代理 -> https://${UPSTREAM}`);
});
