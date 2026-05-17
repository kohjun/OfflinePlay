package com.contenido.domain.admin.dto

import java.time.LocalDate
import java.time.LocalDateTime

/** PR57 — Analytics 시계열 granularity. day 만 지원. */
enum class AdminModerationGranularity { DAY }

/**
 * 한 bucket 의 집계 카운트 (PR57).
 *  - reportCount        : 해당 일의 신고 생성 수 (reports.createdAt 기준).
 *  - autoHideCount      : hiddenReason 이 PR51 자동 사유 ("신고 누적 자동 숨김") 인 hide 수.
 *  - manualHideCount    : 그 외 hide 수 (PR54 운영자 hide 등).
 *  - appealSubmittedCount : appeal 생성 수 (createdAt 기준).
 *  - appealApprovedCount  : APPROVED 처리 수 (reviewedAt 기준).
 *  - appealRejectedCount  : REJECTED 처리 수 (reviewedAt 기준).
 */
data class AdminModerationStatsPoint(
    val date: LocalDate,
    val reportCount: Long,
    val autoHideCount: Long,
    val manualHideCount: Long,
    val appealSubmittedCount: Long,
    val appealApprovedCount: Long,
    val appealRejectedCount: Long,
)

/** 위험 채널 row — 해당 채널 / 채널 소속 콘텐츠의 hidden 누적 수. */
enum class ChannelRiskLevel { WATCH, RISK }

data class AdminRiskyChannelResponse(
    val channelId: Long,
    val channelName: String,
    val ownerNickname: String,
    val hiddenCount: Long,
    val pendingReportCount: Long? = null,
    val riskLevel: ChannelRiskLevel,
)

/**
 * 운영 지표 응답 (PR57).
 *
 *  - [from] ~ [to] 구간의 [series] 시계열 + 같은 구간의 [totals] 합계.
 *  - [riskyChannels] 는 구간 무관 누적 hidden 콘텐츠 (현시점 hidden 상태) Top 5.
 *    위험 채널 판단은 구간보다는 현재 상태가 의미 있다 — 운영자가 즉시 검토할 후보.
 */
data class AdminModerationStatsResponse(
    val from: LocalDateTime,
    val to: LocalDateTime,
    val granularity: AdminModerationGranularity,
    val series: List<AdminModerationStatsPoint>,
    val totals: AdminModerationStatsPoint,
    val riskyChannels: List<AdminRiskyChannelResponse>,
)

/**
 * PR93 — 운영자(actor) 활동 요약. moderation_audit_logs 를 actor 단위로 집계해
 * "지난 30일간 누가 얼마나 hide/ban/appeal/report 를 처리했는지" 를 한 표로 본다.
 *
 * 분류는 [com.contenido.domain.admin.entity.ModerationAuditAction] enum 기준:
 *  - hideCount             : TARGET_HIDDEN
 *  - unhideCount           : TARGET_UNHIDDEN
 *  - channelBanCount       : CHANNEL_BANNED
 *  - channelUnbanCount     : CHANNEL_UNBANNED
 *  - appealDecisionCount   : APPEAL_APPROVED + APPEAL_REJECTED
 *  - reportDecisionCount   : REPORT_RESOLVED + REPORT_DISMISSED
 *  - thresholdUpdateCount  : THRESHOLD_UPDATED
 *  - archiveCount          : AUDIT_LOGS_ARCHIVED
 *  - forcedRefundCount     : TICKET_FORCED_REFUNDED (PR109)
 *
 * actorSystem 은 system actor (V9 seed, [SystemActorService]) 여부 — scheduled archive 자동
 * 실행분이 사람 운영분과 섞여 보이지 않도록 UI 가 별도 뱃지로 표시한다.
 *
 * `totalActionCount` 는 위 모든 분류의 합이 아니라 audit row 개수 — 분류에 포함되지 않은 액션도
 * 들어가 있을 수 있다. 분류 외 행위가 새로 추가되면 합과 분류 카운트가 어긋날 수 있으므로 본 DTO
 * 갱신 시 함께 점검한다.
 */
data class AdminModerationActorStatItem(
    val actorId: Long,
    val actorNickname: String,
    val actorSystem: Boolean,
    val totalActionCount: Long,
    val hideCount: Long,
    val unhideCount: Long,
    val channelBanCount: Long,
    val channelUnbanCount: Long,
    val appealDecisionCount: Long,
    val reportDecisionCount: Long,
    val thresholdUpdateCount: Long,
    val archiveCount: Long,
    /** PR109 — [ModerationAuditAction.TICKET_FORCED_REFUNDED] 처리 건수. */
    val forcedRefundCount: Long,
)

data class AdminModerationActorStatsResponse(
    val from: LocalDateTime,
    val to: LocalDateTime,
    val limit: Int,
    val items: List<AdminModerationActorStatItem>,
)
