import { apiClient } from './client'

/**
 * PR160 — Event room chat API.
 *  - GET /events/{eid}/chat/can-enter : 권한 가드만 빠르게. 200 OK → 진입 가능.
 *  - GET /events/{eid}/chat/messages?beforeCreatedAt=&beforeId=&size= : 히스토리.
 *  - POST /events/{eid}/chat/messages : 메시지 송신 (text only).
 *
 * 실시간 수신은 별도 endpoint 가 아니라 기존 `/notifications/connect` SSE 의
 * `event-chat` named event 로 fan-in (notifications.ts 의 onChatMessage 콜백).
 */

export interface EventChatMessage {
  id: number
  eventId: number
  senderId: number
  senderNickname: string
  content: string
  isAnnouncement: boolean
  createdAt: string
}

export interface EventChatHistory {
  items: EventChatMessage[]
  nextBeforeCreatedAt: string | null
  nextBeforeId: number | null
}

export interface SendChatMessageRequest {
  content: string
  isAnnouncement?: boolean
}

export function canEnterEventChat(eventId: number) {
  return apiClient.get<boolean>(`/events/${eventId}/chat/can-enter`)
}

export function getEventChatHistory(
  eventId: number,
  params?: { beforeCreatedAt?: string; beforeId?: number; size?: number },
) {
  return apiClient.get<EventChatHistory>(`/events/${eventId}/chat/messages`, params)
}

export function sendEventChatMessage(eventId: number, request: SendChatMessageRequest) {
  return apiClient.post<EventChatMessage>(`/events/${eventId}/chat/messages`, request)
}
