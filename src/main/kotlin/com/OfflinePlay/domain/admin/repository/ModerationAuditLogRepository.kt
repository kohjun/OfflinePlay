package com.contenido.domain.admin.repository

import com.contenido.domain.admin.entity.ModerationAuditLog
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.LocalDateTime

/**
 * PR61 신설, PR62 에서 [JpaSpecificationExecutor] 추가, PR64 에서 retention dry-run 메서드.
 *
 * derived query 6종(findByAction, findByTargetType, ...) 은 PR62 의 새 필터(actorId/from/to)
 * 와 결합되면 조합이 폭증해서 유지가 어렵다. [Specification] 으로 일원화하고 [ModerationAuditLogSpecs]
 * 에서 필터를 합성한다.
 *
 * Retention dry-run (PR64) 은 단일 필드 (createdAt) 기준 카운트/단건 조회라 derived query 로
 * 충분 — Specification 까지 동원할 필요 없음.
 */
interface ModerationAuditLogRepository :
    JpaRepository<ModerationAuditLog, Long>,
    JpaSpecificationExecutor<ModerationAuditLog> {

    /** PR64 — cutoffAt 이전(미포함) 의 row 수. retention dry-run 카운트. */
    fun countByCreatedAtBefore(cutoffAt: LocalDateTime): Long

    /** PR64 — 가장 오래된 audit log 1건. row 가 0건이면 null. */
    fun findFirstByOrderByCreatedAtAsc(): ModerationAuditLog?

    /** PR64 — 가장 최근 audit log 1건. row 가 0건이면 null. */
    fun findFirstByOrderByCreatedAtDesc(): ModerationAuditLog?

    /**
     * PR66 — archive 대상 fetch. cutoffAt 이전 row 를 createdAt ASC 로 [pageable] 만큼.
     * 호출자가 Pageable.ofSize(1000) 등으로 1회 batch 크기를 강제.
     */
    fun findByCreatedAtBeforeOrderByCreatedAtAsc(
        cutoffAt: LocalDateTime,
        pageable: Pageable,
    ): List<ModerationAuditLog>

    /**
     * PR93 — actor 활동 요약용. 구간 안의 모든 audit row 를 fetch 한다. 본 MVP 에서는 30일 default
     * 범위라 in-memory group 으로 충분 — DB 측 GROUP BY 최적화는 후속.
     */
    fun findByCreatedAtBetween(from: LocalDateTime, to: LocalDateTime): List<ModerationAuditLog>
}
