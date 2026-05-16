package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AuditLogArchivePreviewResponse
import com.contenido.domain.admin.dto.AuditLogArchiveResultResponse
import com.contenido.domain.admin.dto.ExecuteAuditLogArchiveRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLogArchive
import com.contenido.domain.admin.repository.ModerationAuditLogArchiveRepository
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AuditLogArchiveConfirmationRequiredException
import com.contenido.global.exception.AuditLogArchiveStaleException
import com.contenido.global.exception.InvalidRetentionRangeException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.data.domain.PageRequest
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
) {

    companion object {
        const val ARCHIVE_LIMIT: Int = 1000
        const val CONFIRM_TEXT_REQUIRED: String = "ARCHIVE"
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

        val pageable = PageRequest.of(0, ARCHIVE_LIMIT)
        val batch = moderationAuditLogRepository
            .findByCreatedAtBeforeOrderByCreatedAtAsc(cutoffAt, pageable)

        // archive 복사 — actorNicknameSnapshot 으로 archive 시점 nickname 보존.
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
                    archivedBy = admin,
                )
            )
        }
        // active 에서 제거. deleteAll 은 한 번에 SQL DELETE in batch 로 처리.
        if (batch.isNotEmpty()) {
            moderationAuditLogRepository.deleteAll(batch)
            // 다음 단계의 record() 가 active 테이블에 새 row 를 넣기 전에 flush 가 필요할 수 있어
            // JPA 가 알아서 처리하도록 명시 호출하지 않음 — 같은 트랜잭션이라 일관성 보장.
        }

        val archivedCount = batch.size.toLong()
        val remaining = moderationAuditLogRepository.countByCreatedAtBefore(cutoffAt)

        // active 에 archive 액션 audit 기록. 본 row 의 createdAt 은 now() 이므로 cutoffAt 이후 →
        // 같은 batch 에 포함되지 않음 (이미 위에서 deleteAll 완료).
        moderationAuditLogService.record(
            actorId = adminUserId,
            action = ModerationAuditAction.AUDIT_LOGS_ARCHIVED,
            afterValue = mapOf(
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
