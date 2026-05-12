package com.contenido.domain.explore.controller

import com.contenido.domain.explore.dto.ExploreResponse
import com.contenido.domain.explore.service.ExploreService
import com.contenido.global.response.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/v1/explore
 *
 * 홈/Explore 진입점. keyword/category/contentType 모두 optional 이며,
 * 잘못된 enum 값은 무시(빈 결과 정책)된다. 비로그인 허용.
 */
@RestController
@RequestMapping("/api/v1/explore")
class ExploreController(
    private val exploreService: ExploreService,
) {

    @GetMapping
    fun explore(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) contentType: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<ExploreResponse> =
        ApiResponse.ok(exploreService.explore(keyword, category, contentType, page, size))
}
