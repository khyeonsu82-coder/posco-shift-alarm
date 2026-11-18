/* 교대 알람 서비스 워커 — 재설치 없이 항상 최신 (network-first, 캐시 폴백) */
const CACHE = 'shift-alarm-v3';
const PRECACHE = [
  './',
  './manifest.json?v=1',
  './icon-192.png?v=1',
  './icon-512.png?v=1',
  './apple-touch-icon.png?v=1',
  './favicon-32.png?v=1'
];

self.addEventListener('install', event => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE).then(c => c.addAll(PRECACHE)).catch(() => {})
  );
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const sameOrigin = new URL(req.url).origin === self.location.origin;

  event.respondWith((async () => {
    try {
      const res = await fetch(req, sameOrigin && req.mode === 'navigate' ? { cache: 'no-store' } : undefined);
      if (sameOrigin && res && res.ok && res.type === 'basic') {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(req, copy)).catch(() => {});
      }
      return res;
    } catch (err) {
      const cached = await caches.match(req);
      if (cached) return cached;
      if (req.mode === 'navigate') {
        const shell = await caches.match('./');
        if (shell) return shell;
      }
      throw err;
    }
  })());
});
