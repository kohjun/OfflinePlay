package com.contenido.domain.creator.dto

import com.contenido.domain.creator.entity.ApplicationStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class ApplyRequest(
    @field:NotBlank(message = "신청 사유는 필수입니다.")
    @field:Size(max = 1000, message = "신청 사유는 1000자 이하여야 합니다.")
    val reason: String,

    val portfolioUrl: String? = null,
)

data class RejectRequest(
    val rejectReason: String? = null,
)

data class CreatorApplicationResponse(
    val id: Long,
    val applicantNickname: String,
    val reason: String,
    val portfolioUrl: String?,
    val status: ApplicationStatus,
    val createdAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
)
