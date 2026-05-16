package com.contenido.domain.review.entity

import com.contenido.domain.event.entity.Event
import com.contenido.domain.user.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * 이벤트 참가자가 남기는 후기 (별점 1~5 + 본문).
 *
 * 제약:
 *  - (event, author) UNIQUE — 한 사용자가 한 이벤트에 후기를 하나만 남길 수 있다.
 *  - rating 은 서비스 레이어에서 1..5 범위 검증 (DB 레벨 CHECK 는 생략 — 다중 DB 호환).
 *  - 작성 권한은 서비스가 USED 티켓 보유자만으로 가드.
 *  - 수정은 본인만, 삭제는 본인 + ADMIN 만 (ReviewService).
 *
 * 채널/이벤트 평균 별점은 본 엔티티의 집계 쿼리로 계산 — Event 에 캐시 컬럼은 추가하지 않는다.
 * 트래픽이 늘면 별도 PR 에서 denormalize.
 */
@Entity
@Table(
    name = "reviews",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_reviews_event_author", columnNames = ["event_id", "author_id"]),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class Review(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: Event,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    val author: User,

    @Column(nullable = false)
    var rating: Int,

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

    /**
     * 신고 누적 자동 조치 (PR51) — 임계치 초과 시 [ReportService] 가 [hide] 호출.
     * 값이 채워진 row 는 일반 사용자 조회에서 제외되지만 Admin/작성자 본인 확인은 가능.
     * 수동 delete 와 의미를 분리한다 — hide 만으로 데이터는 보존.
     */
    @Column(name = "hidden_at")
    var hiddenAt: LocalDateTime? = null
        protected set

    @Column(name = "hidden_reason", length = 255)
    var hiddenReason: String? = null
        protected set

    val isHidden: Boolean
        get() = hiddenAt != null

    fun update(rating: Int, content: String) {
        this.rating = rating
        this.content = content
    }

    /** 중복 호출은 no-op — 첫 hide 시점/사유를 보존. */
    fun hide(reason: String) {
        if (hiddenAt != null) return
        hiddenAt = LocalDateTime.now()
        hiddenReason = reason.take(255)
    }
}
