package com.contenido.domain.admin.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * ADMIN 채널 제재 요청 (PR58). reason 은 cascade 된 hide 사유로 동일하게 사용.
 * 한 채널 전체와 소속 콘텐츠가 같이 가려지므로 reason 은 필수.
 */
data class AdminBanChannelRequest(
    @field:NotBlank(message = "제재 사유는 필수입니다.")
    @field:Size(max = 255, message = "제재 사유는 255자 이하여야 합니다.")
    val reason: String,
)

/**
 * 채널 제재/해제 응답 (PR58).
 *  - [cascadedEventCount] / [cascadedPostCount] / [cascadedReviewCount] 는 본 호출에서 "새로
 *    숨김 처리한" row 수 — 이미 hidden 이던 row 는 제외. 운영자가 cascade 영향 범위를 확인.
 *  - COMMENT cascade 는 본 PR 범위 밖 — channel 매핑이 복잡(targetType=EVENT/POST/COMMENT)해서
 *    후속 PR.
 */
data class AdminChannelBanResponse(
    val channelId: Long,
    val channelName: String,
    val isActive: Boolean,
    val hidden: Boolean,
    val hiddenAt: LocalDateTime? = null,
    val hiddenReason: String? = null,
    val cascadedEventCount: Int,
    val cascadedPostCount: Int,
    val cascadedReviewCount: Int,
)
