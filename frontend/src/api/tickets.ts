import { apiClient } from './client'
import type { TicketDetail } from '../types'

/**
 * GET /api/v1/tickets/{ticketId}
 *
 * 참가자(buyer) 본인 또는 ADMIN 만 조회 가능. 그 외는 403.
 * checkInCode 는 결정형 문자열 (보안 QR 은 후속 과제).
 */
export function getTicket(ticketId: number) {
  return apiClient.get<TicketDetail>(`/tickets/${ticketId}`)
}

/**
 * POST /api/v1/tickets/{ticketId}/check-in — 현장 체크인.
 * 채널 owner/STAFF 또는 ADMIN 만 허용. buyer 본인은 거절(403).
 */
export function checkInTicket(ticketId: number) {
  return apiClient.post<TicketDetail>(`/tickets/${ticketId}/check-in`)
}

/**
 * POST /api/v1/tickets/check-in — 체크인 코드 기반 체크인.
 * 코드 형식: `CONTENIDO-{ticketId}-{eventId}` (앞뒤 공백은 서버에서 trim).
 */
export function checkInByCode(checkInCode: string) {
  return apiClient.post<TicketDetail>('/tickets/check-in', { checkInCode })
}

export interface EventCheckInTicket {
  ticketId: number
  buyerId: number
  buyerNickname: string
  status: import('../types').TicketStatus
  purchasedAt: string
  usedAt: string | null
}

export interface EventCheckInSummary {
  eventId: number
  eventTitle: string
  issuedCount: number
  checkedInCount: number
  notCheckedInCount: number
  tickets: EventCheckInTicket[]
}

/** GET /api/v1/events/{eventId}/check-ins — owner/STAFF/ADMIN. */
export function getEventCheckIns(eventId: number) {
  return apiClient.get<EventCheckInSummary>(`/events/${eventId}/check-ins`)
}
