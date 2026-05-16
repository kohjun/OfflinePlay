package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminBanChannelRequest
import com.contenido.domain.admin.dto.AdminChannelBanResponse
import com.contenido.domain.admin.dto.AdminHideTargetRequest
import com.contenido.domain.admin.dto.AdminModerationGranularity
import com.contenido.domain.admin.dto.AdminModerationPriority
import com.contenido.domain.admin.dto.AdminModerationQueueItemResponse
import com.contenido.domain.admin.dto.AdminModerationStatsPoint
import com.contenido.domain.admin.dto.AdminModerationStatsResponse
import com.contenido.domain.admin.dto.AdminModerationTargetResponse
import com.contenido.domain.admin.dto.AdminRiskyChannelResponse
import com.contenido.domain.admin.dto.ChannelRiskLevel
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.report.service.ReportService
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.ReportTargetNotFoundException
import com.contenido.global.exception.TargetAlreadyHiddenException
import com.contenido.global.exception.TargetNotHiddenException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
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
    private val notificationService: NotificationService,
    private val moderationThresholdService: ModerationThresholdService,
) {

    companion object {
        private const val PREVIEW_LIMIT = 80

        /** PR57 — Stats 기본 범위 (now - 30days ~ now). */
        const val STATS_DEFAULT_DAYS: Long = 30

        /** 위험 채널 판단 임계치: hidden 콘텐츠 누적 5건 이상이면 RISK, 1~4 면 WATCH. */
        const val CHANNEL_RISK_THRESHOLD: Int = 5

        /** 위험 채널 Top N. */
        const val RISKY_CHANNELS_LIMIT: Int = 5
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
     * 채널 제재 (PR58). hide + deactivate + cascade hide (events/posts/reviews).
     *  - 이미 hidden 이면 [TargetAlreadyHiddenException].
     *  - 채널 미존재 → [ChannelNotFoundException].
     *  - cascade 대상 중 이미 hidden 인 row 는 entity.hide() 가 no-op — 응답 cascade*Count 는
     *    "본 호출에서 새로 숨긴" row 수만 카운트.
     *  - PENDING appeal 은 자동 reject 하지 않음 — 운영자가 appeal 큐에서 별도 처리 (PR54 일관).
     *  - COMMENT cascade 는 본 PR 범위 밖 — channel 매핑이 복잡(targetType=EVENT/POST/COMMENT).
     *    후속 PR.
     */
    @Transactional
    fun banChannelForModeration(channelId: Long, request: AdminBanChannelRequest): AdminChannelBanResponse {
        val channel = channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }
        if (channel.isHidden) throw TargetAlreadyHiddenException()

        // 1. 채널 자체 hide + deactivate.
        channel.hide(request.reason)
        channel.deactivate()

        // 2. cascade — 이미 hidden 인 row 는 카운트 제외.
        val events = eventRepository.findByChannel(channel)
        var eventCount = 0
        events.forEach {
            if (!it.isHidden) {
                it.hide(request.reason); eventCount++
            }
        }
        val posts = postRepository.findByChannel(channel)
        var postCount = 0
        posts.forEach {
            if (!it.isHidden) {
                it.hide(request.reason); postCount++
            }
        }
        val reviews = reviewRepository.findByEventChannelId(channelId)
        var reviewCount = 0
        reviews.forEach {
            if (!it.isHidden) {
                it.hide(request.reason); reviewCount++
            }
        }

        // PR59 — channel.owner 에게 즉시 알림. cascade 영향 카운트를 message 에 포함.
        // notification 실패가 ban 트랜잭션을 깨뜨리지 않도록 runCatching (기존 패턴).
        runCatching {
            notificationService.notify(
                receiverIds = listOf(channel.owner.id),
                type = NotificationType.CHANNEL_BANNED,
                title = "채널이 운영 정책으로 숨김 처리되었습니다.",
                message = buildBanMessage(
                    channelName = channel.name,
                    reason = request.reason,
                    eventCount = eventCount,
                    postCount = postCount,
                    reviewCount = reviewCount,
                ),
                targetType = "channels",
                targetId = channel.id,
            )
        }

        return AdminChannelBanResponse(
            channelId = channel.id,
            channelName = channel.name,
            isActive = channel.isActive,
            hidden = channel.isHidden,
            hiddenAt = channel.hiddenAt,
            hiddenReason = channel.hiddenReason,
            cascadedEventCount = eventCount,
            cascadedPostCount = postCount,
            cascadedReviewCount = reviewCount,
        )
    }

    /**
     * 채널 제재 해제 (PR58). unhide + activate. **소속 콘텐츠는 자동 unhide 하지 않는다** —
     * 채널 제재 해제와 개별 콘텐츠 안전성은 별도 판단. 개별 콘텐츠는 PR54 의 [unhideTarget] 으로
     * 따로 처리.
     */
    @Transactional
    fun unbanChannelForModeration(channelId: Long): AdminChannelBanResponse {
        val channel = channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }
        if (!channel.isHidden) throw TargetNotHiddenException()

        channel.unhide()
        channel.activate()

        // PR59 — owner 에게 해제 사실 알림. 소속 콘텐츠는 자동 복구되지 않음을 message 에 안내.
        runCatching {
            notificationService.notify(
                receiverIds = listOf(channel.owner.id),
                type = NotificationType.CHANNEL_UNBANNED,
                title = "채널 숨김이 해제되었어요.",
                message = "“${channel.name}” 채널이 다시 활성화됐어요. " +
                    "이전에 숨김 처리됐던 이벤트/공지/후기는 자동으로 복구되지 않으니 필요한 항목은 " +
                    "운영팀에 문의해주세요.",
                targetType = "channels",
                targetId = channel.id,
            )
        }

        return AdminChannelBanResponse(
            channelId = channel.id,
            channelName = channel.name,
            isActive = channel.isActive,
            hidden = channel.isHidden,
            hiddenAt = channel.hiddenAt,
            hiddenReason = channel.hiddenReason,
            cascadedEventCount = 0,
            cascadedPostCount = 0,
            cascadedReviewCount = 0,
        )
    }

    /** PR59 — ban 알림 message 빌더. cascade 카운트가 0 이면 해당 토큰 제외. */
    private fun buildBanMessage(
        channelName: String,
        reason: String,
        eventCount: Int,
        postCount: Int,
        reviewCount: Int,
    ): String {
        val cascadeTokens = listOfNotNull(
            ("이벤트 ${eventCount}개").takeIf { eventCount > 0 },
            ("공지 ${postCount}개").takeIf { postCount > 0 },
            ("후기 ${reviewCount}개").takeIf { reviewCount > 0 },
        )
        val cascadeSuffix = if (cascadeTokens.isEmpty()) ""
        else " ${cascadeTokens.joinToString(", ")}가 함께 숨김 처리됐어요."
        return "“${channelName}” 채널이 운영 정책 위반으로 숨김 처리됐어요 (사유: ${reason}).$cascadeSuffix " +
            "이의 제기는 마이페이지 > 내 이의 제기 또는 크리에이터 스튜디오 > 숨김 처리된 콘텐츠에서 신청할 수 있어요."
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

    /**
     * 운영 지표 (PR57). day granularity 시계열 + totals + 위험 채널 Top 5.
     *
     * 집계 방식:
     *  - reports.createdAt 범위 → reportCount per day.
     *  - report_appeals.createdAt 범위 → appealSubmittedCount per day.
     *  - report_appeals.reviewedAt 범위 + status APPROVED/REJECTED → approved/rejected per day.
     *  - 5도메인 entity.hiddenAt 범위 → hide count.
     *    hiddenReason 이 [ReportService.AUTO_HIDE_REASON] 이면 autoHideCount, 아니면 manualHide.
     *  - 위험 채널: 현재 hidden 인 5도메인 row 를 channel 로 매핑 후 그룹 합산.
     *    REVIEW.event.channel / POST.channel / EVENT.channel / CHANNEL 자체. COMMENT 는 channel
     *    매핑이 복잡(targetType=EVENT/POST/COMMENT) 해서 본 MVP 에서는 제외 — 후속.
     */
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

    // ── PR57 stats helpers ──────────────────────────────────────────────────

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
