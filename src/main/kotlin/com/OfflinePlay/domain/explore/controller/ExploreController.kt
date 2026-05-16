package com.contenido.domain.explore.controller

import com.contenido.domain.explore.dto.ExploreResponse
import com.contenido.domain.explore.service.ExploreService
import com.contenido.global.response.ApiResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * GET /api/v1/explore
 *
 * 홈/Explore 진입점. 모든 필터 param 은 optional 이며, 잘못된 enum 값은 무시(빈 결과 정책)된다.
 * 비로그인 허용.
 *
 * 다중 필터 (PR45):
 *  - keyword          : title/description LIKE
 *  - category         : ChannelCategory enum
 *  - contentType      : ContentType enum (ORIGINAL/CLASSIC/SPECIAL)
 *  - location         : 장소 LIKE (예: "서울", "강남")
 *  - minFee / maxFee  : 참가비 범위 (둘 다 0 가능 — 무료 이벤트 포함)
 *  - startFrom / startTo : 이벤트 시작 시각 범위 (ISO LocalDateTime)
 *  - excludeClosed    : 종료된 이벤트 제외 (기본 true)
 *  - excludeFull      : 정원 마감 이벤트 제외 (기본 false)
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
        @RequestParam(required = false) location: String?,
        @RequestParam(required = false) minFee: Long?,
        @RequestParam(required = false) maxFee: Long?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        startFrom: LocalDateTime?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        startTo: LocalDateTime?,
        @RequestParam(defaultValue = "true") excludeClosed: Boolean,
        @RequestParam(defaultValue = "false") excludeFull: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<ExploreResponse> =
        ApiResponse.ok(
            exploreService.explore(
                keyword = keyword,
                category = category,
                contentType = contentType,
                location = location,
                minFee = minFee,
                maxFee = maxFee,
                startFrom = startFrom,
                startTo = startTo,
                excludeClosed = excludeClosed,
                excludeFull = excludeFull,
                page = page,
                size = size,
            )
        )
}
