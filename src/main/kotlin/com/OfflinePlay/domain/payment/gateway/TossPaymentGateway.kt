package com.contenido.domain.payment.gateway

import com.contenido.domain.payment.entity.PaymentProvider
import com.contenido.global.config.TossPaymentProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.util.Base64

/**
 * Toss Payments confirm API 어댑터 (sandbox + production).
 *
 *  - Endpoint: `POST {api-base-url}/v1/payments/confirm`
 *  - Auth    : `Authorization: Basic Base64(<secretKey>:)` — 콜론 뒤는 비움 (Toss 규약).
 *  - Body    : `{ "paymentKey": ..., "orderId": ..., "amount": ... }`
 *
 * 실패 분류:
 *  - 4xx : PG 가 거절 (잘못된 카드, 잔액 부족, 금액 불일치 등). [PaymentGatewayConfirmResult.Failure]
 *          로 응답하고 PaymentService 가 PaymentAttempt 를 FAILED 로 전환한다.
 *  - 5xx 또는 IO 오류 : 외부 의존성 실패 — 동일하게 Failure 로 응답해 사용자에게 재시도 가능 토스트를
 *          띄운다. 자동 재시도는 후속 PR 에서 Retry/Outbox 와 함께 도입.
 *
 * 본 구현은 sandbox 키가 없는 환경에선 [com.contenido.global.config.PaymentConfig] 가 빈을 만들지
 * 않으므로 [MockPaymentGateway] 로 대체된다.
 */
class TossPaymentGateway(
    private val properties: TossPaymentProperties,
    private val restClient: RestClient,
) : PaymentGateway {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun provider(): PaymentProvider = PaymentProvider.TOSS

    override fun confirm(request: PaymentGatewayConfirmRequest): PaymentGatewayConfirmResult {
        val auth = "Basic " + Base64.getEncoder()
            .encodeToString("${properties.secretKey}:".toByteArray())

        return try {
            val body = mapOf(
                "paymentKey" to request.paymentKey,
                "orderId" to request.orderId,
                "amount" to request.amount,
            )
            val response: Map<String, Any?>? = restClient.post()
                .uri("${properties.apiBaseUrl}/v1/payments/confirm")
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(Map::class.java) as Map<String, Any?>?

            val providerPaymentKey = response?.get("paymentKey") as? String ?: request.paymentKey
            val approvedAt = response?.get("approvedAt") as? String

            PaymentGatewayConfirmResult.Success(
                provider = PaymentProvider.TOSS,
                providerPaymentKey = providerPaymentKey,
                approvedAt = approvedAt,
            )
        } catch (e: RestClientResponseException) {
            val errorBody = runCatching { e.responseBodyAsString }.getOrNull()
            log.warn(
                "[TossPaymentGateway] confirm rejected status={} body={}",
                e.statusCode, errorBody,
            )
            val (code, message) = parseTossError(e.statusCode, errorBody)
            PaymentGatewayConfirmResult.Failure(
                provider = PaymentProvider.TOSS,
                code = code,
                message = message,
            )
        } catch (e: Exception) {
            log.error("[TossPaymentGateway] confirm IO error", e)
            PaymentGatewayConfirmResult.Failure(
                provider = PaymentProvider.TOSS,
                code = "PG_IO_ERROR",
                message = e.message ?: "결제 게이트웨이 통신 오류",
            )
        }
    }

    private fun parseTossError(status: HttpStatusCode, body: String?): Pair<String, String> {
        // Toss error body 형식: { "code": "...", "message": "..." }
        if (body.isNullOrBlank()) return "PG_HTTP_${status.value()}" to "PG 가 결제를 거절했습니다."
        val codeMatch = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)
        val msgMatch = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)
        val code = codeMatch?.groupValues?.get(1) ?: "PG_HTTP_${status.value()}"
        val message = msgMatch?.groupValues?.get(1) ?: "PG 가 결제를 거절했습니다."
        return code to message
    }
}
