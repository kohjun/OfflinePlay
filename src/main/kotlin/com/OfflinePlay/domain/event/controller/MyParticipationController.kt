package com.contenido.domain.event.controller

import com.contenido.domain.event.dto.MyParticipationItemResponse
import com.contenido.domain.event.service.EventService
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/**
 * MY 화면 "내 신청/티켓" 진입점.
 *
 * 특정 이벤트 컨텍스트가 아니라 사용자 전체 신청 이력을 다루므로, 이벤트-스코프인
 * [ParticipationController] 와 별도로 둔다.
 */
@RestController
@RequestMapping("/api/v1/participations")
class MyParticipationController(
    private val eventService: EventService,
) {

    /**
     * GET /api/v1/participations/me — 본인 신청/티켓 페이지 목록.
     *
     * 페이지 사이즈는 기본 20. MY 화면 placeholder 카드와 1:1 매핑.
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    fun getMine(
        @AuthenticationPrincipal userId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<MyParticipationItemResponse>> {
        return ApiResponse.ok(PageResponse.of(eventService.getMyParticipations(userId, page, size)))
    }
}
