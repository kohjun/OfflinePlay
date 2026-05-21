import { apiClient } from './client'

/**
 * PR141 — 이벤트 공지 API.
 *
 *  - POST: owner/STAFF/ADMIN
 *  - GET : 위 + APPROVED 참가자
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
}

export interface CreateEventAnnouncementRequest {
  title: string
  content: string
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
