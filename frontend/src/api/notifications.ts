import { apiClient, tokenStorage } from './client'
import type { Notification, PageResponse } from '../types'

const SSE_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

export interface NotificationStreamOptions {
  onMessage: (notification: Notification) => void
  onOpen?: () => void
  /** Called every time EventSource fires an error event (including transient ones). */
  onError?: (error: Event) => void
  /** Called once when the stream is permanently closed (manual close or maxErrors reached). */
  onClose?: () => void
  /** Stream gives up and closes after this many consecutive errors. Default 5. */
  maxErrors?: number
}

/**
 * Open an SSE connection to the notifications stream.
 *
 * Security tradeoff:
 *   The browser's EventSource API cannot set an Authorization header, so the access
 *   token is appended as a query parameter. The backend auth filter accepts this.
 *   Query strings can leak through proxy/access logs, so longer-term we should swap
 *   to either:
 *     - a short-lived SSE-only token issued by /auth/sse-token, or
 *     - cookie auth with SameSite=Strict.
 *   Tracked as a follow-up; not in scope for PR5.
 *
 * @returns a function that closes the stream and fires onClose() exactly once.
 */
export function connectNotificationStream(
  options: NotificationStreamOptions,
): () => void {
  const { onMessage, onOpen, onError, onClose, maxErrors = 5 } = options

  let closed = false
  const fireClose = () => {
    if (closed) return
    closed = true
    onClose?.()
  }

  const token = tokenStorage.get()
  if (!token) {
    // No point opening a stream we know will be rejected. Resolve immediately.
    fireClose()
    return () => {
      /* already closed */
    }
  }

  const url = new URL(
    `${SSE_BASE.replace(/\/$/, '')}/notifications/connect`,
    window.location.origin,
  )
  url.searchParams.set('token', token)

  const source = new EventSource(url.toString(), { withCredentials: true })
  let errorCount = 0

  source.onopen = () => {
    errorCount = 0
    onOpen?.()
  }

  source.onerror = (event) => {
    errorCount += 1
    onError?.(event)
    if (errorCount >= maxErrors) {
      source.close()
      fireClose()
    }
  }

  source.addEventListener('notification', (event) => {
    try {
      const data = JSON.parse((event as MessageEvent).data) as Notification
      onMessage(data)
    } catch {
      /* ignore malformed payload */
    }
  })

  source.onmessage = (event) => {
    if (!event.data) return
    try {
      const data = JSON.parse(event.data) as Notification
      onMessage(data)
    } catch {
      /* heartbeat or non-notification event — ignore */
    }
  }

  return () => {
    source.close()
    fireClose()
  }
}

export function getNotifications(params?: { page?: number; size?: number; unreadOnly?: boolean }) {
  return apiClient.get<PageResponse<Notification>>('/notifications', params)
}

export function markNotificationRead(id: number) {
  return apiClient.patch<Notification>(`/notifications/${id}/read`)
}

export function markAllNotificationsRead() {
  return apiClient.patch<Notification[]>('/notifications/read-all')
}
