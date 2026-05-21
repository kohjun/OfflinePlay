package com.contenido.domain.event.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * PR141 — 이벤트 공지 한 건.
 *
 *  - author 는 owner/STAFF/ADMIN 중 한 명 — service 에서 가드.
 *  - title 200자 / content TEXT — 본 PR 은 HTML 가공 없이 plain text 그대로 표시.
 *  - 알림 발송은 본 entity 와는 별도 — service 가 NotificationService.notify 로 호출한다.
 */
@Entity
@Table(
    name = "event_announcements",
    indexes = [
        Index(name = "idx_event_announcements_event", columnList = "event_id"),
        Index(name = "idx_event_announcements_event_created", columnList = "event_id, created_at"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class EventAnnouncement(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: Event,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    val author: User,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set
}
