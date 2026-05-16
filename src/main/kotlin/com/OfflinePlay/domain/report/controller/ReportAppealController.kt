package com.contenido.domain.report.controller

import com.contenido.domain.report.dto.CreateReportAppealRequest
import com.contenido.domain.report.dto.ReportAppealResponse
import com.contenido.domain.report.service.ReportAppealService
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/** 사용자(작성자/소유자) appeal 생성 + 본인 appeal 목록 조회. */
@RestController
@RequestMapping("/api/v1/report-appeals")
class ReportAppealController(
    private val reportAppealService: ReportAppealService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAppeal(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CreateReportAppealRequest,
    ): ApiResponse<ReportAppealResponse> =
        ApiResponse.created(reportAppealService.createAppeal(userId, request), "이의 제기가 접수되었습니다.")

    @GetMapping("/my")
    fun listMyAppeals(
        @AuthenticationPrincipal userId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<ReportAppealResponse>> =
        ApiResponse.ok(PageResponse.of(reportAppealService.listMyAppeals(userId, page, size)))
}
