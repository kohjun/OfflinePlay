package com.contenido.domain.payment.webhook

import com.contenido.domain.payment.webhook.PaymentWebhookSignatureVerifier.VerificationResult
import com.contenido.global.config.TossPaymentProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PaymentWebhookSignatureVerifierTest {

    private val secret = "test-secret-key-do-not-use-in-prod"
    private val body = """{"idempotencyKey":"abc","amount":30000,"status":"PAID"}"""

    @Test
    fun `required=false 면 Bypassed 반환 (signature 없어도)`() {
        val verifier = PaymentWebhookSignatureVerifier(
            TossPaymentProperties(webhookSignatureRequired = false, secretKey = ""),
        )
        assertThat(verifier.verify(body, signatureHeader = null))
            .isEqualTo(VerificationResult.Bypassed)
    }

    @Test
    fun `required=true + secretKey 가 비어 있으면 Misconfigured`() {
        val verifier = PaymentWebhookSignatureVerifier(
            TossPaymentProperties(webhookSignatureRequired = true, secretKey = ""),
        )
        assertThat(verifier.verify(body, signatureHeader = "anything"))
            .isEqualTo(VerificationResult.Misconfigured)
    }

    @Test
    fun `required=true + secret 있는데 signature 헤더가 없으면 Missing`() {
        val verifier = PaymentWebhookSignatureVerifier(
            TossPaymentProperties(webhookSignatureRequired = true, secretKey = secret),
        )
        assertThat(verifier.verify(body, signatureHeader = null))
            .isEqualTo(VerificationResult.Missing)
        assertThat(verifier.verify(body, signatureHeader = ""))
            .isEqualTo(VerificationResult.Missing)
    }

    @Test
    fun `required=true + 잘못된 signature 면 Invalid`() {
        val verifier = PaymentWebhookSignatureVerifier(
            TossPaymentProperties(webhookSignatureRequired = true, secretKey = secret),
        )
        assertThat(verifier.verify(body, signatureHeader = "deadbeef"))
            .isEqualTo(VerificationResult.Invalid)
    }

    @Test
    fun `required=true + 정상 HMAC hex 면 Valid`() {
        val verifier = PaymentWebhookSignatureVerifier(
            TossPaymentProperties(webhookSignatureRequired = true, secretKey = secret),
        )
        val validHex = hmacSha256Hex(secret, body)
        assertThat(verifier.verify(body, signatureHeader = validHex))
            .isEqualTo(VerificationResult.Valid)
    }

    @Test
    fun `body 가 한 글자만 달라져도 Invalid (HMAC 민감도 회귀)`() {
        val verifier = PaymentWebhookSignatureVerifier(
            TossPaymentProperties(webhookSignatureRequired = true, secretKey = secret),
        )
        val validHex = hmacSha256Hex(secret, body)
        val tampered = body.replace("PAID", "FAIL")
        // 동일 signature 헤더로 변조 body 가 들어오면 거부되어야 한다.
        assertThat(verifier.verify(tampered, signatureHeader = validHex))
            .isEqualTo(VerificationResult.Invalid)
    }

    // HMAC-SHA256 hex — 운영 구현이 사용하는 것과 동일 알고리즘으로 reference 값 생성.
    private fun hmacSha256Hex(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
