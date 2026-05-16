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
import com.contenido.domain.admin.dto.AuditLogArchivePreviewResponse
import com.contenido.domain.admin.dto.AuditLogArchiveResultResponse
import com.contenido.domain.admin.dto.AuditLogRetentionPolicyResponse
import com.contenido.domain.admin.dto.ExecuteAuditLogArchiveRequest
import com.contenido.domain.admin.dto.ModerationAuditLogResponse
import com.contenido.domain.admin.dto.ModerationThresholdResponse
import com.contenido.domain.admin.dto.UpdateModerationThresholdsRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.service.AdminModerationService
import com.contenido.domain.admin.service.ModerationAuditLogArchiveService
import com.contenido.domain.admin.service.ModerationAuditLogRetentionService
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
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
    private val moderationAuditLogRetentionService: ModerationAuditLogRetentionService,
    private val moderationAuditLogArchiveService: ModerationAuditLogArchiveService,
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

    // ── 운영 감사 로그 — PR61, PR62 (필터 확장), PR63 (detail + export) ──────

    @GetMapping("/moderation/audit-logs")
    fun getModerationAuditLogs(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) action: ModerationAuditAction?,
        @RequestParam(required = false) targetType: ReportTargetType?,
        @RequestParam(required = false) targetId: Long?,
        @RequestParam(required = false) actorId: Long?,
        // ISO datetime 또는 date-only. date-only 는 service 가 from=00:00 / to=23:59:59.999999999 로 확장.
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): ApiResponse<PageResponse<ModerationAuditLogResponse>> =
        ApiResponse.ok(
            PageResponse.of(
                moderationAuditLogService.list(
                    page = page,
                    size = size,
                    action = action,
                    targetType = targetType,
                    targetId = targetId,
                    actorId = actorId,
                    from = from,
                    to = to,
                ),
            ),
        )

    @GetMapping("/moderation/audit-logs/{id}")
    fun getModerationAuditLog(@PathVariable id: Long): ApiResponse<ModerationAuditLogResponse> =
        ApiResponse.ok(moderationAuditLogService.get(id))

    /**
     * PR63 — CSV export. PR62 list 와 동일 필터, 최대 [ModerationAuditLogService.MAX_EXPORT_ROWS]
     * 건. Content-Disposition attachment 로 브라우저가 파일 다운로드 처리.
     */
    @GetMapping("/moderation/audit-logs/export", produces = ["text/csv"])
    fun exportModerationAuditLogs(
        @RequestParam(required = false) action: ModerationAuditAction?,
        @RequestParam(required = false) targetType: ReportTargetType?,
        @RequestParam(required = false) targetId: Long?,
        @RequestParam(required = false) actorId: Long?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): ResponseEntity<String> {
        val csv = moderationAuditLogService.exportToCsv(
            action = action,
            targetType = targetType,
            targetId = targetId,
            actorId = actorId,
            from = from,
            to = to,
        )
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType("text/csv; charset=UTF-8")
            contentDisposition = ContentDisposition.attachment()
                .filename("moderation-audit-logs.csv")
                .build()
            set("X-Export-Limit", ModerationAuditLogService.MAX_EXPORT_ROWS.toString())
        }
        return ResponseEntity(csv, headers, HttpStatus.OK)
    }

    /**
     * PR64 — audit log retention 정책 조회 + dry-run.
     *
     * 경로 (`/audit-log-retention`) 는 의도적으로 `/audit-logs/{id}` 와 충돌하지 않는 별도
     * prefix 를 사용. 본 endpoint 는 **삭제하지 않는다** — 운영자가 영향 범위만 미리 확인.
     */
    @GetMapping("/moderation/audit-log-retention")
    fun getAuditLogRetention(
        @RequestParam(required = false) retentionDays: Long?,
    ): ApiResponse<AuditLogRetentionPolicyResponse> =
        ApiResponse.ok(moderationAuditLogRetentionService.getRetentionPolicy(retentionDays))

    // ── 운영 감사 로그 archive — PR66 ────────────────────────────────────────

    /** archive 실행 전 영향 범위 미리보기. */
    @GetMapping("/moderation/audit-log-retention/archive-preview")
    fun getAuditLogArchivePreview(
        @RequestParam(required = false) retentionDays: Long?,
    ): ApiResponse<AuditLogArchivePreviewResponse> =
        ApiResponse.ok(moderationAuditLogArchiveService.previewArchive(retentionDays))

    /**
     * archive 실행. expectedCutoffAt / expectedCandidateCount stale 가드 + confirmText='ARCHIVE'.
     * 한 번에 최대 1000건. 결과로 archive 한 건수와 남은 후보 수를 반환.
     */
    @PostMapping("/moderation/audit-log-retention/archive")
    fun executeAuditLogArchive(
        @AuthenticationPrincipal adminUserId: Long,
        @Valid @RequestBody request: ExecuteAuditLogArchiveRequest,
    ): ApiResponse<AuditLogArchiveResultResponse> =
        ApiResponse.ok(
            moderationAuditLogArchiveService.executeArchive(adminUserId, request),
            "오래된 감사 로그를 아카이브했어요.",
        )
}
