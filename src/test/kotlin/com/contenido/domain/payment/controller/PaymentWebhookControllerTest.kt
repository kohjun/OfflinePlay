package com.contenido.domain.payment.controller

import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.payment.dto.PaymentWebhookRequest
import com.contenido.domain.payment.service.PaymentService
import com.contenido.domain.payment.webhook.PaymentWebhookSignatureVerifier
import com.contenido.domain.search.service.SearchSyncService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook signature 검증 통합 테스트.
 *
 * `webhook-signature-required=true` + 고정 secret 으로 컨텍스트를 띄워, controller 가
 * raw body 와 Toss-Signature 헤더를 받아 verifier 를 통과시킨 뒤에만
 * [PaymentService.handleWebhook] 으로 진입하는지 검증한다.
 *
 *  - PaymentService 는 @MockkBean — webhook 진입 여부만 확인하면 충분.
 *  - SearchSyncService / NotificationService 는 @MockkBean(relaxed=true) — 컨텍스트 빈 의존성
 *    경량화. payment 흐름은 사용하지 않지만 다른 빈들이 import 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "payment.toss.webhook-signature-required=true",
        // 아래 secret 은 PaymentWebhookControllerTest.SECRET 과 동일 값을 유지해야 한다 —
        // 어노테이션 인자는 컴파일 타임 상수만 허용하므로 상수 참조가 아닌 리터럴.
        "payment.toss.secret-key=test-secret-key-do-not-use-in-prod",
    ],
)
class PaymentWebhookControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean lateinit var paymentService: PaymentService
    @MockkBean(relaxed = true) lateinit var searchSyncService: SearchSyncService
    @MockkBean(relaxed = true) lateinit var notificationService: NotificationService

    private val body = """{"idempotencyKey":"abc-123","amount":30000,"status":"PAID","provider":"TOSS"}"""

    @Test
    fun `valid signature 면 200 + PaymentService 진입`() {
        every { paymentService.handleWebhook(any<PaymentWebhookRequest>()) } just Runs
        val validHex = hmacSha256Hex(SECRET, body)

        mockMvc.perform(
            post("/api/v1/payments/webhook")
                .header(PaymentWebhookSignatureVerifier.SIGNATURE_HEADER, validHex)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        verify(exactly = 1) { paymentService.handleWebhook(any<PaymentWebhookRequest>()) }
    }

    @Test
    fun `signature 헤더 부재 면 401 + service 호출 X`() {
        mockMvc.perform(
            post("/api/v1/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isUnauthorized)

        verify(exactly = 0) { paymentService.handleWebhook(any<PaymentWebhookRequest>()) }
    }

    @Test
    fun `잘못된 signature 면 401 + service 호출 X`() {
        mockMvc.perform(
            post("/api/v1/payments/webhook")
                .header(PaymentWebhookSignatureVerifier.SIGNATURE_HEADER, "0".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isUnauthorized)

        verify(exactly = 0) { paymentService.handleWebhook(any<PaymentWebhookRequest>()) }
    }

    @Test
    fun `body 변조 시 같은 signature 로는 401 (HMAC 민감도 회귀)`() {
        val originalHex = hmacSha256Hex(SECRET, body)
        val tampered = body.replace("\"PAID\"", "\"FAIL\"")

        mockMvc.perform(
            post("/api/v1/payments/webhook")
                .header(PaymentWebhookSignatureVerifier.SIGNATURE_HEADER, originalHex)
                .contentType(MediaType.APPLICATION_JSON)
                .content(tampered),
        ).andExpect(status().isUnauthorized)

        verify(exactly = 0) { paymentService.handleWebhook(any<PaymentWebhookRequest>()) }
    }

    @Test
    fun `signature 통과해도 JSON 깨졌으면 400 + service 호출 X`() {
        val malformed = """{"idempotencyKey":"abc","amount":30000,"status":"NOT_AN_ENUM"}"""
        val validHex = hmacSha256Hex(SECRET, malformed)

        mockMvc.perform(
            post("/api/v1/payments/webhook")
                .header(PaymentWebhookSignatureVerifier.SIGNATURE_HEADER, validHex)
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformed),
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { paymentService.handleWebhook(any<PaymentWebhookRequest>()) }
    }

    private fun hmacSha256Hex(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SECRET = "test-secret-key-do-not-use-in-prod"
    }
}
