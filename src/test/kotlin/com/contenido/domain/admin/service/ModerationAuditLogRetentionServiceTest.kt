package com.contenido.domain.admin.service

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLog
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.global.exception.InvalidRetentionRangeException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

/**
 * PR64 — retention dry-run service. 실제 삭제는 발생하지 않는다.
 *
 *  - default 365 dry-run count.
 *  - 30 / 3650 boundary 허용.
 *  - 30 미만 / 3650 초과 reject.
 *  - oldest / newest 매핑.
 *  - repository 의 deleteXxx / removeXxx 가 절대 호출되지 않음을 verify.
 */
@ExtendWith(MockKExtension::class)
class ModerationAuditLogRetentionServiceTest {

    @MockK lateinit var moderationAuditLogRepository: ModerationAuditLogRepository

    private lateinit var service: ModerationAuditLogRetentionService

    private val FIXED_NOW: LocalDateTime = LocalDateTime.of(2026, 5, 17, 12, 0)

    @BeforeEach
    fun setUp() {
        service = ModerationAuditLogRetentionService(moderationAuditLogRepository)
    }

    @Test
    fun `getRetentionPolicy - default 365 cutoffAt = now - 365 + count`() {
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 42L
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc() } returns null
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc() } returns null

        val result = service.getRetentionPolicy(retentionDays = null, now = FIXED_NOW)

        assertThat(result.retentionDays).isEqualTo(365L)
        assertThat(result.cutoffAt).isEqualTo(FIXED_NOW.minusDays(365))
        assertThat(result.dryRunDeletableCount).isEqualTo(42L)
        assertThat(result.minimumRetentionDays).isEqualTo(30L)
        assertThat(result.maximumRetentionDays).isEqualTo(3650L)
        verify { moderationAuditLogRepository.countByCreatedAtBefore(FIXED_NOW.minusDays(365)) }
    }

    @Test
    fun `getRetentionPolicy - override 30 days 허용`() {
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 7L
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc() } returns null
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc() } returns null

        val result = service.getRetentionPolicy(retentionDays = 30L, now = FIXED_NOW)

        assertThat(result.retentionDays).isEqualTo(30L)
        assertThat(result.cutoffAt).isEqualTo(FIXED_NOW.minusDays(30))
        verify { moderationAuditLogRepository.countByCreatedAtBefore(FIXED_NOW.minusDays(30)) }
    }

    @Test
    fun `getRetentionPolicy - override 3650 days 허용`() {
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 0L
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc() } returns null
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc() } returns null

        val result = service.getRetentionPolicy(retentionDays = 3650L, now = FIXED_NOW)

        assertThat(result.retentionDays).isEqualTo(3650L)
        assertThat(result.cutoffAt).isEqualTo(FIXED_NOW.minusDays(3650))
    }

    @Test
    fun `getRetentionPolicy - 30 미만은 InvalidRetentionRangeException`() {
        assertThrows<InvalidRetentionRangeException> {
            service.getRetentionPolicy(retentionDays = 29L, now = FIXED_NOW)
        }
        // 거절은 DB I/O 전에 발생해야 한다.
        verify(exactly = 0) { moderationAuditLogRepository.countByCreatedAtBefore(any()) }
    }

    @Test
    fun `getRetentionPolicy - 3650 초과는 InvalidRetentionRangeException`() {
        assertThrows<InvalidRetentionRangeException> {
            service.getRetentionPolicy(retentionDays = 3651L, now = FIXED_NOW)
        }
        verify(exactly = 0) { moderationAuditLogRepository.countByCreatedAtBefore(any()) }
    }

    @Test
    fun `getRetentionPolicy - 0 또는 음수도 InvalidRetentionRangeException`() {
        assertThrows<InvalidRetentionRangeException> {
            service.getRetentionPolicy(retentionDays = 0L, now = FIXED_NOW)
        }
        assertThrows<InvalidRetentionRangeException> {
            service.getRetentionPolicy(retentionDays = -1L, now = FIXED_NOW)
        }
    }

    @Test
    fun `getRetentionPolicy - oldest 와 newest createdAt 을 그대로 매핑`() {
        val oldestLog = buildLog(createdAt = LocalDateTime.of(2024, 1, 1, 0, 0))
        val newestLog = buildLog(createdAt = LocalDateTime.of(2026, 5, 16, 23, 59))
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 1L
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc() } returns oldestLog
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc() } returns newestLog

        val result = service.getRetentionPolicy(retentionDays = 365L, now = FIXED_NOW)

        assertThat(result.oldestAuditLogCreatedAt).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0))
        assertThat(result.newestAuditLogCreatedAt).isEqualTo(LocalDateTime.of(2026, 5, 16, 23, 59))
    }

    @Test
    fun `getRetentionPolicy - row 0건이면 oldest, newest 는 null`() {
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 0L
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc() } returns null
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc() } returns null

        val result = service.getRetentionPolicy(retentionDays = null, now = FIXED_NOW)

        assertThat(result.oldestAuditLogCreatedAt).isNull()
        assertThat(result.newestAuditLogCreatedAt).isNull()
        assertThat(result.dryRunDeletableCount).isEqualTo(0L)
    }

    @Test
    fun `getRetentionPolicy - dry-run 만, repository 의 어떤 delete 도 호출하지 않는다`() {
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 100L
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc() } returns null
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc() } returns null

        service.getRetentionPolicy(retentionDays = 365L, now = FIXED_NOW)

        // delete(entity) vs delete(Specification) 가 overload 라 any() 만으로는 ambiguous.
        // 명시적 타입 인자로 entity 오버로드를 선택. Specification 쪽 (PR62 추가) 도 별도 검증.
        verify(exactly = 0) { moderationAuditLogRepository.delete(any<ModerationAuditLog>()) }
        verify(exactly = 0) { moderationAuditLogRepository.delete(any<org.springframework.data.jpa.domain.Specification<ModerationAuditLog>>()) }
        verify(exactly = 0) { moderationAuditLogRepository.deleteAll() }
        verify(exactly = 0) { moderationAuditLogRepository.deleteAll(any()) }
        verify(exactly = 0) { moderationAuditLogRepository.deleteById(any()) }
        verify(exactly = 0) { moderationAuditLogRepository.deleteAllById(any()) }
    }

    private fun buildLog(createdAt: LocalDateTime): ModerationAuditLog {
        val actor = User("a@b.c", "encoded", "admin", "01012345678").apply {
            ReflectionTestUtils.setField(this, "id", 99L)
            updateRole(UserRole.ADMIN)
        }
        return ModerationAuditLog(
            actor = actor,
            action = ModerationAuditAction.TARGET_HIDDEN,
            targetType = ReportTargetType.REVIEW,
            targetId = 1L,
            beforeValue = null,
            afterValue = null,
            reason = null,
        ).apply {
            ReflectionTestUtils.setField(this, "id", 1L)
            ReflectionTestUtils.setField(this, "createdAt", createdAt)
        }
    }
}
