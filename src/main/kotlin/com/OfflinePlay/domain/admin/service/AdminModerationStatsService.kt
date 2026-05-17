package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminModerationGranularity
import com.contenido.domain.admin.dto.AdminModerationStatsPoint
import com.contenido.domain.admin.dto.AdminModerationStatsResponse
import com.contenido.domain.admin.dto.AdminRiskyChannelResponse
import com.contenido.domain.admin.dto.ChannelRiskLevel
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.report.service.ReportService
import com.contenido.domain.review.repository.ReviewRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * PR87 — `AdminModerationService` 에서 운영 지표(PR57) 책임을 분리한 서비스.
 *
 * 집계 방식 (변경 없음):
 *  - reports.createdAt 범위 → reportCount per day.
 *  - report_appeals.createdAt 범위 → appealSubmittedCount per day.
 *  - report_appeals.reviewedAt 범위 + status APPROVED/REJECTED → approved/rejected per day.
 *  - 5도메인 entity.hiddenAt 범위 → hide count.
 *    hiddenReason 이 [ReportService.AUTO_HIDE_REASON] 이면 autoHideCount, 아니면 manualHide.
 *  - 위험 채널: 현재 hidden 인 5도메인 row 를 channel 로 매핑 후 그룹 합산.
 *    REVIEW.event.channel / POST.channel / EVENT.channel / CHANNEL 자체. COMMENT 는 channel
 *    매핑이 복잡(targetType=EVENT/POST/COMMENT) 해서 본 MVP 에서는 제외 — 후속.
 */
