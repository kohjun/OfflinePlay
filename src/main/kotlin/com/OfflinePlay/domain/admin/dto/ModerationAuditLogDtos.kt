package com.contenido.domain.admin.dto

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.report.entity.ReportTargetType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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

/**
 * PR66 — archive 미리보기. preview 결과의 `cutoffAt` / `candidateCount` 를 execute 요청에 그대로
 * echo 해야 stale 상태에서 잘못된 양을 archive 하지 않는다.
 *
 *  - candidateCount : cutoffAt 이전 row 총합 (1000 초과 가능).
 *  - archiveLimit   : 한 번에 옮길 수 있는 최대.
 *  - willArchiveCount = min(candidateCount, archiveLimit).
 */
data class AuditLogArchivePreviewResponse(
    val retentionDays: Long,
    val cutoffAt: LocalDateTime,
    val candidateCount: Long,
    val archiveLimit: Int,
    val willArchiveCount: Long,
    val oldestAuditLogCreatedAt: LocalDateTime?,
    val newestAuditLogCreatedAt: LocalDateTime?,
)

/**
 * PR66 — archive 실행 요청.
 *  - retentionDays : 선택. 빈 값이면 PR64 default 사용.
 *  - expectedCutoffAt / expectedCandidateCount : preview 응답의 값 그대로 echo (stale 가드).
 *  - confirmText : 'ARCHIVE' 정확 일치 필수 (실수 방지 + UI 가 사용자에게 명시).
 */
data class ExecuteAuditLogArchiveRequest(
    val retentionDays: Long? = null,
    val expectedCutoffAt: LocalDateTime,
    val expectedCandidateCount: Long,
    @field:NotBlank @field:Size(max = 16)
    val confirmText: String,
)

/**
 * PR66 — archive 실행 결과. UI 가 후속 미리보기 갱신 + 토스트에 사용.
 *  - archivedCount : 본 호출에서 옮긴 행 수 (최대 [AuditLogArchivePreviewResponse.archiveLimit]).
 *  - remainingCandidateCount : 옮기고 난 뒤에도 cutoffAt 이전에 남아 있는 row 수.
 */
data class AuditLogArchiveResultResponse(
    val archivedCount: Long,
    val cutoffAt: LocalDateTime,
    val remainingCandidateCount: Long,
)
