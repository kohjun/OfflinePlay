import type { ChatStreamMessage } from '../api/notifications'

/**
 * PR160 — Event chat 실시간 fan-in.
 *
 * useNotificationStream 의 onChatMessage 콜백이 본 스토어의 `dispatch(msg)` 를 호출한다.
 * ChatPanel 은 `subscribe(callback)` 으로 메시지를 받아 본인 룸 (eventId 매칭) 만 골라낸다.
 *
 * 본 스토어는 메시지 history 를 유지하지 않는다 — history 는 ChatPanel 의 local state.
 * 본 스토어는 단순 "새 메시지 도착" event bus 역할.
 */
const subscribers = new Set<(message: ChatStreamMessage) => void>()

export const chatStore = {
  /** SSE 가 새 메시지를 받았을 때 호출. 모든 subscriber 에게 fan-out. */
  dispatch(message: ChatStreamMessage) {
    subscribers.forEach((fn) => {
      try {
        fn(message)
      } catch {
        /* 한 subscriber 의 예외가 다른 subscriber 를 막지 않게 */
      }
    })
  },
  /** ChatPanel 이 mount 시 호출. 반환 함수를 unmount cleanup 에서 사용. */
  subscribe(callback: (message: ChatStreamMessage) => void): () => void {
    subscribers.add(callback)
    return () => {
      subscribers.delete(callback)
    }
  },
}
