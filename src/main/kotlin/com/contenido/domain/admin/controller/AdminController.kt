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
    ): ApiResponse<PageResponse<AdminUserResponse>> =
        ApiResponse.ok(PageResponse.of(adminService.getUsers(page, size)))

    @GetMapping("/users/{id}")
    fun getUser(@PathVariable id: Long): ApiResponse<AdminUserResponse> =
        ApiResponse.ok(adminService.getUser(id))

    @PatchMapping("/users/{id}/ban")
    fun banUser(@PathVariable id: Long): ApiResponse<AdminUserResponse> =
        ApiResponse.ok(adminService.banUser(id))

    // ── 채널 관리 ──────────────────────────────────────────────────────────────

    @GetMapping("/channels")
    fun getChannels(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<AdminChannelResponse>> =
        ApiResponse.ok(PageResponse.of(adminService.getChannels(page, size)))

    @PatchMapping("/channels/{id}/ban")
    fun banChannel(@PathVariable id: Long): ApiResponse<AdminChannelResponse> =
        ApiResponse.ok(adminService.banChannel(id))

    // ── 신고 관리 ──────────────────────────────────────────────────────────────

    @GetMapping("/reports")
    fun getReports(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<ReportResponse>> =
        ApiResponse.ok(PageResponse.of(adminService.getReports(page, size)))
}
