package com.contenido.domain.creator.controller

import com.contenido.domain.creator.dto.CreatorChannelAnalyticsResponse
import com.contenido.domain.creator.service.CreatorAnalyticsService
import com.contenido.global.response.ApiResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

/**
 * PR153 — Creator Studio 채널 분석.
 *
 * `from` / `to` 둘 다 optional. ISO LocalDateTime (예: 2026-05-01T00:00:00). 둘 다 비우면
 * 전체 기간. 시간대는 서버 로컬 기준.
 */
@RestController
@RequestMapping("/api/v1/creator/channels/{channelId}/analytics")
class CreatorAnalyticsController(
    private val creatorAnalyticsService: CreatorAnalyticsService,
) {

    @GetMapping
    fun getChannelAnalytics(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        from: LocalDateTime?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        to: LocalDateTime?,
    ): ApiResponse<CreatorChannelAnalyticsResponse> {
        return ApiResponse.ok(
            creatorAnalyticsService.getChannelAnalytics(userId, channelId, from, to),
        )
    }
}
