package com.contenido.domain.report.service

import com.contenido.domain.report.dto.CreateReportRequest
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ReportService(
    private val reportRepository: ReportRepository,
    private val userRepository: UserRepository,
) {

    @Transactional
    fun createReport(userId: Long, request: CreateReportRequest): ReportResponse {
        val reporter = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (reporter.isDeleted) throw DeletedUserException()

        val report = reportRepository.save(
            Report(
                reporter = reporter,
                targetType = request.targetType,
                targetId = request.targetId,
                reason = request.reason,
            )
        )

        return report.toResponse()
    }

    // ── private ──────────────────────────────────────────────────────────────

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
