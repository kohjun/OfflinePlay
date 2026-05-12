package com.contenido.domain.notification.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

enum class NotificationType {
    NEW_EVENT, NEW_POST, NEW_COMMENT, NEW_LIKE,
    /** 크리에이터(기획자) 신청 결과 — 어드민이 승인/거절. */
    APPLICATION_APPROVED, APPLICATION_REJECTED,
    /** 이벤트 참가 신청이 접수됨 — 채널 owner(기획자)에게 발송. */
    PARTICIPATION_REQUESTED,
    /** 이벤트 참가 신청 결과 — 채널 owner(기획자)가 승인/거절. */
    PARTICIPATION_APPROVED, PARTICIPATION_REJECTED,
    /** APPROVED 상태에서 참가자가 직접 취소 — 채널 owner(기획자)에게 발송. */
    PARTICIPATION_CANCELED,
    /** 티켓 발급 완료 — 승인된 참가자(buyer)에게 발송. */
    TICKET_ISSUED,
    /** 현장 체크인 완료 — 참가자(buyer)에게 발송. */
    TICKET_CHECKED_IN,
}

@Entity
@Table(name = "notifications")
@EntityListeners(AuditingEntityListener::class)
class Notification(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    val receiver: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
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
