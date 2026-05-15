import { apiClient } from './client'
import type { PaymentPrepareResponse } from '../types'

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
