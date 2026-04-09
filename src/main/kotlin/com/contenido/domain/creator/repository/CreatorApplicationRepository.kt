package com.contenido.domain.creator.repository

import com.contenido.domain.creator.entity.ApplicationStatus
import com.contenido.domain.creator.entity.CreatorApplication
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CreatorApplicationRepository : JpaRepository<CreatorApplication, Long> {
    fun findByApplicantAndStatus(applicant: User, status: ApplicationStatus): CreatorApplication?
    fun existsByApplicantAndStatus(applicant: User, status: ApplicationStatus): Boolean
    fun findByStatus(status: ApplicationStatus, pageable: Pageable): Page<CreatorApplication>
    fun findTopByApplicantOrderByCreatedAtDesc(applicant: User): CreatorApplication?
}
