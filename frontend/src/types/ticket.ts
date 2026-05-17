/** Mirrors backend TicketStatus. PG 미연동이라 PAID 만 발급되며 USED/REFUNDED/CANCELED 는 향후 상태. */
export type TicketStatus = 'PAID' | 'USED' | 'REFUNDED' | 'CANCELED'

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
