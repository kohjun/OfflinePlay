package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.ExecuteAuditLogArchiveRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLog
import com.contenido.domain.admin.entity.ModerationAuditLogArchive
import com.contenido.domain.admin.repository.ModerationAuditLogArchiveRepository
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AuditLogArchiveConfirmationRequiredException
import com.contenido.global.exception.AuditLogArchiveStaleException
import com.contenido.global.exception.InvalidRetentionRangeException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.domain.Pageable
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR66 — archive 서비스 동작 검증.
 *
 *  - previewArchive: cutoff + candidateCount + willArchiveCount min(candidates, limit).
 *  - executeArchive:
 *    - confirmText 틀리면 AuditLogArchiveConfirmationRequiredException.
 *    - retentionDays 범위 밖이면 InvalidRetentionRangeException.
 *    - expectedCandidateCount mismatch 면 AuditLogArchiveStaleException.
 *    - 정상 케이스: batch 복사 + active deleteAll + AUDIT_LOGS_ARCHIVED 기록 + remaining 카운트.
 *    - hard delete 메서드는 batch deleteAll 만 — 다른 delete API 사용 없음.
 */
@ExtendWith(MockKExtension::class)
class ModerationAuditLogArchiveServiceTest {

    @MockK lateinit var moderationAuditLogRepository: ModerationAuditLogRepository
    @MockK lateinit var moderationAuditLogArchiveRepository: ModerationAuditLogArchiveRepository
    @MockK(relaxed = true) lateinit var moderationAuditLogService: ModerationAuditLogService
    @MockK lateinit var userRepository: UserRepository

    private lateinit var service: ModerationAuditLogArchiveService

    private val FIXED_NOW: LocalDateTime = LocalDateTime.of(2026, 5, 17, 12, 0)
    private val ADMIN_ID: Long = 99L

    @BeforeEach
    fun setUp() {
        service = ModerationAuditLogArchiveService(
            moderationAuditLogRepository,
            moderationAuditLogArchiveRepository,
            moderationAuditLogService,
            userRepository,
        )
    }

    // ── preview ───────────────────────────────────────────────────────────────

