package com.contenido.domain.region.controller

import com.contenido.domain.region.dto.SidoResponse
import com.contenido.domain.region.service.RegionService
import com.contenido.global.response.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * PR147 — region catalog. 비로그인 허용 (SecurityConfig 의 permitAll 분기 필요).
 *
 * 단일 엔드포인트로 시도+시군구 nested 한 번에 반환 — frontend RegionPicker 가 한 번 fetch 후 캐싱.
 */
@RestController
@RequestMapping("/api/v1/regions")
class RegionController(
    private val regionService: RegionService,
) {

    @GetMapping
    fun getSidoTree(): ApiResponse<List<SidoResponse>> {
        return ApiResponse.ok(regionService.getSidoTree())
    }
}
