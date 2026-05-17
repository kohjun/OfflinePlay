package com.contenido.domain.admin.controller

import com.contenido.domain.report.dto.ReportAppealResponse
import com.contenido.domain.report.dto.ReviewReportAppealRequest
import com.contenido.domain.report.service.ReportAppealService
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PR87 — `AdminController` 에서 이의 제기(appeal) endpoint 만 분리한 컨트롤러. 경로/권한/응답
 * 모두 PR52 와 동일하게 유지된다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminAppealController(
    private val reportAppealService: ReportAppealService,
) {

    @GetMapping("/report-appeals")
    fun getReportAppeals(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: String?,
    ): ApiResponse<PageResponse<ReportAppealResponse>> =
        ApiResponse.ok(PageResponse.of(reportAppealService.listAppealsForAdmin(page, size, status)))

    @PatchMapping("/report-appeals/{id}/approve")
    fun approveReportAppeal(
        @AuthenticationPrincipal adminUserId: Long,
        @PathVariable id: Long,
    ): ApiResponse<ReportAppealResponse> =
        ApiResponse.ok(reportAppealService.approveAppeal(adminUserId, id), "숨김을 해제했어요.")

    @PatchMapping("/report-appeals/{id}/reject")
    fun rejectReportAppeal(
        @AuthenticationPrincipal adminUserId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody(required = false) request: ReviewReportAppealRequest?,
    ): ApiResponse<ReportAppealResponse> =
        ApiResponse.ok(
            reportAppealService.rejectAppeal(adminUserId, id, request ?: ReviewReportAppealRequest()),
            "이의 제기를 거절했어요.",
        )
}