    @Test
    fun `previewArchive - default 365 cutoff + candidateCount`() {
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 42L
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc() } returns null
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc() } returns null

        val result = service.previewArchive(retentionDays = null, now = FIXED_NOW)

        assertThat(result.retentionDays).isEqualTo(365L)
        assertThat(result.cutoffAt).isEqualTo(FIXED_NOW.minusDays(365))
        assertThat(result.candidateCount).isEqualTo(42L)
        assertThat(result.archiveLimit).isEqualTo(1000)
        assertThat(result.willArchiveCount).isEqualTo(42L) // 42 < 1000
    }

    @Test
    fun `previewArchive - candidateCount 가 1000 초과면 willArchiveCount = 1000`() {
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 2500L
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtAsc() } returns null
        every { moderationAuditLogRepository.findFirstByOrderByCreatedAtDesc() } returns null

        val result = service.previewArchive(retentionDays = 365L, now = FIXED_NOW)

        assertThat(result.candidateCount).isEqualTo(2500L)
        assertThat(result.willArchiveCount).isEqualTo(1000L)
    }

    @Test
    fun `previewArchive - retentionDays 30 미만은 InvalidRetentionRangeException`() {
        assertThrows<InvalidRetentionRangeException> {
            service.previewArchive(retentionDays = 29L, now = FIXED_NOW)
        }
    }

    // ── execute ──────────────────────────────────────────────────────────────

    @Test
    fun `executeArchive - confirmText 가 ARCHIVE 아니면 AuditLogArchiveConfirmationRequiredException`() {
        val request = ExecuteAuditLogArchiveRequest(
            retentionDays = 365L,
            expectedCutoffAt = FIXED_NOW.minusDays(365),
            expectedCandidateCount = 0L,
            confirmText = "archive", // 소문자 — 정확 일치 아님
        )
        assertThrows<AuditLogArchiveConfirmationRequiredException> {
            service.executeArchive(ADMIN_ID, request)
        }
        verify(exactly = 0) { moderationAuditLogRepository.countByCreatedAtBefore(any()) }
    }

    @Test
    fun `executeArchive - expectedCandidateCount mismatch 시 AuditLogArchiveStaleException`() {
        val request = ExecuteAuditLogArchiveRequest(
            retentionDays = 365L,
            expectedCutoffAt = FIXED_NOW.minusDays(365),
            expectedCandidateCount = 10L,
            confirmText = "ARCHIVE",
        )
        // server 현재 count 는 11 — preview 후 새 row 가 들어왔다는 시나리오.
        every {
            moderationAuditLogRepository.countByCreatedAtBefore(FIXED_NOW.minusDays(365))
        } returns 11L

        assertThrows<AuditLogArchiveStaleException> {
            service.executeArchive(ADMIN_ID, request)
        }
        verify(exactly = 0) { moderationAuditLogArchiveRepository.save(any()) }
        verify(exactly = 0) { moderationAuditLogRepository.deleteAll(any<Iterable<ModerationAuditLog>>()) }
    }

    @Test
    fun `executeArchive - 정상 케이스 복사 + active deleteAll + AUDIT_LOGS_ARCHIVED 기록 + remaining 0`() {
        val admin = createUser(ADMIN_ID, "admin")
        val batch = listOf(
            buildLog(id = 1L, createdAt = FIXED_NOW.minusDays(400)),
            buildLog(id = 2L, createdAt = FIXED_NOW.minusDays(380)),
        )
        val cutoffAt = FIXED_NOW.minusDays(365)
        val request = ExecuteAuditLogArchiveRequest(
            retentionDays = 365L,
            expectedCutoffAt = cutoffAt,
            expectedCandidateCount = 2L,
            confirmText = "ARCHIVE",
        )

        every { moderationAuditLogRepository.countByCreatedAtBefore(cutoffAt) } returnsMany listOf(2L, 0L)
        every { userRepository.findById(ADMIN_ID) } returns Optional.of(admin)
        every {
            moderationAuditLogRepository.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoffAt, any())
        } returns batch
        val saved = slot<ModerationAuditLogArchive>()
        val savedRows = mutableListOf<ModerationAuditLogArchive>()
        every { moderationAuditLogArchiveRepository.save(capture(saved)) } answers {
            savedRows += saved.captured
            saved.captured
        }
        every { moderationAuditLogRepository.deleteAll(any<Iterable<ModerationAuditLog>>()) } returns Unit

        val result = service.executeArchive(ADMIN_ID, request)

        assertThat(result.archivedCount).isEqualTo(2L)
        assertThat(result.cutoffAt).isEqualTo(cutoffAt)
        assertThat(result.remainingCandidateCount).isEqualTo(0L)
        // archive 복사 2건.
        assertThat(savedRows).hasSize(2)
        assertThat(savedRows[0].originalId).isEqualTo(1L)
        assertThat(savedRows[0].actorNicknameSnapshot).isEqualTo("alice") // batch row 의 actor
        assertThat(savedRows[1].originalId).isEqualTo(2L)
        verify(exactly = 1) { moderationAuditLogRepository.deleteAll(batch) }
        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = ADMIN_ID,
                action = ModerationAuditAction.AUDIT_LOGS_ARCHIVED,
                targetType = null,
                targetId = null,
                beforeValue = null,
                afterValue = mapOf(
                    "archivedCount" to 2L,
                    "cutoffAt" to cutoffAt.toString(),
                    "remainingCandidateCount" to 0L,
                ),
                reason = null,
            )
        }
    }

    @Test
    fun `executeArchive - candidates 0 이면 deleteAll 호출하지 않고 archivedCount 0`() {
        val admin = createUser(ADMIN_ID, "admin")
        val cutoffAt = FIXED_NOW.minusDays(365)
        val request = ExecuteAuditLogArchiveRequest(
            retentionDays = 365L,
            expectedCutoffAt = cutoffAt,
            expectedCandidateCount = 0L,
            confirmText = "ARCHIVE",
        )
        every { moderationAuditLogRepository.countByCreatedAtBefore(cutoffAt) } returns 0L
        every { userRepository.findById(ADMIN_ID) } returns Optional.of(admin)
        every {
            moderationAuditLogRepository.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoffAt, any())
        } returns emptyList()

        val result = service.executeArchive(ADMIN_ID, request)

        assertThat(result.archivedCount).isEqualTo(0L)
        assertThat(result.remainingCandidateCount).isEqualTo(0L)
        verify(exactly = 0) { moderationAuditLogArchiveRepository.save(any()) }
        verify(exactly = 0) { moderationAuditLogRepository.deleteAll(any<Iterable<ModerationAuditLog>>()) }
        // archive 행이 0건이어도 운영 추적성을 위해 audit 자체는 기록.
        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = ADMIN_ID,
                action = ModerationAuditAction.AUDIT_LOGS_ARCHIVED,
                targetType = null,
                targetId = null,
                beforeValue = null,
                afterValue = any(),
                reason = null,
            )
        }
    }

    @Test
    fun `executeArchive - Pageable size 가 ARCHIVE_LIMIT 1000 으로 고정`() {
        val admin = createUser(ADMIN_ID, "admin")
        val cutoffAt = FIXED_NOW.minusDays(365)
        val request = ExecuteAuditLogArchiveRequest(
            retentionDays = 365L,
            expectedCutoffAt = cutoffAt,
            expectedCandidateCount = 5000L,
            confirmText = "ARCHIVE",
        )
        every { moderationAuditLogRepository.countByCreatedAtBefore(cutoffAt) } returnsMany listOf(5000L, 4000L)
        every { userRepository.findById(ADMIN_ID) } returns Optional.of(admin)
        val pageableSlot = slot<Pageable>()
        every {
            moderationAuditLogRepository.findByCreatedAtBeforeOrderByCreatedAtAsc(
                cutoffAt, capture(pageableSlot),
            )
        } returns emptyList() // batch 내용 검증은 다른 테스트에서

        service.executeArchive(ADMIN_ID, request)

        assertThat(pageableSlot.captured.pageSize).isEqualTo(1000)
        assertThat(pageableSlot.captured.pageNumber).isEqualTo(0)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun createUser(id: Long, nickname: String): User =
        User("u$id@test.com", "encoded", nickname, "0101234${id.toString().padStart(4, '0')}")
            .apply {
                ReflectionTestUtils.setField(this, "id", id)
                updateRole(UserRole.ADMIN)
            }

    private fun buildLog(id: Long, createdAt: LocalDateTime): ModerationAuditLog {
        val actor = createUser(id = 1L, nickname = "alice")
        return ModerationAuditLog(
            actor = actor,
            action = ModerationAuditAction.TARGET_HIDDEN,
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
            beforeValue = null,
            afterValue = null,
            reason = "정책 위반",
        ).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", createdAt)
        }
    }
}
