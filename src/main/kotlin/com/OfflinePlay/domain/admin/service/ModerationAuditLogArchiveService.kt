package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.ArchivedModerationAuditLogResponse
import com.contenido.domain.admin.dto.AuditLogArchivePreviewResponse
import com.contenido.domain.admin.dto.AuditLogArchiveResultResponse
import com.contenido.domain.admin.dto.ExecuteAuditLogArchiveRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLogArchive
import com.contenido.domain.admin.repository.ModerationAuditLogArchiveRepository
import com.contenido.domain.admin.repository.ModerationAuditLogArchiveSpecs
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.ArchivedModerationAuditLogNotFoundException
import com.contenido.global.exception.AuditLogArchiveConfirmationRequiredException
import com.contenido.global.exception.AuditLogArchiveStaleException
import com.contenido.global.exception.InvalidRetentionRangeException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * PR66 — 오래된 audit log 를 archive table 로 이동.
 *
 * 정책:
 *  - hard delete 안 함. active row 를 archive 에 복사한 뒤 active 에서 제거.
 *  - 한 번에 최대 [ARCHIVE_LIMIT] 건. 그 이상은 preview/execute 를 다시 호출.
 *  - preview/execute 사이의 stale 을 막기 위해 expectedCutoffAt / expectedCandidateCount 가
 *    server 재계산 결과와 다르면 [AuditLogArchiveStaleException].
 *  - confirmText 는 정확히 `ARCHIVE` — 운영자가 실수로 누르는 것을 줄이는 안전 가드.
 *  - 성공 시 active 테이블에 [ModerationAuditAction.AUDIT_LOGS_ARCHIVED] 액션을 1건 기록.
 *    이 새 row 는 createdAt > cutoffAt 이므로 본 batch 에 포함되지 않는다.
 *  - scheduler / 자동화는 PR68. 본 PR 은 수동 호출만.
 */
