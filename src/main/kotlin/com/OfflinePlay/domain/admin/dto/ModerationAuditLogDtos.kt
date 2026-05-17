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

/**
 * PR67 — archived audit log 단건 응답. active 의 [ModerationAuditLogResponse] 와 shape 이
 * 닮았지만:
 *  - `originalId` 가 PK 역할 (archive row 본인 id 가 아니라 active 에 있던 id 를 노출).
 *  - `actorNicknameSnapshot` 은 archive 시점의 nickname 박혀 있음 (사용자 이름 변경 후에도 보존).
 *  - `archivedAt` / `archivedBy` 추가.
 */
data class ArchivedModerationAuditLogResponse(
    val originalId: Long,
    val actorId: Long,
    val actorNicknameSnapshot: String,
    val action: ModerationAuditAction,
    val targetType: ReportTargetType?,
    val targetId: Long?,
    val beforeValue: String?,
    val afterValue: String?,
    val reason: String?,
    val originalCreatedAt: LocalDateTime,
    val archivedAt: LocalDateTime,
    val archivedBy: Long,
)

/**
 * PR68 신설, PR69 부터 actor null 이어도 system actor 가 archive 실행. PR70 부터 runtime
 * scheduling 상태도 함께 노출.
 *
 *  - enabled / cron / updatedBy / updatedAt : DB 의 영구 설정.
 *  - runtimeScheduled : 현재 프로세스에 등록된 schedule future 가 살아 있는지. test profile /
 *    runner 미등록 환경에서는 항상 false.
 *  - lastRescheduledAt : 마지막 reschedule 시각. null 이면 아직 한 번도 등록 안 됨.
 */
data class AuditLogRetentionSchedulerResponse(
    val enabled: Boolean,
    val cron: String,
    val updatedBy: Long?,
    val updatedAt: LocalDateTime,
    val runtimeScheduled: Boolean = false,
    val lastRescheduledAt: LocalDateTime? = null,
)

/**
 * PR68 — scheduler 부분 갱신 요청. enabled / cron 각각 optional.
 *  - cron 은 Spring 6-field 형식 (예: `0 30 3 * * *`). 본 PR 은 server 측에서 형식까지 깊게
 *    검증하지는 않고 길이만 제한 — 잘못 입력하면 ApplicationContext refresh 단계가 아니라 다음
 *    schedule poll 에서 실패 로그. 후속 PR 에서 사전 parse 추가 가능.
 */
data class UpdateAuditLogRetentionSchedulerRequest(
    val enabled: Boolean? = null,
    @field:Size(max = 64)
    val cron: String? = null,
)
