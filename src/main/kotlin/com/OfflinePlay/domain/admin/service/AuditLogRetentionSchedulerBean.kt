package com.contenido.domain.admin.service

import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * PR68 — @Scheduled cron 트리거. 본 bean 은 PR50 의 hang 방지 정책을 따라 test profile 에서
 * 등록되지 않는다 — `@Scheduled` 가 등록되면 non-daemon ThreadPoolTaskScheduler 가 살아 있어
 * SpringBootTest 종료가 늦어진다.
 *
 * 주의:
 *  - [@Scheduled.cron] 은 startup 시점에 한 번 해석된다. 운영 중 DB cron 변경은 다음 부팅부터
 *    반영. 부팅 없이 즉시 적용은 후속 PR (TaskScheduler 동적 등록) 에서 다룬다.
 *  - 기본값 `0 30 3 * * *` (매일 03:30 KST). 운영자가 다른 시간이 필요하면 application.yml 의
 *    `audit-log-retention.scheduler.cron` 으로 override.
 *  - timeZone Asia/Seoul — 운영팀이 한국 기준으로 새벽 시간을 의도하는 경우가 많아 명시.
 */
@Component
@Profile("!test")
class AuditLogRetentionSchedulerBean(
    private val service: AuditLogRetentionSchedulerService,
) {
    @Scheduled(
        cron = "\${audit-log-retention.scheduler.cron:0 30 3 * * *}",
        zone = "Asia/Seoul",
    )
    fun tick() {
        service.runIfEnabled()
    }
}
