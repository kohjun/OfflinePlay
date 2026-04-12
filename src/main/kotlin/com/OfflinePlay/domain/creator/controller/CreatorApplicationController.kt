package com.contenido.domain.creator.controller

import com.contenido.domain.creator.dto.ApplyRequest
import com.contenido.domain.creator.dto.CreatorApplicationResponse
import com.contenido.domain.creator.dto.RejectRequest
import com.contenido.domain.creator.service.CreatorApplicationService
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class CreatorApplicationController(
    private val applicationService: CreatorApplicationService,
) {

    @PostMapping("/creator/apply")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PARTICIPANT')")
    fun applyForCreator(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: ApplyRequest,
    ): ApiResponse<Nothing> {
        applicationService.apply(userId, request.reason, request.portfolioUrl)
        return ApiResponse.ok("크리에이터 신청이 완료되었습니다.")
    }

    @GetMapping("/creator/my-application")
    @PreAuthorize("isAuthenticated()")
    fun getMyApplication(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<CreatorApplicationResponse> {
        return ApiResponse.ok(applicationService.getMyApplication(userId))
    }

    @GetMapping("/admin/creator/applications")
    @PreAuthorize("hasRole('ADMIN')")
    fun getPendingApplications(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<CreatorApplicationResponse>> {
        return ApiResponse.ok(applicationService.getPendingApplications(page, size))
    }

    @PatchMapping("/admin/creator/applications/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    fun approveApplication(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable id: Long,
    ): ApiResponse<Nothing> {
        applicationService.approve(adminId, id)
        return ApiResponse.ok("크리에이터 신청을 승인했습니다.")
    }

    @PatchMapping("/admin/creator/applications/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    fun rejectApplication(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable id: Long,
        @RequestBody(required = false) request: RejectRequest?,
    ): ApiResponse<Nothing> {
        applicationService.reject(adminId, id, request?.rejectReason)
        return ApiResponse.ok("크리에이터 신청을 거절했습니다.")
    }
}
