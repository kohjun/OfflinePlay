package com.contenido.domain.creator.dto

import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportTargetType
import java.time.LocalDateTime

/**
 * Creator Studio "숨김 처리된 콘텐츠" 섹션 row (PR53).
 *
 *  - 작성자/소유자가 본인 권한의 hidden 대상만 본다.
 *  - [appealStatus] 가 NONE/REJECTED 면 이의 제기 가능.
 *  - PENDING 이면 "검토 대기 중", APPROVED 면 (정책상 곧 unhide 되므로) 별도 표시 또는 제외.
 */
data class CreatorModerationHiddenItemResponse(
    val targetType: ReportTargetType,
    val targetId: Long,
    val targetTitle: String,
    val targetPreview: String,
    val hiddenAt: LocalDateTime,
    val hiddenReason: String?,
    val pendingReportCount: Long,
    /**
     * 현재 row 에 대한 본인 appeal 상태. NONE = 아직 제출 X. PENDING/APPROVED/REJECTED 는
     * ReportAppealStatus 와 동일.
     */
    val appealStatus: AppealStatusView,
    val appealId: Long? = null,
)

/** appealStatus 응답 전용 — NONE 을 추가해 frontend 가 분기하기 쉽게. */
enum class AppealStatusView {
    NONE, PENDING, APPROVED, REJECTED;

    companion object {
        fun from(status: ReportAppealStatus?): AppealStatusView = when (status) {
            null -> NONE
            ReportAppealStatus.PENDING -> PENDING
            ReportAppealStatus.APPROVED -> APPROVED
            ReportAppealStatus.REJECTED -> REJECTED
        }
    }
}
