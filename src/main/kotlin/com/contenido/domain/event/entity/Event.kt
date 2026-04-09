package com.contenido.domain.event.entity

import com.contenido.domain.channel.entity.Channel
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

enum class EventStatus {
    UPCOMING, ONGOING, CLOSED
}

@Entity
@Table(name = "events")
@EntityListeners(AuditingEntityListener::class)
class Event(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    val channel: Channel,

    @Column(nullable = false)
    var title: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var description: String,

    @Column(name = "thumbnail_url")
    var thumbnailUrl: String? = null,

    @Column(name = "start_at", nullable = false)
    val startAt: LocalDateTime,

    @Column(name = "end_at", nullable = false)
    val endAt: LocalDateTime,

    @Column(name = "max_participants")
    val maxParticipants: Int? = null,

    @Column(name = "current_participants", nullable = false)
    var currentParticipants: Int = 0,

    @Column(name = "like_count", nullable = false)
    var likeCount: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EventStatus = EventStatus.UPCOMING,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Version
    val version: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set

    fun increaseParticipant() {
        currentParticipants++
    }

    fun isFull(): Boolean = maxParticipants != null && currentParticipants >= maxParticipants
}
