package com.contenido.domain.admin.controller

import com.contenido.domain.admin.service.AdminService
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PR87 — `AdminController` 에서 신고(report) endpoint 만 분리한 컨트롤러.
 *
 * 경로/권한/응답 모두 기존과 동일. AdminController 와 같은 base path 를 공유하므로 새 endpoint 가
 * 추가되거나 사라지지 않는다 (mechanical move).
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminReportController(
    private val adminService: AdminService,
) {

    @GetMapping("/reports")
    fun getReports(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) targetType: String?,
    ): ApiResponse<PageResponse<ReportResponse>> =
        ApiResponse.ok(PageResponse.of(adminService.getReports(page, size, targetType)))

    @PatchMapping("/reports/{id}/resolve")
    fun resolveReport(
        @AuthenticationPrincipal adminUserId: Long,
        @PathVariable id: Long,
    ): ApiResponse<ReportResponse> =
        ApiResponse.ok(adminService.resolveReport(adminUserId, id), "신고를 해결 처리했습니다.")

    @PatchMapping("/reports/{id}/dismiss")
    fun dismissReport(
        @AuthenticationPrincipal adminUserId: Long,
        @PathVariable id: Long,
    ): ApiResponse<ReportResponse> =
        ApiResponse.ok(adminService.dismissReport(adminUserId, id), "신고를 기각 처리했습니다.")
}
