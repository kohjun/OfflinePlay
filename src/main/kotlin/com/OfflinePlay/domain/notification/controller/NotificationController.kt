package com.contenido.domain.notification.controller

import com.contenido.domain.notification.dto.NotificationPreferenceResponse
import com.contenido.domain.notification.dto.NotificationResponse
import com.contenido.domain.notification.dto.UpdateNotificationPreferencesRequest
import com.contenido.domain.notification.service.NotificationPreferenceService
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.notification.service.SseEmitterService
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(
    private val notificationService: NotificationService,
    private val sseEmitterService: SseEmitterService,
    private val notificationPreferenceService: NotificationPreferenceService,
) {

    @GetMapping("/connect", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun connect(
        @AuthenticationPrincipal userId: Long,
    ): SseEmitter {
        return sseEmitterService.connect(userId)
    }

    @GetMapping
    fun getNotifications(
        @AuthenticationPrincipal userId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<NotificationResponse>> {
        return ApiResponse.ok(PageResponse.of(notificationService.getNotifications(userId, page, size)))
    }

    @GetMapping("/unread-count")
    fun getUnreadCount(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<Long> {
        return ApiResponse.ok(notificationService.getUnreadCount(userId))
    }

    @PatchMapping("/{id}/read")
    fun markAsRead(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ): ApiResponse<Nothing> {
        notificationService.markAsRead(userId, id)
        return ApiResponse.ok("알림을 읽음 처리했습니다.")
    }

    @PatchMapping("/read-all")
    fun markAllAsRead(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<Nothing> {
        notificationService.markAllAsRead(userId)
        return ApiResponse.ok("모든 알림을 읽음 처리했습니다.")
    }

    /**
     * PR95 — 사용자별 알림 수신 선호 조회. 모든 NotificationType 을 반환하며 row 가 없는 type 은
     * enabled=true 로 채워서 응답한다.
     */
    @GetMapping("/preferences")
    fun getPreferences(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<List<NotificationPreferenceResponse>> {
        return ApiResponse.ok(notificationPreferenceService.getMyPreferences(userId))
    }

    /**
     * PR95 — 알림 수신 선호 부분 갱신. request 에 없는 type 은 기존 값 유지. 같은 type 중복은 마지막
     * 값 채택. 응답은 갱신 후의 전체 preference 목록.
     */
    @PatchMapping("/preferences")
    fun updatePreferences(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: UpdateNotificationPreferencesRequest,
    ): ApiResponse<List<NotificationPreferenceResponse>> {
        return ApiResponse.ok(
            notificationPreferenceService.updateMyPreferences(userId, request),
            "알림 수신 설정을 저장했어요.",
        )
    }
}
