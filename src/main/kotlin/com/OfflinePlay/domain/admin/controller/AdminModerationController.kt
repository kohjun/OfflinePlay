package com.contenido.domain.admin.controller

import com.contenido.domain.admin.dto.AdminBanChannelRequest
import com.contenido.domain.admin.dto.AdminChannelBanResponse
import com.contenido.domain.admin.dto.AdminHideTargetRequest
import com.contenido.domain.admin.dto.AdminModerationActorStatsResponse
import com.contenido.domain.admin.dto.AdminModerationGranularity
import com.contenido.domain.admin.dto.AdminModerationPriority
import com.contenido.domain.admin.dto.AdminModerationQueueItemResponse
import com.contenido.domain.admin.dto.AdminModerationStatsResponse
import com.contenido.domain.admin.dto.AdminModerationTargetResponse
import com.contenido.domain.admin.dto.ModerationThresholdResponse
import com.contenido.domain.admin.dto.UpdateModerationThresholdsRequest
import com.contenido.domain.admin.service.AdminModerationService
import com.contenido.domain.admin.service.ModerationThresholdService
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * PR87 — `AdminController` 에서 moderation 액션(`/moderation/...`) endpoint 만 분리한 컨트롤러.
 * 경로/권한/응답 모두 기존(PR54/55/57/58/60)과 동일하게 유지된다.
 *
 * 신고/이의 제기는 [AdminReportController] / [AdminAppealController] 에, 감사 로그는
 * [AdminAuditController] 에 분리되어 있다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminModerationController(
    private val adminModerationService: AdminModerationService,
    private val moderationThresholdService: ModerationThresholdService,
) {

    // ── 운영 지표 — PR57 ─────────────────────────────────────────────────────

    @GetMapping("/moderation/stats")
    fun getModerationStats(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: LocalDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: LocalDateTime?,
        @RequestParam(required = false) granularity: AdminModerationGranularity?,
    ): ApiResponse<AdminModerationStatsResponse> =
        ApiResponse.ok(adminModerationService.getStats(from, to, granularity))

    /**
     * PR93 — 운영자 활동 요약. moderation_audit_logs 를 actor 단위로 group.
     * 기본 30일 / Top 10. limit 은 1..50 으로 clamp.
     */
    @GetMapping("/moderation/actor-stats")
    fun getModerationActorStats(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: LocalDateTime?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: LocalDateTime?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<AdminModerationActorStatsResponse> =
        ApiResponse.ok(adminModerationService.getActorStats(from, to, limit))

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
}
