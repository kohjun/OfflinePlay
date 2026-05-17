package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminBanChannelRequest
import com.contenido.domain.admin.dto.AdminChannelBanResponse
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.TargetAlreadyHiddenException
import com.contenido.global.exception.TargetNotHiddenException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR87 — `AdminModerationService` 에서 채널 제재(ban) / 해제(unban) 책임을 분리한 서비스.
 * 동작은 PR58 그대로 유지하며 단순 mechanical move 다. 호출은 `AdminModerationService` 가
 * facade 로 위임하므로 외부 호출처(컨트롤러/테스트) 변경은 없다.
 *
 * 정책 (변경 없음):
 *  - 이미 hidden 인 채널 ban 재시도 → [TargetAlreadyHiddenException]
 *  - 채널 미존재 → [ChannelNotFoundException]
 *  - cascade 대상 중 이미 hidden 인 row 는 entity.hide() 가 no-op — 응답 cascade*Count 는
 *    "본 호출에서 새로 숨긴" row 수만 카운트
 *  - PENDING appeal 은 자동 reject 하지 않음 — 운영자가 appeal 큐에서 별도 처리 (PR54 일관)
 *  - COMMENT cascade 는 본 PR 범위 밖
 */
@Service
@Transactional(readOnly = true)
class AdminChannelBanService(
    private val channelRepository: ChannelRepository,
    private val eventRepository: EventRepository,
    private val postRepository: PostRepository,
    private val reviewRepository: ReviewRepository,
    private val notificationService: NotificationService,
    private val moderationAuditLogService: ModerationAuditLogService,
) {

    @Transactional
    fun banChannelForModeration(
        actorId: Long,
        channelId: Long,
        request: AdminBanChannelRequest,
    ): AdminChannelBanResponse {
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

        // PR61 — ban 은 cascade 영향이 커서 카운트를 audit after 에 그대로 보존한다.
        moderationAuditLogService.record(
            actorId = actorId,
            action = ModerationAuditAction.CHANNEL_BANNED,
            targetType = ReportTargetType.CHANNEL,
            targetId = channel.id,
            afterValue = mapOf(
                "cascadedEventCount" to eventCount,
                "cascadedPostCount" to postCount,
                "cascadedReviewCount" to reviewCount,
            ),
            reason = request.reason,
        )

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
     * 채널 제재 해제와 개별 콘텐츠 안전성은 별도 판단. 개별 콘텐츠는 PR54 의 [AdminModerationService.unhideTarget] 으로
     * 따로 처리.
     */
    @Transactional
    fun unbanChannelForModeration(actorId: Long, channelId: Long): AdminChannelBanResponse {
        val channel = channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }
        if (!channel.isHidden) throw TargetNotHiddenException()

        // 해제 직전 사유 보존 — audit 컨텍스트.
        val priorReason = channel.hiddenReason

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

        moderationAuditLogService.record(
            actorId = actorId,
            action = ModerationAuditAction.CHANNEL_UNBANNED,
            targetType = ReportTargetType.CHANNEL,
            targetId = channel.id,
            reason = priorReason,
        )

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
}
