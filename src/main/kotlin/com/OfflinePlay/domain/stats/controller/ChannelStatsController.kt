package com.contenido.domain.stats.controller

import com.contenido.domain.stats.dto.ChannelStatsResponse
import com.contenido.domain.stats.service.ChannelStatsService
import com.contenido.global.response.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/channels")
class ChannelStatsController(
    private val channelStatsService: ChannelStatsService,
) {

    @GetMapping("/{channelId}/stats")
    fun getChannelStats(
        @PathVariable channelId: Long,
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<ChannelStatsResponse> =
        ApiResponse.ok(channelStatsService.getChannelStats(userId, channelId))
}
