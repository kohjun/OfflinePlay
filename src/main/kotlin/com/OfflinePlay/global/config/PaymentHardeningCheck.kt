package com.contenido.global.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 결제 운영 hardening — 부팅 시점에 misconfigured 결제 설정을 잡아 fail-fast 한다.
 *
 * 차단 규칙 (둘 중 하나라도 해당되면 부팅 실패):
 *
 *  1. `payment.toss.enabled=true` 인데 `secretKey` 가 비어 있음
 *     → [com.contenido.domain.payment.gateway.TossPaymentGateway] 가 Authorization 헤더를
 *       만들 수 없어 모든 confirm 호출이 PG 에서 거절된다.
 *
 *  2. `payment.toss.webhook-signature-required=true` 인데 `secretKey` 가 비어 있음
 *     → [com.contenido.domain.payment.webhook.PaymentWebhookSignatureVerifier] 가
 *       모든 webhook 을 [VerificationResult.Misconfigured] → HTTP 500 로 반환해 PG 가
 *       webhook 재시도를 멈추지 않게 된다.
 *
 * 의도: 운영 배포 직후 결제 자체가 죽어있는 채로 트래픽을 받는 상황을 막는다. 로컬/CI 는
 * 두 플래그가 모두 false 가 디폴트라 영향 없음. 통합 테스트(`secretKey` 세팅된 컨텍스트)도
 * 통과.
 *
 * 운영 가이드: docs/payment-refund-policy.md §11.
 */
@Component
class PaymentHardeningCheck(
    private val tossProperties: TossPaymentProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationStartedEvent::class)
    fun verify() {
        val secretBlank = tossProperties.secretKey.isBlank()
        val violations = mutableListOf<String>()

        if (tossProperties.enabled && secretBlank) {
            violations += "payment.toss.enabled=true 인데 secretKey 가 비어 있습니다 — " +
                "TossPaymentGateway 가 PG 호출 시 Authorization 헤더를 만들 수 없습니다."
        }
        if (tossProperties.webhookSignatureRequired && secretBlank) {
            violations += "payment.toss.webhook-signature-required=true 인데 secretKey 가 " +
                "비어 있습니다 — webhook 검증이 항상 500 으로 실패합니다."
        }

        if (violations.isEmpty()) {
            log.info(
                "[PaymentHardeningCheck] OK (enabled={}, webhookSignatureRequired={}, secretKey={})",
                tossProperties.enabled,
                tossProperties.webhookSignatureRequired,
                if (secretBlank) "<blank>" else "<set>",
            )
            return
        }

        val message = buildString {
            appendLine("결제 설정이 잘못되어 부팅을 중단합니다 (docs/payment-refund-policy.md §11):")
            violations.forEach { appendLine("  - $it") }
            appendLine("필요한 env var: TOSS_SECRET_KEY (그리고 TOSS_PAYMENTS_ENABLED, TOSS_WEBHOOK_SIGNATURE_REQUIRED).")
        }
        throw IllegalStateException(message)
    }
}
