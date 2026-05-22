import { useEffect, useRef } from 'react'
import { connectNotificationStream, getNotifications } from '../api/notifications'
import { chatStore } from '../stores/chatStore'
import { notificationStore } from '../stores/notificationStore'
import { useAuth } from './useAuth'

/**
 * App 레벨에서 한 번만 SSE 연결을 유지한다. 인증 사용자가 있을 때만 연결하고,
 * 로그아웃되면 정리한다. 각 페이지(NotificationsPage 외)도 notificationStore 의
 * onIncoming 콜백을 구독해 자기 화면을 refetch 할 수 있다.
 */
export function useNotificationStream() {
  const { isAuthenticated } = useAuth()
  const initialFetchedRef = useRef(false)

  useEffect(() => {
    if (!isAuthenticated) return
    // 초기 1회 — 마운트 시 안 읽은 카운트가 hero(notifications icon dot) 에 반영되도록
    // 첫 페이지를 미리 로드한다.
    if (!initialFetchedRef.current) {
      initialFetchedRef.current = true
      getNotifications({ page: 0, size: 20 })
        .then((res) => notificationStore.setItems(res.content))
        .catch(() => {
          /* non-fatal */
        })
    }

    notificationStore.setStreamStatus('connecting')
    const disconnect = connectNotificationStream({
      onMessage: (incoming) => {
        notificationStore.prepend(incoming)
      },
      onChatMessage: (message) => {
        // PR160 — event-chat fan-in. ChatPanel 이 chatStore.subscribe 로 받아 본인 룸만 필터.
        chatStore.dispatch(message)
      },
      onOpen: () => {
        notificationStore.setStreamStatus('connected')
      },
      onError: () => {
        notificationStore.setStreamStatus('connecting')
      },
      onClose: () => {
        notificationStore.setStreamStatus('disconnected')
      },
    })
    return disconnect
  }, [isAuthenticated])
}
