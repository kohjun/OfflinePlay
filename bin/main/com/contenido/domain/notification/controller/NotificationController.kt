package com.contenido.domain.notification.controller

import com.contenido.domain.notification.dto.NotificationResponse
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.notification.service.SseEmitterService
import com.contenido.global.response.ApiResponse
import org.springframework.data.domain.Page
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(
    private val notificationService: NotificationService,
    private val sseEmitterService: SseEmitterService,
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
    ): ApiResponse<Page<NotificationResponse>> {
        return ApiResponse.ok(notificationService.getNotifications(userId, page, size))
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
}
