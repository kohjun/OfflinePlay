/**
 * Mirrors backend TicketStatus.
 * PR117 — PARTIALLY_REFUNDED 추가. 부분 환불 진행 중 (refundedAmount > 0, < amount) 상태이며
 * 참가 자격은 유지된다. 누적 환불액이 amount 에 도달하면 REFUNDED 로 cascade.
 */
export type TicketStatus =
  | 'PAID'
  | 'USED'
  | 'REFUNDED'
  | 'CANCELED'
  | 'PARTIALLY_REFUNDED'

/**
 * Mirrors backend TicketDetailResponse — 참가자 티켓 패스/QR 화면.
 *
 *  - checkInCode : MVP 단계 결정형 문자열. 실제 보안 QR (서명/만료) 은 후속 과제.
 */
export interface TicketDetail {
  ticketId: number
  ticketStatus: TicketStatus
  eventId: number
  eventTitle: string
  channelId: number
  channelName: string
  mainImageUrl: string
  startAt: string
  endAt: string
  location: string
  participationFee: number
  buyerId: number
  buyerNickname: string
  purchasedAt: string
  checkInCode: string
  /** PAID → USED 로 전환된 시각. USED 가 아니면 null. */
  usedAt?: string | null
}
