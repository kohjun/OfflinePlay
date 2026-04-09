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
     * 알림 생성 + SSE 즉시 전송.
     * 부모 트랜잭션에 참여하지 않도록 REQUIRES_NEW 사용 — 이벤트/게시물 저장 실패와 무관하게 커밋되지 않으며,
     * 반대로 알림 저장 실패가 부모 트랜잭션을 롤백하지 않도록 호출부에서 예외를 흡수해야 한다.
     */
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
