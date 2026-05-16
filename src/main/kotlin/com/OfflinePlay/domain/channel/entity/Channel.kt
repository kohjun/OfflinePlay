package com.contenido.domain.channel.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@Table(name = "channels")
@EntityListeners(AuditingEntityListener::class)
class Channel(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    val owner: User,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var description: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val category: ChannelCategory,

    @Column(name = "thumbnail_url")
    var thumbnailUrl: String? = null,

    @Column(name = "subscriber_count", nullable = false)
    var subscriberCount: Long = 0,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
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
     * 신고 누적 자동 숨김 (PR51). [isActive] (Admin ban) 와 분리된 차원 —
     * isActive=false 는 Admin 의 명시적 운영 조치(ban), hidden 은 신고 누적 자동 조치.
     */
    @Column(name = "hidden_at")
    var hiddenAt: LocalDateTime? = null
        protected set

    @Column(name = "hidden_reason", length = 255)
    var hiddenReason: String? = null
        protected set

    val isHidden: Boolean
        get() = hiddenAt != null

    fun increaseSubscriber() {
        subscriberCount++
    }

    fun decreaseSubscriber() {
        if (subscriberCount > 0) subscriberCount--
    }

    fun deactivate() {
        isActive = false
    }

    fun hide(reason: String) {
        if (hiddenAt != null) return
        hiddenAt = LocalDateTime.now()
        hiddenReason = reason.take(255)
    }
}
