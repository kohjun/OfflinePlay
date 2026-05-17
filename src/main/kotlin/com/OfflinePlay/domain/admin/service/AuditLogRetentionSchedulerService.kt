package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AuditLogRetentionSchedulerResponse
import com.contenido.domain.admin.dto.UpdateAuditLogRetentionSchedulerRequest
import com.contenido.domain.admin.entity.AuditLogRetentionSchedulerSetting
import com.contenido.domain.admin.repository.AuditLogRetentionSchedulerSettingRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.InvalidSchedulerCronException
import com.contenido.global.exception.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/**
 * PR68 — audit log archive scheduler 의 운영 설정 + tick 진입점.
 * PR69 — system actor 도입 후 "no actor skip" 정책 제거.
 * PR70 — cron 사전 검증 + DB 저장 commit 직후 runtime schedule 동적 재등록.
 *
 * 정책:
 *  - 단일 row (id=1). seed 없으면 default OFF 로 생성.
 *  - 기본 OFF. ADMIN 이 명시적으로 ON 으로 토글해야 archive 실행.
 *  - 실패해도 앱 전체 중단 금지 — logger.warn / error 만 남김.
 *  - PR70: cron 변경 / enable 토글이 즉시 runtime 에 반영. afterCommit hook 으로 DB rollback
 *    시 runtime 도 동기화되지 않도록 안전.
 */
@Service
@Transactional(readOnly = true)
class AuditLogRetentionSchedulerService(
    private val repository: AuditLogRetentionSchedulerSettingRepository,
    private val moderationAuditLogArchiveService: ModerationAuditLogArchiveService,
    private val userRepository: UserRepository,
    /**
     * PR70 — runner 는 `@Profile("!test")` 라 test context 에서는 bean 이 없다. ObjectProvider
     * 로 받아 ifAvailable 패턴으로 호출 — null 가드 + 회로 단절.
     */
    private val schedulerRunnerProvider: ObjectProvider<AuditLogRetentionSchedulerRunner>,
) {

    companion object {
        const val DEFAULT_CRON: String = "0 30 3 * * *"
        private const val MAX_CRON_LENGTH: Int = 64
    }

    private val log = LoggerFactory.getLogger(javaClass)

    fun getSettings(): AuditLogRetentionSchedulerResponse {
        return loadOrCreate().toResponse()
    }

    /**
     * settings 갱신. PR70: cron 사전 검증 + commit 후 runner.reschedule.
     *
     *  - cron 이 비어 있지 않으면 [CronExpression.parse] 로 형식 검증 — 실패 시 400.
     *  - DB 저장 후 [TransactionSynchronizationManager] 의 afterCommit 에 runner 호출을 예약 →
     *    트랜잭션이 rollback 되면 runtime 도 그대로 유지.
     */
    @Transactional
    fun updateSettings(
        adminUserId: Long,
        request: UpdateAuditLogRetentionSchedulerRequest,
    ): AuditLogRetentionSchedulerResponse {
        val admin = userRepository.findById(adminUserId).orElseThrow { UserNotFoundException() }
        val nextCron = request.cron?.trim()?.takeIf { it.isNotEmpty() }
        if (nextCron != null) {
            if (nextCron.length > MAX_CRON_LENGTH) {
                throw InvalidSchedulerCronException(
                    "cron 표현식이 너무 깁니다 (최대 ${MAX_CRON_LENGTH}자)."
                )
            }
            if (!CronExpression.isValidExpression(nextCron)) {
                throw InvalidSchedulerCronException("cron 표현식 형식이 잘못됐어요: '$nextCron'.")
            }
        }
        val current = loadOrCreate()
        current.update(
            enabled = request.enabled ?: current.enabled,
            cron = nextCron ?: current.cron,
            updatedBy = admin,
            at = LocalDateTime.now(),
        )
        // commit 후 runner 갱신. tx 활성화 안 됐을 때 (테스트 직접 호출 등) 는 즉시 호출.
        val effectiveEnabled = current.enabled
        val effectiveCron = current.cron
        scheduleRuntimeReschedule(effectiveEnabled, effectiveCron)
        return current.toResponse()
    }

    @Transactional
    fun runIfEnabled() {
        val settings = runCatching { loadOrCreate() }.getOrElse {
            log.warn("audit-log-retention scheduler: cannot read settings, skipping tick", it)
            return
        }
        if (!settings.enabled) {
            log.debug("audit-log-retention scheduler: disabled, skipping tick")
            return
        }
        runCatching {
            val scheduledByAdminId = settings.updatedBy?.id
            val result = moderationAuditLogArchiveService.executeScheduledArchive(scheduledByAdminId)
            log.info(
                "audit-log-retention scheduler: archived={} cutoffAt={} remaining={} scheduledBy={}",
                result.archivedCount, result.cutoffAt, result.remainingCandidateCount,
                scheduledByAdminId,
            )
        }.onFailure { ex ->
            log.error("audit-log-retention scheduler: archive failed", ex)
        }
    }

    @Transactional
    protected fun loadOrCreate(): AuditLogRetentionSchedulerSetting {
        return repository.findById(AuditLogRetentionSchedulerSetting.SINGLE_ROW_ID).orElseGet {
            repository.save(
                AuditLogRetentionSchedulerSetting(
                    id = AuditLogRetentionSchedulerSetting.SINGLE_ROW_ID,
                    enabled = false,
                    cron = DEFAULT_CRON,
                    updatedBy = null,
                    updatedAt = LocalDateTime.now(),
                )
            )
        }
    }

    /**
     * PR70 — afterCommit hook 으로 runner.reschedule 호출 예약. tx 활성화 안 된 호출 경로 (테스트
     * 직접 호출 등) 에서는 즉시 실행. runner bean 이 등록되지 않은 환경 (test profile) 에서는 no-op.
     */
    private fun scheduleRuntimeReschedule(enabled: Boolean, cron: String) {
        val runner = schedulerRunnerProvider.ifAvailable ?: return
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        runCatching { runner.reschedule(enabled, cron) }.onFailure { ex ->
                            log.warn("audit-log-retention scheduler: runtime reschedule failed", ex)
                        }
                    }
                },
            )
        } else {
            runCatching { runner.reschedule(enabled, cron) }.onFailure { ex ->
                log.warn("audit-log-retention scheduler: runtime reschedule failed", ex)
            }
        }
    }

    private fun AuditLogRetentionSchedulerSetting.toResponse(): AuditLogRetentionSchedulerResponse {
        val runner = schedulerRunnerProvider.ifAvailable
        return AuditLogRetentionSchedulerResponse(
            enabled = enabled,
            cron = cron,
            updatedBy = updatedBy?.id,
            updatedAt = updatedAt,
            runtimeScheduled = runner?.isRuntimeScheduled() ?: false,
            lastRescheduledAt = runner?.lastRescheduledAt,
        )
    }
}
