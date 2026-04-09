package com.contenido.domain.admin.controller

import com.contenido.domain.admin.dto.AdminChannelResponse
import com.contenido.domain.admin.dto.AdminUserResponse
import com.contenido.domain.admin.service.AdminService
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminController(
    private val adminService: AdminService,
) {

    // ── 유저 관리 ──────────────────────────────────────────────────────────────

    @GetMapping("/users")
    fun getUsers(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<AdminUserResponse>> {
        val result = adminService.getUsers(page, size)
        return ApiResponse.success(PageResponse.of(result))
    }

    @GetMapping("/users/{id}")
    fun getUser(@PathVariable id: Long): ApiResponse<AdminUserResponse> =
        ApiResponse.success(adminService.getUser(id))

    @PatchMapping("/users/{id}/ban")
    fun banUser(@PathVariable id: Long): ApiResponse<AdminUserResponse> =
        ApiResponse.success(adminService.banUser(id))

    // ── 채널 관리 ──────────────────────────────────────────────────────────────

    @GetMapping("/channels")
    fun getChannels(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<AdminChannelResponse>> {
        val result = adminService.getChannels(page, size)
        return ApiResponse.success(PageResponse.of(result))
    }

    @PatchMapping("/channels/{id}/ban")
    fun banChannel(@PathVariable id: Long): ApiResponse<AdminChannelResponse> =
        ApiResponse.success(adminService.banChannel(id))

    // ── 신고 관리 ──────────────────────────────────────────────────────────────

    @GetMapping("/reports")
    fun getReports(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<ReportResponse>> {
        val result = adminService.getReports(page, size)
        return ApiResponse.success(PageResponse.of(result))
    }
}
