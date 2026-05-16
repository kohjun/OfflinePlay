package com.contenido.domain.creator.service

import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.creator.dto.AppealStatusView
import com.contenido.domain.creator.dto.CreatorModerationHiddenItemResponse
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Creator Studio "숨김 처리된 콘텐츠" 섹션의 데이터 진입점 (PR53).
 *
 *  - 본인이 author 인 REVIEW/COMMENT/POST
 *  - 본인이 channel.owner 인 EVENT/CHANNEL
 *
 * 다른 사람의 hidden 콘텐츠가 섞이지 않도록 5개 repository 각각 author/owner 필터된 쿼리만
 * 사용한다. PR52 의 ReportAppeal 도메인에서 최신 appeal 1건을 조회해 row 별 appealStatus 를
 * 결정한다. PENDING report 카운트는 ReportRepository.countByTargetTypeAndTargetIdAndStatus
 * 재사용 (단건 N+1 — hidden 콘텐츠는 흔치 않으므로 허용).
 */
@Service
@Transactional(readOnly = true)
class CreatorModerationService(
    private val userRepository: UserRepository,
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

    fun listMyHidden(userId: Long): List<CreatorModerationHiddenItemResponse> {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()

        val rows = mutableListOf<CreatorModerationHiddenItemResponse>()

        // REVIEW: author 본인.
        reviewRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(user).forEach { r ->
            rows += buildRow(
                user = user,
                targetType = ReportTargetType.REVIEW,
                targetId = r.id,
                title = "${r.event.title} 후기",
                preview = r.content.preview(),
                hiddenAt = r.hiddenAt ?: return@forEach,
                hiddenReason = r.hiddenReason,
            )
        }

        // COMMENT: author 본인.
        commentRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(user).forEach { c ->
            rows += buildRow(
                user = user,
                targetType = ReportTargetType.COMMENT,
                targetId = c.id,
                title = "댓글",
                preview = c.content.preview(),
                hiddenAt = c.hiddenAt ?: return@forEach,
                hiddenReason = c.hiddenReason,
            )
        }

        // POST: author 본인.
        postRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(user).forEach { p ->
            rows += buildRow(
                user = user,
                targetType = ReportTargetType.POST,
                targetId = p.id,
                title = p.title.ifBlank { "공지" },
                preview = (p.title.ifBlank { p.content }).preview(),
                hiddenAt = p.hiddenAt ?: return@forEach,
                hiddenReason = p.hiddenReason,
            )
        }

        // EVENT: 본인이 channel owner.
        eventRepository.findHiddenByChannelOwner(user).forEach { e ->
            rows += buildRow(
                user = user,
                targetType = ReportTargetType.EVENT,
                targetId = e.id,
                title = e.title,
                preview = e.title.preview(),
                hiddenAt = e.hiddenAt ?: return@forEach,
                hiddenReason = e.hiddenReason,
            )
        }

        // CHANNEL: owner 본인.
        channelRepository.findByOwnerAndHiddenAtIsNotNullOrderByHiddenAtDesc(user).forEach { ch ->
            rows += buildRow(
                user = user,
                targetType = ReportTargetType.CHANNEL,
                targetId = ch.id,
                title = ch.name,
                preview = ch.name.preview(),
                hiddenAt = ch.hiddenAt ?: return@forEach,
                hiddenReason = ch.hiddenReason,
            )
        }

        // 5개 도메인 결과를 hiddenAt 내림차순으로 통합.
        return rows.sortedByDescending { it.hiddenAt }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun buildRow(
        user: User,
        targetType: ReportTargetType,
        targetId: Long,
        title: String,
        preview: String,
        hiddenAt: java.time.LocalDateTime,
        hiddenReason: String?,
    ): CreatorModerationHiddenItemResponse {
        val pendingReportCount = reportRepository.countByTargetTypeAndTargetIdAndStatus(
            targetType = targetType,
            targetId = targetId,
            status = ReportStatus.PENDING,
        )
        val latestAppeal = reportAppealRepository
            .findFirstByRequesterAndTargetTypeAndTargetIdOrderByCreatedAtDesc(user, targetType, targetId)
        return CreatorModerationHiddenItemResponse(
            targetType = targetType,
            targetId = targetId,
            targetTitle = title,
            targetPreview = preview,
            hiddenAt = hiddenAt,
            hiddenReason = hiddenReason,
            pendingReportCount = pendingReportCount,
            appealStatus = AppealStatusView.from(latestAppeal?.status),
            appealId = latestAppeal?.id,
        )
    }

    private fun String.preview(): String {
        val trimmed = trim()
        return if (trimmed.length > PREVIEW_LIMIT) trimmed.substring(0, PREVIEW_LIMIT) + "…" else trimmed
    }
}
