package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminBanChannelRequest
import com.contenido.domain.admin.dto.AdminChannelBanResponse
import com.contenido.domain.admin.dto.AdminHideTargetRequest
import com.contenido.domain.admin.dto.AdminModerationGranularity
import com.contenido.domain.admin.dto.AdminModerationPriority
import com.contenido.domain.admin.dto.AdminModerationQueueItemResponse
import com.contenido.domain.admin.dto.AdminModerationStatsResponse
import com.contenido.domain.admin.dto.AdminModerationTargetResponse
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.global.exception.ReportTargetNotFoundException
import com.contenido.global.exception.TargetAlreadyHiddenException
import com.contenido.global.exception.TargetNotHiddenException
import org.springframework.data.domain.Page
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * ADMIN 수동 hide / unhide (PR54) + 채널 제재/queue/stats facade.
 *
 * PR54 기본 정책 (변경 없음):
 *  - hide/unhide 는 appeal 도메인을 자동으로 변경하지 않는다 — 운영자가 appeal 큐에서
 *    별도 처리한다 (PENDING appeal 이 있어도 수동 hide 가 그 appeal 을 reject 처리하지
 *    않고, 수동 unhide 가 PENDING appeal 을 approve 처리하지도 않는다).
 *  - 이미 hidden 상태에 hide 재호출은 409 [TargetAlreadyHiddenException].
 *  - hidden 이 아닌 대상에 unhide 호출은 409 [TargetNotHiddenException] (PR52 재사용).
 *  - 계정 제재/채널 ban 은 [AdminChannelBanService] 가 담당 (PR87 에서 분리).
 *
 * PR87 — 책임 분리:
 *  - 채널 ban/unban  → [AdminChannelBanService]
 *  - moderation queue → [AdminModerationQueueService]
 *  - moderation stats → [AdminModerationStatsService]
 *  본 클래스는 hide/unhide 의 직접 구현 + 위 3개의 facade 역할만 한다. 외부에서 보던
 *  `AdminModerationService` public method 시그니처는 변경되지 않으므로 컨트롤러/테스트
 *  호출부는 그대로 유지된다.
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
    private val moderationAuditLogService: ModerationAuditLogService,
    private val channelBanService: AdminChannelBanService,
    private val queueService: AdminModerationQueueService,
    private val statsService: AdminModerationStatsService,
) {

    companion object {
        private const val PREVIEW_LIMIT = 80
    }

    @Transactional
    fun hideTarget(
        actorId: Long,
        targetType: ReportTargetType,
        targetId: Long,
        request: AdminHideTargetRequest,
    ): AdminModerationTargetResponse {
        val ctx = loadContext(targetType, targetId)
        if (ctx.hidden) throw TargetAlreadyHiddenException()
        applyHide(targetType, targetId, request.reason)
        // PR61 — 같은 트랜잭션에 audit. record 가 실패하면 hide 도 rollback.
        moderationAuditLogService.record(
            actorId = actorId,
            action = ModerationAuditAction.TARGET_HIDDEN,
            targetType = targetType,
            targetId = targetId,
            reason = request.reason,
        )
        return loadContext(targetType, targetId).toResponse()
    }

    @Transactional
    fun unhideTarget(
        actorId: Long,
        targetType: ReportTargetType,
        targetId: Long,
    ): AdminModerationTargetResponse {
        val ctx = loadContext(targetType, targetId)
        if (!ctx.hidden) throw TargetNotHiddenException()
        applyUnhide(targetType, targetId)
        moderationAuditLogService.record(
            actorId = actorId,
            action = ModerationAuditAction.TARGET_UNHIDDEN,
            targetType = targetType,
            targetId = targetId,
            // unhide 자체에는 입력 reason 이 없으므로 직전 hide 의 사유를 컨텍스트로 남긴다.
            reason = ctx.hiddenReason,
        )
        return loadContext(targetType, targetId).toResponse()
    }

    // ── PR87 facade: 채널 ban/unban 위임 ─────────────────────────────────────

    @Transactional
    fun banChannelForModeration(
        actorId: Long,
        channelId: Long,
        request: AdminBanChannelRequest,
    ): AdminChannelBanResponse =
        channelBanService.banChannelForModeration(actorId, channelId, request)

    @Transactional
    fun unbanChannelForModeration(actorId: Long, channelId: Long): AdminChannelBanResponse =
        channelBanService.unbanChannelForModeration(actorId, channelId)

    // ── PR87 facade: queue / stats 위임 ─────────────────────────────────────

    fun getQueue(
        page: Int,
        size: Int,
        targetType: ReportTargetType?,
        hidden: Boolean?,
        priority: AdminModerationPriority?,
    ): Page<AdminModerationQueueItemResponse> =
        queueService.getQueue(page, size, targetType, hidden, priority)

    fun getStats(
        from: LocalDateTime?,
        to: LocalDateTime?,
        granularity: AdminModerationGranularity?,
    ): AdminModerationStatsResponse =
        statsService.getStats(from, to, granularity)

    // ── private hide/unhide helpers ──────────────────────────────────────────

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
        val hiddenAt: LocalDateTime?,
        val hiddenReason: String?,
    )
}
