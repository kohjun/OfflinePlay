package com.contenido.domain.notification.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

enum class NotificationType {
    NEW_EVENT, NEW_POST, NEW_COMMENT, NEW_LIKE
}

@Entity
@Table(name = "notifications")
@EntityListeners(AuditingEntityListener::class)
class Notification(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    val receiver: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: NotificationType,

    @Column(nullable = false)
    val title: String,

    @Column(nullable = false)
    val message: String,

    @Column(name = "target_type", nullable = false, length = 20)
    val targetType: String,

    @Column(name = "target_id", nullable = false)
    val targetId: Long,

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    fun markAsRead() {
        isRead = true
    }
}