@Service
@Transactional(readOnly = true)
class ModerationAuditLogArchiveService(
    private val moderationAuditLogRepository: ModerationAuditLogRepository,
    private val moderationAuditLogArchiveRepository: ModerationAuditLogArchiveRepository,
    private val moderationAuditLogService: ModerationAuditLogService,
    private val userRepository: UserRepository,
    private val systemActorService: SystemActorService,
) {

    companion object {
        const val ARCHIVE_LIMIT: Int = 1000
        const val CONFIRM_TEXT_REQUIRED: String = "ARCHIVE"

        /** PR67 — archive 조회용 export CSV 행 한도. PR63 active export 와 동일 한도. */
        const val EXPORT_LIMIT: Int = 1000

        /**
         * PR67 — archive CSV 헤더. originalId 가 첫 컬럼 — 활성 export 와 일관된 컬럼 순서.
         *
         * PR131 — 환불 분석용 파생 컬럼 10 개 append-only (active export 와 동일 컬럼 정의).
         * 기존 prefix 11 컬럼은 위치 / 이름 그대로. archive CSV 도 lookup enrichment 미적용
         * (`afterValue` JSON 파생값만) — N+1 회피.
         */
        const val CSV_HEADER: String =
            "originalId,originalCreatedAt,archivedAt,actorId,actorNickname,action,targetType,targetId,reason,beforeValue,afterValue," +
                "refundKind,ticketId,paymentAttemptId,eventId,refundAmount,refundedAmount," +
                "remainingRefundableAmount,ticketStatus,paymentStatus,fullRefund"

        private const val CSV_LINE_TERMINATOR = "\r\n"

        /**
         * PR130 — archive detail enrichment 대상 action 집합. active 의
         * [ModerationAuditLogService] 와 동일한 정의 (`PAYMENT_PARTIALLY_REFUNDED` /
         * `PAYMENT_REFUNDED`). 두 enum 값 모두 PR122 audit payload shape 을 공유하므로 동일
         * helper [ModerationAuditLogService.buildPaymentRefundContext] 로 처리.
         */
        private val PAYMENT_REFUND_ACTIONS = setOf(
            ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED,
            ModerationAuditAction.PAYMENT_REFUNDED,
        )
    }

    fun previewArchive(
        retentionDays: Long? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): AuditLogArchivePreviewResponse {
        val effective = effectiveRetention(retentionDays)
        val cutoffAt = now.minusDays(effective)
        val candidates = moderationAuditLogRepository.countByCreatedAtBefore(cutoffAt)
        val oldest = moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc()?.createdAt
        val newest = moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc()?.createdAt
        return AuditLogArchivePreviewResponse(
            retentionDays = effective,
            cutoffAt = cutoffAt,
            candidateCount = candidates,
            archiveLimit = ARCHIVE_LIMIT,
            willArchiveCount = minOf(candidates, ARCHIVE_LIMIT.toLong()),
            oldestAuditLogCreatedAt = oldest,
            newestAuditLogCreatedAt = newest,
        )
    }

    /**
     * preview 검증 → archive 복사 → active 삭제 → audit 기록 → 잔여 후보 카운트. 모두 같은 트랜잭션.
     *
     *  - cutoffAt 은 [ExecuteAuditLogArchiveRequest.expectedCutoffAt] 을 그대로 사용 — preview 와
     *    같은 시점 기준으로 일관성 보장.
     *  - candidateCount stale 검사는 expectedCutoffAt 기준의 현재 count 와 expectedCandidateCount
     *    를 비교. 불일치면 409.
     */
    @Transactional
    fun executeArchive(
        adminUserId: Long,
        request: ExecuteAuditLogArchiveRequest,
    ): AuditLogArchiveResultResponse {
        if (request.confirmText != CONFIRM_TEXT_REQUIRED) {
            throw AuditLogArchiveConfirmationRequiredException()
        }
        // retentionDays 범위 가드 — preview 와 같은 정책.
        effectiveRetention(request.retentionDays)

        val cutoffAt = request.expectedCutoffAt
        val currentCount = moderationAuditLogRepository.countByCreatedAtBefore(cutoffAt)
        if (currentCount != request.expectedCandidateCount) {
            throw AuditLogArchiveStaleException()
        }

        val admin = userRepository.findById(adminUserId).orElseThrow { UserNotFoundException() }
        val (archivedCount, remaining) = doArchiveBatch(cutoffAt, admin)

        // active 에 archive 액션 audit 기록. PR69 부터 mode=MANUAL 동봉.
        moderationAuditLogService.record(
            actorId = adminUserId,
            action = ModerationAuditAction.AUDIT_LOGS_ARCHIVED,
            afterValue = mapOf(
                "mode" to "MANUAL",
                "archivedCount" to archivedCount,
                "cutoffAt" to cutoffAt.toString(),
                "remainingCandidateCount" to remaining,
            ),
        )

        return AuditLogArchiveResultResponse(
            archivedCount = archivedCount,
            cutoffAt = cutoffAt,
            remainingCandidateCount = remaining,
        )
    }

    /**
     * PR68 신설, PR69 에서 system actor 도입 후 audit 기록 추가.
     *
     * 수동 [executeArchive] 와 동일한 batch 로직을 공유하지만:
     *  - confirmText / stale 검사 없음 (자동 실행이라 round-trip 이 없음).
     *  - actor 는 항상 [SystemActorService] 의 system actor — `archived_by` 와 audit actor 둘 다.
     *  - audit 기록 추가 (PR69): mode=SCHEDULED, reason="Scheduled audit log archive",
     *    afterValue 에 archivedCount / cutoffAt / remaining + (있다면) scheduler 를 마지막
     *    토글한 ADMIN id 를 `scheduledBy` 로 같이 박는다 — 운영 추적성 보강.
     *  - retentionDays 는 항상 [ModerationAuditLogRetentionService.DEFAULT_RETENTION_DAYS].
     */
    @Transactional
    fun executeScheduledArchive(
        scheduledByAdminId: Long? = null,
    ): AuditLogArchiveResultResponse {
        val systemActor = systemActorService.getSystemActor()
        val cutoffAt = LocalDateTime.now()
            .minusDays(ModerationAuditLogRetentionService.DEFAULT_RETENTION_DAYS)
        val (archivedCount, remaining) = doArchiveBatch(cutoffAt, systemActor)

        moderationAuditLogService.record(
            actorId = systemActor.id,
            action = ModerationAuditAction.AUDIT_LOGS_ARCHIVED,
            afterValue = buildMap<String, Any?> {
                put("mode", "SCHEDULED")
                put("archivedCount", archivedCount)
                put("cutoffAt", cutoffAt.toString())
                put("remainingCandidateCount", remaining)
                if (scheduledByAdminId != null) put("scheduledBy", scheduledByAdminId)
            },
            reason = "Scheduled audit log archive",
        )

        return AuditLogArchiveResultResponse(
            archivedCount = archivedCount,
            cutoffAt = cutoffAt,
            remainingCandidateCount = remaining,
        )
    }

    /**
     * archive batch 핵심 로직. [executeArchive] / [executeScheduledArchive] 가 공유.
     * 반환값 = (archivedCount, remainingCandidateCount).
     */
    private fun doArchiveBatch(
        cutoffAt: LocalDateTime,
        archivedBy: com.contenido.domain.user.entity.User,
    ): Pair<Long, Long> {
        val pageable = PageRequest.of(0, ARCHIVE_LIMIT)
        val batch = moderationAuditLogRepository
            .findByCreatedAtBeforeOrderByCreatedAtAsc(cutoffAt, pageable)
        batch.forEach { src ->
            moderationAuditLogArchiveRepository.save(
                ModerationAuditLogArchive(
                    originalId = src.id,
                    actor = src.actor,
                    actorNicknameSnapshot = src.actor.nickname,
                    action = src.action,
                    targetType = src.targetType,
                    targetId = src.targetId,
                    beforeValue = src.beforeValue,
                    afterValue = src.afterValue,
                    reason = src.reason,
                    originalCreatedAt = src.createdAt,
                    archivedBy = archivedBy,
                )
            )
        }
        if (batch.isNotEmpty()) moderationAuditLogRepository.deleteAll(batch)
        val archivedCount = batch.size.toLong()
        val remaining = moderationAuditLogRepository.countByCreatedAtBefore(cutoffAt)
        return archivedCount to remaining
    }

    // ── PR67: archived audit log browse ──────────────────────────────────────

    /**
     * archived row 목록. PR62 active list 와 동일 axes (action / targetType / targetId / actorId
     * / from / to) 를 받지만 시간 축은 `originalCreatedAt`. 정렬은 `originalCreatedAt DESC` 고정.
     *
     * from/to 는 controller 에서 PR62 와 같은 형태로 String 을 받고 본 service 가 동일 규칙으로
     * 해석해야 한다 — 의존성 단순화를 위해 active 측 helper [ModerationAuditLogService.parseRangeBoundary]
     * 를 재사용한다.
     */
    fun listArchived(
        page: Int,
        size: Int,
        action: ModerationAuditAction? = null,
        targetType: ReportTargetType? = null,
        targetId: Long? = null,
        actorId: Long? = null,
        from: String? = null,
        to: String? = null,
    ): Page<ArchivedModerationAuditLogResponse> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "originalCreatedAt"))
        val spec = buildArchiveSpec(action, targetType, targetId, actorId, from, to)
        return moderationAuditLogArchiveRepository.findAll(spec, pageable).map { it.toResponse() }
    }

    /**
     * PR67 — 단건 상세. archive 본인 PK 가 아니라 active 에 있던 [originalId] 기준 조회.
     *
     * PR130 — `TICKET_FORCED_REFUNDED` row 는 `forcedRefundContext`, `PAYMENT_PARTIALLY_REFUNDED`
     * / `PAYMENT_REFUNDED` row 는 `paymentRefundContext` 채움. active detail 과 동일 정책 —
     * archive list / CSV 는 enrichment 미적용 (N+1 회피 + CSV 호환).
     */
    fun getArchived(originalId: Long): ArchivedModerationAuditLogResponse {
        val row = moderationAuditLogArchiveRepository.findByOriginalId(originalId)
            ?: throw ArchivedModerationAuditLogNotFoundException()
        return row.toResponse(enrichRefundContexts = true)
    }

    /**
     * PR67 — archive CSV export. 동일 필터 + 최대 [EXPORT_LIMIT] 건. `originalCreatedAt` DESC.
     * escaping 정책은 active export 와 동일 (PR63 [ModerationAuditLogService.csvEscape] 재사용).
     */
    fun exportArchivedToCsv(
        action: ModerationAuditAction? = null,
        targetType: ReportTargetType? = null,
        targetId: Long? = null,
        actorId: Long? = null,
        from: String? = null,
        to: String? = null,
    ): String {
        val pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "originalCreatedAt"))
        val spec = buildArchiveSpec(action, targetType, targetId, actorId, from, to)
        val rows = moderationAuditLogArchiveRepository.findAll(spec, pageable).content
        return buildCsv(rows)
    }

    private fun buildArchiveSpec(
        action: ModerationAuditAction?,
        targetType: ReportTargetType?,
        targetId: Long?,
        actorId: Long?,
        from: String?,
        to: String?,
    ) = ModerationAuditLogArchiveSpecs.withFilters(
        action = action,
        targetType = targetType,
        targetId = targetId,
        actorId = actorId,
        from = moderationAuditLogService.parseRangeBoundary(from, endOfDay = false),
        to = moderationAuditLogService.parseRangeBoundary(to, endOfDay = true),
    )

    private fun buildCsv(rows: List<ModerationAuditLogArchive>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append(CSV_LINE_TERMINATOR)
        rows.forEach { log ->
            sb.append(log.originalId).append(',')
            sb.append(moderationAuditLogService.csvEscape(log.originalCreatedAt.toString())).append(',')
            sb.append(moderationAuditLogService.csvEscape(log.archivedAt.toString())).append(',')
            sb.append(log.actor.id).append(',')
            sb.append(moderationAuditLogService.csvEscape(log.actorNicknameSnapshot)).append(',')
            sb.append(log.action.name).append(',')
            sb.append(log.targetType?.name ?: "").append(',')
            sb.append(log.targetId?.toString() ?: "").append(',')
            sb.append(moderationAuditLogService.csvEscape(log.reason)).append(',')
            sb.append(moderationAuditLogService.csvEscape(log.beforeValue)).append(',')
            sb.append(moderationAuditLogService.csvEscape(log.afterValue))
            // PR131 — active export 와 동일 helper 재사용 (코드 / 정책 단일 원천).
            moderationAuditLogService
                .csvRefundDerivedColumns(log.action, log.afterValue)
                .forEach { sb.append(',').append(it) }
            sb.append(CSV_LINE_TERMINATOR)
        }
        return sb.toString()
    }

    private fun ModerationAuditLogArchive.toResponse(
        enrichRefundContexts: Boolean = false,
    ) = ArchivedModerationAuditLogResponse(
        originalId = originalId,
        actorId = actor.id,
        actorNicknameSnapshot = actorNicknameSnapshot,
        action = action,
        targetType = targetType,
        targetId = targetId,
        beforeValue = beforeValue,
        afterValue = afterValue,
        reason = reason,
        originalCreatedAt = originalCreatedAt,
        archivedAt = archivedAt,
        archivedBy = archivedBy.id,
        forcedRefundContext = if (enrichRefundContexts && action == ModerationAuditAction.TICKET_FORCED_REFUNDED)
            moderationAuditLogService.buildForcedRefundContext(afterValue)
        else null,
        paymentRefundContext = if (enrichRefundContexts && action in PAYMENT_REFUND_ACTIONS)
            moderationAuditLogService.buildPaymentRefundContext(afterValue)
        else null,
    )

    /**
     * retentionDays 범위 가드. preview / execute 양쪽이 같은 정책을 쓰도록 [ModerationAuditLogRetentionService]
     * 의 상수를 재사용.
     */
    private fun effectiveRetention(retentionDays: Long?): Long {
        val effective = retentionDays ?: ModerationAuditLogRetentionService.DEFAULT_RETENTION_DAYS
        if (effective !in ModerationAuditLogRetentionService.MINIMUM_RETENTION_DAYS
                ..ModerationAuditLogRetentionService.MAXIMUM_RETENTION_DAYS) {
            throw InvalidRetentionRangeException(
                "retentionDays 는 ${ModerationAuditLogRetentionService.MINIMUM_RETENTION_DAYS}~" +
                    "${ModerationAuditLogRetentionService.MAXIMUM_RETENTION_DAYS} 사이여야 합니다.",
            )
        }
        return effective
    }
}
