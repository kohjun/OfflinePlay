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

    fun increaseSubscriber() {
        subscriberCount++
    }

    fun decreaseSubscriber() {
        if (subscriberCount > 0) subscriberCount--
    }

    fun deactivate() {
        isActive = false
    }
}
