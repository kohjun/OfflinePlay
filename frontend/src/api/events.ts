import { apiClient } from './client'
import type {
  Event,
  EventApplicant,
  EventComment,
  EventPayload,
  MyParticipation,
  MyParticipationItem,
  PageResponse,
} from '../types'

export function getEvents(channelId: number, params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<Event>>(`/channels/${channelId}/events`, params)
}

export function getEvent(channelId: number, eventId: number) {
  return apiClient.get<Event>(`/channels/${channelId}/events/${eventId}`)
}

/**
 * GET /api/v1/events/{eventId} — channelId 모를 때 (알림/Studio 진입) 사용.
 * 응답의 channelId 로 채널 상세 등 후속 라우팅이 가능하다.
 */
export function getEventById(eventId: number) {
  return apiClient.get<Event>(`/events/${eventId}`)
}

export function createEvent(channelId: number, payload: EventPayload) {
  return apiClient.post<Event>(`/channels/${channelId}/events`, payload)
}

/**
 * PATCH /api/v1/events/{eventId} — owner/ADMIN 만. 모든 필드 optional.
 * 정원/참가비 변경에는 서버 안전 정책이 있다 (이미 발급된 티켓 등).
 */
export interface UpdateEventPayload {
  title?: string
  description?: string
  location?: string
  mainImageUrl?: string
  startAt?: string
  endAt?: string
  maxParticipants?: number
  participationFee?: number
  refundPolicy?: string
  detailContent?: string
  contentType?: import('../types').ContentType
}

export function updateEvent(eventId: number, payload: UpdateEventPayload) {
  return apiClient.patch<Event>(`/events/${eventId}`, payload)
}

// ── 참가 신청 → 기획자 승인/거절 워크플로 ───────────────────────────────────────

/** POST /api/v1/events/{eventId}/participations — 참가 신청. PENDING 으로 저장된다. */
export function applyEvent(eventId: number) {
  return apiClient.post<MyParticipation>(`/events/${eventId}/participations`)
}

/** PATCH /api/v1/events/{eventId}/participations/me/cancel — 본인 PENDING 신청 취소. */
export function cancelEventApplication(eventId: number) {
  return apiClient.patch<MyParticipation>(`/events/${eventId}/participations/me/cancel`)
}

/** GET /api/v1/events/{eventId}/participations/me — 본인 신청 상태. 없으면 data: null. */
export function getMyParticipation(eventId: number) {
  return apiClient.get<MyParticipation | null>(`/events/${eventId}/participations/me`)
}

/** GET /api/v1/events/{eventId}/participations — 기획자/관리자 신청자 목록. */
export function listEventApplicants(eventId: number) {
  return apiClient.get<EventApplicant[]>(`/events/${eventId}/participations`)
}

/** PATCH /api/v1/events/{eventId}/participations/{pid}/approve — 기획자 승인. */
export function approveParticipation(eventId: number, participationId: number) {
  return apiClient.patch<MyParticipation>(`/events/${eventId}/participations/${participationId}/approve`)
}

/** PATCH /api/v1/events/{eventId}/participations/{pid}/reject — 기획자 거절. */
export function rejectParticipation(
  eventId: number,
  participationId: number,
  reason?: string | null,
) {
  return apiClient.patch<MyParticipation>(
    `/events/${eventId}/participations/${participationId}/reject`,
    { reason: reason ?? null },
  )
}

/** GET /api/v1/participations/me — MY 페이지 "내 신청/티켓" 페이지 목록. */
export function getMyParticipations(params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<MyParticipationItem>>('/participations/me', params)
}

// ── Comments ────────────────────────────────────────────────────────────────
// TODO(PR3): backend comment route is `/{TargetType}/{id}/comments` with uppercase enum.
// Will be replaced by generic comments module in PR3.
export function getEventComments(eventId: number, params?: { page?: number; size?: number }) {
  return apiClient.get<PageResponse<EventComment>>(`/EVENT/${eventId}/comments`, params)
}

export function postEventComment(eventId: number, content: string) {
  return apiClient.post<EventComment>(`/EVENT/${eventId}/comments`, { content })
}
