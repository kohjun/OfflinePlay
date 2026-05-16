package com.contenido.domain.report.repository

import com.contenido.domain.report.entity.ReportAppeal
import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ReportAppealRepository : JpaRepository<ReportAppeal, Long> {

    /** 같은 requester 가 같은 (targetType, targetId) 에 PENDING appeal 을 또 만들지 못하게. */
    fun existsByRequesterAndTargetTypeAndTargetIdAndStatus(
        requester: User,
        targetType: ReportTargetType,
        targetId: Long,
        status: ReportAppealStatus,
    ): Boolean

    /** 본인 appeal 목록 — 최신순. */
    fun findByRequesterOrderByCreatedAtDesc(requester: User, pageable: Pageable): Page<ReportAppeal>

    /** ADMIN appeal 큐 — 상태 필터, 최신순. */
    fun findByStatusOrderByCreatedAtDesc(status: ReportAppealStatus, pageable: Pageable): Page<ReportAppeal>

    /** ADMIN appeal 전체 큐 — 상태 무관, 최신순. */
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<ReportAppeal>
}
