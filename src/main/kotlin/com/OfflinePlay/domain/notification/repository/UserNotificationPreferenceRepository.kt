package com.contenido.domain.notification.repository

import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.entity.UserNotificationPreference
import com.contenido.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository

/**
 * PR95 — UserNotificationPreference repository.
 * row 가 없는 type 은 enabled = true 로 간주하는 fallback 정책이라 derived query 만으로 충분.
 */
interface UserNotificationPreferenceRepository : JpaRepository<UserNotificationPreference, Long> {

    fun findByUser(user: User): List<UserNotificationPreference>

    fun findByUserId(userId: Long): List<UserNotificationPreference>

    fun findByUserAndNotificationType(
        user: User,
        notificationType: NotificationType,
    ): UserNotificationPreference?

    fun findByUserIdAndNotificationType(
        userId: Long,
        notificationType: NotificationType,
    ): UserNotificationPreference?
}
