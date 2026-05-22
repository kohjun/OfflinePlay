package com.contenido.domain.user.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/**
 * PR146 — 매너 평가 작성 요청.
 *
 *  - rating         : 1~5 (TINYINT). bean validation 으로 가드.
 *  - revieweeId     : 평가 대상 사용자 id. service 가 host/participant 양쪽 다 가드.
 *  - tags           : 운영이 정의한 string slug 목록 (예: ["FRIENDLY", "PUNCTUAL"]). frontend 가 set 고정.
 *  - comment        : optional, 500자.
 */
data class CreateMannerFeedbackRequest(
    val revieweeId: Long,

    @field:Min(1)
    @field:Max(5)
    val rating: Int,

    @field:Size(max = 20)
    val tags: List<@Size(min = 1, max = 30) String> = emptyList(),

    @field:Size(max = 500)
    val comment: String? = null,
)

/**
 * PR146 — 사용자별 매너 요약. 누적 3건 미만 시 service 가 null 반환.
 *
 *  - averageRating : 1.0~5.0. 소수점 한 자리까지 표시 권장 (frontend 가 toFixed(1)).
 *  - count         : 누적 평가 수. 3 이상 (그 미만이면 응답 자체가 null).
 *  - topTags       : 빈도 내림차순 상위 3개 (slug). 빈도 동률이면 사전순.
 */
data class MannerSummaryResponse(
    val userId: Long,
    val averageRating: Double,
    val count: Long,
    val topTags: List<String>,
)
