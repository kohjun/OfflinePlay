package com.contenido.domain.creator.controller

import com.contenido.domain.creator.dto.CreatorStudioResponse
import com.contenido.domain.creator.service.CreatorStudioService
import com.contenido.global.response.ApiResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Creator Studio 진입점.
 *
 * 기존 `/api/v1/creator/stats` 는 단순 카운트만 반환했고 본 PR 이후로는 사용하지 않는다
 * (호환을 위해 엔드포인트는 유지). MY → "기획자 스튜디오" 진입은 이 endpoint 로 일원화한다.
 */
@RestController
@RequestMapping("/api/v1/creator")
class CreatorStudioController(
    private val creatorStudioService: CreatorStudioService,
) {

    @GetMapping("/studio")
    @PreAuthorize("hasRole('CREATOR') or hasRole('ADMIN')")
    fun getStudio(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<CreatorStudioResponse> {
        return ApiResponse.ok(creatorStudioService.getStudio(userId))
    }
}
