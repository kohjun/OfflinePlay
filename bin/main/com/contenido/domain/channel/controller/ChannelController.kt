package com.contenido.domain.channel.controller

import com.contenido.domain.channel.dto.ChannelDetailResponse
import com.contenido.domain.channel.dto.ChannelResponse
import com.contenido.domain.channel.dto.CreateChannelRequest
import com.contenido.domain.channel.dto.UpdateChannelRequest
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.service.ChannelService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/channels")
class ChannelController(
    private val channelService: ChannelService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CREATOR')")
    fun createChannel(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CreateChannelRequest,
    ): ApiResponse<ChannelResponse> {
        return ApiResponse.created(channelService.createChannel(userId, request), "채널이 생성되었습니다.")
    }

    @GetMapping("/{channelId}")
    fun getChannel(
        @PathVariable channelId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ApiResponse<ChannelDetailResponse> {
        return ApiResponse.ok(channelService.getChannel(channelId, userId))
    }

    @PatchMapping("/{channelId}")
    fun updateChannel(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: UpdateChannelRequest,
    ): ApiResponse<ChannelResponse> {
        return ApiResponse.ok(channelService.updateChannel(userId, channelId, request), "채널이 수정되었습니다.")
    }

    @GetMapping("/category/{category}")
    fun getChannelsByCategory(
        @PathVariable category: ChannelCategory,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ): ApiResponse<Page<ChannelResponse>> {
        return ApiResponse.ok(channelService.getChannelsByCategory(category, page, size))
    }

    @PostMapping("/{channelId}/subscribe")
    fun subscribe(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
    ): ApiResponse<Nothing> {
        channelService.subscribe(userId, channelId)
        return ApiResponse.ok("채널을 구독했습니다.")
    }

    @DeleteMapping("/{channelId}/subscribe")
    fun unsubscribe(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
    ): ApiResponse<Nothing> {
        channelService.unsubscribe(userId, channelId)
        return ApiResponse.ok("구독을 취소했습니다.")
    }

    @GetMapping("/my/subscriptions")
    fun getMySubscriptions(
        @AuthenticationPrincipal userId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ): ApiResponse<Page<ChannelResponse>> {
        return ApiResponse.ok(channelService.getMySubscriptions(userId, page, size))
    }
}
