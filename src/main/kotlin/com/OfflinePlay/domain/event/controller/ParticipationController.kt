package com.contenido.domain.event.controller

import com.contenido.domain.event.dto.ParticipationApplicantResponse
import com.contenido.domain.event.dto.ParticipationResponse
import com.contenido.domain.event.dto.RejectParticipationRequest
import com.contenido.domain.event.service.EventService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/**
 * 이벤트 참가 신청 / 승인 / 거절 워크플로 진입점.
 *
 * 채널 prefix 없이 eventId 만으로 다룬다. 채널 owner 검증은 서비스에서 수행한다.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/participations")
class ParticipationController(
    private val eventService: EventService,
) {

    /** POST — 참가 신청. PENDING 상태로 저장된다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    fun apply(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<ParticipationResponse> {
        val result = eventService.applyForEvent(userId, eventId)
        return ApiResponse.created(result, "참가 신청이 접수되었습니다.")
    }

    /** GET /me — 내 참가 상태 조회. 신청 이력 없으면 data: null. */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    fun getMine(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<ParticipationResponse?> {
        return ApiResponse.ok(eventService.getMyParticipation(userId, eventId))
    }

    /** PATCH /me/cancel — 본인 PENDING 신청 취소. */
    @PatchMapping("/me/cancel")
    @PreAuthorize("isAuthenticated()")
    fun cancelMine(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<ParticipationResponse> {
        return ApiResponse.ok(eventService.cancelMyApplication(userId, eventId), "참가 신청이 취소되었습니다.")
    }

    /** GET — 기획자/관리자 신청자 목록 조회. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<List<ParticipationApplicantResponse>> {
        return ApiResponse.ok(eventService.listApplicants(userId, eventId))
    }

    /** PATCH /{participationId}/approve — 기획자 승인. */
    @PatchMapping("/{participationId}/approve")
    @PreAuthorize("isAuthenticated()")
    fun approve(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @PathVariable participationId: Long,
    ): ApiResponse<ParticipationResponse> {
        return ApiResponse.ok(
            eventService.approveParticipation(userId, eventId, participationId),
            "참가 신청을 승인했습니다.",
        )
    }

    /** PATCH /{participationId}/reject — 기획자 거절. body 의 reason 은 optional. */
    @PatchMapping("/{participationId}/reject")
    @PreAuthorize("isAuthenticated()")
    fun reject(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @PathVariable participationId: Long,
        @Valid @RequestBody(required = false) request: RejectParticipationRequest?,
    ): ApiResponse<ParticipationResponse> {
        return ApiResponse.ok(
            eventService.rejectParticipation(userId, eventId, participationId, request?.reason),
            "참가 신청을 거절했습니다.",
        )
    }
}
