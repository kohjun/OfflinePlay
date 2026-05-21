package com.contenido.domain.notification.service

import com.contenido.domain.notification.dto.NotificationResponse
import com.contenido.domain.notification.entity.Notification
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.repository.NotificationRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.NotificationNotFoundException
import com.contenido.global.exception.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val sseEmitterService: SseEmitterService,
    private val notificationPreferenceService: NotificationPreferenceService,
    private val pushNotificationService: PushNotificationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 알림 생성 + SSE 즉시 전송. @Async로 별도 스레드에서 실행되어 호출자 트랜잭션과 독립적으로 처리된다.
     *
     * PR95 — receiver 의 [NotificationPreferenceService.isEnabled] 가 false 인 경우 row 저장 +
     * SSE 발송 모두 skip. preference 조회 자체가 실패하면 fail-open(true) 으로 기존 흐름 유지.
     */
    @Async
    @Transactional
    fun notify(
        receiverIds: List<Long>,
        type: NotificationType,
        title: String,
        message: String,
        targetType: String,
        targetId: Long,
    ) {
        if (receiverIds.isEmpty()) return

        val allowedIds = receiverIds.filter { notificationPreferenceService.isEnabled(it, type) }
        if (allowedIds.isEmpty()) return

        val receivers = userRepository.findAllById(allowedIds)

        val notifications = notificationRepository.saveAll(
            receivers.map { receiver ->
                Notification(
                    receiver = receiver,
                    type = type,
                    title = title,
                    message = message,
                    targetType = targetType,
                    targetId = targetId,
                )
            }
        )

        // DB 저장 후 SSE 전송 (저장된 ID 포함)
        notifications.forEach { notification ->
            sseEmitterService.sendToUser(notification.receiver.id, notification.toResponse())
        }

        // PR140 — Web Push 발송 (best-effort). preference 가 통과한 receiver 만 도달하므로
        // 별도 필터링이 필요하지 않다. 발송 실패는 row/SSE 트랜잭션을 깨지 않도록 try-catch.
        runCatching {
            pushNotificationService.dispatch(notifications)
        }.onFailure { e ->
            log.warn("[notify] push dispatch failed type={} count={} err={}", type, notifications.size, e.message)
        }
    }

    fun getNotifications(userId: Long, page: Int, size: Int): Page<NotificationResponse> {
        val receiver = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        return notificationRepository
            .findByReceiverOrderByCreatedAtDesc(receiver, PageRequest.of(page, size))
            .map { it.toResponse() }
    }

    @Transactional
    fun markAsRead(userId: Long, notificationId: Long) {
        val notification = notificationRepository.findById(notificationId)
            .orElseThrow { NotificationNotFoundException() }
        if (notification.receiver.id == userId) {
            notification.markAsRead()
        }
    }

    @Transactional
    fun markAllAsRead(userId: Long) {
        val receiver = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        notificationRepository.markAllAsReadByReceiver(receiver)
    }

    fun getUnreadCount(userId: Long): Long {
        val receiver = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        return notificationRepository.countByReceiverAndIsReadFalse(receiver)
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun Notification.toResponse() = NotificationResponse(
        id = id,
        type = type,
        title = title,
        message = message,
        targetType = targetType,
        targetId = targetId,
        isRead = isRead,
        createdAt = createdAt,
    )
}
