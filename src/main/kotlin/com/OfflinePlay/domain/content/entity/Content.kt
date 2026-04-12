package com.contenido.domain.content.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

enum class ContentStatus {
    DRAFT, PUBLISHED, DELETED
}

@Entity
@Table(name = "contents")
@EntityListeners(AuditingEntityListener::class)
class Content(

    @Column(nullable = false)
    var title: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var description: String,

    @Column(name = "thumbnail_url")
    var thumbnailUrl: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    val creator: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ContentStatus = ContentStatus.DRAFT,

    @Column(name = "view_count", nullable = false)
    var viewCount: Long = 0,
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

    fun increaseViewCount() {
        viewCount++
    }
}
