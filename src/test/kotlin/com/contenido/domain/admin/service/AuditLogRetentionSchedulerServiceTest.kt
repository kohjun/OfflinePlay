package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AuditLogArchiveResultResponse
import com.contenido.domain.admin.dto.UpdateAuditLogRetentionSchedulerRequest
import com.contenido.domain.admin.entity.AuditLogRetentionSchedulerSetting
import com.contenido.domain.admin.repository.AuditLogRetentionSchedulerSettingRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.InvalidSchedulerCronException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.ObjectProvider
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR68 — scheduler service 동작 검증.
 * PR70 — cron 사전 검증 + commit 후 runner.reschedule 호출 검증 추가.
 *
 *  - getSettings: row 없으면 default(OFF, 03:30) 생성.
 *  - updateSettings: enabled / cron / updatedBy 갱신, invalid cron 은 400.
 *  - runIfEnabled:
 *    - disabled → archive 호출 안 함.
 *    - enabled + updatedBy 없음 → system actor 가 archive (PR69).
 *    - enabled + updatedBy 있음 → executeScheduledArchive 호출.
 *    - archive 실패해도 예외가 전파되지 않음 (swallow).
 */
@ExtendWith(MockKExtension::class)
class AuditLogRetentionSchedulerServiceTest {

    @MockK lateinit var repository: AuditLogRetentionSchedulerSettingRepository
    @MockK(relaxed = true) lateinit var moderationAuditLogArchiveService: ModerationAuditLogArchiveService
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var schedulerRunnerProvider: ObjectProvider<AuditLogRetentionSchedulerRunner>
    @MockK(relaxed = true) lateinit var runner: AuditLogRetentionSchedulerRunner

    private lateinit var service: AuditLogRetentionSchedulerService

    @BeforeEach
    fun setUp() {
        // test 기본은 runner 가 등록된 상태 가정 — 일부 테스트에서 null 로 override.
        every { schedulerRunnerProvider.ifAvailable } returns runner
        every { runner.isRuntimeScheduled() } returns false
        every { runner.lastRescheduledAt } returns null
        service = AuditLogRetentionSchedulerService(
            repository,
            moderationAuditLogArchiveService,
            userRepository,
            schedulerRunnerProvider,
        )
    }

    @Test
    fun `getSettings - row 없으면 default 로 새로 만든다 (enabled=false, cron 03_30)`() {
        every { repository.findById(1L) } returns Optional.empty()
        val saved = slot<AuditLogRetentionSchedulerSetting>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val result = service.getSettings()

        assertThat(result.enabled).isFalse()
        assertThat(result.cron).isEqualTo("0 30 3 * * *")
        assertThat(result.updatedBy).isNull()
        assertThat(saved.captured.id).isEqualTo(1L)
        assertThat(saved.captured.enabled).isFalse()
    }

