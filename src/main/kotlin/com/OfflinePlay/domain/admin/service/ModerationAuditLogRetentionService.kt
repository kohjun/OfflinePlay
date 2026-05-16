package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AuditLogRetentionPolicyResponse
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
import com.contenido.global.exception.InvalidRetentionRangeException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * PR64 — Audit log 보존 정책 조회 + dry-run.
 *
 * 실제 삭제/archive 는 **수행하지 않는다**. 운영자가 정책과 영향 범위를 사전에 확인할 수 있도록
 * dry-run count + oldest/newest 만 노출. 실제 cleanup 은 후속 PR (배치 jobs / scheduled task /
 * archive table) 에서 안전 가드와 함께 도입.
 *
 * 정책 저장:
 *  - 별도 테이블/Flyway migration 없이 service constant 로 시작 (PR60 의 threshold 설정 테이블은
 *    targetType enum 키라 retention scalar 와 schema 가 안 맞음 — 무리한 재사용보다 깨끗한 분리).
 *  - 운영 중 변경이 필요해지면 후속 PR 에서 KV 저장소나 전용 row 로 승격.
 *  - 호출자가 retentionDays 를 query 로 명시하면 그 값을, 없으면 [DEFAULT_RETENTION_DAYS] 사용.
 */
@Service
@Transactional(readOnly = true)
class ModerationAuditLogRetentionService(
    private val moderationAuditLogRepository: ModerationAuditLogRepository,
) {

    companion object {
        /** GDPR / 운영 audit 일반 권고 기준의 균형점. 컴플라이언스 요구 시 query param 으로 override. */
        const val DEFAULT_RETENTION_DAYS: Long = 365

        /** 너무 짧게 잡으면 incident 회고가 막힌다. */
        const val MINIMUM_RETENTION_DAYS: Long = 30

        /** 너무 길면 사실상 무한 보관 — 운영 부담. ~10년. */
        const val MAXIMUM_RETENTION_DAYS: Long = 3650
    }

    /**
     * 보존 정책 + dry-run 카운트 조회.
     *
     *  - [retentionDays] null 이면 [DEFAULT_RETENTION_DAYS] 사용.
     *  - 범위 검증: [MINIMUM_RETENTION_DAYS] ~ [MAXIMUM_RETENTION_DAYS]. 벗어나면
     *    [IllegalArgumentException] — controller @ExceptionHandler 가 400 으로 매핑.
     *  - [now] 는 단위 테스트 친화적으로 주입 가능 (default LocalDateTime.now()).
     *  - cutoffAt = now - retentionDays. cutoffAt **이전** (미포함) row 가 dry-run 대상.
     *  - 실제 삭제하지 않는다 — 본 서비스는 read-only.
     */
    fun getRetentionPolicy(
        retentionDays: Long? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): AuditLogRetentionPolicyResponse {
        val effective = retentionDays ?: DEFAULT_RETENTION_DAYS
        if (effective !in MINIMUM_RETENTION_DAYS..MAXIMUM_RETENTION_DAYS) {
            throw InvalidRetentionRangeException(
                "retentionDays 는 ${MINIMUM_RETENTION_DAYS}~${MAXIMUM_RETENTION_DAYS} 사이여야 합니다."
            )
        }
        val cutoffAt = now.minusDays(effective)
        val deletable = moderationAuditLogRepository.countByCreatedAtBefore(cutoffAt)
        val oldest = moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc()?.createdAt
        val newest = moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc()?.createdAt
        return AuditLogRetentionPolicyResponse(
            retentionDays = effective,
            minimumRetentionDays = MINIMUM_RETENTION_DAYS,
            maximumRetentionDays = MAXIMUM_RETENTION_DAYS,
            cutoffAt = cutoffAt,
            dryRunDeletableCount = deletable,
            oldestAuditLogCreatedAt = oldest,
            newestAuditLogCreatedAt = newest,
        )
    }
}
