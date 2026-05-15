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
