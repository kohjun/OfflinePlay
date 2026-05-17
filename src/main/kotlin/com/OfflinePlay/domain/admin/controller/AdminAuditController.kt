package com.contenido.domain.admin.controller

import com.contenido.domain.admin.dto.ArchivedModerationAuditLogResponse
import com.contenido.domain.admin.dto.AuditLogArchivePreviewResponse
import com.contenido.domain.admin.dto.AuditLogArchiveResultResponse
import com.contenido.domain.admin.dto.AuditLogRetentionPolicyResponse
import com.contenido.domain.admin.dto.AuditLogRetentionSchedulerResponse
import com.contenido.domain.admin.dto.ExecuteAuditLogArchiveRequest
import com.contenido.domain.admin.dto.ModerationAuditLogResponse
import com.contenido.domain.admin.dto.UpdateAuditLogRetentionSchedulerRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.service.AuditLogRetentionSchedulerService
import com.contenido.domain.admin.service.ModerationAuditLogArchiveService
import com.contenido.domain.admin.service.ModerationAuditLogRetentionService
import com.contenido.domain.admin.service.ModerationAuditLogService
import com.contenido.domain.report.entity.ReportTargetType
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PR87 — 감사 로그(audit log) + retention + archive + scheduler endpoint 만 모은 컨트롤러.
 * 모든 경로/권한/응답은 PR61~PR70 와 동일하게 유지된다 (mechanical move).
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminAuditController(
    private val moderationAuditLogService: ModerationAuditLogService,
    private val moderationAuditLogRetentionService: ModerationAuditLogRetentionService,
    private val moderationAuditLogArchiveService: ModerationAuditLogArchiveService,
    private val auditLogRetentionSchedulerService: AuditLogRetentionSchedulerService,
) {

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

    // ── archived audit log browse — PR67 ─────────────────────────────────────

    /**
     * PR67 — archive 목록. PR62 active list 와 동일 axes, 시간 축은 `originalCreatedAt`.
     * `/audit-logs/archive` 경로는 PR63 `/audit-logs/{id}` 보다 더 specific 해서 라우팅 충돌 없음
     * (Spring 은 가장 긴 path 를 우선 매칭).
     */
    @GetMapping("/moderation/audit-logs/archive")
    fun listArchivedAuditLogs(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) action: ModerationAuditAction?,
        @RequestParam(required = false) targetType: ReportTargetType?,
        @RequestParam(required = false) targetId: Long?,
        @RequestParam(required = false) actorId: Long?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): ApiResponse<PageResponse<ArchivedModerationAuditLogResponse>> =
        ApiResponse.ok(
            PageResponse.of(
                moderationAuditLogArchiveService.listArchived(
                    page = page, size = size,
                    action = action, targetType = targetType, targetId = targetId,
                    actorId = actorId, from = from, to = to,
                ),
            ),
        )

    /**
     * PR67 — archive CSV export. produces 가 text/csv 라서 `/audit-logs/archive/export` 가
     * `/audit-logs/archive/{originalId}` 보다 먼저 매칭되어야 한다 — Spring 은 literal segment 를
     * variable 보다 우선하므로 충돌 없음.
     */
    @GetMapping("/moderation/audit-logs/archive/export", produces = ["text/csv"])
    fun exportArchivedAuditLogs(
        @RequestParam(required = false) action: ModerationAuditAction?,
        @RequestParam(required = false) targetType: ReportTargetType?,
        @RequestParam(required = false) targetId: Long?,
        @RequestParam(required = false) actorId: Long?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): ResponseEntity<String> {
        val csv = moderationAuditLogArchiveService.exportArchivedToCsv(
            action = action, targetType = targetType, targetId = targetId,
            actorId = actorId, from = from, to = to,
        )
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType("text/csv; charset=UTF-8")
            contentDisposition = ContentDisposition.attachment()
                .filename("moderation-audit-logs-archive.csv")
                .build()
            set("X-Export-Limit", "1000")
        }
        return ResponseEntity(csv, headers, HttpStatus.OK)
    }

    /** PR67 — archive 단건 상세. originalId 기준 조회 (archive 본인 PK 가 아니다). */
    @GetMapping("/moderation/audit-logs/archive/{originalId}")
    fun getArchivedAuditLog(
        @PathVariable originalId: Long,
    ): ApiResponse<ArchivedModerationAuditLogResponse> =
        ApiResponse.ok(moderationAuditLogArchiveService.getArchived(originalId))

    // ── audit log retention scheduler — PR68 ─────────────────────────────────

    @GetMapping("/moderation/audit-log-retention/scheduler")
    fun getAuditLogRetentionScheduler(): ApiResponse<AuditLogRetentionSchedulerResponse> =
        ApiResponse.ok(auditLogRetentionSchedulerService.getSettings())

    @PatchMapping("/moderation/audit-log-retention/scheduler")
    fun updateAuditLogRetentionScheduler(
        @AuthenticationPrincipal adminUserId: Long,
        @Valid @RequestBody request: UpdateAuditLogRetentionSchedulerRequest,
    ): ApiResponse<AuditLogRetentionSchedulerResponse> =
        ApiResponse.ok(
            auditLogRetentionSchedulerService.updateSettings(adminUserId, request),
            "스케줄러 설정을 갱신했어요.",
        )
}
