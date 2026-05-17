import type { TicketStatus } from './ticket'

/** Mirrors backend PaymentStatus. */
export type PaymentStatus = 'READY' | 'PAID' | 'FAILED' | 'CANCELED'

/** Mirrors backend PaymentProvider. NONE = PR39 단계 (실제 PG 미연동). */
export type PaymentProvider = 'NONE' | 'TOSS' | 'PORTONE'

/**
 * Mirrors backend PaymentPrepareResponse.
 * idempotencyKey 를 PG SDK 호출 시 orderId 로 그대로 전달한다.
 */
export interface PaymentPrepareResponse {
  paymentAttemptId: number
  eventId: number
  amount: number
  orderName: string
  idempotencyKey: string
  status: PaymentStatus
}

/**
 * Mirrors backend PaymentConfirmRequest. PG SDK 콜백으로 받은 paymentKey 와
 * orderId/amount 를 그대로 전달해 백엔드가 PG 에 confirm 호출하게 한다.
 */
export interface PaymentConfirmRequest {
  paymentKey: string
  orderId: string
  amount: number
}

/**
 * Mirrors backend PaymentConfirmResponse.
 *  - ticketId 는 PAID 가 아니면 null.
 *  - approvedAt 은 PG 가 알려준 승인 시각(ISO-8601), MockPaymentGateway 환경에선 null.
 */
export interface PaymentConfirmResponse {
  paymentAttemptId: number
  status: PaymentStatus
  provider: PaymentProvider
  amount: number
  ticketId: number | null
  providerPaymentKey: string | null
  approvedAt: string | null
}

/** Mirrors backend RefundTicketRequest. reason 은 빈 값일 수 있으며 서버가 USER_REQUEST 로 대체. */
export interface RefundTicketRequest {
  reason?: string | null
}

/** Mirrors backend RefundTicketResponse. */
export interface RefundTicketResponse {
  ticketId: number
  ticketStatus: TicketStatus
  paymentAttemptId: number
  provider: PaymentProvider
  amount: number
  refundedAt: string
  providerPaymentKey: string | null
}

/**
 * PR106 — Mirrors backend AdminForcedRefundResponse.
 * 일반 환불 응답에 운영 사유(`refundReason`)가 추가된 형태. 부분 환불은 미지원 (`amount` 는 전액).
 */
export interface AdminForcedRefundResponse {
  ticketId: number
  ticketStatus: TicketStatus
  paymentAttemptId: number
  provider: PaymentProvider
  amount: number
  refundedAt: string
  providerPaymentKey: string | null
  refundReason: string
}
