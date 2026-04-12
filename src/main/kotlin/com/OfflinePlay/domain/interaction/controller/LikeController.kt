package com.contenido.domain.interaction.controller

import com.contenido.domain.interaction.dto.LikeResponse
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.interaction.service.LikeService
import com.contenido.global.response.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/{targetType}/{targetId}/likes")
class LikeController(
    private val likeService: LikeService,
) {

    @PostMapping
    fun toggleLike(
        @AuthenticationPrincipal userId: Long,
        @PathVariable targetType: TargetType,
        @PathVariable targetId: Long,
    ): ApiResponse<LikeResponse> {
        return ApiResponse.ok(likeService.toggleLike(userId, targetType, targetId))
    }

    @GetMapping
    fun getLikeStatus(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable targetType: TargetType,
        @PathVariable targetId: Long,
    ): ApiResponse<LikeResponse> {
        return ApiResponse.ok(likeService.getLikeStatus(userId, targetType, targetId))
    }
}
