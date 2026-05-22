/* eslint-disable no-restricted-globals */
/**
 * CONTENIDO — Service Worker.
 *
 * PR139: install/activate (즉시 활성화) — Web Push 구독 무결성.
 * PR140: push + notificationclick — payload 처리 + 라우팅 메시지.
 * PR157: install precache + navigate fetch fallback — offline shell.
 *
 * Worker 는 ESM 이 아닌 classic script 라 import/export 를 쓰지 않는다.
 */

// PR157 — sw.js / manifest / icons / offline.html / index 변경 시 본 버전을 bump 해야 새 cache 가
// 활성화되고 옛 cache 가 삭제된다. release-notes-local-bundle.md 의 push 전 checklist 항목.
const SHELL_VERSION = 'v1'
const SHELL_CACHE = `contenido-shell-${SHELL_VERSION}`

// 항상 cache 해두는 정적 자원. dist/assets/* 의 build hash 자원은 runtime cache 회피 — first cold start
// 후 offline 으로 들어가면 React JS 로딩이 실패할 수 있고, 이 경우 navigate 가 offline.html 로 fallback.
// 더 깊은 캐싱이 필요하면 후속 PR 에서 vite-plugin-pwa 도입.
const PRECACHE_URLS = [
  '/',
  '/index.html',
  '/manifest.webmanifest',
  '/icons/icon-192.svg',
  '/icons/icon-512.svg',
  '/offline.html',
]

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then((cache) =>
      // addAll 은 한 entry 라도 실패하면 전체 install 이 실패한다. 정적 자원만 모아 안전.
      cache.addAll(PRECACHE_URLS).catch(() => {
        // dev 서버에서 / 경로가 404 등으로 응답할 가능성 — install 자체는 통과시키고
        // runtime fetch handler 가 offline.html 로 fallback.
      }),
    ),
  )
  // 첫 구독 시 reload 없이 활성화.
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      // 옛 cache 모두 삭제 (SHELL_VERSION 가 바뀌면).
      const keys = await caches.keys()
      await Promise.all(
        keys
          .filter((k) => k.startsWith('contenido-shell-') && k !== SHELL_CACHE)
          .map((k) => caches.delete(k)),
      )
      await self.clients.claim()
    })(),
  )
})

/**
 * PR157 — navigate request 만 cache-first → fallback offline.html.
 *  - API/asset 요청은 그냥 network passthrough (cache 안 함). 환불/결제 같은 hot path 에 stale 응답이
 *    절대 들어가지 않도록.
 *  - manifest / icons / offline 같은 shell 자원은 cache 우선.
 */
self.addEventListener('fetch', (event) => {
  const request = event.request

  // 메서드가 GET 이 아니면 passthrough — POST/PATCH/DELETE 는 절대 캐시하지 않음.
  if (request.method !== 'GET') return

  // navigate (HTML) request — cache-first, fallback offline.html.
  if (request.mode === 'navigate') {
    event.respondWith(
      (async () => {
        try {
          const fresh = await fetch(request)
          // 성공한 navigate 응답은 그대로 사용 (cache 갱신은 install precache 가 책임).
          return fresh
        } catch (_) {
          // 네트워크 실패 시 cached `/` 시도 → 없으면 offline.html.
          const cached = await caches.match('/index.html')
          if (cached) return cached
          const fallback = await caches.match('/offline.html')
          if (fallback) return fallback
          return new Response('offline', { status: 503, statusText: 'Service Unavailable' })
        }
      })(),
    )
    return
  }

  // shell static 자원 (manifest / icons / offline.html) — cache 우선.
  const url = new URL(request.url)
  if (
    url.origin === self.location.origin &&
    (url.pathname === '/manifest.webmanifest' ||
      url.pathname.startsWith('/icons/') ||
      url.pathname === '/offline.html')
  ) {
    event.respondWith(
      caches.match(request).then((cached) => cached || fetch(request)),
    )
    return
  }

  // 그 외 모든 요청 (API / dist/assets/* JS·CSS 등) 은 그대로 network — cache 안 함.
})

self.addEventListener('push', (event) => {
  let payload = {}
  try {
    payload = event.data ? event.data.json() : {}
  } catch (_) {
    // 일부 push provider 는 JSON 이 아닌 plain text 를 보낸다. message 로 보여주기만.
    payload = { title: 'CONTENIDO', body: event.data ? event.data.text() : '' }
  }
  const title = payload.title || 'CONTENIDO'
  const options = {
    body: payload.body || payload.message || '',
    icon: payload.icon || '/icons/icon-192.svg',
    badge: payload.badge || '/icons/icon-192.svg',
    tag: payload.tag,
    data: {
      url: payload.url || payload.path || '/notifications',
      notificationId: payload.notificationId,
      type: payload.type,
      targetType: payload.targetType,
      targetId: payload.targetId,
    },
  }
  event.waitUntil(self.registration.showNotification(title, options))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const target = (event.notification.data && event.notification.data.url) || '/notifications'
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      // 같은 origin 의 탭이 이미 있으면 거기로 focus + 라우팅 메시지.
      for (const client of clientList) {
        if ('focus' in client) {
          client.focus()
          // Workspace 의 라우터가 messages 를 처리하도록 메시지를 보낸다. 라우팅 처리기가 없어도 OK.
          if ('postMessage' in client) {
            try {
              client.postMessage({ source: 'contenido-sw', type: 'navigate', url: target })
            } catch (_) {
              /* ignore */
            }
          }
          // 페이지가 같은 origin 이지만 다른 path 면 직접 이동.
          if (client.url && new URL(client.url).pathname !== target) {
            try {
              client.navigate(target)
            } catch (_) {
              /* navigate 가 거부될 수 있음 — 그래도 focus 는 유지 */
            }
          }
          return
        }
      }
      if (self.clients.openWindow) {
        return self.clients.openWindow(target)
      }
      return undefined
    }),
  )
})
