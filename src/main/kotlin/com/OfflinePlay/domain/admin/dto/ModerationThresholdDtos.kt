package com.contenido.domain.admin.dto

import com.contenido.domain.report.entity.ReportTargetType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/** PR60 — 자동 hide 임계치 단건 응답. */
data class ModerationThresholdResponse(
    val targetType: ReportTargetType,
    val threshold: Int,
)

/**
 * 임계치 부분 갱신 요청 (PR60). 5개 targetType 중 보내고 싶은 것만 채워 보낸다.
 * null 인 필드는 변경하지 않음. 모든 필드가 null 이면 no-op.
 * 1 <= value <= 100 가드 — controller @Valid 가 잡는다.
 */
data class UpdateModerationThresholdsRequest(
    @field:Min(1, message = "임계치는 1 이상이어야 합니다.")
    @field:Max(100, message = "임계치는 100 이하여야 합니다.")
    val review: Int? = null,

    @field:Min(1, message = "임계치는 1 이상이어야 합니다.")
    @field:Max(100, message = "임계치는 100 이하여야 합니다.")
    val comment: Int? = null,

    @field:Min(1, message = "임계치는 1 이상이어야 합니다.")
    @field:Max(100, message = "임계치는 100 이하여야 합니다.")
    val post: Int? = null,

    @field:Min(1, message = "임계치는 1 이상이어야 합니다.")
    @field:Max(100, message = "임계치는 100 이하여야 합니다.")
    val event: Int? = null,

    @field:Min(1, message = "임계치는 1 이상이어야 합니다.")
    @field:Max(100, message = "임계치는 100 이하여야 합니다.")
    val channel: Int? = null,
)
