package com.contenido.domain.creator.dto

import java.time.LocalDateTime

/**
 * PR153 — Creator Studio 매출/환불 분석 응답.
 *
 *  - 채널 단위 합계 + 이벤트별 breakdown.
 *  - 모든 금액은 KRW (소수점 없는 Long).
 *  - 결제 시도가 PAID / PARTIALLY_REFUNDED 일 때만 집계 — READY/FAILED/CANCELED 는 매출이 아님.
 *  - 본 응답은 audit_logs 를 join 하지 않는다. ADMIN forced refund 금액은 PaymentAttempt.refundedAmount
 *    누적에 이미 포함되어 있으므로 분리 표시를 하려면 ADMIN audit 도구를 별도로 본다.
 */
data class CreatorChannelAnalyticsResponse(
    val channelId: Long,
    val from: LocalDateTime?,
    val to: LocalDateTime?,
    val grossRevenue: Long,
    val refundedAmount: Long,
    val netRevenue: Long,
    /** PR117 부분 환불 진행 중인 PaymentAttempt 의 누적 환불액 합계. */
    val partialRefundAmount: Long,
    /** 전액 환불된 PaymentAttempt 개수 (status=PAID + refundedAmount=amount). */
    val fullRefundCount: Long,
    val paidAttemptCount: Long,
    val events: List<CreatorEventAnalytics>,
)

data class CreatorEventAnalytics(
    val eventId: Long,
    val eventTitle: String,
    val grossRevenue: Long,
    val refundedAmount: Long,
    val netRevenue: Long,
    val partialRefundAmount: Long,
    val fullRefundCount: Long,
    val paidAttemptCount: Long,
)
