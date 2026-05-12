package com.contenido.domain.ticket.controller

import com.contenido.domain.ticket.dto.CheckInByCodeRequest
import com.contenido.domain.ticket.dto.TicketDetailResponse
import com.contenido.domain.ticket.service.TicketService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 참가자 티켓 패스/QR 화면 API.
 *
 * 인증 필요. buyer 본인 또는 ADMIN 만 접근 가능 — 서비스 레이어에서 검증한다.
 */
@RestController
@RequestMapping("/api/v1/tickets")
class TicketController(
    private val ticketService: TicketService,
) {

    @GetMapping("/{ticketId}")
    @PreAuthorize("isAuthenticated()")
    fun getTicket(
        @AuthenticationPrincipal userId: Long,
        @PathVariable ticketId: Long,
    ): ApiResponse<TicketDetailResponse> =
        ApiResponse.ok(ticketService.getTicketDetail(userId, ticketId))

    /**
     * POST /api/v1/tickets/{ticketId}/check-in
     *
     * 현장 스태프(채널 owner/STAFF) 또는 ADMIN 이 호출. PAID → USED 로 전환된다.
     * buyer 본인은 거절(403), 잘못된 상태는 409.
     */
    @PostMapping("/{ticketId}/check-in")
    @PreAuthorize("isAuthenticated()")
    fun checkIn(
        @AuthenticationPrincipal userId: Long,
        @PathVariable ticketId: Long,
    ): ApiResponse<TicketDetailResponse> =
        ApiResponse.ok(ticketService.checkInTicket(userId, ticketId), "체크인이 완료되었습니다.")

    /**
     * POST /api/v1/tickets/check-in
     *
     * 현장 스태프가 체크인 코드를 받아 체크인 처리. ticketId 직접 URL 진입 대신
     * 이 엔드포인트로 코드를 보낸다 — 코드 형식이 깨지거나 ticketId/eventId mismatch 면 400.
     */
    @PostMapping("/check-in")
    @PreAuthorize("isAuthenticated()")
    fun checkInByCode(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CheckInByCodeRequest,
    ): ApiResponse<TicketDetailResponse> =
        ApiResponse.ok(ticketService.checkInByCode(userId, request.checkInCode), "체크인이 완료되었습니다.")
}
