import type { TicketStatus } from './ticket'

/**
 * Mirrors backend PaymentStatus.
 * PR117 — PARTIALLY_REFUNDED 추가 (부분 환불 진행 중 PaymentAttempt 상태).
 */
export type PaymentStatus =
  | 'READY'
  | 'PAID'
  | 'FAILED'
  | 'CANCELED'
  | 'PARTIALLY_REFUNDED'

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

/**
 * Mirrors backend RefundTicketRequest.
 *  - reason : 빈 값일 수 있으며 서버가 USER_REQUEST 로 대체.
 *  - amount : PR117 — 부분 환불 금액 (원, BIGINT). null/undefined 이면 남은 환불 가능 금액 전체
 *             (= 전액 환불). 1 <= amount <= remainingRefundableAmount 가 아니면 backend 가 400.
 */
export interface RefundTicketRequest {
  reason?: string | null
  amount?: number | null
}

/**
 * Mirrors backend RefundTicketResponse.
 *  - amount                    : 결제 총액 (참조용).
 *  - refundedAmount            : PR117 — 누적 환불 금액 (이번 호출 포함).
 *  - remainingRefundableAmount : PR117 — 남은 환불 가능 금액. 0 이면 fully refunded.
 */
export interface RefundTicketResponse {
  ticketId: number
  ticketStatus: TicketStatus
  paymentAttemptId: number
  provider: PaymentProvider
  amount: number
  refundedAmount: number
  remainingRefundableAmount: number
  refundedAt: string
  providerPaymentKey: string | null
}

/**
 * PR106 — Mirrors backend AdminForcedRefundResponse.
 * 일반 환불 응답에 운영 사유(`refundReason`)가 추가된 형태.
 *
 * PR134 — 부분 강제 환불 지원에 따라 누적 환불액 / 남은 환불 가능액 / fullRefund 플래그 3 필드
 * 추가. backend 가 optional default 로 직렬화하므로 옛 응답도 호환된다.
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
  refundedAmount?: number
  remainingRefundableAmount?: number
  fullRefund?: boolean
}

/**
 * PR134 — `POST /admin/tickets/{ticketId}/forced-refund` body.
 *  - `reason` : 1~500자 운영 사유.
 *  - `amount` : optional 부분 환불 금액. null/미지정 → remaining 전액 환불 (PR106 기존 동작).
 *    1 <= amount <= remainingRefundableAmount 일 때만 허용 — 범위 위반은 backend 가 400.
 */
export interface AdminForcedRefundRequest {
  reason: string
  amount?: number
}
