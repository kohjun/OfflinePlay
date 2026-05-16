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
