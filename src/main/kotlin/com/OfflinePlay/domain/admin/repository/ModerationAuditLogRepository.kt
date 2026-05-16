package com.contenido.domain.admin.repository

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLog
import com.contenido.domain.report.entity.ReportTargetType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ModerationAuditLogRepository : JpaRepository<ModerationAuditLog, Long> {

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<ModerationAuditLog>

    fun findByActionOrderByCreatedAtDesc(
        action: ModerationAuditAction,
        pageable: Pageable,
    ): Page<ModerationAuditLog>

    fun findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
        targetType: ReportTargetType,
        targetId: Long,
        pageable: Pageable,
    ): Page<ModerationAuditLog>

    fun findByTargetTypeOrderByCreatedAtDesc(
        targetType: ReportTargetType,
        pageable: Pageable,
    ): Page<ModerationAuditLog>

    fun findByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
        action: ModerationAuditAction,
        targetType: ReportTargetType,
        targetId: Long,
        pageable: Pageable,
    ): Page<ModerationAuditLog>

    fun findByActionAndTargetTypeOrderByCreatedAtDesc(
        action: ModerationAuditAction,
        targetType: ReportTargetType,
        pageable: Pageable,
    ): Page<ModerationAuditLog>
}