@Service
@Transactional(readOnly = true)
class AdminModerationStatsService(
    private val reviewRepository: ReviewRepository,
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
    private val eventRepository: EventRepository,
    private val channelRepository: ChannelRepository,
    private val reportRepository: ReportRepository,
    private val reportAppealRepository: ReportAppealRepository,
) {

    companion object {
        /** PR57 — Stats 기본 범위 (now - 30days ~ now). */
        const val STATS_DEFAULT_DAYS: Long = 30

        /** 위험 채널 판단 임계치: hidden 콘텐츠 누적 5건 이상이면 RISK, 1~4 면 WATCH. */
        const val CHANNEL_RISK_THRESHOLD: Int = 5

        /** 위험 채널 Top N. */
        const val RISKY_CHANNELS_LIMIT: Int = 5
    }

    fun getStats(
        from: LocalDateTime?,
        to: LocalDateTime?,
        granularity: AdminModerationGranularity?,
    ): AdminModerationStatsResponse {
        val toEffective = to ?: LocalDateTime.now()
        val fromEffective = from ?: toEffective.minusDays(STATS_DEFAULT_DAYS)
        val g = granularity ?: AdminModerationGranularity.DAY

        // bucket 초기화 — 빈 날도 0 으로 채운다.
        val dates: List<LocalDate> = generateSequence(fromEffective.toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter(toEffective.toLocalDate()) }
            .toList()
        val seed: MutableMap<LocalDate, MutableStatsRow> = dates.associateWith { MutableStatsRow() }.toMutableMap()

        // reports → reportCount.
        reportRepository.findByCreatedAtBetween(fromEffective, toEffective).forEach { r ->
            seed.bucket(r.createdAt.toLocalDate())?.let { it.reportCount += 1 }
        }
        // appeals 제출.
        reportAppealRepository.findByCreatedAtBetween(fromEffective, toEffective).forEach { a ->
            seed.bucket(a.createdAt.toLocalDate())?.let { it.appealSubmittedCount += 1 }
        }
        // appeals 처리 (APPROVED/REJECTED).
        reportAppealRepository.findByReviewedAtBetween(fromEffective, toEffective).forEach { a ->
            val day = (a.reviewedAt ?: return@forEach).toLocalDate()
            val bucket = seed.bucket(day) ?: return@forEach
            when (a.status) {
                com.contenido.domain.report.entity.ReportAppealStatus.APPROVED -> bucket.appealApprovedCount += 1
                com.contenido.domain.report.entity.ReportAppealStatus.REJECTED -> bucket.appealRejectedCount += 1
                else -> Unit
            }
        }
        // hidden 5 domains.
        collectHiddenInRange(fromEffective, toEffective).forEach { (day, reason) ->
            val bucket = seed.bucket(day) ?: return@forEach
            if (reason == ReportService.AUTO_HIDE_REASON) bucket.autoHideCount += 1
            else bucket.manualHideCount += 1
        }

        val series = dates.map { d ->
            val row = seed[d]!!
            AdminModerationStatsPoint(
                date = d,
                reportCount = row.reportCount,
                autoHideCount = row.autoHideCount,
                manualHideCount = row.manualHideCount,
                appealSubmittedCount = row.appealSubmittedCount,
                appealApprovedCount = row.appealApprovedCount,
                appealRejectedCount = row.appealRejectedCount,
            )
        }
        val totals = AdminModerationStatsPoint(
            date = fromEffective.toLocalDate(),
            reportCount = series.sumOf { it.reportCount },
            autoHideCount = series.sumOf { it.autoHideCount },
            manualHideCount = series.sumOf { it.manualHideCount },
            appealSubmittedCount = series.sumOf { it.appealSubmittedCount },
            appealApprovedCount = series.sumOf { it.appealApprovedCount },
            appealRejectedCount = series.sumOf { it.appealRejectedCount },
        )

        val riskyChannels = buildRiskyChannels()

        return AdminModerationStatsResponse(
            from = fromEffective,
            to = toEffective,
            granularity = g,
            series = series,
            totals = totals,
            riskyChannels = riskyChannels,
        )
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private class MutableStatsRow(
        var reportCount: Long = 0L,
        var autoHideCount: Long = 0L,
        var manualHideCount: Long = 0L,
        var appealSubmittedCount: Long = 0L,
        var appealApprovedCount: Long = 0L,
        var appealRejectedCount: Long = 0L,
    )

    private fun MutableMap<LocalDate, MutableStatsRow>.bucket(date: LocalDate): MutableStatsRow? = this[date]

    /**
     * 5도메인 hidden row 를 (hiddenAt 의 day, hiddenReason) 페어로 평탄화. service 단에서 day 별
     * autoHide/manualHide 분류에 사용.
     */
    private fun collectHiddenInRange(from: LocalDateTime, to: LocalDateTime): List<Pair<LocalDate, String?>> {
        val rows = mutableListOf<Pair<LocalDate, String?>>()
        reviewRepository.findByHiddenAtBetween(from, to).forEach { it.hiddenAt?.let { at -> rows += at.toLocalDate() to it.hiddenReason } }
        commentRepository.findByHiddenAtBetween(from, to).forEach { it.hiddenAt?.let { at -> rows += at.toLocalDate() to it.hiddenReason } }
        postRepository.findByHiddenAtBetween(from, to).forEach { it.hiddenAt?.let { at -> rows += at.toLocalDate() to it.hiddenReason } }
        eventRepository.findByHiddenAtBetween(from, to).forEach { it.hiddenAt?.let { at -> rows += at.toLocalDate() to it.hiddenReason } }
        channelRepository.findByHiddenAtBetween(from, to).forEach { it.hiddenAt?.let { at -> rows += at.toLocalDate() to it.hiddenReason } }
        return rows
    }

    /**
     * 현재 시점 hidden 상태인 콘텐츠를 channel 단위로 합산해 Top N 위험 채널을 반환.
     *  - REVIEW → review.event.channel
     *  - POST   → post.channel
     *  - EVENT  → event.channel
     *  - CHANNEL → 자기 자신
     *  - COMMENT 는 channel 매핑이 복잡 (targetType=EVENT/POST) — MVP 범위 밖, 후속.
     */
    private fun buildRiskyChannels(): List<AdminRiskyChannelResponse> {
        val counts = mutableMapOf<Long, Long>()
        val channelCache = mutableMapOf<Long, Channel>()

        fun bump(ch: Channel) {
            channelCache.putIfAbsent(ch.id, ch)
            counts.merge(ch.id, 1L, Long::plus)
        }

        reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc().forEach { bump(it.event.channel) }
        postRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc().forEach { bump(it.channel) }
        eventRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc().forEach { bump(it.channel) }
        channelRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc().forEach { bump(it) }

        return counts.entries
            .sortedByDescending { it.value }
            .take(RISKY_CHANNELS_LIMIT)
            .mapNotNull { (channelId, hiddenCount) ->
                val ch = channelCache[channelId] ?: return@mapNotNull null
                AdminRiskyChannelResponse(
                    channelId = ch.id,
                    channelName = ch.name,
                    ownerNickname = ch.owner.nickname,
                    hiddenCount = hiddenCount,
                    pendingReportCount = null, // 비용 줄이려 본 MVP 에선 채우지 않음. 후속에서 batch.
                    riskLevel = if (hiddenCount >= CHANNEL_RISK_THRESHOLD) ChannelRiskLevel.RISK
                    else ChannelRiskLevel.WATCH,
                )
            }
    }
}
