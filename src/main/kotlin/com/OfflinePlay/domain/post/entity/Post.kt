package com.contenido.domain.post.entity

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

enum class PostStatus {
    PUBLISHED, DELETED
}

@Entity
@Table(name = "posts")
@EntityListeners(AuditingEntityListener::class)
class Post(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id", nullable = false)
    val channel: Channel,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    val author: User,

    @Column(nullable = false)
    var title: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "thumbnail_url")
    var thumbnailUrl: String? = null,

    @Column(name = "view_count", nullable = false)
    var viewCount: Long = 0,

    @Column(name = "like_count", nullable = false)
    var likeCount: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PostStatus = PostStatus.PUBLISHED,
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
     * 신고 누적 자동 숨김 (PR51). [status] (PUBLISHED/DELETED) 와 분리된 차원 —
     * status=DELETED 는 작성자 의도 삭제, hidden 은 운영 차원의 숨김.
     */
    @Column(name = "hidden_at")
    var hiddenAt: LocalDateTime? = null
        protected set

    @Column(name = "hidden_reason", length = 255)
    var hiddenReason: String? = null
        protected set

    val isHidden: Boolean
        get() = hiddenAt != null

    fun increaseViewCount() {
        viewCount++
    }

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
