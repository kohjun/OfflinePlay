package com.contenido.domain.recommendation.controller

import com.contenido.domain.recommendation.dto.RecommendationSegment
import com.contenido.domain.recommendation.dto.RecommendationsResponse
import com.contenido.domain.recommendation.service.RecommendationService
import com.contenido.global.response.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PR148 — 추천 endpoint. 비로그인 허용 — SecurityConfig 의 permitAll 분기 필요.
 *  - userId 가 들어오면 (인증된 호출) RECOMMENDED, segment 명시 시 해당 segment.
 *  - 비로그인 시 POPULAR fallback.
 */
@RestController
@RequestMapping("/api/v1/recommendations")
class RecommendationController(
    private val recommendationService: RecommendationService,
) {

    @GetMapping("/events")
    fun events(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) segment: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<RecommendationsResponse> {
        val parsed = segment?.let { runCatching { RecommendationSegment.valueOf(it.uppercase()) }.getOrNull() }
        return ApiResponse.ok(recommendationService.recommend(userId, parsed, size))
    }
}
