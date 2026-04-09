package com.contenido.domain.notification.repository

import com.contenido.domain.notification.entity.Notification
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface NotificationRepository : JpaRepository<Notification, Long> {

    fun findByReceiverOrderByCreatedAtDesc(receiver: User, pageable: Pageable): Page<Notification>

    fun countByReceiverAndIsReadFalse(receiver: User): Long

    fun findByReceiverAndIsReadFalse(receiver: User): List<Notification>

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.receiver = :receiver AND n.isRead = false")
    fun markAllAsReadByReceiver(receiver: User)
}
