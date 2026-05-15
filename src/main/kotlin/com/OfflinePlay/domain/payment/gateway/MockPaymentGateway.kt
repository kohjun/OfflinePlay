package com.contenido.domain.payment.gateway

import com.contenido.domain.payment.entity.PaymentProvider
import org.slf4j.LoggerFactory

/**
 * sandbox 키가 없는 로컬/CI 환경에서 사용하는 mock PG.
 *
 * confirm 은 항상 [PaymentGatewayConfirmResult.Success] 로 응답한다. 운영에서는
 * `payment.toss.enabled=true` 가 설정되어 [TossPaymentGateway] 가 대신 빈으로 주입된다.
 *
 * 운영 환경에서 mock 이 활성화되는 일이 없도록 [com.contenido.global.config.PaymentConfig] 가
 * `@ConditionalOnProperty(havingValue=false, matchIfMissing=true)` 로 가드한다.
 */
class MockPaymentGateway : PaymentGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun provider(): PaymentProvider = PaymentProvider.NONE

    override fun confirm(request: PaymentGatewayConfirmRequest): PaymentGatewayConfirmResult {
        log.info(
            "[MockPaymentGateway] confirm orderId={} paymentKey={} amount={} — mock success",
            request.orderId, request.paymentKey, request.amount,
        )
        return PaymentGatewayConfirmResult.Success(
            provider = PaymentProvider.NONE,
            providerPaymentKey = "mock-${request.paymentKey}",
        )
    }
}
