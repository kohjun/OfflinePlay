package com.contenido.domain.report.service

import com.contenido.domain.admin.service.ModerationThresholdService
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.dto.CreateReportRequest
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.ReportAlreadyExistsException
import com.contenido.global.exception.ReportTargetNotFoundException
import com.contenido.global.exception.SelfReportNotAllowedException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 신고 도메인 진입점.
 *
 * PR48 강화:
 *  - targetType 별로 대상 존재 여부 검증 (없으면 [ReportTargetNotFoundException]).
 *  - 본인이 작성/소유한 대상은 신고 차단 ([SelfReportNotAllowedException]).
 *  - 같은 reporter 의 같은 (targetType, targetId) 중복 신고 차단
 *    ([ReportAlreadyExistsException]).
 *
 * 대상 owner 판정:
 *  - POST/COMMENT/REVIEW : author
 *  - CHANNEL             : channel.owner
 *  - EVENT               : event.channel.owner (이벤트 자체 owner 가 없어 채널 owner 로 위임)
 *
 * 자동 비공개/제재/신고 임계치 처리는 본 PR 범위 밖 — 운영자가 Admin 페이지에서 수동 처리.
 */
@Service
@Transactional(readOnly = true)
class ReportService(
    private val reportRepository: ReportRepository,
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
    private val eventRepository: EventRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val reviewRepository: ReviewRepository,
    private val moderationThresholdService: ModerationThresholdService,
) {

    companion object {
        /**
         * PR51 시점 default. PR60 부터 실제 임계치는 DB 의 moderation_threshold_settings (운영 중
         * 변경 가능) 에서 [ModerationThresholdService.thresholdFor] 로 조회한다. 이 상수는
         * fallback/문서화 목적으로 [ModerationThresholdService.DEFAULTS] 와 1:1 매치.
         */
        const val AUTO_HIDE_REASON = "신고 누적 자동 숨김"
    }

    @Transactional
    fun createReport(userId: Long, request: CreateReportRequest): ReportResponse {
        val reporter = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (reporter.isDeleted) throw DeletedUserException()

        // 1. 대상 존재 + 본인 글 여부 확인 — 대상이 없으면 404.
        val targetOwnerId = resolveTargetOwnerId(request.targetType, request.targetId)
        if (targetOwnerId == reporter.id) throw SelfReportNotAllowedException()

        // 2. 같은 reporter 의 같은 (targetType, targetId) 중복 신고 차단.
        if (reportRepository.existsByReporterAndTargetTypeAndTargetId(
                reporter = reporter,
                targetType = request.targetType,
                targetId = request.targetId,
            )
        ) {
            throw ReportAlreadyExistsException()
        }

        val report = reportRepository.save(
            Report(
                reporter = reporter,
                targetType = request.targetType,
                targetId = request.targetId,
                reason = request.reason,
            )
        )

        // 3. PR51 — PENDING 신고가 임계치에 도달하면 대상 자동 숨김.
        //    이미 hidden 인 대상에 추가 신고가 들어와도 entity.hide() 가 no-op.
        maybeAutoHide(request.targetType, request.targetId)

        // createReport 응답에는 target preview 를 노출하지 않는다 — 본인 신고 confirmation 에서
        // 대상 본문을 다시 노출할 필요 없음. Admin 목록(AdminService)에서만 preview 를 채운다.
        return report.toResponse()
    }

    private fun maybeAutoHide(targetType: ReportTargetType, targetId: Long) {
        // PR60 — DB 의 운영 가능한 임계치를 조회. DB miss 시 service 단 default fallback.
        val threshold = moderationThresholdService.thresholdFor(targetType)
        val pendingCount = reportRepository.countByTargetTypeAndTargetIdAndStatus(
            targetType = targetType,
            targetId = targetId,
            status = ReportStatus.PENDING,
        )
        if (pendingCount < threshold) return

        when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId)
                .ifPresent { it.hide(AUTO_HIDE_REASON) }
            ReportTargetType.COMMENT -> commentRepository.findById(targetId)
                .ifPresent { it.hide(AUTO_HIDE_REASON) }
            ReportTargetType.POST -> postRepository.findById(targetId)
                .ifPresent { it.hide(AUTO_HIDE_REASON) }
            ReportTargetType.EVENT -> eventRepository.findById(targetId)
                .ifPresent { it.hide(AUTO_HIDE_REASON) }
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId)
                .ifPresent { it.hide(AUTO_HIDE_REASON) }
        }
    }

    /**
     * targetType + targetId 로 실제 대상을 찾아 owner(=author/owner) userId 를 반환.
     * 대상 미존재면 [ReportTargetNotFoundException].
     */
    private fun resolveTargetOwnerId(targetType: ReportTargetType, targetId: Long): Long {
        return when (targetType) {
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId)
                .orElseThrow { ReportTargetNotFoundException() }
                .owner.id
            ReportTargetType.POST -> postRepository.findById(targetId)
                .orElseThrow { ReportTargetNotFoundException() }
                .author.id
            ReportTargetType.EVENT -> eventRepository.findById(targetId)
                .orElseThrow { ReportTargetNotFoundException() }
                .channel.owner.id
            ReportTargetType.COMMENT -> commentRepository.findById(targetId)
                .orElseThrow { ReportTargetNotFoundException() }
                .author.id
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId)
                .orElseThrow { ReportTargetNotFoundException() }
                .author.id
        }
    }

    private fun Report.toResponse() = ReportResponse(
        id = id,
        reporterNickname = reporter.nickname,
        targetType = targetType,
        targetId = targetId,
        reason = reason,
        status = status,
        createdAt = createdAt,
    )
}
