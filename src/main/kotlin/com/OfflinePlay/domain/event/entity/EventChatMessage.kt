package com.contenido.domain.event.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * PR160 — 이벤트룸 채팅 메시지 한 건.
 *
 *  - text only (MVP). 이미지/파일/답글은 후속 PR.
 *  - is_announcement = TRUE → 운영자 push 발송 트리거 (NotificationService.notify EVENT_ANNOUNCEMENT).
 *  - soft delete 만 — 본인 메시지 삭제는 후속.
 */
@Entity
@Table(
    name = "event_chat_messages",
    indexes = [
        Index(name = "idx_event_chat_messages_event_created", columnList = "event_id, created_at"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class EventChatMessage(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: Event,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    val sender: User,

    @Column(nullable = false, length = 500)
    var content: String,

    @Column(name = "is_announcement", nullable = false)
    val isAnnouncement: Boolean = false,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
        protected set

    val isDeleted: Boolean
        get() = deletedAt != null

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }
}
