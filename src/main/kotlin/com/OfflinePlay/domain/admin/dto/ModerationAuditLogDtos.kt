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

/**
 * PR64 — audit log retention policy 조회 + dry-run 카운트.
 *
 *  - 본 PR 은 dry-run 만. 실제 삭제/archive 는 후속 PR.
 *  - cutoffAt 이전에 createdAt 이 있는 row 수가 [dryRunDeletableCount].
 *  - 운영자가 입력한 [retentionDays] 가 [minimumRetentionDays] ~ [maximumRetentionDays]
 *    범위를 벗어나면 controller 가 400 으로 반려한다 — 클라이언트가 응답까지 도달했다면
 *    값은 항상 허용 범위 안.
 *  - oldest/newest 는 row 가 0건이면 null.
 */
data class AuditLogRetentionPolicyResponse(
    val retentionDays: Long,
    val minimumRetentionDays: Long,
    val maximumRetentionDays: Long,
    val cutoffAt: LocalDateTime,
    val dryRunDeletableCount: Long,
    val oldestAuditLogCreatedAt: LocalDateTime?,
    val newestAuditLogCreatedAt: LocalDateTime?,
)
