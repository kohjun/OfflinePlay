import { apiClient } from './client'
import type {
  AdminForcedRefundResponse,
  PaymentConfirmRequest,
  PaymentConfirmResponse,
  PaymentPrepareResponse,
  RefundTicketRequest,
  RefundTicketResponse,
} from '../types'

/**
 * POST /api/v1/events/{eventId}/payments/prepare
 *
 * 유료 이벤트 결제 페이지로 진입하기 직전 호출한다. 응답의 idempotencyKey 를
 * 클라이언트가 PG SDK 의 orderId 로 그대로 전달한다.
 *
 * 같은 (user, event) 에서 다시 호출하면 동일한 PaymentAttempt 가 반환된다(멱등).
 *
 * 거부 케이스 (백엔드 throw):
 *  - 무료 이벤트(participationFee == 0): 400 FreeEventCannotPreparePaymentException
 *  - 이미 PAID/USED 티켓 보유: 409 AlreadyJoinedException
 *  - 정원 가득: 409 EventFullException
 *  - 채널 owner 본인: 409 OwnerCannotApplyException
 *  - 이벤트 시작 후: 409 EventAlreadyStartedException
 *  - 이벤트 CLOSED: 409 EventClosedException
 *
 * 본 helper 는 PR39 단계에선 EventDetailPage 가 아직 호출하지 않는다(무료 흐름 유지).
 * PR40 에서 유료 이벤트 CTA 가 분기될 때부터 사용된다.
 */
export function preparePayment(eventId: number) {
  return apiClient.post<PaymentPrepareResponse>(`/events/${eventId}/payments/prepare`)
}

/**
 * POST /api/v1/payments/{paymentAttemptId}/confirm
 *
 * PG SDK 결제창 콜백으로 받은 paymentKey 를 백엔드에 전달해 PG 측 confirm 을
 * 트리거한다. 성공 시 Ticket(PAID) 이 발급되고 EventParticipation 이 APPROVED 로 보장된다.
 *
 * sandbox 키가 아직 없는 PR40 단계에선 백엔드의 MockPaymentGateway 가 항상 성공으로
 * 응답한다. EventDetailPage 의 유료 CTA 는 `mock-${idempotencyKey}` 형식의 더미
 * paymentKey 를 생성해 호출한다 — PR41 에서 실제 Toss SDK 연동으로 교체.
 */
export function confirmPayment(
  paymentAttemptId: number,
  request: PaymentConfirmRequest,
) {
  return apiClient.post<PaymentConfirmResponse>(
    `/payments/${paymentAttemptId}/confirm`,
    request,
  )
}

/**
 * POST /api/v1/tickets/{ticketId}/refund
 *
 * 환불 요청. buyer 본인 / 채널 owner / ADMIN 만 가능. STAFF 는 권한 없음.
 *
 * 거부 케이스 (백엔드 throw):
 *  - 인증 부재 / 권한 없음: 403 UnauthorizedException
 *  - USED ticket: 409 TicketAlreadyUsedException
 *  - CANCELED ticket: 409 PaymentNotRefundableException
 *  - PaymentAttempt 부재 또는 providerPaymentKey 부재: 409 PaymentNotRefundableException
 *  - PG gateway 거절: 502 RefundFailedException
 *
 * 멱등: 이미 REFUNDED 인 ticket 은 gateway 재호출 없이 기존 정보로 응답.
 */
export function refundTicket(ticketId: number, request: RefundTicketRequest = {}) {
  return apiClient.post<RefundTicketResponse>(`/tickets/${ticketId}/refund`, request)
}

/**
 * POST /api/v1/admin/tickets/{ticketId}/forced-refund
 *
 * PR106 — ADMIN 전용 강제 환불 도구. 일반 [refundTicket] 와 달리:
 *  - USED 티켓도 전액 환불 가능 (체크인 후 노쇼 보상, 행사 취소 보상 등)
 *  - 이벤트 시작 시각 이후도 가능 (deadline 가드 우회)
 *  - 사유(reason) 필수, 최대 500자. audit log 에 그대로 기록된다.
 *
 * 거부 케이스 (백엔드 throw):
 *  - actor 가 ADMIN 아님: 403 UnauthorizedException
 *  - ticket REFUNDED: 409 TicketAlreadyRefundedException (멱등 응답 아님 — 실수 방지)
 *  - ticket CANCELED: 409 PaymentNotRefundableException
 *  - PaymentAttempt 부재 / 상태 부적합: 409 PaymentNotRefundableException
 *  - PG gateway 거절: 502 RefundFailedException
 */
export function forceRefundTicket(ticketId: number, reason: string) {
  return apiClient.post<AdminForcedRefundResponse>(
    `/admin/tickets/${ticketId}/forced-refund`,
    { reason },
  )
}
