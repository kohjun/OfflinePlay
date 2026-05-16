package com.contenido.domain.report.service

import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.dto.CreateReportRequest
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.domain.report.entity.Report
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
) {

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

        // createReport 응답에는 target preview 를 노출하지 않는다 — 본인 신고 confirmation 에서
        // 대상 본문을 다시 노출할 필요 없음. Admin 목록(AdminService)에서만 preview 를 채운다.
        return report.toResponse()
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
