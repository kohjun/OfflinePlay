package com.contenido.domain.creator.service

import com.contenido.domain.creator.entity.ApplicationStatus
import com.contenido.domain.creator.entity.CreatorApplication
import com.contenido.domain.creator.repository.CreatorApplicationRepository
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AlreadyCreatorException
import com.contenido.global.exception.DuplicateApplicationException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CreatorApplicationService(
    private val applicationRepository: CreatorApplicationRepository,
    private val userRepository: UserRepository
) {

    @Transactional
    fun apply(userId: Long, reason: String, portfolioUrl: String?) {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }

        if (user.role == UserRole.CREATOR) {
            throw AlreadyCreatorException()
        }

        if (applicationRepository.existsByApplicantAndStatus(user, ApplicationStatus.PENDING)) {
            throw DuplicateApplicationException()
        }

        val application = CreatorApplication(
            applicant = user,
            reason = reason,
            portfolioUrl = portfolioUrl
        )
        applicationRepository.save(application)
    }

    @Transactional
fun approve(adminUserId: Long, applicationId: Long) {
    val admin = userRepository.findById(adminUserId)
        .orElseThrow { UserNotFoundException() }
        
    val application = applicationRepository.findById(applicationId)
        .orElseThrow { IllegalArgumentException("신청 내역이 없습니다.") }

    application.approve(admin)
    
    // application.applicant 는 User 엔티티이므로 updateRole 호출 가능
    application.applicant.updateRole(UserRole.CREATOR)
}

    @Transactional
    fun reject(adminUserId: Long, applicationId: Long, rejectReason: String?) {
        val admin = userRepository.findById(adminUserId).orElseThrow { UserNotFoundException() }
        val application = applicationRepository.findById(applicationId)
            .orElseThrow { IllegalArgumentException("신청 내역이 존재하지 않습니다.") }

        application.reject(admin)
        
        // TODO: 알림 전송 로직 (Phase 15 비동기 알림에서 구현)
    }
}