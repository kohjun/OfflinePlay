package com.contenido.domain.creator.controller

import com.contenido.domain.creator.dto.CreatorModerationHiddenItemResponse
import com.contenido.domain.creator.service.CreatorModerationService
import com.contenido.global.response.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 작성자/소유자가 본인 권한의 자동 숨김 콘텐츠를 확인하고 이의 제기로 이어지는 진입점 (PR53).
 *
 * 권한: 로그인 사용자면 누구나. REVIEW/COMMENT 작성자는 PARTICIPANT 일 수 있어 CREATOR/ADMIN
 * 제한을 두지 않는다. CreatorModerationService 가 author/owner 필터를 통해 본인 콘텐츠만
 * 응답하므로 권한 우회 위험 없음.
 */
@RestController
@RequestMapping("/api/v1/creator/moderation")
class CreatorModerationController(
    private val creatorModerationService: CreatorModerationService,
) {

    @GetMapping("/hidden")
    fun getMyHiddenContent(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<List<CreatorModerationHiddenItemResponse>> =
        ApiResponse.ok(creatorModerationService.listMyHidden(userId))
}
