package com.contenido.domain.admin.controller

import com.contenido.domain.admin.dto.AdminBanChannelRequest
import com.contenido.domain.admin.dto.AdminChannelBanResponse
import com.contenido.domain.admin.dto.AdminChannelResponse
import com.contenido.domain.admin.dto.AdminHideTargetRequest
import com.contenido.domain.admin.dto.AdminModerationGranularity
import com.contenido.domain.admin.dto.AdminModerationPriority
import com.contenido.domain.admin.dto.AdminModerationQueueItemResponse
import com.contenido.domain.admin.dto.AdminModerationStatsResponse
import com.contenido.domain.admin.dto.AdminModerationTargetResponse
import com.contenido.domain.admin.dto.AdminUserResponse
import com.contenido.domain.admin.dto.ModerationAuditLogResponse
import com.contenido.domain.admin.dto.ModerationThresholdResponse
import com.contenido.domain.admin.dto.UpdateModerationThresholdsRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.service.AdminModerationService
import com.contenido.domain.admin.service.ModerationAuditLogService
import com.contenido.domain.admin.service.ModerationThresholdService
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDateTime
import com.contenido.domain.admin.service.AdminService
import com.contenido.domain.report.dto.ReportAppealResponse
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.domain.report.dto.ReviewReportAppealRequest
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.service.ReportAppealService
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminController(
    private val adminService: AdminService,
    private val reportAppealService: ReportAppealService,
    private val adminModerationService: AdminModerationService,
    private val moderationThresholdService: ModerationThresholdService,
    private val moderationAuditLogService: ModerationAuditLogService,
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

    // ── 이의 제기(appeal) 관리 — PR52 ────────────────────────────────────────

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

    // ── 운영 지표 — PR57 ─────────────────────────────────────────────────────

    @GetMapping("/moderation/stats")
    fun getModerationStats(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: LocalDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: LocalDateTime?,
        @RequestParam(required = false) granularity: AdminModerationGranularity?,
    ): ApiResponse<AdminModerationStatsResponse> =
        ApiResponse.ok(adminModerationService.getStats(from, to, granularity))

    // ── 통합 moderation queue — PR55 ─────────────────────────────────────────

    @GetMapping("/moderation/queue")
    fun getModerationQueue(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) targetType: ReportTargetType?,
        @RequestParam(required = false) hidden: Boolean?,
        @RequestParam(required = false) priority: AdminModerationPriority?,
    ): ApiResponse<PageResponse<AdminModerationQueueItemResponse>> =
        ApiResponse.ok(
            PageResponse.of(adminModerationService.getQueue(page, size, targetType, hidden, priority)),
        )

    // ── 수동 hide / unhide — PR54 ────────────────────────────────────────────

    @PatchMapping("/moderation/{targetType}/{targetId}/hide")
    fun hideTarget(
        @AuthenticationPrincipal adminUserId: Long,
        @PathVariable targetType: ReportTargetType,
        @PathVariable targetId: Long,
        @Valid @RequestBody request: AdminHideTargetRequest,
    ): ApiResponse<AdminModerationTargetResponse> =
        ApiResponse.ok(
            adminModerationService.hideTarget(adminUserId, targetType, targetId, request),
            "대상을 숨김 처리했어요.",
        )

    @PatchMapping("/moderation/{targetType}/{targetId}/unhide")
    fun unhideTarget(
        @AuthenticationPrincipal adminUserId: Long,
        @PathVariable targetType: ReportTargetType,
        @PathVariable targetId: Long,
    ): ApiResponse<AdminModerationTargetResponse> =
        ApiResponse.ok(
            adminModerationService.unhideTarget(adminUserId, targetType, targetId),
            "숨김을 해제했어요.",
        )

    // ── 채널 제재 / 해제 — PR58 ──────────────────────────────────────────────

    @PatchMapping("/moderation/channels/{channelId}/ban")
    fun banChannelForModeration(
        @AuthenticationPrincipal adminUserId: Long,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: AdminBanChannelRequest,
    ): ApiResponse<AdminChannelBanResponse> =
        ApiResponse.ok(
            adminModerationService.banChannelForModeration(adminUserId, channelId, request),
            "채널을 제재하고 관련 콘텐츠를 숨겼어요.",
        )

    @PatchMapping("/moderation/channels/{channelId}/unban")
    fun unbanChannelForModeration(
        @AuthenticationPrincipal adminUserId: Long,
        @PathVariable channelId: Long,
    ): ApiResponse<AdminChannelBanResponse> =
        ApiResponse.ok(
            adminModerationService.unbanChannelForModeration(adminUserId, channelId),
            "채널 제재를 해제했어요.",
        )

    // ── 자동 hide 임계치 조정 — PR60 ──────────────────────────────────────────

    @GetMapping("/moderation/thresholds")
    fun getModerationThresholds(): ApiResponse<List<ModerationThresholdResponse>> =
        ApiResponse.ok(moderationThresholdService.getThresholds())

    @PatchMapping("/moderation/thresholds")
    fun updateModerationThresholds(
        @AuthenticationPrincipal adminUserId: Long,
        @Valid @RequestBody request: UpdateModerationThresholdsRequest,
    ): ApiResponse<List<ModerationThresholdResponse>> =
        ApiResponse.ok(
            moderationThresholdService.updateThresholds(adminUserId, request),
            "임계치를 갱신했어요.",
        )

    // ── 운영 감사 로그 — PR61 ────────────────────────────────────────────────

    @GetMapping("/moderation/audit-logs")
    fun getModerationAuditLogs(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) action: ModerationAuditAction?,
        @RequestParam(required = false) targetType: ReportTargetType?,
        @RequestParam(required = false) targetId: Long?,
    ): ApiResponse<PageResponse<ModerationAuditLogResponse>> =
        ApiResponse.ok(
            PageResponse.of(moderationAuditLogService.list(page, size, action, targetType, targetId)),
        )
}
