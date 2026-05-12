import { useSyncExternalStore } from 'react'
import type { Notification } from '../types'

export type StreamStatus = 'connecting' | 'connected' | 'disconnected'

interface NotificationState {
  items: Notification[]
  unreadCount: number
  streamStatus: StreamStatus
}

let state: NotificationState = {
  items: [],
  unreadCount: 0,
  streamStatus: 'connecting',
}

const listeners = new Set<() => void>()
const incomingListeners = new Set<(n: Notification) => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

function countUnread(items: Notification[]) {
  return items.filter((item) => !item.isRead).length
}

export const notificationStore = {
  getSnapshot: () => state,
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
  setItems(items: Notification[]) {
    state = { ...state, items, unreadCount: countUnread(items) }
    emit()
  },
  appendItems(incoming: Notification[]) {
    // Skip ids already in state (could happen if SSE delivered a notification
    // while the user scrolled into the same page that pagination just fetched).
    const existingIds = new Set(state.items.map((item) => item.id))
    const fresh = incoming.filter((item) => !existingIds.has(item.id))
    if (fresh.length === 0) return
    const items = [...state.items, ...fresh]
    state = { ...state, items, unreadCount: countUnread(items) }
    emit()
  },
  markRead(id: number) {
    const items = state.items.map((item) => (item.id === id ? { ...item, isRead: true } : item))
    state = { ...state, items, unreadCount: countUnread(items) }
    emit()
  },
  /** optimistic read 실패 시 롤백용 — 단건을 다시 unread 로 되돌린다. */
  markUnread(id: number) {
    const items = state.items.map((item) => (item.id === id ? { ...item, isRead: false } : item))
    state = { ...state, items, unreadCount: countUnread(items) }
    emit()
  },
  markAllRead() {
    const items = state.items.map((item) => ({ ...item, isRead: true }))
    state = { ...state, items, unreadCount: 0 }
    emit()
  },
  prepend(notification: Notification) {
    if (state.items.some((item) => item.id === notification.id)) return
    const items = [notification, ...state.items]
    state = { ...state, items, unreadCount: countUnread(items) }
    emit()
    // SSE 실시간 수신 — 외부 페이지가 화면을 갱신할 수 있도록 별도 listener 도 호출.
    incomingListeners.forEach((cb) => {
      try {
        cb(notification)
      } catch {
        /* listener 가 실패해도 전체 흐름은 보호 */
      }
    })
  },
  setStreamStatus(status: StreamStatus) {
    if (state.streamStatus === status) return
    state = { ...state, streamStatus: status }
    emit()
  },
  /**
   * SSE 로 새 알림이 도착할 때마다 호출되는 콜백 등록. 페이지가 자신과 관련된 알림에
   * 반응해 refetch 하기 위해 사용한다. 반환값은 unsubscribe 함수.
   */
  onIncoming(cb: (n: Notification) => void): () => void {
    incomingListeners.add(cb)
    return () => {
      incomingListeners.delete(cb)
    }
  },
}

export function useNotificationStore() {
  return useSyncExternalStore(
    notificationStore.subscribe,
    notificationStore.getSnapshot,
    notificationStore.getSnapshot,
  )
}
