package com.contenido.domain.interaction.entity

import com.contenido.domain.user.entity.User
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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

    /**
     * PR152 — 이벤트룸 댓글 inline 이미지 url 목록 (최대 3장 — service validation).
     * JSON 직렬화 (List<String>). null / blank → 빈 리스트로 변환.
     * 다른 targetType (POST 등) 의 댓글은 본 컬럼을 사용하지 않는다 — null 그대로 둔다.
     */
    @Convert(converter = CommentImagesConverter::class)
    @Column(name = "images", columnDefinition = "TEXT")
    var images: List<String> = emptyList(),
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

/**
 * PR152 — comment images TEXT 컬럼 ↔ `List<String>` 변환기.
 *  - null / blank DB 값 → 빈 리스트.
 *  - 손상된 JSON 도 빈 리스트로 fallback — entity load 가 깨지지 않게.
 */
@Converter(autoApply = false)
class CommentImagesConverter : AttributeConverter<List<String>, String?> {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    override fun convertToDatabaseColumn(attribute: List<String>?): String? =
        if (attribute.isNullOrEmpty()) null else mapper.writeValueAsString(attribute)

    override fun convertToEntityAttribute(dbData: String?): List<String> =
        if (dbData.isNullOrBlank()) emptyList()
        else runCatching { mapper.readValue<List<String>>(dbData) }.getOrDefault(emptyList())
}
