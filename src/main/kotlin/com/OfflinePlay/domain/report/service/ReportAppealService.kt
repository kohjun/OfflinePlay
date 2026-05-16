package com.contenido.domain.report.service

import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.dto.CreateReportAppealRequest
import com.contenido.domain.report.dto.ReportAppealResponse
import com.contenido.domain.report.dto.ReviewReportAppealRequest
import com.contenido.domain.report.entity.ReportAppeal
import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AppealAlreadyExistsException
import com.contenido.global.exception.AppealNotAllowedException
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.ReportAppealAlreadyProcessedException
import com.contenido.global.exception.ReportAppealNotFoundException
import com.contenido.global.exception.ReportTargetNotFoundException
import com.contenido.global.exception.TargetNotHiddenException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 자동 숨김 대상에 대한 이의 제기(appeal) 도메인 (PR52).
 *
 * 정책:
 *  - 사용자: 본인이 작성/소유한 hidden 대상에 한해 appeal 생성 가능.
 *  - 같은 (requester, target) PENDING appeal 1건 제한 (중복 차단).
 *  - ADMIN approve: 대상 unhide + appeal APPROVED. 신고 row 자체는 RESOLVED/DISMISSED 등으로
 *    자동 전환하지 않는다 — 운영자가 신고 큐에서 별도 처리. (Admin context 가 appeal 승인 사실을
 *    노출하므로 별도 카드 액션 없이도 충분.)
 *  - ADMIN reject: hidden 유지 + rejectReason 보존.
 *
 * 본 PR 범위 밖 (TODO):
 *  - 작성자에게 hidden 사실 인지/CTA 노출 (MyPage 진입 외 자동 알림)
 *  - 신고자 패널티 / 자동 unhide 카운터 리셋 정책
 *  - appeal 거절 후 일정 기간 재신청 제한
 */
