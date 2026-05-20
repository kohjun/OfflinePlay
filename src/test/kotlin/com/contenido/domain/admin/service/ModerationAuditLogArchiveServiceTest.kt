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
import com.contenido.global.exception.ArchivedModerationAuditLogNotFoundException
import com.contenido.global.exception.AuditLogArchiveConfirmationRequiredException
import com.contenido.global.exception.AuditLogArchiveStaleException
import com.contenido.global.exception.InvalidRetentionRangeException
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
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
    @MockK lateinit var systemActorService: SystemActorService

    private lateinit var service: ModerationAuditLogArchiveService

    private val FIXED_NOW: LocalDateTime = LocalDateTime.of(2026, 5, 17, 12, 0)
    private val ADMIN_ID: Long = 99L
    private val SYSTEM_ACTOR_ID: Long = 1L

    @BeforeEach
    fun setUp() {
        service = ModerationAuditLogArchiveService(
            moderationAuditLogRepository,
            moderationAuditLogArchiveRepository,
            moderationAuditLogService,
            userRepository,
            systemActorService,
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
                    "mode" to "MANUAL",
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
        // archive 행이 0건이어도 운영 추적성을 위해 audit 자체는 기록 (mode=MANUAL).
        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = ADMIN_ID,
                action = ModerationAuditAction.AUDIT_LOGS_ARCHIVED,
                targetType = null,
                targetId = null,
                beforeValue = null,
                afterValue = match<Map<String, Any?>> { it["mode"] == "MANUAL" },
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

    // ── PR67: browse / detail / export ───────────────────────────────────────

    /**
     * archive service 의 list/export 는 moderationAuditLogService.parseRangeBoundary / csvEscape
     * 에 위임 — relaxed mock 으로 두면 둘 다 default(null/"") 를 반환해 동작이 깨진다. 본 PR67
     * 테스트군이 시작될 때 실제 로직과 동치인 answers 를 한 번에 stub.
     */
    private fun stubAuditServiceHelpers() {
        every { moderationAuditLogService.parseRangeBoundary(any(), any()) } answers {
            val raw = firstArg<String?>()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@answers null
            if ('T' in raw) {
                LocalDateTime.parse(raw)
            } else {
                val date = java.time.LocalDate.parse(raw)
                if (secondArg<Boolean>()) date.atTime(23, 59, 59, 999_999_999)
                else date.atStartOfDay()
            }
        }
        every { moderationAuditLogService.csvEscape(any()) } answers {
            val v = firstArg<String?>()
            if (v.isNullOrEmpty()) ""
            else if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r'))
                "\"" + v.replace("\"", "\"\"") + "\""
            else v
        }
        // PR131 — archive CSV 가 active CSV 와 동일 helper 로 refund 파생 컬럼 10 개를 만든다.
        // mockk(relaxed=true) 의 default 는 `emptyList()` 라 row 의 컬럼 수가 어긋난다.
        every { moderationAuditLogService.csvRefundDerivedColumns(any(), any()) } returns
            List(10) { "" }
    }

    @Test
    fun `listArchived - Spec 으로 findAll 호출 + originalCreatedAt DESC pageable`() {
        stubAuditServiceHelpers()
        val pageableSlot = slot<Pageable>()
        every {
            moderationAuditLogArchiveRepository.findAll(
                any<Specification<ModerationAuditLogArchive>>(), capture(pageableSlot),
            )
        } returns PageImpl(emptyList(), Pageable.ofSize(20), 0)

        service.listArchived(page = 0, size = 20)

        val sort = pageableSlot.captured.sort.getOrderFor("originalCreatedAt")
        assertThat(sort).isNotNull
        assertThat(sort!!.direction).isEqualTo(Sort.Direction.DESC)
    }

    @Test
    fun `listArchived - 응답에 archive snapshot nickname 매핑`() {
        stubAuditServiceHelpers()
        val row = buildArchive(originalId = 7L, snapshot = "old-nickname")
        every {
            moderationAuditLogArchiveRepository.findAll(
                any<Specification<ModerationAuditLogArchive>>(), any<Pageable>(),
            )
        } returns PageImpl(listOf(row), Pageable.ofSize(20), 1)

        val page = service.listArchived(page = 0, size = 20)

        assertThat(page.content).hasSize(1)
        assertThat(page.content[0].originalId).isEqualTo(7L)
        assertThat(page.content[0].actorNicknameSnapshot).isEqualTo("old-nickname")
    }

    @Test
    fun `getArchived - 존재 시 응답 매핑`() {
        every {
            moderationAuditLogArchiveRepository.findByOriginalId(7L)
        } returns buildArchive(originalId = 7L, snapshot = "alice")

        val result = service.getArchived(7L)

        assertThat(result.originalId).isEqualTo(7L)
        assertThat(result.actorNicknameSnapshot).isEqualTo("alice")
    }

    @Test
    fun `getArchived - 미존재면 ArchivedModerationAuditLogNotFoundException`() {
        every { moderationAuditLogArchiveRepository.findByOriginalId(999L) } returns null

        assertThrows<ArchivedModerationAuditLogNotFoundException> { service.getArchived(999L) }
    }

    // ── PR130 archive detail enrichment ──────────────────────────────────────

    @Test
    fun `getArchived - TICKET_FORCED_REFUNDED row 는 forcedRefundContext 채워지고 paymentRefundContext 는 null`() {
        val afterJson = """{"ticketId":123,"paymentAttemptId":456,"amount":25000}"""
        val ctx = com.contenido.domain.admin.dto.ForcedRefundAuditContextResponse(
            ticketId = 123L, paymentAttemptId = 456L, amount = 25_000L, ticketStatus = "REFUNDED",
            buyerId = 7L, buyerNickname = "buyer", buyerEmail = "b@test.com",
            eventId = 50L, eventTitle = "ev", channelId = 30L, channelName = "ch",
            contextAvailable = true,
        )
        every {
            moderationAuditLogArchiveRepository.findByOriginalId(11L)
        } returns buildRefundArchive(11L, ModerationAuditAction.TICKET_FORCED_REFUNDED, afterJson)
        every { moderationAuditLogService.buildForcedRefundContext(afterJson) } returns ctx

        val result = service.getArchived(11L)

        assertThat(result.action).isEqualTo(ModerationAuditAction.TICKET_FORCED_REFUNDED)
        assertThat(result.forcedRefundContext).isEqualTo(ctx)
        assertThat(result.paymentRefundContext).isNull()
        verify(exactly = 1) { moderationAuditLogService.buildForcedRefundContext(afterJson) }
        verify(exactly = 0) { moderationAuditLogService.buildPaymentRefundContext(any()) }
    }

    @Test
    fun `getArchived - PAYMENT_PARTIALLY_REFUNDED row 는 paymentRefundContext 채워지고 forcedRefundContext 는 null`() {
        val afterJson = """{"ticketId":123,"refundAmount":5000,"fullRefund":false}"""
        val ctx = com.contenido.domain.admin.dto.PaymentRefundAuditContextResponse(
            ticketId = 123L, paymentAttemptId = 456L, eventId = 50L,
            refundAmount = 5000L, refundedAmount = 5000L, remainingRefundableAmount = 20_000L,
            ticketStatus = "PARTIALLY_REFUNDED", paymentStatus = "PARTIALLY_REFUNDED", fullRefund = false,
            buyerId = 7L, buyerNickname = "buyer", buyerEmail = "b@test.com",
            eventTitle = "ev", channelId = 30L, channelName = "ch",
            contextAvailable = true,
        )
        every {
            moderationAuditLogArchiveRepository.findByOriginalId(12L)
        } returns buildRefundArchive(12L, ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED, afterJson)
        every { moderationAuditLogService.buildPaymentRefundContext(afterJson) } returns ctx

        val result = service.getArchived(12L)

        assertThat(result.paymentRefundContext).isEqualTo(ctx)
        assertThat(result.forcedRefundContext).isNull()
        verify(exactly = 1) { moderationAuditLogService.buildPaymentRefundContext(afterJson) }
        verify(exactly = 0) { moderationAuditLogService.buildForcedRefundContext(any()) }
    }

    @Test
    fun `getArchived - PAYMENT_REFUNDED row 도 동일하게 paymentRefundContext 채워짐`() {
        val afterJson = """{"ticketId":123,"fullRefund":true}"""
        val ctx = com.contenido.domain.admin.dto.PaymentRefundAuditContextResponse(
            ticketId = 123L, paymentAttemptId = null, eventId = 50L,
            refundAmount = null, refundedAmount = null, remainingRefundableAmount = null,
            ticketStatus = "REFUNDED", paymentStatus = "REFUNDED", fullRefund = true,
            buyerId = 7L, buyerNickname = "buyer", buyerEmail = null,
            eventTitle = "ev", channelId = 30L, channelName = "ch",
            contextAvailable = true,
        )
        every {
            moderationAuditLogArchiveRepository.findByOriginalId(13L)
        } returns buildRefundArchive(13L, ModerationAuditAction.PAYMENT_REFUNDED, afterJson)
        every { moderationAuditLogService.buildPaymentRefundContext(afterJson) } returns ctx

        val result = service.getArchived(13L)

        assertThat(result.action).isEqualTo(ModerationAuditAction.PAYMENT_REFUNDED)
        assertThat(result.paymentRefundContext!!.fullRefund).isTrue()
        assertThat(result.forcedRefundContext).isNull()
    }

    @Test
    fun `getArchived - non-refund action 은 두 context 모두 null + helper 호출 없음`() {
        every {
            moderationAuditLogArchiveRepository.findByOriginalId(14L)
        } returns buildArchive(originalId = 14L, snapshot = "admin")

        val result = service.getArchived(14L)

        assertThat(result.forcedRefundContext).isNull()
        assertThat(result.paymentRefundContext).isNull()
        verify(exactly = 0) { moderationAuditLogService.buildForcedRefundContext(any()) }
        verify(exactly = 0) { moderationAuditLogService.buildPaymentRefundContext(any()) }
    }

    @Test
    fun `getArchived - TICKET_FORCED_REFUNDED + helper 가 contextAvailable=false 돌려도 detail 200`() {
        val afterJson = "not-json{"
        val fallback = com.contenido.domain.admin.dto.ForcedRefundAuditContextResponse(
            ticketId = null, paymentAttemptId = null, amount = null, ticketStatus = null,
            buyerId = null, buyerNickname = null, buyerEmail = null,
            eventId = null, eventTitle = null, channelId = null, channelName = null,
            contextAvailable = false,
        )
        every {
            moderationAuditLogArchiveRepository.findByOriginalId(15L)
        } returns buildRefundArchive(15L, ModerationAuditAction.TICKET_FORCED_REFUNDED, afterJson)
        every { moderationAuditLogService.buildForcedRefundContext(afterJson) } returns fallback

        val result = service.getArchived(15L)

        assertThat(result.forcedRefundContext!!.contextAvailable).isFalse()
    }

    @Test
    fun `listArchived - PAYMENT_PARTIALLY_REFUNDED row 가 있어도 list 응답은 context 둘 다 null + helper 호출 없음`() {
        val row = buildRefundArchive(
            42L, ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED,
            """{"ticketId":123,"refundAmount":5000}""",
        )
        every {
            moderationAuditLogArchiveRepository.findAll(
                any<Specification<ModerationAuditLogArchive>>(), any<Pageable>(),
            )
        } returns PageImpl(listOf(row), Pageable.ofSize(20), 1)

        val page = service.listArchived(page = 0, size = 20)

        assertThat(page.content).hasSize(1)
        assertThat(page.content[0].paymentRefundContext).isNull()
        assertThat(page.content[0].forcedRefundContext).isNull()
        verify(exactly = 0) { moderationAuditLogService.buildPaymentRefundContext(any()) }
        verify(exactly = 0) { moderationAuditLogService.buildForcedRefundContext(any()) }
    }

    private fun buildRefundArchive(
        originalId: Long,
        action: ModerationAuditAction,
        afterValue: String?,
    ): ModerationAuditLogArchive {
        val actor = createUser(id = 1L, nickname = "buyer")
        val admin = createUser(id = 99L, nickname = "admin")
        return ModerationAuditLogArchive(
            originalId = originalId,
            actor = actor,
            actorNicknameSnapshot = "buyer",
            action = action,
            targetType = null,
            targetId = null,
            beforeValue = null,
            afterValue = afterValue,
            reason = "환불",
            originalCreatedAt = LocalDateTime.of(2024, 1, 1, 0, 0),
            archivedBy = admin,
        ).apply {
            ReflectionTestUtils.setField(this, "id", originalId * 10)
            ReflectionTestUtils.setField(this, "archivedAt", LocalDateTime.of(2025, 1, 1, 0, 0))
        }
    }

    @Test
    fun `exportArchivedToCsv - 빈 결과면 헤더 1줄`() {
        stubAuditServiceHelpers()
        every {
            moderationAuditLogArchiveRepository.findAll(
                any<Specification<ModerationAuditLogArchive>>(), any<Pageable>(),
            )
        } returns PageImpl(emptyList(), Pageable.ofSize(1000), 0)

        val csv = service.exportArchivedToCsv()

        assertThat(csv).isEqualTo(ModerationAuditLogArchiveService.CSV_HEADER + "\r\n")
    }

    @Test
    fun `exportArchivedToCsv - 정상 row + 컬럼 순서 originalId 가 첫 컬럼`() {
        stubAuditServiceHelpers()
        val row = buildArchive(originalId = 42L, snapshot = "admin").apply {
            ReflectionTestUtils.setField(
                this, "originalCreatedAt",
                LocalDateTime.of(2024, 1, 1, 0, 0),
            )
            ReflectionTestUtils.setField(
                this, "archivedAt",
                LocalDateTime.of(2025, 1, 1, 0, 0),
            )
        }
        every {
            moderationAuditLogArchiveRepository.findAll(
                any<Specification<ModerationAuditLogArchive>>(), any<Pageable>(),
            )
        } returns PageImpl(listOf(row), Pageable.ofSize(1000), 1)

        val csv = service.exportArchivedToCsv()
        val lines = csv.split("\r\n")

        assertThat(lines[0]).isEqualTo(ModerationAuditLogArchiveService.CSV_HEADER)
        assertThat(lines[1]).startsWith("42,2024-01-01T00:00,2025-01-01T00:00,1,admin,TARGET_HIDDEN,REVIEW,50,")
    }

    @Test
    fun `exportArchivedToCsv - reason 의 comma 는 quote wrap`() {
        stubAuditServiceHelpers()
        val row = buildArchive(originalId = 1L, snapshot = "admin").apply {
            ReflectionTestUtils.setField(this, "reason", "스팸, 광고")
        }
        every {
            moderationAuditLogArchiveRepository.findAll(
                any<Specification<ModerationAuditLogArchive>>(), any<Pageable>(),
            )
        } returns PageImpl(listOf(row), Pageable.ofSize(1000), 1)

        val csv = service.exportArchivedToCsv()

        assertThat(csv).contains(",\"스팸, 광고\",")
    }

    @Test
    fun `exportArchivedToCsv - PR131 헤더는 PR67 prefix 11 컬럼 + refund 컬럼 10 개`() {
        // active CSV 와 같은 refund-derived 컬럼 정의를 archive CSV 도 그대로 갖는다.
        assertThat(ModerationAuditLogArchiveService.CSV_HEADER).startsWith(
            "originalId,originalCreatedAt,archivedAt,actorId,actorNickname,action,targetType,targetId,reason,beforeValue,afterValue,",
        )
        assertThat(ModerationAuditLogArchiveService.CSV_HEADER).endsWith(
            "refundKind,ticketId,paymentAttemptId,eventId,refundAmount,refundedAmount," +
                "remainingRefundableAmount,ticketStatus,paymentStatus,fullRefund",
        )
        assertThat(ModerationAuditLogArchiveService.CSV_HEADER.count { it == ',' }).isEqualTo(20) // 21 컬럼 → 20 콤마
    }

    @Test
    fun `exportArchivedToCsv - PR131 refund row 는 active helper 가 만든 10 컬럼이 그대로 append`() {
        stubAuditServiceHelpers()
        // 실제 active helper 동작을 흉내 — refund row 가 들어오면 10 개 값 반환.
        every {
            moderationAuditLogService.csvRefundDerivedColumns(
                ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED, any(),
            )
        } returns listOf(
            "PARTIAL", "123", "456", "50", "5000", "5000", "20000",
            "PARTIALLY_REFUNDED", "PARTIALLY_REFUNDED", "false",
        )
        val row = buildRefundArchive(
            42L, ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED,
            """{"ticketId":123}""",
        ).apply {
            ReflectionTestUtils.setField(this, "originalCreatedAt", LocalDateTime.of(2024, 1, 1, 0, 0))
            ReflectionTestUtils.setField(this, "archivedAt", LocalDateTime.of(2025, 1, 1, 0, 0))
        }
        every {
            moderationAuditLogArchiveRepository.findAll(
                any<Specification<ModerationAuditLogArchive>>(), any<Pageable>(),
            )
        } returns PageImpl(listOf(row), Pageable.ofSize(1000), 1)

        val csv = service.exportArchivedToCsv()
        val refundCols = csv.split("\r\n")[1].split(",").takeLast(10)
        assertThat(refundCols).containsExactly(
            "PARTIAL", "123", "456", "50", "5000", "5000", "20000",
            "PARTIALLY_REFUNDED", "PARTIALLY_REFUNDED", "false",
        )
    }

    @Test
    fun `exportArchivedToCsv - PR133 모든 row 가 정확히 21 컬럼 (action 무관)`() {
        stubAuditServiceHelpers()
        // 서로 다른 action 으로 3 row — non-refund / forced / partial. 모두 같은 컬럼 수여야 한다.
        every {
            moderationAuditLogService.csvRefundDerivedColumns(
                ModerationAuditAction.TICKET_FORCED_REFUNDED, any(),
            )
        } returns listOf("FORCED", "1", "2", "", "1000", "", "", "REFUNDED", "", "")
        every {
            moderationAuditLogService.csvRefundDerivedColumns(
                ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED, any(),
            )
        } returns listOf("PARTIAL", "3", "4", "5", "500", "500", "1500", "PARTIALLY_REFUNDED", "PARTIALLY_REFUNDED", "false")
        // non-refund 는 stub 의 default (10 개 빈 값) 그대로.
        val rows = listOf(
            buildArchive(originalId = 1L, snapshot = "admin"),
            buildRefundArchive(2L, ModerationAuditAction.TICKET_FORCED_REFUNDED, """{"ticketId":1}"""),
            buildRefundArchive(3L, ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED, """{"ticketId":3}"""),
        )
        every {
            moderationAuditLogArchiveRepository.findAll(
                any<Specification<ModerationAuditLogArchive>>(), any<Pageable>(),
            )
        } returns PageImpl(rows, Pageable.ofSize(1000), rows.size.toLong())

        val csv = service.exportArchivedToCsv()
        val dataLines = csv.split("\r\n").drop(1).filter { it.isNotEmpty() }
        val headerCommas = ModerationAuditLogArchiveService.CSV_HEADER.count { it == ',' }
        assertThat(dataLines).hasSize(3)
        dataLines.forEach { line ->
            assertThat(line.count { it == ',' }).isEqualTo(headerCommas)
        }
    }

    @Test
    fun `getArchived - PR133 detail 응답에서 한 row 가 두 context 를 동시에 갖지 않음 (mutual exclusion)`() {
        // 같은 row 가 TICKET_FORCED_REFUNDED 면 paymentRefundContext null, PAYMENT_REFUNDED 면
        // forcedRefundContext null 임을 한 케이스에 모은다 — invariant 가드.
        val forcedRow = buildRefundArchive(
            100L, ModerationAuditAction.TICKET_FORCED_REFUNDED, """{"ticketId":1}""",
        )
        val partialRow = buildRefundArchive(
            101L, ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED, """{"ticketId":2}""",
        )
        every { moderationAuditLogArchiveRepository.findByOriginalId(100L) } returns forcedRow
        every { moderationAuditLogArchiveRepository.findByOriginalId(101L) } returns partialRow
        every { moderationAuditLogService.buildForcedRefundContext(any()) } returns
            com.contenido.domain.admin.dto.ForcedRefundAuditContextResponse(
                ticketId = 1L, paymentAttemptId = null, amount = null, ticketStatus = null,
                buyerId = null, buyerNickname = null, buyerEmail = null,
                eventId = null, eventTitle = null, channelId = null, channelName = null,
                contextAvailable = false,
            )
        every { moderationAuditLogService.buildPaymentRefundContext(any()) } returns
            com.contenido.domain.admin.dto.PaymentRefundAuditContextResponse(
                ticketId = 2L, paymentAttemptId = null, eventId = null,
                refundAmount = null, refundedAmount = null, remainingRefundableAmount = null,
                ticketStatus = null, paymentStatus = null, fullRefund = null,
                buyerId = null, buyerNickname = null, buyerEmail = null,
                eventTitle = null, channelId = null, channelName = null,
                contextAvailable = false,
            )

        val forcedResult = service.getArchived(100L)
        val partialResult = service.getArchived(101L)

        // forced row: forcedRefundContext 만 채워짐
        assertThat(forcedResult.forcedRefundContext).isNotNull
        assertThat(forcedResult.paymentRefundContext).isNull()
        // partial row: paymentRefundContext 만 채워짐
        assertThat(partialResult.paymentRefundContext).isNotNull
        assertThat(partialResult.forcedRefundContext).isNull()
    }

    @Test
    fun `exportArchivedToCsv - Pageable size 가 EXPORT_LIMIT 1000 으로 고정`() {
        stubAuditServiceHelpers()
        val pageableSlot = slot<Pageable>()
        every {
            moderationAuditLogArchiveRepository.findAll(
                any<Specification<ModerationAuditLogArchive>>(), capture(pageableSlot),
            )
        } returns PageImpl(emptyList(), Pageable.ofSize(1000), 0)

        service.exportArchivedToCsv()

        assertThat(pageableSlot.captured.pageSize).isEqualTo(1000)
        assertThat(pageableSlot.captured.pageNumber).isEqualTo(0)
    }

    private fun buildArchive(originalId: Long, snapshot: String): ModerationAuditLogArchive {
        val actor = createUser(id = 1L, nickname = "current-nickname")
        val admin = createUser(id = 99L, nickname = "admin")
        return ModerationAuditLogArchive(
            originalId = originalId,
            actor = actor,
            actorNicknameSnapshot = snapshot,
            action = ModerationAuditAction.TARGET_HIDDEN,
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
            beforeValue = null,
            afterValue = null,
            reason = "정책 위반",
            originalCreatedAt = LocalDateTime.of(2024, 1, 1, 0, 0),
            archivedBy = admin,
        ).apply {
            ReflectionTestUtils.setField(this, "id", originalId * 10)
            ReflectionTestUtils.setField(this, "archivedAt", LocalDateTime.of(2025, 1, 1, 0, 0))
        }
    }

    // mockk import for `mockk(relaxed = true)` if needed by helper sites — keep linker happy.
    @Suppress("unused")
    private val unusedMapper: ObjectMapper = ObjectMapper()
    @Suppress("unused")
    private fun unusedMockkRef() = mockk<Any>()

    // ── PR69: scheduled archive uses system actor + records audit ─────────────

    @Test
    fun `executeScheduledArchive - system actor 가 archive 의 archived_by + audit actor 양쪽 사용`() {
        val systemActor = createUser(SYSTEM_ACTOR_ID, "System")
        every { systemActorService.getSystemActor() } returns systemActor
        every {
            moderationAuditLogRepository.findByCreatedAtBeforeOrderByCreatedAtAsc(any(), any())
        } returns emptyList()
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 0L

        service.executeScheduledArchive(scheduledByAdminId = null)

        verify(exactly = 1) { systemActorService.getSystemActor() }
        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = SYSTEM_ACTOR_ID,
                action = ModerationAuditAction.AUDIT_LOGS_ARCHIVED,
                targetType = null,
                targetId = null,
                beforeValue = null,
                afterValue = match<Map<String, Any?>> { it["mode"] == "SCHEDULED" },
                reason = "Scheduled audit log archive",
            )
        }
    }

    @Test
    fun `executeScheduledArchive - scheduledByAdminId 가 있으면 afterValue 에 동봉`() {
        val systemActor = createUser(SYSTEM_ACTOR_ID, "System")
        every { systemActorService.getSystemActor() } returns systemActor
        every {
            moderationAuditLogRepository.findByCreatedAtBeforeOrderByCreatedAtAsc(any(), any())
        } returns emptyList()
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 0L

        service.executeScheduledArchive(scheduledByAdminId = 7L)

        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = SYSTEM_ACTOR_ID,
                action = ModerationAuditAction.AUDIT_LOGS_ARCHIVED,
                targetType = null,
                targetId = null,
                beforeValue = null,
                afterValue = match<Map<String, Any?>> {
                    it["mode"] == "SCHEDULED" && it["scheduledBy"] == 7L
                },
                reason = "Scheduled audit log archive",
            )
        }
    }

    @Test
    fun `executeScheduledArchive - row 옮길 때 archive entity 의 archivedBy 는 system actor`() {
        val systemActor = createUser(SYSTEM_ACTOR_ID, "System")
        every { systemActorService.getSystemActor() } returns systemActor
        val batch = listOf(buildLog(id = 1L, createdAt = FIXED_NOW.minusDays(400)))
        every {
            moderationAuditLogRepository.findByCreatedAtBeforeOrderByCreatedAtAsc(any(), any())
        } returns batch
        every { moderationAuditLogRepository.countByCreatedAtBefore(any()) } returns 0L
        every { moderationAuditLogRepository.deleteAll(any<Iterable<ModerationAuditLog>>()) } returns Unit
        val saved = slot<ModerationAuditLogArchive>()
        every { moderationAuditLogArchiveRepository.save(capture(saved)) } answers { saved.captured }

        service.executeScheduledArchive()

        assertThat(saved.captured.archivedBy.id).isEqualTo(SYSTEM_ACTOR_ID)
        assertThat(saved.captured.originalId).isEqualTo(1L)
    }
}
