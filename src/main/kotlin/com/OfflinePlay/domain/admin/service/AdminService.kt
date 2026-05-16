package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminChannelResponse
import com.contenido.domain.admin.dto.AdminUserResponse
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AlreadyBannedException
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.ReportAlreadyProcessedException
import com.contenido.global.exception.ReportNotFoundException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminService(
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
    private val reportRepository: ReportRepository,
    // PR48 — Admin 의 신고 목록 응답에 target preview 를 채우기 위함.
    private val eventRepository: EventRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val reviewRepository: ReviewRepository,
) {

    companion object {
        private const val PREVIEW_LIMIT = 80
    }

    fun getUsers(page: Int, size: Int): Page<AdminUserResponse> =
        userRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
            .map { it.toAdminResponse() }

    fun getUser(userId: Long): AdminUserResponse =
        userRepository.findById(userId).orElseThrow { UserNotFoundException() }.toAdminResponse()

    @Transactional
    fun banUser(userId: Long): AdminUserResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw AlreadyBannedException()
        user.softDelete()
        return user.toAdminResponse()
    }

    fun getChannels(page: Int, size: Int): Page<AdminChannelResponse> =
        channelRepository.findAll(PageRequest.of(page, size))
            .map { it.toAdminResponse() }

    @Transactional
    fun banChannel(channelId: Long): AdminChannelResponse {
        val channel = channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }
        if (!channel.isActive) throw AlreadyBannedException()
        channel.deactivate()
        return channel.toAdminResponse()
    }

    /**
     * 신고 목록. targetType 파라미터가 유효한 enum 이면 해당 타입만 필터, 잘못된 값/null 이면
     * 전체. PR48: REVIEW 등 타입별 빠른 컨텍스트가 필요할 때 사용.
     */
    fun getReports(page: Int, size: Int, targetType: String?): Page<ReportResponse> {
        val pageable = PageRequest.of(page, size)
        val parsed = targetType?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ReportTargetType.valueOf(it) }.getOrNull() }
        val reports = if (parsed != null) {
            reportRepository.findByTargetTypeOrderByCreatedAtDesc(parsed, pageable)
        } else {
            reportRepository.findAllByOrderByCreatedAtDesc(pageable)
        }
        return reports.map { it.toResponseWithPreview() }
    }

    @Transactional
    fun resolveReport(reportId: Long): ReportResponse {
        val report = findPendingReport(reportId)
        report.resolve()
        return report.toResponseWithPreview()
    }

    @Transactional
    fun dismissReport(reportId: Long): ReportResponse {
        val report = findPendingReport(reportId)
        report.dismiss()
        return report.toResponseWithPreview()
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findPendingReport(reportId: Long): Report {
        val report = reportRepository.findById(reportId).orElseThrow { ReportNotFoundException() }
        if (!report.isPending) throw ReportAlreadyProcessedException()
        return report
    }

    private fun User.toAdminResponse() = AdminUserResponse(
        id = id,
        email = email,
        nickname = nickname,
        role = role,
        isDeleted = isDeleted,
        createdAt = createdAt,
    )

    private fun Channel.toAdminResponse() = AdminChannelResponse(
        id = id,
        name = name,
        ownerNickname = owner.nickname,
        category = category,
        categoryDisplayName = category.displayName,
        subscriberCount = subscriberCount,
        isActive = isActive,
        createdAt = createdAt,
    )

    /**
     * PR48 — 신고 응답에 targetPreview / targetRating 을 채워서 Admin 페이지에서 맥락을 바로
     * 볼 수 있게 한다. 대상이 이미 삭제됐으면 두 필드 모두 null (신고 자체는 유지).
     *
     * 비용: 신고 1건마다 단건 조회 1회 — 페이지당 최대 size 번. 신고는 흔치 않은 트래픽이라
     * batch 최적화는 트래픽 증가 후 별도 PR.
     */
    private fun Report.toResponseWithPreview(): ReportResponse {
        val (preview, rating) = resolveTargetContext(targetType, targetId)
        return ReportResponse(
            id = id,
            reporterNickname = reporter.nickname,
            targetType = targetType,
            targetId = targetId,
            reason = reason,
            status = status,
            createdAt = createdAt,
            targetPreview = preview,
            targetRating = rating,
        )
    }

    private fun resolveTargetContext(
        targetType: ReportTargetType,
        targetId: Long,
    ): Pair<String?, Int?> {
        return when (targetType) {
            ReportTargetType.CHANNEL -> channelRepository.findById(targetId)
                .map<Pair<String?, Int?>> { it.name.preview() to null }
                .orElse(null to null)
            ReportTargetType.POST -> postRepository.findById(targetId)
                .map<Pair<String?, Int?>> { (it.title.ifBlank { it.content }).preview() to null }
                .orElse(null to null)
            ReportTargetType.EVENT -> eventRepository.findById(targetId)
                .map<Pair<String?, Int?>> { it.title.preview() to null }
                .orElse(null to null)
            ReportTargetType.COMMENT -> commentRepository.findById(targetId)
                .map<Pair<String?, Int?>> { it.content.preview() to null }
                .orElse(null to null)
            ReportTargetType.REVIEW -> reviewRepository.findById(targetId)
                .map<Pair<String?, Int?>> { it.content.preview() to it.rating }
                .orElse(null to null)
        }
    }

    private fun String.preview(): String {
        val trimmed = trim()
        return if (trimmed.length > PREVIEW_LIMIT) trimmed.substring(0, PREVIEW_LIMIT) + "…" else trimmed
    }
}
