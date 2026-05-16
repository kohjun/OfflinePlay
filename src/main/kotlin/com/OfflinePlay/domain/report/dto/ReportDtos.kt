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
    /**
     * PR48 — Admin 페이지에서 신고 맥락을 바로 보기 위한 짧은 preview.
     *  - REVIEW  : 본문 앞 80자
     *  - POST    : 제목 또는 본문 앞 80자
     *  - COMMENT : 본문 앞 80자
     *  - EVENT   : 이벤트 제목
     *  - CHANNEL : 채널 이름
     * 대상이 이미 삭제됐으면 null (신고 자체는 유지). createReport 응답에서는 항상 null —
     * 작성자/신고자 본인이 본 응답에서 대상 본문을 다시 노출하지 않기 위함.
     */
    val targetPreview: String? = null,
    /** PR48 — REVIEW 일 때만 채워지는 별점. 다른 타입은 null. Admin 빠른 컨텍스트. */
    val targetRating: Int? = null,
)
