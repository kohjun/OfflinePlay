package com.contenido.domain.payment.controller

import com.contenido.domain.payment.dto.PaymentConfirmRequest
import com.contenido.domain.payment.dto.PaymentConfirmResponse
import com.contenido.domain.payment.dto.PaymentPrepareResponse
import com.contenido.domain.payment.dto.PaymentWebhookRequest
import com.contenido.domain.payment.service.PaymentService
import com.contenido.global.response.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class PaymentController(
    private val paymentService: PaymentService,
) {

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
     * PG webhook. provider 별 signature/HMAC 검증은 후속 PR 에서 진입 전 필터로 추가.
     * 현재는 idempotencyKey 기반 매핑 + 멱등 처리만 수행한다.
     */
    @PostMapping("/payments/webhook")
    fun handleWebhook(
        @RequestBody request: PaymentWebhookRequest,
    ): ApiResponse<Nothing> {
        paymentService.handleWebhook(request)
        return ApiResponse.ok("결제 webhook 처리 완료")
    }
}
