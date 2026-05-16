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
 * 검증 규약 (Toss Payments docs 기준):
 *  - 헤더: `Toss-Signature` ([SIGNATURE_HEADER])
 *  - 값  : `hex(hmac_sha256(secretKey, rawBody))` — 소문자 hex string, prefix 없음
 *  - 비교: [MessageDigest.isEqual] 로 timing-safe (평문 `==` 은 사이드채널 누출 위험)
 *
 * Toss 가 header 이름/포맷을 바꾸는 경우 [SIGNATURE_HEADER] 와 [computeHmacHex] 만 갱신.
 *
 * 검증 결과는 [VerificationResult] 로 분류된다 — 컨트롤러가 이 결과를 받아 401/500 등으로 매핑한다.
 *
 *  - [VerificationResult.Bypassed]      : `webhook-signature-required=false` (local/dev/CI). 통과.
 *  - [VerificationResult.Valid]         : 헤더가 있고 secret 기반 HMAC 과 일치. 통과.
 *  - [VerificationResult.Missing]       : required=true 인데 헤더 부재 → 401.
 *  - [VerificationResult.Invalid]       : required=true + 헤더 있지만 HMAC 불일치 → 401.
 *  - [VerificationResult.Misconfigured] : required=true 인데 secretKey 가 비어 있음 → 500.
 *
 * required=true 인데 secretKey 가 비어 있는 운영 misconfig 는 부팅 시 [PaymentHardeningCheck]
 * 가 fail-fast 로 잡아 정상 운영에서는 [VerificationResult.Misconfigured] 가 발생하지 않는다.
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
