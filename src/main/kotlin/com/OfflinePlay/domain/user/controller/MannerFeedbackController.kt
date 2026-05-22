package com.contenido.domain.user.controller

import com.contenido.domain.user.dto.CreateMannerFeedbackRequest
import com.contenido.domain.user.service.MannerFeedbackService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/**
 * PR146 — 이벤트 종료 후 host ↔ 참가자 매너 평가 작성.
 *
 * 조회는 `/users/{userId}/manner-summary` (UserController 에 위치) 에서. 본 컨트롤러는 작성만.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/manner-feedbacks")
class MannerFeedbackController(
    private val mannerFeedbackService: MannerFeedbackService,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @Valid @RequestBody request: CreateMannerFeedbackRequest,
    ): ApiResponse<Long> {
        val saved = mannerFeedbackService.create(userId, eventId, request)
        return ApiResponse.created(saved.id, "매너 평가를 남겼어요.")
    }
}
