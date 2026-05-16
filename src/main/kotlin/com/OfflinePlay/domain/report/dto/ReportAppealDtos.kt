package com.contenido.domain.report.dto

import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportTargetType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/** 사용자 appeal 생성 요청. */
data class CreateReportAppealRequest(
    @field:NotNull(message = "대상 타입은 필수입니다.")
    val targetType: ReportTargetType,

    @field:NotNull(message = "대상 ID는 필수입니다.")
    val targetId: Long,

    @field:NotBlank(message = "이의 제기 사유는 필수입니다.")
    @field:Size(max = 1000, message = "이의 제기 사유는 1000자 이하여야 합니다.")
    val reason: String,
)

/** ADMIN 거절 요청 — rejectReason 은 선택 (없으면 default 사유). */
data class ReviewReportAppealRequest(
    @field:Size(max = 500, message = "거절 사유는 500자 이하여야 합니다.")
    val rejectReason: String? = null,
)

data class ReportAppealResponse(
    val id: Long,
    val targetType: ReportTargetType,
    val targetId: Long,
    val requesterId: Long,
    val requesterNickname: String,
    val reason: String,
    val status: ReportAppealStatus,
    val rejectReason: String? = null,
    val createdAt: LocalDateTime,
    val reviewedAt: LocalDateTime? = null,
    /** ADMIN 큐 응답에서만 채워짐 (사용자 본인 응답은 null). */
    val targetPreview: String? = null,
    val targetHidden: Boolean = false,
)
