package com.contenido.domain.payment.webhook

import com.contenido.global.config.TossPaymentProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Toss webhook 의 `Toss-Signature` 헤더를 HMAC-SHA256 으로 검증한다.
 *
 * 현재 구현: `hex(hmac_sha256(secretKey, rawBody))` 와 단순 비교.
 *
 * **TODO (운영 전 필수)**: Toss 공식 webhook 문서로 header 이름 / 인코딩(hex vs base64) /
 * 추가 prefix(예: `t=...,v1=...`) 형식을 재확인하고 [VERSION_HEX] 와 헤더 이름을 맞춰야 한다.
 * docs/payment-refund-policy.md §10 에도 같은 TODO 가 명시돼 있다.
 *
 * 검증 결과는 [VerificationResult] 로 분류된다 — 컨트롤러가 이 결과를 받아 401/500 등으로 매핑한다.
 *
 *  - [VerificationResult.Bypassed]      : `webhook-signature-required=false` (local/dev/CI). 통과.
 *  - [VerificationResult.Valid]         : 헤더가 있고 secret 기반 HMAC 과 일치. 통과.
 *  - [VerificationResult.Missing]       : required=true 인데 헤더 부재 → 401.
 *  - [VerificationResult.Invalid]       : required=true + 헤더 있지만 HMAC 불일치 → 401.
 *  - [VerificationResult.Misconfigured] : required=true 인데 secretKey 가 비어 있음 → 500.
 *
 * 키 비교는 [MessageDigest.isEqual] 로 timing-safe 비교한다 — 평문 `==` 비교는 사이드채널
 * 누출 위험.
 */
@Component
class PaymentWebhookSignatureVerifier(
    private val properties: TossPaymentProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun verify(rawBody: String, signatureHeader: String?): VerificationResult {
        if (!properties.webhookSignatureRequired) {
            return VerificationResult.Bypassed
        }
        if (properties.secretKey.isBlank()) {
            log.error("[webhook signature] required=true 인데 secretKey 가 비어 있습니다 — 운영 설정 오류")
            return VerificationResult.Misconfigured
        }
        if (signatureHeader.isNullOrBlank()) {
            return VerificationResult.Missing
        }

        val expected = computeHmacHex(rawBody, properties.secretKey)
        val match = MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            signatureHeader.toByteArray(StandardCharsets.UTF_8),
        )
        return if (match) VerificationResult.Valid else VerificationResult.Invalid
    }

    private fun computeHmacHex(rawBody: String, secret: String): String {
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALG))
        val bytes = mac.doFinal(rawBody.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    sealed class VerificationResult {
        data object Bypassed : VerificationResult()
        data object Valid : VerificationResult()
        data object Missing : VerificationResult()
        data object Invalid : VerificationResult()
        data object Misconfigured : VerificationResult()
    }

    companion object {
        private const val HMAC_ALG = "HmacSHA256"

        /**
         * 운영 hardening 단계에서 검증된 Toss webhook signature 헤더 이름.
         * 변경 시 [PaymentWebhookSignatureVerifier] / 컨트롤러 / 테스트 모두 동기 갱신.
         */
        const val SIGNATURE_HEADER = "Toss-Signature"
    }
}
