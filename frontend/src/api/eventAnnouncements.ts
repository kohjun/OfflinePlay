import { apiClient } from './client'

/**
 * PR141 + PR151 — 이벤트 공지 API.
 *
 *  - POST          : owner/STAFF/ADMIN
 *  - GET           : 위 + APPROVED 참가자
 *  - PATCH /pin    : owner/STAFF/ADMIN, 같은 이벤트의 기존 pinned 자동 해제
 *  - POST /read    : 위 + APPROVED 참가자 (read receipt 멱등 upsert)
 *  - GET /unread-count : 위 + APPROVED 참가자
 * 권한이 없으면 backend 가 403 으로 막는다.
 */

export interface EventAnnouncement {
  id: number
  eventId: number
  authorId: number
  authorNickname: string
  title: string
  content: string
  createdAt: string
  updatedAt: string
  /** PR151 — pin 표시. true 면 list 응답에서 상단에 정렬됨. */
  pinned: boolean
  /** PR151 — viewer 가 본 공지인지. */
  read: boolean
  /** PR152 — 첨부 이미지 url 목록 (displayOrder asc). */
  imageUrls: string[]
}

export interface CreateEventAnnouncementRequest {
  title: string
  content: string
  /** PR152 — 최대 3장. backend 가 validation. */
  imageUrls?: string[]
}

export interface UnreadAnnouncementCount {
  eventId: number
  unreadCount: number
}

export function getEventAnnouncements(eventId: number) {
  return apiClient.get<EventAnnouncement[]>(`/events/${eventId}/announcements`)
}

export function createEventAnnouncement(
  eventId: number,
  request: CreateEventAnnouncementRequest,
) {
  return apiClient.post<EventAnnouncement>(`/events/${eventId}/announcements`, request)
}

export function setEventAnnouncementPinned(
  eventId: number,
  announcementId: number,
  pinned: boolean,
) {
  return apiClient.patch<EventAnnouncement>(
    `/events/${eventId}/announcements/${announcementId}/pin`,
    { pinned },
  )
}

export function markEventAnnouncementAsRead(eventId: number, announcementId: number) {
  return apiClient.post<unknown>(`/events/${eventId}/announcements/${announcementId}/read`)
}

export function getEventUnreadAnnouncementCount(eventId: number) {
  return apiClient.get<UnreadAnnouncementCount>(`/events/${eventId}/announcements/unread-count`)
}
