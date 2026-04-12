package com.contenido.domain.report.dto

import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateReportRequest(
    @field:NotNull(message = "신고 대상 타입은 필수입니다.")
    val targetType: ReportTargetType,

    @field:NotNull(message = "신고 대상 ID는 필수입니다.")
    val targetId: Long,

    @field:NotBlank(message = "신고 사유는 필수입니다.")
    @field:Size(max = 500, message = "신고 사유는 500자 이하여야 합니다.")
    val reason: String,
)

data class ReportResponse(
    val id: Long,
    val reporterNickname: String,
    val targetType: ReportTargetType,
    val targetId: Long,
    val reason: String,
    val status: ReportStatus,
    val createdAt: LocalDateTime,
)
