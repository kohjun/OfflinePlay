/* eslint-disable no-restricted-globals */
/**
 * CONTENIDO — Web Push Service Worker (PR139 baseline + PR140 dispatch).
 *
 * PR139 등록만 다루고, push 수신 핸들러는 PR140 의 dispatch 와 함께 사용된다.
 *  - install / activate : 즉시 활성화 → 사용자가 첫 구독 시 reload 없이 바로 사용.
 *  - push               : payload.data 가 JSON 이면 notification.showNotification 으로 표시.
 *  - notificationclick  : 알림 click 시 data.url 로 focus/open. 없으면 /notifications 로.
 *
 * Worker 는 ESM 이 아닌 classic script 로 등록되므로 import/export 를 쓰지 않는다.
 */

self.addEventListener('install', () => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
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
    icon: payload.icon || '/favicon.ico',
    badge: payload.badge || '/favicon.ico',
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
