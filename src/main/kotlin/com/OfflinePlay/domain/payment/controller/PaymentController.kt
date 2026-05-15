package com.contenido.domain.payment.controller

import com.contenido.domain.payment.dto.PaymentConfirmRequest
import com.contenido.domain.payment.dto.PaymentConfirmResponse
import com.contenido.domain.payment.dto.PaymentPrepareResponse
import com.contenido.domain.payment.dto.PaymentWebhookRequest
import com.contenido.domain.payment.dto.RefundTicketRequest
import com.contenido.domain.payment.dto.RefundTicketResponse
import com.contenido.domain.payment.service.PaymentService
import com.contenido.domain.payment.webhook.PaymentWebhookSignatureVerifier
import com.contenido.domain.payment.webhook.PaymentWebhookSignatureVerifier.VerificationResult
import com.contenido.global.exception.InvalidWebhookSignatureException
import com.contenido.global.exception.MalformedWebhookBodyException
import com.contenido.global.exception.WebhookMisconfiguredException
import com.contenido.global.response.ApiResponse
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class PaymentController(
    private val paymentService: PaymentService,
    private val signatureVerifier: PaymentWebhookSignatureVerifier,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 결제 준비. 유료 이벤트 결제 페이지로 진입하기 직전 호출한다.
     * 응답의 idempotencyKey 를 클라이언트가 PG SDK 의 orderId 로 그대로 전달한다.
     */
    @PostMapping("/events/{eventId}/payments/prepare")
    fun preparePayment(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<PaymentPrepareResponse> {
        val response = paymentService.preparePayment(userId, eventId)
        return ApiResponse.ok(response, "결제 정보가 준비되었습니다.")
    }

    /**
     * 클라이언트 confirm. PG SDK 결제창 콜백 → 백엔드가 PG 에 confirm 호출 → 성공 시 티켓 발급.
     *
     * webhook 과 함께 PAID 처리 진입점이 두 개가 되지만 양쪽 모두 멱등(같은 PaymentAttempt 가
     * 이미 PAID 면 no-op).
     */
    @PostMapping("/payments/{paymentAttemptId}/confirm")
    fun confirmPayment(
        @AuthenticationPrincipal userId: Long,
        @PathVariable paymentAttemptId: Long,
        @RequestBody request: PaymentConfirmRequest,
    ): ApiResponse<PaymentConfirmResponse> {
        val response = paymentService.confirmPayment(userId, paymentAttemptId, request)
        return ApiResponse.ok(response, "결제가 완료되었습니다.")
    }

    /**
     * 환불 요청 — buyer 본인 / 채널 owner / ADMIN 만 가능. STAFF 는 환불 권한 없음.
     *
     * 멱등: 이미 REFUNDED 인 티켓은 gateway 재호출 없이 기존 정보로 응답.
     * USED 인 티켓은 거부 (체크인 후 환불은 운영 도구로 별도).
     */
    @PostMapping("/tickets/{ticketId}/refund")
    fun refundTicket(
        @AuthenticationPrincipal userId: Long,
        @PathVariable ticketId: Long,
        @RequestBody(required = false) request: RefundTicketRequest?,
    ): ApiResponse<RefundTicketResponse> {
        val response = paymentService.refundPaymentByTicket(
            actorId = userId,
            ticketId = ticketId,
            request = request ?: RefundTicketRequest(),
        )
        return ApiResponse.ok(response, "환불이 완료되었습니다.")
    }

    /**
     * PG webhook.
     *
     *  - rawBody 를 그대로 받아서 [signatureVerifier] 가 HMAC 검증한 뒤
     *    [ObjectMapper] 로 [PaymentWebhookRequest] 로 파싱한다.
     *  - signature 검증 실패: 401 ([InvalidWebhookSignatureException]).
     *  - signature 설정 오류 (required=true 인데 secret 미설정): 500 ([WebhookMisconfiguredException]).
     *  - 파싱 실패: 400 ([MalformedWebhookBodyException]).
     *  - 정상 진입 후 처리는 [PaymentService.handleWebhook] 의 멱등 로직에 위임.
     *
     * SIGNATURE_HEADER 이름은 [PaymentWebhookSignatureVerifier.SIGNATURE_HEADER] 가 단일 source of truth.
     */
    @PostMapping("/payments/webhook")
    fun handleWebhook(
        @RequestBody rawBody: String,
        @RequestHeader(
            name = PaymentWebhookSignatureVerifier.SIGNATURE_HEADER,
            required = false,
        )
        signatureHeader: String?,
    ): ApiResponse<Nothing> {
        when (signatureVerifier.verify(rawBody, signatureHeader)) {
            VerificationResult.Bypassed, VerificationResult.Valid -> Unit
            VerificationResult.Missing ->
                throw InvalidWebhookSignatureException("결제 webhook 서명 헤더가 없습니다.")
            VerificationResult.Invalid ->
                throw InvalidWebhookSignatureException("결제 webhook 서명이 일치하지 않습니다.")
            VerificationResult.Misconfigured ->
                throw WebhookMisconfiguredException()
        }

        val request = try {
            objectMapper.readValue(rawBody, PaymentWebhookRequest::class.java)
        } catch (e: JsonProcessingException) {
            log.warn("[webhook] malformed body: {}", e.originalMessage)
            throw MalformedWebhookBodyException()
        }

        paymentService.handleWebhook(request)
        return ApiResponse.ok("결제 webhook 처리 완료")
    }
}
