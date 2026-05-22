package com.contenido.domain.interest.controller

import com.contenido.domain.interest.dto.InterestResponse
import com.contenido.domain.interest.dto.UpdateMyInterestsRequest
import com.contenido.domain.interest.service.InterestService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/**
 * PR147 — 관심사 catalog + 내 관심사 set.
 *
 *  - `GET /api/v1/interests` : 카탈로그 전체 (비로그인 허용 — SecurityConfig 분기 필요).
 *  - `GET /api/v1/users/me/interests` : 내 관심사.
 *  - `PATCH /api/v1/users/me/interests` : set 갱신.
 */
@RestController
class InterestController(
    private val interestService: InterestService,
) {

    @GetMapping("/api/v1/interests")
    fun listAll(): ApiResponse<List<InterestResponse>> {
        return ApiResponse.ok(interestService.listAll())
    }

    @GetMapping("/api/v1/users/me/interests")
    fun listMine(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<List<InterestResponse>> {
        return ApiResponse.ok(interestService.listMine(userId))
    }

    @PatchMapping("/api/v1/users/me/interests")
    fun updateMine(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: UpdateMyInterestsRequest,
    ): ApiResponse<List<InterestResponse>> {
        return ApiResponse.ok(interestService.updateMine(userId, request), "관심사를 저장했어요.")
    }
}
