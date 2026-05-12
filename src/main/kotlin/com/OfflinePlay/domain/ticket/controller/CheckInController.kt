package com.contenido.domain.ticket.controller

import com.contenido.domain.ticket.dto.EventCheckInSummaryResponse
import com.contenido.domain.ticket.service.TicketService
import com.contenido.global.response.ApiResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 이벤트별 체크인 현황 조회. ADMIN/owner/STAFF 만 접근 — 서비스에서 검증한다.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/check-ins")
class CheckInController(
    private val ticketService: TicketService,
) {

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<EventCheckInSummaryResponse> =
        ApiResponse.ok(ticketService.getEventCheckIns(userId, eventId))
}
