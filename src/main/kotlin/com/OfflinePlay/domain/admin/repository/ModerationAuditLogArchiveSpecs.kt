package com.contenido.domain.admin.repository

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLogArchive
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.entity.User
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.time.LocalDateTime

/**
 * PR67 — archived audit log 조회용 동적 필터.
 *
 * active 의 [ModerationAuditLogSpecs] 와 동일한 의미를 가지지만 시간 축은
 * `original_created_at` 컬럼을 기준으로 한다 — archived_at 이 아니라 원본의 발생 시각으로
 * 필터링하는 것이 사고 회고에 자연스럽다.
 */
object ModerationAuditLogArchiveSpecs {

    fun withFilters(
        action: ModerationAuditAction?,
        targetType: ReportTargetType?,
        targetId: Long?,
        actorId: Long?,
        from: LocalDateTime?,
        to: LocalDateTime?,
    ): Specification<ModerationAuditLogArchive> = Specification { root, _, cb ->
        val predicates = mutableListOf<Predicate>()
        action?.let { predicates += cb.equal(root.get<ModerationAuditAction>("action"), it) }
        targetType?.let { predicates += cb.equal(root.get<ReportTargetType>("targetType"), it) }
        targetId?.let { predicates += cb.equal(root.get<Long>("targetId"), it) }
        actorId?.let { predicates += cb.equal(root.get<User>("actor").get<Long>("id"), it) }
        from?.let { predicates += cb.greaterThanOrEqualTo(root.get("originalCreatedAt"), it) }
        to?.let { predicates += cb.lessThanOrEqualTo(root.get("originalCreatedAt"), it) }
        if (predicates.isEmpty()) cb.conjunction() else cb.and(*predicates.toTypedArray())
    }
}
