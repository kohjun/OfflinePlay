package com.contenido.domain.admin.service

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ScheduledFuture

/**
 * PR70 — audit log archive scheduler 의 동적 cron 재등록 runner.
 *
 * PR68 의 `@Scheduled` 정적 bean 을 대체. cron / enabled 변경 시 [reschedule] 을 호출하면
 * 기존 future 를 cancel 하고 새 cron 으로 다시 등록한다. ApplicationReadyEvent 에서 현재 DB
 * 설정으로 부팅 시 등록.
 *
 * Threading:
 *  - 모든 reschedule / cancel 은 `@Synchronized` — PATCH 와 boot reschedule 의 race 차단.
 *  - 이미 실행 중인 archive tick 은 cancel(false) 으로 인터럽트하지 않고 자연 종료를 기다린다.
 *
 * test profile:
 *  - `@Profile("!test")` 로 bean 등록을 막아 SpringBootTest 종료가 늦어지지 않게 한다 (PR50
 *    hang 방지 패턴).
 *  - 단위 테스트는 본 클래스를 직접 인스턴스화해서 `reschedule()` 동작을 검증.
 */
@Component
@Profile("!test")
class AuditLogRetentionSchedulerRunner(
    private val taskScheduler: TaskScheduler,
    @Lazy private val schedulerService: AuditLogRetentionSchedulerService,
) {

    companion object {
        private val SCHEDULE_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private var future: ScheduledFuture<*>? = null

    // Spring all-open plugin 이 var 를 open 으로 만들어 'private set' 을 금지하므로 backing
    // field + read-only val 패턴으로 우회.
    @Volatile
    private var lastRescheduledAtField: LocalDateTime? = null
    val lastRescheduledAt: LocalDateTime?
        get() = lastRescheduledAtField

    /** 부팅 후 현재 DB 설정을 읽어 등록. enabled=false 면 아무것도 등록하지 않는다. */
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        runCatching {
            val current = schedulerService.getSettings()
            reschedule(enabled = current.enabled, cron = current.cron)
        }.onFailure { ex ->
            log.warn("audit-log-retention scheduler runner: initial reschedule failed", ex)
        }
    }

    /**
     * 등록된 future 가 있으면 cancel 한 뒤 enabled=true 일 때만 새 [cron] 으로 등록.
     * cron 은 호출자가 이미 [org.springframework.scheduling.support.CronExpression.parse] 로
     * 사전 검증한 상태를 가정 — 잘못된 cron 을 그대로 던져 [CronTrigger] 가 실패하지 않도록.
     */
    @Synchronized
    fun reschedule(enabled: Boolean, cron: String) {
        future?.let {
            it.cancel(false)
            log.info("audit-log-retention scheduler runner: cancelled previous future")
        }
        future = null
        if (!enabled) {
            lastRescheduledAtField = LocalDateTime.now()
            log.info("audit-log-retention scheduler runner: disabled, no future scheduled")
            return
        }
        val trigger = CronTrigger(cron, SCHEDULE_ZONE)
        future = taskScheduler.schedule({
            // 본 runnable 은 새 tx 안에서 동작 — runIfEnabled() 가 @Transactional.
            schedulerService.runIfEnabled()
        }, trigger)
        lastRescheduledAtField = LocalDateTime.now()
        log.info("audit-log-retention scheduler runner: scheduled with cron='{}' zone={}", cron, SCHEDULE_ZONE)
    }

    /** 현재 future 가 살아 있는지 여부 — PATCH 응답의 runtimeScheduled 에 노출. */
    @Synchronized
    fun isRuntimeScheduled(): Boolean {
        val f = future ?: return false
        return !f.isCancelled && !f.isDone
    }

    @PreDestroy
    @Synchronized
    fun shutdown() {
        future?.cancel(false)
        future = null
        log.info("audit-log-retention scheduler runner: shutdown, future cancelled")
    }
}
