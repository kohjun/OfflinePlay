package com.contenido.domain.event.entity

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.region.entity.Region
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

    @Column(nullable = false)
    var location: String,

    @Column(name = "main_image_url", nullable = false)
    var mainImageUrl: String,

    @Column(name = "start_at", nullable = false)
    var startAt: LocalDateTime,

    @Column(name = "end_at", nullable = false)
    var endAt: LocalDateTime,

    @Column(name = "max_participants", nullable = false)
    var maxParticipants: Int,

    @Column(name = "participation_fee", nullable = false)
    var participationFee: Long,

    @Column(name = "refund_policy", nullable = false, columnDefinition = "TEXT")
    var refundPolicy: String,

    @Column(name = "detail_content", nullable = false, columnDefinition = "TEXT")
    var detailContent: String,

    @Column(name = "current_participants", nullable = false)
    var currentParticipants: Int = 0,

    @Column(name = "like_count", nullable = false)
    var likeCount: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EventStatus = EventStatus.UPCOMING,

    /**
     * 홈 화면 콘텐츠 유형 섹션(Original/Classic/Special)과 매핑된다.
     * 기존 데이터 호환을 위해 nullable. 신규 이벤트는 생성 시 반드시 지정한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", length = 20)
    var contentType: ContentType? = null,

    /**
     * PR147 — 정규화된 region. free-form `location` 과 병존 (legacy backfill 대상).
     * EventService.createEvent / updateEvent 에서 nullable 입력.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_code")
    var region: Region? = null,
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

    /**
     * 신고 누적 자동 숨김 (PR51). [status] (UPCOMING/ONGOING/CLOSED) 와 분리된 차원 —
     * status 는 시간 기반 lifecycle, hidden 은 운영 차원의 숨김.
     */
    @Column(name = "hidden_at")
    var hiddenAt: LocalDateTime? = null
        protected set

    @Column(name = "hidden_reason", length = 255)
    var hiddenReason: String? = null
        protected set

    val isHidden: Boolean
        get() = hiddenAt != null

    fun increaseParticipant() {
        currentParticipants++
    }

    fun decreaseParticipant() {
        if (currentParticipants > 0) currentParticipants--
    }

    fun isFull(): Boolean = currentParticipants >= maxParticipants

    fun hide(reason: String) {
        if (hiddenAt != null) return
        hiddenAt = LocalDateTime.now()
        hiddenReason = reason.take(255)
    }

    /** PR52 — ADMIN appeal 승인. */
    fun unhide() {
        if (hiddenAt == null) return
        hiddenAt = null
        hiddenReason = null
    }
}
