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

    /**
     * PR53 — Creator Studio "내 숨김" 섹션이 row 별 현재 appeal 상태/id 를 표시하기 위함.
     * (requester, targetType, targetId) 기준 최신 row 1건 — PENDING 이 있으면 그것, 아니면
     * 가장 최근 처리(APPROVED/REJECTED). 결과가 없으면 NONE 으로 매핑한다.
     */
    fun findFirstByRequesterAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
        requester: User,
        targetType: ReportTargetType,
        targetId: Long,
    ): ReportAppeal?

    /**
     * PR54 — Admin 수동 hide/unhide 응답이 row 의 최신 appeal 상태를 함께 보여주기 위함.
     * (targetType, targetId) 기준 최신 appeal 1건 — requester 무관.
     */
    fun findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(
        targetType: ReportTargetType,
        targetId: Long,
    ): ReportAppeal?

    /** PR55 — Admin moderation queue 빌드. status 의 모든 row 를 createdAt 내림차순으로. */
    fun findByStatusOrderByCreatedAtDesc(status: ReportAppealStatus): List<ReportAppeal>
}
