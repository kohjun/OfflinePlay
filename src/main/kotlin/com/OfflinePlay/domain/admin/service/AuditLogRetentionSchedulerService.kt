package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AuditLogRetentionSchedulerResponse
import com.contenido.domain.admin.dto.UpdateAuditLogRetentionSchedulerRequest
import com.contenido.domain.admin.entity.AuditLogRetentionSchedulerSetting
import com.contenido.domain.admin.repository.AuditLogRetentionSchedulerSettingRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * PR68 — audit log archive scheduler 의 운영 설정 + tick 진입점.
 *
 * 정책:
 *  - 단일 row (id=1). seed 가 없는 운영 환경에서도 첫 호출 시 default OFF 로 생성.
 *  - 기본 OFF. ADMIN 이 명시적으로 ON 으로 토글해야 [runIfEnabled] 가 실제 archive 를 실행.
 *  - 실패해도 앱 전체 중단 금지 — logger.warn / error 만 남김 (`@Scheduled` 호출자가 swallowing).
 *  - scheduler 실행은 audit 로 남기지 않는다 (system actor 모델이 없으므로) — application log 에만 기록.
 *    수동 archive 만 [ModerationAuditAction.AUDIT_LOGS_ARCHIVED] 를 audit 에 기록 (PR66).
 */
@Service
@Transactional(readOnly = true)
class AuditLogRetentionSchedulerService(
    private val repository: AuditLogRetentionSchedulerSettingRepository,
    private val moderationAuditLogArchiveService: ModerationAuditLogArchiveService,
    private val userRepository: UserRepository,
) {

    companion object {
        const val DEFAULT_CRON: String = "0 30 3 * * *"
        private const val MAX_CRON_LENGTH: Int = 64
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 현재 설정 조회. row 가 없으면 default(OFF, 03:30) 으로 새로 만든다 (V8 seed 누락 안전판).
     * read-only 트랜잭션이지만 자동 생성 분기는 별도 [@Transactional] 메서드로 분리해 안전.
     */
    fun getSettings(): AuditLogRetentionSchedulerResponse {
        return loadOrCreate().toResponse()
    }

    /** PR68 — settings 갱신. updatedBy 는 호출 admin. cron 은 형식 깊게 검증하지 않음 (길이만). */
    @Transactional
    fun updateSettings(
        adminUserId: Long,
        request: UpdateAuditLogRetentionSchedulerRequest,
    ): AuditLogRetentionSchedulerResponse {
        val admin = userRepository.findById(adminUserId).orElseThrow { UserNotFoundException() }
        val nextCron = request.cron?.trim()?.takeIf { it.isNotEmpty() }
        require((nextCron?.length ?: 0) <= MAX_CRON_LENGTH) {
            "cron 표현식이 너무 깁니다 (최대 ${MAX_CRON_LENGTH}자)."
        }
        val current = loadOrCreate()
        current.update(
            enabled = request.enabled ?: current.enabled,
            cron = nextCron ?: current.cron,
            updatedBy = admin,
            at = LocalDateTime.now(),
        )
        return current.toResponse()
    }

    /**
     * PR68 — @Scheduled bean 의 tick 진입점. enabled=true 이고 updatedBy 가 설정돼 있을 때만
     * archive 실행. 모든 예외를 swallow 해 다음 tick 영향이 없도록 한다.
     *
     * test profile 에서는 [AuditLogRetentionSchedulerBean] 이 등록되지 않아 본 메서드도 자동 호출
     * 되지 않는다 — 단위 테스트는 이 메서드를 직접 호출해 동작 검증.
     */
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
        val actor = settings.updatedBy
        if (actor == null) {
            log.warn(
                "audit-log-retention scheduler: enabled=true but no updatedBy configured — " +
                    "an ADMIN must toggle the scheduler to record an actor for archive ownership.",
            )
            return
        }
        runCatching {
            val result = moderationAuditLogArchiveService.executeScheduledArchive(actor.id)
            log.info(
                "audit-log-retention scheduler: archived={} cutoffAt={} remaining={}",
                result.archivedCount, result.cutoffAt, result.remainingCandidateCount,
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

    private fun AuditLogRetentionSchedulerSetting.toResponse() = AuditLogRetentionSchedulerResponse(
        enabled = enabled,
        cron = cron,
        updatedBy = updatedBy?.id,
        updatedAt = updatedAt,
    )
}
