package com.contenido.domain.creator.service

import com.contenido.domain.creator.dto.CreatorApplicationResponse
import com.contenido.domain.creator.entity.ApplicationStatus
import com.contenido.domain.creator.entity.CreatorApplication
import com.contenido.domain.creator.repository.CreatorApplicationRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AlreadyCreatorException
import com.contenido.global.exception.DuplicateApplicationException
import com.contenido.global.exception.UserNotFoundException
import com.contenido.global.response.PageResponse
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CreatorApplicationService(
    private val applicationRepository: CreatorApplicationRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
) {

    @Transactional
    fun apply(userId: Long, reason: String, portfolioUrl: String?) {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }

        if (user.role == UserRole.CREATOR) throw AlreadyCreatorException()

        if (applicationRepository.existsByApplicantAndStatus(user, ApplicationStatus.PENDING)) {
            throw DuplicateApplicationException()
        }

        applicationRepository.save(
            CreatorApplication(applicant = user, reason = reason, portfolioUrl = portfolioUrl)
        )
    }

    fun getMyApplication(userId: Long): CreatorApplicationResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val application = applicationRepository.findTopByApplicantOrderByCreatedAtDesc(user)
            ?: throw IllegalStateException("신청 내역이 없습니다.")
        return application.toResponse()
    }

    fun getPendingApplications(page: Int, size: Int): PageResponse<CreatorApplicationResponse> {
        val result = applicationRepository.findByStatus(ApplicationStatus.PENDING, PageRequest.of(page, size))
            .map { it.toResponse() }
        return PageResponse.of(result)
    }

    @Transactional
    fun approve(adminUserId: Long, applicationId: Long) {
        val admin = userRepository.findById(adminUserId).orElseThrow { UserNotFoundException() }
        val application = applicationRepository.findById(applicationId)
            .orElseThrow { IllegalArgumentException("신청 내역이 없습니다.") }

        application.approve(admin)
        application.applicant.updateRole(UserRole.CREATOR)

        runCatching {
            notificationService.notify(
                receiverIds = listOf(application.applicant.id),
                type = NotificationType.APPLICATION_APPROVED,
                title = "크리에이터 신청 결과",
                message = "크리에이터 신청이 승인되었습니다. 이제 채널을 만들 수 있습니다.",
                targetType = "creator-applications",
                targetId = application.id,
            )
        }
    }

    @Transactional
    fun reject(adminUserId: Long, applicationId: Long, rejectReason: String?) {
        val admin = userRepository.findById(adminUserId).orElseThrow { UserNotFoundException() }
        val application = applicationRepository.findById(applicationId)
            .orElseThrow { IllegalArgumentException("신청 내역이 존재하지 않습니다.") }

        application.reject(admin)

        runCatching {
            notificationService.notify(
                receiverIds = listOf(application.applicant.id),
                type = NotificationType.APPLICATION_REJECTED,
                title = "크리에이터 신청 결과",
                message = "크리에이터 신청이 거절되었습니다.",
                targetType = "creator-applications",
                targetId = application.id,
            )
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun CreatorApplication.toResponse() = CreatorApplicationResponse(
        id = id,
        applicantNickname = applicant.nickname,
        reason = reason,
        portfolioUrl = portfolioUrl,
        status = status,
        createdAt = createdAt,
        reviewedAt = reviewedAt,
    )
}
