package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AuditLogRetentionSchedulerResponse
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import java.time.LocalDateTime
import java.util.concurrent.ScheduledFuture

/**
 * PR70 — Runner 단위 테스트. SpringBootTest 없이 직접 인스턴스화.
 *
 *  - reschedule(true, cron) 호출 시 새 future 등록 + lastRescheduledAt 갱신.
 *  - reschedule(false, _) 호출 시 기존 future cancel + 새 등록 없음.
 *  - cron 변경 시 기존 future cancel 후 새 future 등록.
 *  - isRuntimeScheduled() 는 future 상태 그대로 노출.
 *  - onApplicationReady: 부팅 시 DB 설정으로 reschedule.
 *  - shutdown: future cancel.
 */
@ExtendWith(MockKExtension::class)
class AuditLogRetentionSchedulerRunnerTest {

    @MockK lateinit var taskScheduler: TaskScheduler
    @MockK lateinit var schedulerService: AuditLogRetentionSchedulerService

    private lateinit var runner: AuditLogRetentionSchedulerRunner

    @BeforeEach
    fun setUp() {
        runner = AuditLogRetentionSchedulerRunner(taskScheduler, schedulerService)
    }

    @Test
    fun `reschedule - enabled=true 일 때 새 future 등록 + lastRescheduledAt 갱신`() {
        val future = mockk<ScheduledFuture<*>>(relaxed = true)
        every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns future

        assertThat(runner.lastRescheduledAt).isNull()
        assertThat(runner.isRuntimeScheduled()).isFalse()

        runner.reschedule(enabled = true, cron = "0 30 3 * * *")

        verify(exactly = 1) { taskScheduler.schedule(any(), any<CronTrigger>()) }
        assertThat(runner.lastRescheduledAt).isNotNull()
    }

    @Test
    fun `reschedule - enabled=false 면 기존 future cancel + 새 등록 안 함`() {
        val firstFuture = mockk<ScheduledFuture<*>>(relaxed = true)
        every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns firstFuture

        runner.reschedule(enabled = true, cron = "0 30 3 * * *")
        runner.reschedule(enabled = false, cron = "0 30 3 * * *")

        verify(exactly = 1) { firstFuture.cancel(false) }
        // schedule 은 첫 호출만 (두 번째는 enabled=false 라 skip).
        verify(exactly = 1) { taskScheduler.schedule(any(), any<CronTrigger>()) }
    }

    @Test
    fun `reschedule - cron 변경 시 기존 future cancel 후 새 future 등록`() {
        val first = mockk<ScheduledFuture<*>>(relaxed = true)
        val second = mockk<ScheduledFuture<*>>(relaxed = true)
        every {
            taskScheduler.schedule(any(), any<CronTrigger>())
        } returnsMany listOf(first, second)

        runner.reschedule(enabled = true, cron = "0 30 3 * * *")
        runner.reschedule(enabled = true, cron = "0 0 4 * * *")

        verify(exactly = 1) { first.cancel(false) }
        verify(exactly = 2) { taskScheduler.schedule(any(), any<CronTrigger>()) }
    }

    @Test
    fun `isRuntimeScheduled - future 없으면 false, 살아있으면 true, cancelled 면 false`() {
        assertThat(runner.isRuntimeScheduled()).isFalse()

        val future = mockk<ScheduledFuture<*>>(relaxed = true)
        every { future.isCancelled } returns false
        every { future.isDone } returns false
        every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns future

        runner.reschedule(enabled = true, cron = "0 30 3 * * *")
        assertThat(runner.isRuntimeScheduled()).isTrue()

        every { future.isCancelled } returns true
        assertThat(runner.isRuntimeScheduled()).isFalse()
    }

    @Test
    fun `onApplicationReady - 부팅 시 enabled=true 이면 reschedule 등록`() {
        every { schedulerService.getSettings() } returns AuditLogRetentionSchedulerResponse(
            enabled = true,
            cron = "0 30 3 * * *",
            updatedBy = 7L,
            updatedAt = LocalDateTime.now(),
        )
        val future = mockk<ScheduledFuture<*>>(relaxed = true)
        every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns future

        runner.onApplicationReady()

        verify(exactly = 1) { taskScheduler.schedule(any(), any<CronTrigger>()) }
    }

    @Test
    fun `onApplicationReady - 부팅 시 enabled=false 이면 schedule 호출 안 함`() {
        every { schedulerService.getSettings() } returns AuditLogRetentionSchedulerResponse(
            enabled = false,
            cron = "0 30 3 * * *",
            updatedBy = null,
            updatedAt = LocalDateTime.now(),
        )

        runner.onApplicationReady()

        verify(exactly = 0) { taskScheduler.schedule(any(), any<CronTrigger>()) }
        // lastRescheduledAt 은 disabled 케이스도 기록 (reschedule 진입 자체는 했으므로).
        assertThat(runner.lastRescheduledAt).isNotNull()
    }

    @Test
    fun `onApplicationReady - getSettings 가 throw 해도 예외 전파되지 않음 (warn 로그만)`() {
        every { schedulerService.getSettings() } throws RuntimeException("DB unavailable")

        runner.onApplicationReady()

        // schedule 호출 없이 조용히 끝나야 함.
        verify(exactly = 0) { taskScheduler.schedule(any(), any<CronTrigger>()) }
    }

    @Test
    fun `shutdown - future 있으면 cancel 호출`() {
        val future = mockk<ScheduledFuture<*>>(relaxed = true)
        every { taskScheduler.schedule(any(), any<CronTrigger>()) } returns future

        runner.reschedule(enabled = true, cron = "0 30 3 * * *")
        runner.shutdown()

        verify(exactly = 1) { future.cancel(false) }
        confirmVerified(future)
    }

    @Test
    fun `shutdown - future 없어도 NPE 없이 통과`() {
        // 아무 future 도 등록하지 않은 상태.
        runner.shutdown()
        assertThat(runner.isRuntimeScheduled()).isFalse()
    }
}
