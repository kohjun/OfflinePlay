package com.contenido.domain.admin.dto

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.report.entity.ReportTargetType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * PR61 — audit log 단건 응답. before/after 는 service 가 직렬화한 JSON 문자열 그대로 노출.
 * 운영자가 한 줄로 "누가 / 언제 / 무엇을 / 어떤 대상에" 를 읽을 수 있게 actorNickname 동봉.
 * PR71 — actorSystem: scheduler 등 자동 작업의 actor 여부 (actor.email == system actor email).
 * PR115 — forcedRefundContext: action 이 TICKET_FORCED_REFUNDED 인 단건 detail 조회에서만 채워짐.
 *   list / CSV export / archive 응답은 null 유지 (N+1 회피 + CSV 호환). 본 필드는 조회 시점
 *   enrichment 결과이며, 원본 [beforeValue]/[afterValue] 는 손대지 않는다.
 */
data class ModerationAuditLogResponse(
    val id: Long,
    val actorId: Long,
    val actorNickname: String,
    val actorSystem: Boolean = false,
    val action: ModerationAuditAction,
    val targetType: ReportTargetType?,
    val targetId: Long?,
    val beforeValue: String?,
    val afterValue: String?,
    val reason: String?,
    val createdAt: LocalDateTime,
    val forcedRefundContext: ForcedRefundAuditContextResponse? = null,
)

/**
 * PR115 — `TICKET_FORCED_REFUNDED` audit row 의 detail enrichment payload.
 *
 *  - `afterValue` JSON 에서 파싱한 값 (`ticketId`/`paymentAttemptId`/`amount`/`ticketStatus`) 과
 *    조회 시점에 ticket → buyer / event / channel lookup 으로 채운 값 (id + 사람이 읽는 이름) 을 묶음.
 *  - `contextAvailable` :
 *      - true  : ticketId 가 JSON 에서 잘 나왔고 ticket lookup 도 성공해서 buyer/event/channel 까지
 *                전부 채워졌다.
 *      - false : 둘 중 하나라도 빠짐 — JSON malformed / ticketId 없음 / ticket 삭제됨 등. UI 가
 *                "원본 감사 로그는 확인되지만 티켓 상세 정보를 찾을 수 없습니다." fallback 을 표시.
 *  - 본 응답은 audit row 의 **읽기 뷰** 일 뿐 — 원본 audit row 의 beforeValue/afterValue/reason 은
 *    변경하지 않는다. lookup 실패가 detail 자체를 실패시키지 않도록 service 가 swallow.
 */
data class ForcedRefundAuditContextResponse(
    val ticketId: Long?,
    val paymentAttemptId: Long?,
    val amount: Long?,
    val ticketStatus: String?,
    val buyerId: Long?,
    val buyerNickname: String?,
    val buyerEmail: String?,
    val eventId: Long?,
    val eventTitle: String?,
    val channelId: Long?,
    val channelName: String?,
    val contextAvailable: Boolean,
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
