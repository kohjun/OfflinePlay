package com.contenido.domain.admin.dto

import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportTargetType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * ADMIN 수동 hide 요청 (PR54).
 * 이유는 필수, 255자 제한 — 각 entity 의 hidden_reason 컬럼이 VARCHAR(255).
 */
data class AdminHideTargetRequest(
    @field:NotBlank(message = "숨김 사유는 필수입니다.")
    @field:Size(max = 255, message = "숨김 사유는 255자 이하여야 합니다.")
    val reason: String,
)

/**
 * 수동 hide/unhide 응답 — Admin 페이지가 row 의 hidden 상태/맥락을 즉시 갱신할 수 있게 한다.
 */
data class AdminModerationTargetResponse(
    val targetType: ReportTargetType,
    val targetId: Long,
    val targetTitle: String,
    val targetPreview: String,
    val hidden: Boolean,
    val hiddenAt: LocalDateTime? = null,
    val hiddenReason: String? = null,
    val pendingReportCount: Long,
    /**
     * 대상에 대한 최신 appeal 상태 (requester 무관). 본 PR 의 hide/unhide 는 이 상태를
     * 자동으로 바꾸지 않는다 — 운영자가 appeal 큐에서 별도 처리. null = appeal 자체가 없음.
     */
    val latestAppealStatus: ReportAppealStatus? = null,
)
