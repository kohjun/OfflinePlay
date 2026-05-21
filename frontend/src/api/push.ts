import { apiClient } from './client'

/**
 * PR139 — Web Push 구독 helper.
 *
 * 정책:
 *  - VAPID public key (`VITE_PUSH_VAPID_PUBLIC_KEY`) 가 비어 있거나 SW/PushManager 가 없는
 *    환경에서는 `getPushSupportStatus()` 가 `'unsupported'` 를 반환한다. UI 는 이 분기를
 *    기준으로 fallback 메시지를 그린다.
 *  - SW 등록은 idempotent — 이미 등록되어 있으면 그 등록을 반환.
 *  - subscribe / unsubscribe 는 backend hard-state(POST/DELETE) 를 그대로 호출.
 */

export interface PushSubscriptionResponse {
  id: number
  enabled: boolean
  userAgent?: string | null
  lastSeenAt?: string | null
  createdAt: string
  updatedAt: string
}

export type PushSupportStatus =
  | 'supported'       // SW + PushManager + VAPID key 사용 가능
  | 'unsupported'     // 브라우저가 지원 안 함
  | 'no-vapid-key'    // 빌드에 VAPID key 가 빠짐

export function getPushSupportStatus(): PushSupportStatus {
  if (typeof window === 'undefined') return 'unsupported'
  const hasSw = 'serviceWorker' in navigator
  const hasPush = 'PushManager' in window
  if (!hasSw || !hasPush) return 'unsupported'
  const key = import.meta.env.VITE_PUSH_VAPID_PUBLIC_KEY
  if (!key || key.trim().length === 0) return 'no-vapid-key'
  return 'supported'
}

export function getPushPermission(): NotificationPermission | 'unsupported' {
  if (typeof Notification === 'undefined') return 'unsupported'
  return Notification.permission
}

/**
 * 사용자 권한 요청. 이미 granted/denied 면 그대로 반환.
 */
export async function ensurePushPermission(): Promise<NotificationPermission | 'unsupported'> {
  if (typeof Notification === 'undefined') return 'unsupported'
  if (Notification.permission === 'granted' || Notification.permission === 'denied') {
    return Notification.permission
  }
  return await Notification.requestPermission()
}

/**
 * Service worker 등록 (idempotent). frontend/public/sw.js 가 빌드 산출물의 / 경로에 배포된다.
 */
async function getOrRegisterServiceWorker(): Promise<ServiceWorkerRegistration> {
  const existing = await navigator.serviceWorker.getRegistration('/sw.js')
  if (existing) return existing
  return await navigator.serviceWorker.register('/sw.js')
}

/**
 * VAPID public key (base64url) → PushManager 가 받는 ArrayBuffer.
 *
 * 반환 타입을 ArrayBuffer 로 좁혀 TS lib.dom `PushSubscriptionOptionsInit.applicationServerKey`
 * (`BufferSource`) 와 호환되게 한다. `Uint8Array<ArrayBufferLike>` 는 SharedArrayBuffer 도 포함하므로
 * 그대로 넘기면 lib.dom 시그니처와 어긋난다.
 */
function urlBase64ToArrayBuffer(base64String: string): ArrayBuffer {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4)
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(base64)
  const buffer = new ArrayBuffer(raw.length)
  const view = new Uint8Array(buffer)
  for (let i = 0; i < raw.length; i += 1) {
    view[i] = raw.charCodeAt(i)
  }
  return buffer
}

function arrayBufferToBase64Url(buffer: ArrayBuffer | null): string {
  if (!buffer) return ''
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i += 1) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/**
 * 브라우저에서 PushManager 구독을 만들고(idempotent) backend 에 등록한다.
 * 이미 같은 endpoint 구독이 있으면 backend 는 credential 만 갱신한다.
 */
export async function registerPushSubscription(): Promise<PushSubscriptionResponse> {
  const status = getPushSupportStatus()
  if (status !== 'supported') {
    throw new Error(
      status === 'no-vapid-key'
        ? '푸시 키가 설정되지 않았어요. 잠시 후 다시 시도해주세요.'
        : '이 브라우저에서는 푸시 알림을 사용할 수 없어요.',
    )
  }
  const permission = await ensurePushPermission()
  if (permission !== 'granted') {
    throw new Error('알림 권한이 필요합니다.')
  }
  const registration = await getOrRegisterServiceWorker()
  const vapidKey = import.meta.env.VITE_PUSH_VAPID_PUBLIC_KEY as string
  const applicationServerKey = urlBase64ToArrayBuffer(vapidKey)

  let sub = await registration.pushManager.getSubscription()
  if (!sub) {
    sub = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey,
    })
  }
  const p256dh = arrayBufferToBase64Url(sub.getKey('p256dh'))
  const auth = arrayBufferToBase64Url(sub.getKey('auth'))
  return await apiClient.post<PushSubscriptionResponse>('/push/subscriptions', {
    endpoint: sub.endpoint,
    keys: { p256dh, auth },
    userAgent: navigator.userAgent,
  })
}

/**
 * 브라우저 + backend 양쪽에서 구독을 해지한다. backend 가 row 가 없다고 알려도 (removed=0) UI 는
 * 사용자가 의도한 결과로 본다 — 양쪽 모두 "구독 안 함" 상태가 된다.
 */
export async function unregisterPushSubscription(): Promise<{ removed: number }> {
  const status = getPushSupportStatus()
  let removed = 0
  if (status === 'supported' || status === 'no-vapid-key') {
    const registration = await navigator.serviceWorker.getRegistration('/sw.js')
    if (registration) {
      const sub = await registration.pushManager.getSubscription()
      if (sub) {
        removed = await sendUnsubscribe(sub.endpoint)
        await sub.unsubscribe().catch(() => undefined)
      }
    }
  }
  return { removed }
}

async function sendUnsubscribe(endpoint: string): Promise<number> {
  // apiClient.delete 는 body 를 지원하지 않으므로 fetch 직접 호출.
  const baseUrl = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
  const token = localStorage.getItem('woya.accessToken')
  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/push/subscriptions`, {
    method: 'DELETE',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ endpoint }),
  })
  if (!response.ok) return 0
  const text = await response.text()
  if (!text) return 0
  try {
    const payload = JSON.parse(text) as { data?: number }
    return typeof payload.data === 'number' ? payload.data : 0
  } catch {
    return 0
  }
}

/**
 * 내 활성/비활성 구독 목록 (디바이스 단위). UI 가 디버그/내가 등록한 디바이스 표시에 사용.
 */
export function getMyPushSubscriptions() {
  return apiClient.get<PushSubscriptionResponse[]>('/push/subscriptions/me')
}

/**
 * 현재 브라우저가 PushManager 에 구독돼 있는지 확인. UI 는 토글의 초기 상태에 사용.
 */
export async function getActivePushSubscription(): Promise<PushSubscription | null> {
  const status = getPushSupportStatus()
  if (status === 'unsupported') return null
  const registration = await navigator.serviceWorker.getRegistration('/sw.js')
  if (!registration) return null
  return registration.pushManager.getSubscription()
}
