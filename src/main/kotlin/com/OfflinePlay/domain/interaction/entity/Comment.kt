package com.contenido.domain.interaction.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@Table(name = "comments")
@EntityListeners(AuditingEntityListener::class)
class Comment(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    val author: User,

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    val targetType: TargetType,

    @Column(name = "target_id", nullable = false)
    val targetId: Long,

    @Column(nullable = false, length = 500)
    var content: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    val parentComment: Comment? = null,

    @Column(name = "like_count", nullable = false)
    var likeCount: Long = 0,
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

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
        protected set

    val isDeleted: Boolean
        get() = deletedAt != null

    fun softDelete() {
        deletedAt = LocalDateTime.now()
    }

    /**
     * 신고 누적 자동 숨김 (PR51). [softDelete] 와는 분리된 의미 —
     * delete 는 본인이 직접 지운 것, hide 는 운영(자동/수동) 차원의 숨김.
     */
    @Column(name = "hidden_at")
    var hiddenAt: LocalDateTime? = null
        protected set

    @Column(name = "hidden_reason", length = 255)
    var hiddenReason: String? = null
        protected set

    val isHidden: Boolean
        get() = hiddenAt != null

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
