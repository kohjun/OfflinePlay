package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminHideTargetRequest
import com.contenido.domain.admin.dto.AdminModerationPriority
import com.contenido.domain.admin.dto.AdminModerationQueueItemResponse
import com.contenido.domain.admin.dto.AdminModerationTargetResponse
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.report.service.ReportService
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.global.exception.ReportTargetNotFoundException
import com.contenido.global.exception.TargetAlreadyHiddenException
import com.contenido.global.exception.TargetNotHiddenException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * ADMIN 수동 hide / unhide (PR54).
 *
 * 자동 임계치(PR51) 미달이어도 운영자가 직접 콘텐츠를 숨김 처리하거나 해제할 수 있다.
 * 정책:
 *  - hide/unhide 는 appeal 도메인을 자동으로 변경하지 않는다 — 운영자가 appeal 큐에서
 *    별도 처리한다 (PENDING appeal 이 있어도 수동 hide 가 그 appeal 을 reject 처리하지
 *    않고, 수동 unhide 가 PENDING appeal 을 approve 처리하지도 않는다).
 *  - 이미 hidden 상태에 hide 재호출은 409 [TargetAlreadyHiddenException].
 *  - hidden 이 아닌 대상에 unhide 호출은 409 [TargetNotHiddenException] (PR52 재사용).
 *  - 계정 제재/채널 ban 은 본 PR 범위 밖.
 */
@Service
@Transactional(readOnly = true)
class AdminModerationService(
    private val reviewRepository: ReviewRepository,
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
    private val eventRepository: EventRepository,
    private val channelRepository: ChannelRepository,
    private val reportRepository: ReportRepository,
    private val reportAppealRepository: ReportAppealRepository,
) {

    companion object {
        private const val PREVIEW_LIMIT = 80
    }

    @Transactional
    fun hideTarget(
        targetType: ReportTargetType,
        targetId: Long,
        request: AdminHideTargetRequest,
    ): AdminModerationTargetResponse {
        val ctx = loadContext(targetType, targetId)
        if (ctx.hidden) throw TargetAlreadyHiddenException()
        applyHide(targetType, targetId, request.reason)
        return loadContext(targetType, targetId).toResponse()
    }

    @Transactional
    fun unhideTarget(
        targetType: ReportTargetType,
        targetId: Long,
    ): AdminModerationTargetResponse {
        val ctx = loadContext(targetType, targetId)
        if (!ctx.hidden) throw TargetNotHiddenException()
        applyUnhide(targetType, targetId)
        return loadContext(targetType, targetId).toResponse()
    }

    /**
     * 통합 moderation queue (PR55).
     *
     * 3개 source 의 (targetType, targetId) 키 union → 중복 merge → priority 계산 → 정렬 →
     * filter → page. PENDING report / appeal / hidden 콘텐츠가 한 페이지에서 다 보인다.
     *
     * 비용 관점: 각 source 를 한 번씩만 fetch (PENDING report N + hidden M + PENDING appeal K)
     * 후 메모리에서 merge. 운영 트래픽이 커지면 native group-by + window function 으로 최적화 —
     * 본 PR 은 MVP.
     */
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

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * 대상 fetch + 현재 (title, preview, hidden, hiddenAt, hiddenReason) 추출. 대상 없으면 404.
     * load → 액션 → load 패턴이라 1 트랜잭션 안에서 1차 캐시가 효과. 단순함 위주.
     */
    private fun loadContext(targetType: ReportTargetType, targetId: Long): TargetContext {
        return when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId)
                .map {
                    TargetContext(
                        targetType, targetId, "${it.event.title} 후기", it.content.preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.COMMENT -> commentRepository.findById(targetId)
                .map {
                    TargetContext(
                        targetType, targetId, "댓글", it.content.preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.POST -> postRepository.findById(targetId)
                .map {
                    TargetContext(
                        targetType, targetId,
                        it.title.ifBlank { "공지" }, (it.title.ifBlank { it.content }).preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.EVENT -> eventRepository.findById(targetId)
                .map {
                    TargetContext(
                        targetType, targetId, it.title, it.title.preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId)
                .map {
                    TargetContext(
                        targetType, targetId, it.name, it.name.preview(),
                        it.isHidden, it.hiddenAt, it.hiddenReason,
                    )
                }
                .orElseThrow { ReportTargetNotFoundException() }
        }
    }

    private fun applyHide(targetType: ReportTargetType, targetId: Long, reason: String) {
        when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId).ifPresent { it.hide(reason) }
            ReportTargetType.COMMENT -> commentRepository.findById(targetId).ifPresent { it.hide(reason) }
            ReportTargetType.POST -> postRepository.findById(targetId).ifPresent { it.hide(reason) }
            ReportTargetType.EVENT -> eventRepository.findById(targetId).ifPresent { it.hide(reason) }
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId).ifPresent { it.hide(reason) }
        }
    }

    private fun applyUnhide(targetType: ReportTargetType, targetId: Long) {
        when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId).ifPresent { it.unhide() }
            ReportTargetType.COMMENT -> commentRepository.findById(targetId).ifPresent { it.unhide() }
            ReportTargetType.POST -> postRepository.findById(targetId).ifPresent { it.unhide() }
            ReportTargetType.EVENT -> eventRepository.findById(targetId).ifPresent { it.unhide() }
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId).ifPresent { it.unhide() }
        }
    }

    private fun TargetContext.toResponse(): AdminModerationTargetResponse {
        val pendingReportCount = reportRepository.countByTargetTypeAndTargetIdAndStatus(
            targetType = targetType,
            targetId = targetId,
            status = ReportStatus.PENDING,
        )
        val latestAppeal = reportAppealRepository
            .findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId)
        return AdminModerationTargetResponse(
            targetType = targetType,
            targetId = targetId,
            targetTitle = title,
            targetPreview = preview,
            hidden = hidden,
            hiddenAt = hiddenAt,
            hiddenReason = hiddenReason,
            pendingReportCount = pendingReportCount,
            latestAppealStatus = latestAppeal?.status,
        )
    }

    private fun String.preview(): String {
        val trimmed = trim()
        return if (trimmed.length > PREVIEW_LIMIT) trimmed.substring(0, PREVIEW_LIMIT) + "…" else trimmed
    }

    private data class TargetContext(
        val targetType: ReportTargetType,
        val targetId: Long,
        val title: String,
        val preview: String,
        val hidden: Boolean,
        val hiddenAt: java.time.LocalDateTime?,
        val hiddenReason: String?,
    )

    // ── PR55 queue helpers ───────────────────────────────────────────────────

    private data class TargetKey(val targetType: ReportTargetType, val targetId: Long)

    private data class HiddenSummary(
        val title: String,
        val preview: String,
        val hiddenAt: LocalDateTime,
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
        val context: TargetContext = if (hiddenSummary != null) {
            TargetContext(
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

    private fun computePriority(
        hidden: Boolean,
        isAppealPending: Boolean,
        pendingReportCount: Long,
        targetType: ReportTargetType,
    ): AdminModerationPriority {
        if (hidden || isAppealPending) return AdminModerationPriority.HIGH
        val threshold = ReportService.AUTO_HIDE_THRESHOLDS[targetType] ?: return AdminModerationPriority.LOW
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
}
