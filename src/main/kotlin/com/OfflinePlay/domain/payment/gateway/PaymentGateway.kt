package com.contenido.domain.payment.gateway

import com.contenido.domain.payment.entity.PaymentProvider

/**
 * 결제 PG 어댑터. PaymentService 는 이 인터페이스만 사용해 PG 와 통신한다.
 *
 *  - 실제 PG (Toss/PortOne) 는 [TossPaymentGateway] 같은 구현체로 분리.
 *  - sandbox 키가 아직 없거나 로컬 개발 환경에선 [MockPaymentGateway] 가 빈으로 주입되어
 *    confirm 호출이 항상 Success 로 통과한다.
 *
 * 어떤 구현체가 활성화되는지는 `payment.toss.enabled` 등 application.yml properties 가 결정한다
 * ([com.contenido.global.config.PaymentConfig]).
 */
interface PaymentGateway {

    fun provider(): PaymentProvider

    fun confirm(request: PaymentGatewayConfirmRequest): PaymentGatewayConfirmResult

    fun refund(request: PaymentGatewayRefundRequest): PaymentGatewayRefundResult
}

/**
 * PG confirm 호출에 필요한 최소 정보.
 *
 *  - [orderId]    : PaymentAttempt.idempotencyKey 와 동일. PG 는 이 값으로 prepare/confirm 매칭.
 *  - [paymentKey] : PG 가 발급한 결제 키. 클라이언트가 PG SDK 콜백으로 받은 값을 그대로 전달.
 *  - [amount]     : 결제 금액. PG 와 서버 양쪽이 모두 검증한다 (서버는 PaymentAttempt.amount 와 비교).
 */
data class PaymentGatewayConfirmRequest(
    val orderId: String,
    val paymentKey: String,
    val amount: Long,
)

/**
 * PG confirm 결과.
 *
 *  - [Success] : PG 측 결제 승인이 떨어진 상태. providerPaymentKey 는 환불 호출에 다시 쓰인다.
 *  - [Failure] : PG 가 거절했거나 통신 오류. PaymentService 는 PaymentAttempt 를 FAILED 로 전환하고
 *                예외를 던져 사용자에게 알린다.
 */
sealed class PaymentGatewayConfirmResult {
    data class Success(
        val provider: PaymentProvider,
        val providerPaymentKey: String,
        val approvedAt: String? = null,
    ) : PaymentGatewayConfirmResult()

    data class Failure(
        val provider: PaymentProvider,
        val code: String,
        val message: String,
    ) : PaymentGatewayConfirmResult()
}

/**
 * PG refund 호출에 필요한 정보.
 *
 *  - [providerPaymentKey] : PaymentAttempt.providerPaymentKey 가 보관해 둔 PG 측 결제 키.
 *                            confirm 시점에 채워졌으므로 refund 단계에서 그대로 사용한다.
 *  - [amount]             : 환불 금액. 본 PR42 단계는 전액 환불만 — Ticket.price 와 동일.
 *  - [reason]             : 운영 로그용 사유. PG 별로 길이 제한이 있을 수 있어 service 단에서 trim.
 */
data class PaymentGatewayRefundRequest(
    val providerPaymentKey: String,
    val amount: Long,
    val reason: String,
)

/**
 * PG refund 결과.
 *
 *  - [Success] : PG 가 환불 승인. providerPaymentKey 는 동일 값을 echo 하거나 별도 환불 키를
 *                돌려준다. canceledAt 은 PG 가 알려주는 환불 처리 시각.
 *  - [Failure] : PG 가 거절. PaymentService 는 PaymentAttempt 를 PAID 유지하고
 *                [com.contenido.global.exception.RefundFailedException] 을 던진다.
 */
sealed class PaymentGatewayRefundResult {
    data class Success(
        val provider: PaymentProvider,
        val providerPaymentKey: String,
        val canceledAt: String? = null,
    ) : PaymentGatewayRefundResult()

    data class Failure(
        val provider: PaymentProvider,
        val code: String,
        val message: String,
    ) : PaymentGatewayRefundResult()
}