    @Test
    fun `getSettings - 기존 row 있으면 그대로 반환 + save 호출 안 함`() {
        val admin = createUser(7L)
        val row = buildSetting(enabled = true, cron = "0 0 4 * * *", updatedBy = admin)
        every { repository.findById(1L) } returns Optional.of(row)

        val result = service.getSettings()

        assertThat(result.enabled).isTrue()
        assertThat(result.cron).isEqualTo("0 0 4 * * *")
        assertThat(result.updatedBy).isEqualTo(7L)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `updateSettings - enabled 만 변경하면 cron 은 보존 + runner reschedule 호출`() {
        val admin = createUser(7L)
        val row = buildSetting(enabled = false, cron = "0 30 3 * * *", updatedBy = null)
        every { userRepository.findById(7L) } returns Optional.of(admin)
        every { repository.findById(1L) } returns Optional.of(row)

        val result = service.updateSettings(7L, UpdateAuditLogRetentionSchedulerRequest(enabled = true))

        assertThat(result.enabled).isTrue()
        assertThat(result.cron).isEqualTo("0 30 3 * * *")
        assertThat(result.updatedBy).isEqualTo(7L)
        assertThat(row.enabled).isTrue()
        assertThat(row.updatedBy?.id).isEqualTo(7L)
        // tx 비활성 (단위 테스트 직접 호출) → 즉시 호출.
        verify(exactly = 1) { runner.reschedule(true, "0 30 3 * * *") }
    }

    @Test
    fun `updateSettings - cron 만 변경하면 enabled 보존 + 새 cron 으로 runner reschedule`() {
        val admin = createUser(7L)
        val row = buildSetting(enabled = true, cron = "0 30 3 * * *", updatedBy = admin)
        every { userRepository.findById(7L) } returns Optional.of(admin)
        every { repository.findById(1L) } returns Optional.of(row)

        val result = service.updateSettings(
            7L, UpdateAuditLogRetentionSchedulerRequest(cron = "0 0 4 * * *"),
        )

        assertThat(result.enabled).isTrue()
        assertThat(result.cron).isEqualTo("0 0 4 * * *")
        verify(exactly = 1) { runner.reschedule(true, "0 0 4 * * *") }
    }

    @Test
    fun `updateSettings - PR70 invalid cron 이면 400 + DB 변경 안 됨 + runner 호출 안 됨`() {
        val admin = createUser(7L)
        val row = buildSetting(enabled = false, cron = "0 30 3 * * *", updatedBy = null)
        every { userRepository.findById(7L) } returns Optional.of(admin)
        every { repository.findById(1L) } returns Optional.of(row)

        assertThatThrownBy {
            service.updateSettings(
                7L,
                UpdateAuditLogRetentionSchedulerRequest(enabled = true, cron = "이건 cron 아님"),
            )
        }.isInstanceOf(InvalidSchedulerCronException::class.java)

        // row 가 update 되지 않음 (mock entity 라 상태 변화는 entity.update 가 호출 안 돼야 함).
        assertThat(row.enabled).isFalse()
        assertThat(row.cron).isEqualTo("0 30 3 * * *")
        verify(exactly = 0) { runner.reschedule(any(), any()) }
    }

    @Test
    fun `updateSettings - PR70 cron 길이 64자 초과 이면 400`() {
        val admin = createUser(7L)
        every { userRepository.findById(7L) } returns Optional.of(admin)

        // trim 후에도 64 초과해야 길이 체크에 걸린다 — non-whitespace 로 padding.
        val tooLong = "0 30 3 * * *" + "x".repeat(60)
        assertThatThrownBy {
            service.updateSettings(7L, UpdateAuditLogRetentionSchedulerRequest(cron = tooLong))
        }.isInstanceOf(InvalidSchedulerCronException::class.java)
        verify(exactly = 0) { runner.reschedule(any(), any()) }
    }

    @Test
    fun `updateSettings - PR70 runner bean 없는 환경 (test profile) 에서도 정상 동작`() {
        // ObjectProvider 가 null 반환하면 reschedule 시도 자체를 skip.
        every { schedulerRunnerProvider.ifAvailable } returns null
        val admin = createUser(7L)
        val row = buildSetting(enabled = false, cron = "0 30 3 * * *", updatedBy = null)
        every { userRepository.findById(7L) } returns Optional.of(admin)
        every { repository.findById(1L) } returns Optional.of(row)

        val result = service.updateSettings(7L, UpdateAuditLogRetentionSchedulerRequest(enabled = true))

        assertThat(result.enabled).isTrue()
        assertThat(result.runtimeScheduled).isFalse()
        assertThat(result.lastRescheduledAt).isNull()
    }

    @Test
    fun `runIfEnabled - disabled 이면 archive 호출하지 않음`() {
        val admin = createUser(7L)
        val row = buildSetting(enabled = false, cron = "0 30 3 * * *", updatedBy = admin)
        every { repository.findById(1L) } returns Optional.of(row)

        service.runIfEnabled()

        verify(exactly = 0) { moderationAuditLogArchiveService.executeScheduledArchive(any()) }
    }

    @Test
    fun `runIfEnabled - PR69 updatedBy 없어도 system actor 가 archive 실행 (scheduledByAdminId=null)`() {
        val row = buildSetting(enabled = true, cron = "0 30 3 * * *", updatedBy = null)
        every { repository.findById(1L) } returns Optional.of(row)
        every { moderationAuditLogArchiveService.executeScheduledArchive(null) } returns
            AuditLogArchiveResultResponse(
                archivedCount = 0L,
                cutoffAt = LocalDateTime.now().minusDays(365),
                remainingCandidateCount = 0L,
            )

        service.runIfEnabled()

        verify(exactly = 1) { moderationAuditLogArchiveService.executeScheduledArchive(null) }
    }

    @Test
    fun `runIfEnabled - enabled + updatedBy 가 있으면 scheduledByAdminId 로 admin id 전달`() {
        val admin = createUser(7L)
        val row = buildSetting(enabled = true, cron = "0 30 3 * * *", updatedBy = admin)
        every { repository.findById(1L) } returns Optional.of(row)
        every { moderationAuditLogArchiveService.executeScheduledArchive(7L) } returns
            AuditLogArchiveResultResponse(
                archivedCount = 5L,
                cutoffAt = LocalDateTime.now().minusDays(365),
                remainingCandidateCount = 0L,
            )

        service.runIfEnabled()

        verify(exactly = 1) { moderationAuditLogArchiveService.executeScheduledArchive(7L) }
    }

    @Test
    fun `runIfEnabled - archive 실패해도 예외 전파되지 않음 (swallow)`() {
        val admin = createUser(7L)
        val row = buildSetting(enabled = true, cron = "0 30 3 * * *", updatedBy = admin)
        every { repository.findById(1L) } returns Optional.of(row)
        every { moderationAuditLogArchiveService.executeScheduledArchive(any()) } throws
            RuntimeException("DB connection lost")

        // 예외가 전파되지 않아야 다음 tick 이 정상 동작한다.
        service.runIfEnabled()

        verify(exactly = 1) { moderationAuditLogArchiveService.executeScheduledArchive(7L) }
    }

    private fun createUser(id: Long): User =
        User("u$id@test.com", "encoded", "admin$id", "0101234${id.toString().padStart(4, '0')}")
            .apply {
                ReflectionTestUtils.setField(this, "id", id)
                updateRole(UserRole.ADMIN)
            }

    private fun buildSetting(
        enabled: Boolean,
        cron: String,
        updatedBy: User?,
    ): AuditLogRetentionSchedulerSetting =
        AuditLogRetentionSchedulerSetting(
            id = 1L,
            enabled = enabled,
            cron = cron,
            updatedBy = updatedBy,
            updatedAt = LocalDateTime.now(),
        )
}
