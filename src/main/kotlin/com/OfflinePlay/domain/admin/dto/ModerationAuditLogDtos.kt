package com.contenido.domain.admin.dto

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.report.entity.ReportTargetType
import java.time.LocalDateTime

/**
 * PR61 — audit log 단건 응답. before/after 는 service 가 직렬화한 JSON 문자열 그대로 노출.
 * 운영자가 한 줄로 "누가 / 언제 / 무엇을 / 어떤 대상에" 를 읽을 수 있게 actorNickname 동봉.
 */
data class ModerationAuditLogResponse(
    val id: Long,
    val actorId: Long,
    val actorNickname: String,
    val action: ModerationAuditAction,
    val targetType: ReportTargetType?,
    val targetId: Long?,
    val beforeValue: String?,
    val afterValue: String?,
    val reason: String?,
    val createdAt: LocalDateTime,
)
