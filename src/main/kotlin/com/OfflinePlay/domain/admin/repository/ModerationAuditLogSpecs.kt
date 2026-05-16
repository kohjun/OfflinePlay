package com.contenido.domain.admin.repository

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLog
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.entity.User
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.time.LocalDateTime

/**
 * PR62 — audit log 조회용 동적 필터.
 *
 * 모든 필드는 nullable — 호출자가 채운 것만 AND 로 결합한다. 정렬은 [Pageable] 에서 별도 지정.
 */
object ModerationAuditLogSpecs {

    fun withFilters(
        action: ModerationAuditAction?,
        targetType: ReportTargetType?,
        targetId: Long?,
        actorId: Long?,
        from: LocalDateTime?,
        to: LocalDateTime?,
    ): Specification<ModerationAuditLog> = Specification { root, _, cb ->
        val predicates = mutableListOf<Predicate>()
        action?.let { predicates += cb.equal(root.get<ModerationAuditAction>("action"), it) }
        targetType?.let { predicates += cb.equal(root.get<ReportTargetType>("targetType"), it) }
        targetId?.let { predicates += cb.equal(root.get<Long>("targetId"), it) }
        actorId?.let { predicates += cb.equal(root.get<User>("actor").get<Long>("id"), it) }
        from?.let { predicates += cb.greaterThanOrEqualTo(root.get("createdAt"), it) }
        to?.let { predicates += cb.lessThanOrEqualTo(root.get("createdAt"), it) }
        if (predicates.isEmpty()) cb.conjunction() else cb.and(*predicates.toTypedArray())
    }
}
