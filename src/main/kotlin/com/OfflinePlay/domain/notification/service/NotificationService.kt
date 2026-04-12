package com.contenido.domain.notification.service

import com.contenido.domain.notification.dto.NotificationResponse
import com.contenido.domain.notification.entity.Notification
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.repository.NotificationRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.NotificationNotFoundException
import com.contenido.global.exception.UserNotFoundException
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
) {

    /**
     * 알림 생성 + SSE 즉시 전송. @Async로 별도 스레드에서 실행되어 호출자 트랜잭션과 독립적으로 처리된다.
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

        val receivers = userRepository.findAllById(receiverIds)

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