@Service
@Transactional(readOnly = true)
class ReportAppealService(
    private val reportAppealRepository: ReportAppealRepository,
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
    private val eventRepository: EventRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val reviewRepository: ReviewRepository,
) {

    companion object {
        private const val PREVIEW_LIMIT = 80
    }

    @Transactional
    fun createAppeal(userId: Long, request: CreateReportAppealRequest): ReportAppealResponse {
        val requester = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (requester.isDeleted) throw DeletedUserException()

        // 1. 대상 존재 + hidden 상태 + 본인이 작성/소유 여부 검증.
        val verdict = verifyTarget(request.targetType, request.targetId, requester)

        // 2. 같은 requester 의 같은 target 에 PENDING appeal 있으면 409.
        if (reportAppealRepository.existsByRequesterAndTargetTypeAndTargetIdAndStatus(
                requester = requester,
                targetType = request.targetType,
                targetId = request.targetId,
                status = ReportAppealStatus.PENDING,
            )
        ) {
            throw AppealAlreadyExistsException()
        }

        val appeal = reportAppealRepository.save(
            ReportAppeal(
                targetType = request.targetType,
                targetId = request.targetId,
                requester = requester,
                reason = request.reason,
            )
        )
        return appeal.toResponse(targetPreview = verdict.preview, targetHidden = true)
    }

    /** 사용자 본인 appeal 목록. */
    fun listMyAppeals(userId: Long, page: Int, size: Int): Page<ReportAppealResponse> {
        val requester = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        return reportAppealRepository
            .findByRequesterOrderByCreatedAtDesc(requester, PageRequest.of(page, size))
            .map { it.toResponse(targetPreview = null, targetHidden = isTargetHidden(it.targetType, it.targetId)) }
    }

    /** ADMIN appeal 큐 — status null 이면 전체, 아니면 해당 상태만. */
    fun listAppealsForAdmin(page: Int, size: Int, status: String?): Page<ReportAppealResponse> {
        val pageable = PageRequest.of(page, size)
        val parsed = status?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ReportAppealStatus.valueOf(it) }.getOrNull() }
        val appeals = if (parsed != null) {
            reportAppealRepository.findByStatusOrderByCreatedAtDesc(parsed, pageable)
        } else {
            reportAppealRepository.findAllByOrderByCreatedAtDesc(pageable)
        }
        return appeals.map {
            it.toResponse(
                targetPreview = resolvePreview(it.targetType, it.targetId),
                targetHidden = isTargetHidden(it.targetType, it.targetId),
            )
        }
    }

    @Transactional
    fun approveAppeal(adminUserId: Long, appealId: Long): ReportAppealResponse {
        val admin = userRepository.findById(adminUserId).orElseThrow { UserNotFoundException() }
        val appeal = findPendingAppeal(appealId)
        // 대상이 그 사이 삭제됐을 수 있다 — unhide 시도하되 대상이 없으면 graceful skip.
        unhideTarget(appeal.targetType, appeal.targetId)
        appeal.approve(admin)
        return appeal.toResponse(
            targetPreview = resolvePreview(appeal.targetType, appeal.targetId),
            targetHidden = isTargetHidden(appeal.targetType, appeal.targetId),
        )
    }

    @Transactional
    fun rejectAppeal(adminUserId: Long, appealId: Long, request: ReviewReportAppealRequest): ReportAppealResponse {
        val admin = userRepository.findById(adminUserId).orElseThrow { UserNotFoundException() }
        val appeal = findPendingAppeal(appealId)
        appeal.reject(admin, request.rejectReason)
        return appeal.toResponse(
            targetPreview = resolvePreview(appeal.targetType, appeal.targetId),
            targetHidden = isTargetHidden(appeal.targetType, appeal.targetId),
        )
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findPendingAppeal(appealId: Long): ReportAppeal {
        val appeal = reportAppealRepository.findById(appealId)
            .orElseThrow { ReportAppealNotFoundException() }
        if (!appeal.isPending) throw ReportAppealAlreadyProcessedException()
        return appeal
    }

    /**
     * 대상의 작성자/소유자 검증 + 현재 hidden 상태 검증 + preview 추출을 한 번에.
     *  - 대상 없음 → 404
     *  - hidden=false → 400
     *  - owner != requester → 403
     */
    private fun verifyTarget(
        targetType: ReportTargetType,
        targetId: Long,
        requester: User,
    ): TargetVerdict {
        return when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId)
                .map { TargetVerdict(it.author.id, it.isHidden, it.content.preview()) }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.COMMENT -> commentRepository.findById(targetId)
                .map { TargetVerdict(it.author.id, it.isHidden, it.content.preview()) }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.POST -> postRepository.findById(targetId)
                .map { TargetVerdict(it.author.id, it.isHidden, (it.title.ifBlank { it.content }).preview()) }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.EVENT -> eventRepository.findById(targetId)
                .map { TargetVerdict(it.channel.owner.id, it.isHidden, it.title.preview()) }
                .orElseThrow { ReportTargetNotFoundException() }
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId)
                .map { TargetVerdict(it.owner.id, it.isHidden, it.name.preview()) }
                .orElseThrow { ReportTargetNotFoundException() }
        }.also { v ->
            if (!v.hidden) throw TargetNotHiddenException()
            if (v.ownerId != requester.id) throw AppealNotAllowedException()
        }
    }

    private fun unhideTarget(targetType: ReportTargetType, targetId: Long) {
        when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId).ifPresent { it.unhide() }
            ReportTargetType.COMMENT -> commentRepository.findById(targetId).ifPresent { it.unhide() }
            ReportTargetType.POST -> postRepository.findById(targetId).ifPresent { it.unhide() }
            ReportTargetType.EVENT -> eventRepository.findById(targetId).ifPresent { it.unhide() }
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId).ifPresent { it.unhide() }
        }
    }

    private fun resolvePreview(targetType: ReportTargetType, targetId: Long): String? {
        return when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId).map { it.content.preview() }.orElse(null)
            ReportTargetType.COMMENT -> commentRepository.findById(targetId).map { it.content.preview() }.orElse(null)
            ReportTargetType.POST -> postRepository.findById(targetId)
                .map { (it.title.ifBlank { it.content }).preview() }.orElse(null)
            ReportTargetType.EVENT -> eventRepository.findById(targetId).map { it.title.preview() }.orElse(null)
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId).map { it.name.preview() }.orElse(null)
        }
    }

    private fun isTargetHidden(targetType: ReportTargetType, targetId: Long): Boolean {
        return when (targetType) {
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId).map { it.isHidden }.orElse(false)
            ReportTargetType.COMMENT -> commentRepository.findById(targetId).map { it.isHidden }.orElse(false)
            ReportTargetType.POST -> postRepository.findById(targetId).map { it.isHidden }.orElse(false)
            ReportTargetType.EVENT -> eventRepository.findById(targetId).map { it.isHidden }.orElse(false)
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId).map { it.isHidden }.orElse(false)
        }
    }

    private fun ReportAppeal.toResponse(targetPreview: String?, targetHidden: Boolean): ReportAppealResponse =
        ReportAppealResponse(
            id = id,
            targetType = targetType,
            targetId = targetId,
            requesterId = requester.id,
            requesterNickname = requester.nickname,
            reason = reason,
            status = status,
            rejectReason = rejectReason,
            createdAt = createdAt,
            reviewedAt = reviewedAt,
            targetPreview = targetPreview,
            targetHidden = targetHidden,
        )

    private fun String.preview(): String {
        val trimmed = trim()
        return if (trimmed.length > PREVIEW_LIMIT) trimmed.substring(0, PREVIEW_LIMIT) + "…" else trimmed
    }

    private data class TargetVerdict(val ownerId: Long, val hidden: Boolean, val preview: String)
}
