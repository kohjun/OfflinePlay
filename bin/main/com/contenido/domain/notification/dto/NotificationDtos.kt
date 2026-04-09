package com.contenido.domain.notification.dto

import com.contenido.domain.notification.entity.NotificationType
import java.time.LocalDateTime

data class NotificationResponse(
    val id: Long,
    val type: NotificationType,
    val title: String,
    val message: String,
    val targetType: String,
    val targetId: Long,
    val isRead: Boolean,
    val createdAt: LocalDateTime,
)
