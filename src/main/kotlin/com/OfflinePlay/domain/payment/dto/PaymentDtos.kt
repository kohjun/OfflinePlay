package com.contenido.domain.payment.dto

import com.contenido.domain.payment.entity.PaymentProvider
import com.contenido.domain.payment.entity.PaymentStatus

/**
 * `POST /api/v1/events/{eventId}/payments/prepare` 응답.
 *
 *  - [idempotencyKey] 는 서버가 만든 UUID 로, 클라이언트가 PG SDK 를 호출할 때
 *    orderId 로 그대로 전달한다. 같은 (user, event) 에서 prepare 를 다시 호출하면
 *    동일한 PaymentAttempt(같은 idempotencyKey)가 그대로 반환된다.
 *  - [orderName] 은 PG 결제창 상단에 노출될 주문 이름. 현재는 `이벤트 제목` 그대로.
 */
data class PaymentPrepareResponse(
    val paymentAttemptId: Long,
    val eventId: Long,
    val amount: Long,
    val orderName: String,
    val idempotencyKey: String,
    val status: PaymentStatus,
)

/**
 * `POST /api/v1/payments/webhook` 요청 body.
 *
 * 본 PR 단계에선 provider 별 signature/HMAC 검증을 하지 않고, idempotencyKey 만으로 매핑한다.
 * 실제 PG 연동 PR 에서 [provider] 별 signature 검증 필터 또는 service 진입 단계 검증을 추가한다.
 *
 *  - [idempotencyKey] : prepare 응답으로 클라이언트가 받은 값. PG 가 그대로 echo 해서 보내준다 (orderId).
 *  - [providerPaymentKey] : Toss `paymentKey`, PortOne `imp_uid` 등 PG 가 부여한 결제 식별자.
 *  - [amount] : 실제 결제된 금액. prepare 시점의 PaymentAttempt.amount 와 다르면 거부.
 *  - [status] : `PAID` / `FAILED` / `CANCELED`. READY 는 webhook 으로 도착할 수 없다.
 *  - [provider] : 어떤 PG 가 호출했는지. 미명시면 NONE.
 */
data class PaymentWebhookRequest(
    val idempotencyKey: String,
    val providerPaymentKey: String? = null,
    val amount: Long,
    val status: PaymentStatus,
    val provider: PaymentProvider = PaymentProvider.NONE,
)

/**
 * `POST /api/v1/payments/{paymentAttemptId}/confirm` 요청 body.
 *
 * 클라이언트가 PG SDK 콜백으로 받은 paymentKey 와, 검증용으로 orderId/amount 를 그대로 전달한다.
 * orderId 는 prepare 응답의 idempotencyKey 와 동일한 값이어야 한다.
 */
data class PaymentConfirmRequest(
    val paymentKey: String,
    val orderId: String,
    val amount: Long,
)

/**
 * `POST /api/v1/payments/{paymentAttemptId}/confirm` 응답.
 *
 *  - [paymentAttemptId] / [status] : 갱신된 PaymentAttempt 상태 (성공 시 PAID).
 *  - [ticketId]                    : 발급된 Ticket. PAID 가 아니면 null.
 *  - [providerPaymentKey]          : PG 가 부여한 결제 키 — 환불 호출에 다시 사용.
 *  - [approvedAt]                  : PG 가 알려준 승인 시각 (있을 때).
 */
data class PaymentConfirmResponse(
    val paymentAttemptId: Long,
    val status: PaymentStatus,
    val provider: PaymentProvider,
    val amount: Long,
    val ticketId: Long?,
    val providerPaymentKey: String?,
    val approvedAt: String?,
)

/**
 * `POST /api/v1/tickets/{ticketId}/refund` 요청 body.
 *
 *  - [reason] : 운영 로그에 남기는 사유. 사용자 환불 폼에서 빈 값일 수 있어 service 단에서 trim
 *               + default("USER_REQUEST") 처리. 최대 500자.
 *  - [amount] : PR117 — 부분 환불 금액. null 이면 남은 환불 가능 금액 전체 (기존 전액 환불 동작과
 *               동일). 지정 시 1 이상, [com.contenido.domain.payment.entity.PaymentAttempt.remainingRefundableAmount]
 *               이하여야 한다. 단위는 amount 와 동일 (원, BIGINT).
 */
data class RefundTicketRequest(
    val reason: String? = null,
    val amount: Long? = null,
)

/**
 * `POST /api/v1/tickets/{ticketId}/refund` 응답.
 *
 *  - [ticketId] / [ticketStatus] : 환불 후 ticket 상태 (REFUNDED 또는 PARTIALLY_REFUNDED).
 *  - [amount]                    : 결제 시도의 총 결제 금액 (참조용, PR42 부터 그대로).
 *  - [refundedAmount]            : PR117 — 누적 환불 금액 (이번 호출 포함).
 *  - [remainingRefundableAmount] : PR117 — 남은 환불 가능 금액. 0 이면 fully refunded.
 *  - [refundedAt]                : PaymentAttempt 가 기록한 마지막 환불 처리 시각 (서버 기준).
 *  - [providerPaymentKey]        : PG 측 결제 키. 운영 도구가 환불 추적에 사용한다.
 */
data class RefundTicketResponse(
    val ticketId: Long,
    val ticketStatus: com.contenido.domain.ticket.entity.TicketStatus,
    val paymentAttemptId: Long,
    val provider: PaymentProvider,
    val amount: Long,
    val refundedAmount: Long,
    val remainingRefundableAmount: Long,
    val refundedAt: String,
    val providerPaymentKey: String?,
)

/**
 * PR106 — ADMIN 강제 환불 (`POST /api/v1/admin/tickets/{ticketId}/forced-refund`) 요청 body.
 *
 *  - [reason] : 운영 사유. 필수. 1~500자. audit log 에 그대로 기록된다.
 */
data class AdminForcedRefundRequest(
    @field:jakarta.validation.constraints.NotBlank
    @field:jakarta.validation.constraints.Size(min = 1, max = 500)
    val reason: String,
)

/**
 * PR106 — ADMIN 강제 환불 응답. 일반 환불 응답과 유사하지만 forced reason 을 명시 echo 한다
 * (감사 추적용). cascade 결과(ticket REFUNDED, participation CANCELED, currentParticipants--)는
 * 일반 환불과 동일하다.
 */
data class AdminForcedRefundResponse(
    val ticketId: Long,
    val ticketStatus: com.contenido.domain.ticket.entity.TicketStatus,
    val paymentAttemptId: Long,
    val provider: PaymentProvider,
    val amount: Long,
    val refundedAt: String,
    val providerPaymentKey: String?,
    val refundReason: String,
)
