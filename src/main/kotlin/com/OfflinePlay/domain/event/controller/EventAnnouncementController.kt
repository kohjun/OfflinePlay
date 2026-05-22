package com.contenido.domain.event.controller

import com.contenido.domain.event.dto.CreateEventAnnouncementRequest
import com.contenido.domain.event.dto.EventAnnouncementResponse
import com.contenido.domain.event.dto.UnreadAnnouncementCountResponse
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

    /**
     * PR151 — pin 토글. body `{pinned: true|false}`.
     * 같은 이벤트의 기존 pinned 는 자동 해제 (서비스가 처리).
     */
    @PatchMapping("/{announcementId}/pin")
    fun setPinned(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @PathVariable announcementId: Long,
        @RequestBody request: PinAnnouncementRequest,
    ): ApiResponse<EventAnnouncementResponse> {
        return ApiResponse.ok(
            announcementService.setPinned(userId, eventId, announcementId, request.pinned),
            if (request.pinned) "공지를 상단에 고정했어요." else "공지 고정을 해제했어요.",
        )
    }

    /** PR151 — read receipt 멱등 upsert. */
    @PostMapping("/{announcementId}/read")
    fun markAsRead(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @PathVariable announcementId: Long,
    ): ApiResponse<Nothing> {
        announcementService.markAsRead(userId, eventId, announcementId)
        return ApiResponse.ok("읽음 처리됐어요.")
    }

    /** PR151 — 이벤트 unread 공지 카운트. EventRoomSection 의 공지 탭 dot 에 사용. */
    @GetMapping("/unread-count")
    fun unreadCount(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<UnreadAnnouncementCountResponse> {
        return ApiResponse.ok(
            UnreadAnnouncementCountResponse(
                eventId = eventId,
                unreadCount = announcementService.unreadCount(userId, eventId),
            ),
        )
    }
}

/** PR151 — pin 토글 body. */
data class PinAnnouncementRequest(val pinned: Boolean)
