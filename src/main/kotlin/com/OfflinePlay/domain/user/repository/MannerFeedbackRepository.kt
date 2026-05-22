package com.contenido.domain.user.repository

import com.contenido.domain.user.entity.MannerFeedback
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * PR146 — 사용자 매너 평가 저장소.
 *
 *  - 중복 차단: (reviewer, reviewee, event) UNIQUE — service 에서 existsByXxx 가드 + DB 제약.
 *  - 공개 응답은 누적 3건 이상에서만 제공 — countByRevieweeId 로 분기.
 */
interface MannerFeedbackRepository : JpaRepository<MannerFeedback, Long> {

    fun existsByReviewerIdAndRevieweeIdAndEventId(
        reviewerId: Long,
        revieweeId: Long,
        eventId: Long,
    ): Boolean

    fun countByRevieweeId(revieweeId: Long): Long

    @Query("SELECT AVG(m.rating) FROM MannerFeedback m WHERE m.reviewee.id = :revieweeId")
    fun averageRatingByRevieweeId(@Param("revieweeId") revieweeId: Long): Double?

    /** PR146 — 사용자별 모든 매너 피드백. service 가 in-memory 로 top tag 집계. */
    fun findByRevieweeId(revieweeId: Long): List<MannerFeedback>
}
