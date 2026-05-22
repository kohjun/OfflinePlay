package com.contenido.domain.user.entity

import com.contenido.domain.event.entity.Event
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * PR146 — 사용자 간 매너 평가 한 건.
 *
 *  - (reviewer, reviewee, event) UNIQUE — 같은 이벤트 1회만.
 *  - tags 는 JSON 문자열 (MySQL prod 는 JSON column / H2 test 는 VARCHAR — 양쪽 호환).
 *  - rating 은 1~5 — service 에서 범위 검증.
 *  - comment 는 optional 500자.
 *
 * 운영 의미는 [com.contenido.domain.user.service.MannerFeedbackService] 와
 * V15 migration 주석을 참고.
 */
@Entity
@Table(
    name = "user_manner_feedbacks",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_manner_feedbacks_event_pair",
            columnNames = ["reviewer_id", "reviewee_id", "event_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_user_manner_feedbacks_reviewee", columnList = "reviewee_id"),
        Index(name = "idx_user_manner_feedbacks_event", columnList = "event_id"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class MannerFeedback(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    val reviewer: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewee_id", nullable = false)
    val reviewee: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: Event,

    @Column(nullable = false)
    val rating: Int,

    @Convert(converter = MannerTagsConverter::class)
    @Column(name = "tags", columnDefinition = "TEXT")
    val tags: List<String> = emptyList(),

    @Column(length = 500)
    val comment: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set
}

/**
 * tags 컬럼 (MySQL JSON / H2 TEXT) ↔ Kotlin `List<String>` 변환기.
 *  - null / blank → 빈 리스트 반환.
 *  - 잘못된 JSON 도 빈 리스트로 fallback — 운영 row 가 손상되어도 entity load 가 깨지지 않게.
 */
@Converter(autoApply = false)
class MannerTagsConverter : AttributeConverter<List<String>, String?> {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    override fun convertToDatabaseColumn(attribute: List<String>?): String? =
        if (attribute.isNullOrEmpty()) null else mapper.writeValueAsString(attribute)

    override fun convertToEntityAttribute(dbData: String?): List<String> =
        if (dbData.isNullOrBlank()) emptyList()
        else runCatching { mapper.readValue<List<String>>(dbData) }.getOrDefault(emptyList())
}
