package com.contenido.domain.admin.repository

import com.contenido.domain.admin.entity.ModerationAuditLogArchive
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

/**
 * PR66 신설. PR67 에서 [JpaSpecificationExecutor] 가 list/export 필터에 사용된다 — 본 PR 에서는
 * archive 실행만 다루지만 후속 PR 을 위해 미리 합성해 둔다.
 */
interface ModerationAuditLogArchiveRepository :
    JpaRepository<ModerationAuditLogArchive, Long>,
    JpaSpecificationExecutor<ModerationAuditLogArchive> {

    fun findByOriginalId(originalId: Long): ModerationAuditLogArchive?
}
