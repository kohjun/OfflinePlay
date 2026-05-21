package com.contenido.domain.event.controller

import com.contenido.domain.event.dto.CreateEventAnnouncementRequest
import com.contenido.domain.event.dto.EventAnnouncementResponse
import com.contenido.domain.event.service.EventAnnouncementService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/**
 * PR141 — 이벤트 공지 API.
 *
 *  - POST: owner/STAFF/ADMIN
 *  - GET : 위 + APPROVED 참가자
 *
 * 비공개(인증 필수) 정책은 Security 의 `anyRequest().authenticated()` 가 담당하고, 더 세밀한
 * 권한은 Service 가 [UnauthorizedException] 으로 막는다.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/announcements")
class EventAnnouncementController(
    private val announcementService: EventAnnouncementService,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @Valid @RequestBody request: CreateEventAnnouncementRequest,
    ): ApiResponse<EventAnnouncementResponse> {
        return ApiResponse.created(
            announcementService.create(userId, eventId, request),
            "이벤트 공지를 등록했습니다.",
        )
    }

    @GetMapping
    fun list(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<List<EventAnnouncementResponse>> {
        return ApiResponse.ok(announcementService.list(userId, eventId))
    }
}
