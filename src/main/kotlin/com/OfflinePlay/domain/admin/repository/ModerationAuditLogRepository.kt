package com.contenido.domain.admin.repository

import com.contenido.domain.admin.entity.ModerationAuditLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

/**
 * PR61 신설, PR62 에서 [JpaSpecificationExecutor] 추가.
 *
 * derived query 6종(findByAction, findByTargetType, ...) 은 PR62 의 새 필터(actorId/from/to)
 * 와 결합되면 조합이 폭증해서 유지가 어렵다. [Specification] 으로 일원화하고 [ModerationAuditLogSpecs]
 * 에서 필터를 합성한다.
 */
interface ModerationAuditLogRepository :
    JpaRepository<ModerationAuditLog, Long>,
    JpaSpecificationExecutor<ModerationAuditLog>
