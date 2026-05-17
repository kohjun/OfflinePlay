package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminModerationPriority
import com.contenido.domain.admin.dto.AdminModerationQueueItemResponse
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.global.exception.ReportTargetNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * PR87 — `AdminModerationService` 에서 통합 moderation queue (PR55) 책임을 분리한 서비스.
 *
 * 3개 source 의 (targetType, targetId) 키 union → 중복 merge → priority 계산 → 정렬 →
 * filter → page. PENDING report / appeal / hidden 콘텐츠가 한 페이지에서 다 보인다.
 *
 * 비용 관점: 각 source 를 한 번씩만 fetch (PENDING report N + hidden M + PENDING appeal K)
 * 후 메모리에서 merge. 운영 트래픽이 커지면 native group-by + window function 으로 최적화 —
 * 본 PR 은 MVP. PR87 에서는 로직 변경 없이 클래스만 분리.
 */
@Service
@Transactional(readOnly = true)
class AdminModerationQueueService(
    private val reviewRepository: ReviewRepository,
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
    private val eventRepository: EventRepository,
    private val channelRepository: ChannelRepository,
    private val reportRepository: ReportRepository,
    private val reportAppealRepository: ReportAppealRepository,
    private val moderationThresholdService: ModerationThresholdService,
) {

    companion object {
        private const val PREVIEW_LIMIT = 80
    }

    fun getQueue(
        page: Int,
        size: Int,
        targetType: ReportTargetType?,
        hidden: Boolean?,
        priority: AdminModerationPriority?,
    ): Page<AdminModerationQueueItemResponse> {
        // 1. PENDING report → (key, latestReport, count).
        val pendingReports = reportRepository.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING)
        val reportByKey = pendingReports.groupBy { TargetKey(it.targetType, it.targetId) }

        // 2. PENDING appeal → (key, latestAppeal).
        val pendingAppeals = reportAppealRepository
            .findByStatusOrderByCreatedAtDesc(ReportAppealStatus.PENDING)
        val appealByKey = pendingAppeals.groupBy { TargetKey(it.targetType, it.targetId) }

        // 3. hidden 콘텐츠 — 5도메인 fetch.
        val hiddenByKey = collectHiddenByKey()

        // 4. union key 집합.
        val keys: Set<TargetKey> = reportByKey.keys + appealByKey.keys + hiddenByKey.keys

        val rows = keys.mapNotNull { key ->
            buildQueueRow(
                key = key,
                hiddenSummary = hiddenByKey[key],
                latestReport = reportByKey[key]?.firstOrNull(), // 그룹은 createdAt desc 정렬됨
                pendingReportCount = reportByKey[key]?.size?.toLong() ?: 0L,
                latestPendingAppeal = appealByKey[key]?.firstOrNull(),
            )
        }
            .filter { row ->
                (targetType == null || row.targetType == targetType) &&
                    (hidden == null || row.hidden == hidden) &&
                    (priority == null || row.priority == priority)
            }
            .sortedWith(
                compareBy<AdminModerationQueueItemResponse> { priorityRank(it.priority) }
                    .thenByDescending { it.hiddenAt ?: it.latestReportCreatedAt ?: it.latestAppealCreatedAt },
            )

        val from = (page * size).coerceAtMost(rows.size)
        val to = (from + size).coerceAtMost(rows.size)
        val pageContent = rows.subList(from, to)
        return PageImpl(pageContent, PageRequest.of(page, size), rows.size.toLong())
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private data class TargetKey(val targetType: ReportTargetType, val targetId: Long)

    private data class HiddenSummary(
        val title: String,
        val preview: String,
        val hiddenAt: LocalDateTime,
        val hiddenReason: String?,
    )

    private data class QueueTargetContext(
        val targetType: ReportTargetType,
        val targetId: Long,
        val title: String,
        val preview: String,
        val hidden: Boolean,
        val hiddenAt: LocalDateTime?,
        val hiddenReason: String?,
    )

    /** 5도메인의 모든 hidden 콘텐츠를 TargetKey 로 묶어 반환. queue 빌드용. */
    private fun collectHiddenByKey(): Map<TargetKey, HiddenSummary> {
        val map = mutableMapOf<TargetKey, HiddenSummary>()
        reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc().forEach { r ->
            val hAt = r.hiddenAt ?: return@forEach
            map[TargetKey(ReportTargetType.REVIEW, r.id)] =
                HiddenSummary("${r.event.title} 후기", r.content.preview(), hAt, r.hiddenReason)
        }
        commentRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc().forEach { c ->
            val hAt = c.hiddenAt ?: return@forEach
            map[TargetKey(ReportTargetType.COMMENT, c.id)] =
                HiddenSummary("댓글", c.content.preview(), hAt, c.hiddenReason)
        }
        postRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc().forEach { p ->
            val hAt = p.hiddenAt ?: return@forEach
            map[TargetKey(ReportTargetType.POST, p.id)] = HiddenSummary(
                p.title.ifBlank { "공지" }, (p.title.ifBlank { p.content }).preview(), hAt, p.hiddenReason,
            )
        }
        eventRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc().forEach { e ->
            val hAt = e.hiddenAt ?: return@forEach
            map[TargetKey(ReportTargetType.EVENT, e.id)] =
                HiddenSummary(e.title, e.title.preview(), hAt, e.hiddenReason)
        }
        channelRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc().forEach { ch ->
            val hAt = ch.hiddenAt ?: return@forEach
            map[TargetKey(ReportTargetType.CHANNEL, ch.id)] =
                HiddenSummary(ch.name, ch.name.preview(), hAt, ch.hiddenReason)
        }
        return map
    }

    /**
     * key 와 3개 source 정보로 queue row 1개를 만든다. hidden 정보 없으면 대상 도메인 단건 조회로
     * title/preview 를 보충 — 대상이 그 사이 삭제됐으면 null 반환 (queue 에서 빠짐).
     */
    private fun buildQueueRow(
        key: TargetKey,
        hiddenSummary: HiddenSummary?,
        latestReport: com.contenido.domain.report.entity.Report?,
        pendingReportCount: Long,
        latestPendingAppeal: com.contenido.domain.report.entity.ReportAppeal?,
    ): AdminModerationQueueItemResponse? {
        val context: QueueTargetContext = if (hiddenSummary != null) {
            QueueTargetContext(
                targetType = key.targetType,
                targetId = key.targetId,
                title = hiddenSummary.title,
                preview = hiddenSummary.preview,
                hidden = true,
                hiddenAt = hiddenSummary.hiddenAt,
                hiddenReason = hiddenSummary.hiddenReason,
            )
        } else {
            // hidden 아님 — 대상 도메인 단건 조회로 title/preview 확보. 대상 삭제됐으면 null.
            runCatching { loadContext(key.targetType, key.targetId) }.getOrNull() ?: return null
        }

        val isAppealPending = latestPendingAppeal != null
        val priority = computePriority(
            hidden = context.hidden,
            isAppealPending = isAppealPending,
            pendingReportCount = pendingReportCount,
            targetType = key.targetType,
        )

        return AdminModerationQueueItemResponse(
            targetType = context.targetType,
            targetId = context.targetId,
            targetTitle = context.title,
            targetPreview = context.preview,
            hidden = context.hidden,
            hiddenAt = context.hiddenAt,
            hiddenReason = context.hiddenReason,
            pendingReportCount = pendingReportCount,
            latestReportId = latestReport?.id,
            latestReportReason = latestReport?.reason,
            latestReportCreatedAt = latestReport?.createdAt,
            latestAppealId = latestPendingAppeal?.id,
            latestAppealStatus = latestPendingAppeal?.status,
            latestAppealReason = latestPendingAppeal?.reason,
            latestAppealCreatedAt = latestPendingAppeal?.createdAt,
            priority = priority,
        )
    }

    /**
     * buildQueueRow 가 hidden 아닌 대상의 title/preview 를 얻기 위한 단건 조회.
     * 대상 미존재 → [ReportTargetNotFoundException] (호출처가 runCatching 으로 swallow).
     */
    private fun loadContext(targetType: ReportTargetType, targetId: Long): QueueTargetContext {
        return when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId)
                .map {
                    QueueTargetContext(
                        targetType, targetId, "${it.event.title} 후기", it.content.preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.COMMENT -> commentRepository.findById(targetId)
                .map {
                    QueueTargetContext(
                        targetType, targetId, "댓글", it.content.preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.POST -> postRepository.findById(targetId)
                .map {
                    QueueTargetContext(
                        targetType, targetId,
                        it.title.ifBlank { "공지" }, (it.title.ifBlank { it.content }).preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.EVENT -> eventRepository.findById(targetId)
                .map {
                    QueueTargetContext(
                        targetType, targetId, it.title, it.title.preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId)
                .map {
                    QueueTargetContext(
                        targetType, targetId, it.name, it.name.preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
        }
    }

    private fun computePriority(
        hidden: Boolean,
        isAppealPending: Boolean,
        pendingReportCount: Long,
        targetType: ReportTargetType,
    ): AdminModerationPriority {
        if (hidden || isAppealPending) return AdminModerationPriority.HIGH
        // PR60 — 운영 가능한 DB 임계치를 사용. DB miss 시 default fallback.
        val threshold = moderationThresholdService.thresholdFor(targetType)
        // 70% 이상이면 MEDIUM. 임계치 도달은 이미 자동 hide 되어 hidden=true 가 되므로 사실상 70~99%.
        val mediumFloor = (threshold * 7 + 9) / 10 // ceil(threshold * 0.7)
        return if (pendingReportCount >= mediumFloor) AdminModerationPriority.MEDIUM
        else AdminModerationPriority.LOW
    }

    /** 정렬용 — HIGH=0 / MEDIUM=1 / LOW=2 (오름차순 = HIGH 가 먼저). */
    private fun priorityRank(p: AdminModerationPriority): Int = when (p) {
        AdminModerationPriority.HIGH -> 0
        AdminModerationPriority.MEDIUM -> 1
        AdminModerationPriority.LOW -> 2
    }

    private fun String.preview(): String {
        val trimmed = trim()
        return if (trimmed.length > PREVIEW_LIMIT) trimmed.substring(0, PREVIEW_LIMIT) + "…" else trimmed
    }
}
