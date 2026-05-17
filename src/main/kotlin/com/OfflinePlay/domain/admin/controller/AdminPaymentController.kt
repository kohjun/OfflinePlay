package com.contenido.domain.admin.controller

import com.contenido.domain.admin.service.AdminPaymentService
import com.contenido.domain.payment.dto.AdminForcedRefundRequest
import com.contenido.domain.payment.dto.AdminForcedRefundResponse
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * PR106 — ADMIN 전용 결제/환불 운영 도구 엔드포인트.
 *
 * 현재 endpoint 1종:
 *  - `POST /api/v1/admin/tickets/{ticketId}/forced-refund` — USED 또는 시작 후 PAID 티켓을 전액
 *    강제 환불. 일반 환불 흐름(`PaymentController.refundTicket`) 의 deadline / USED 가드를
 *    우회한다. 권한 / 상태 가드는 service 가 담당.
 *
 * 분리 이유: 기존 AdminController 군이 운영 책임별로 쪼개져 있어 (report / appeal / moderation /
 * audit), 결제 운영 도구도 동일한 결로 별도 컨트롤러로 둔다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminPaymentController(
    private val adminPaymentService: AdminPaymentService,
) {

    @PostMapping("/tickets/{ticketId}/forced-refund")
    fun forcedRefundTicket(
        @AuthenticationPrincipal adminUserId: Long,
        @PathVariable ticketId: Long,
        @Valid @RequestBody request: AdminForcedRefundRequest,
    ): ApiResponse<AdminForcedRefundResponse> =
        ApiResponse.ok(
            adminPaymentService.forceRefund(adminUserId, ticketId, request),
            "강제 환불을 처리했어요.",
        )
}
