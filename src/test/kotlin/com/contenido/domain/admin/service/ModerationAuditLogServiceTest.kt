package com.contenido.domain.admin.service

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLog
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UserNotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
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
import java.time.format.DateTimeParseException
import java.util.Optional

/**
 * PR61 + PR62 — audit log 기록/조회 검증.
 *
 *  - record() : 모든 입력 + JSON 직렬화 + actor lookup
 *  - record() actor 미존재 → UserNotFoundException
 *  - list()   : Specification 기반 호출, Pageable 정렬은 createdAt DESC
 *  - parseRangeBoundary : datetime / date-only / endOfDay 분기
 *  - Specification 합성 : 각 필터가 cb.equal / cb.greaterThanOrEqualTo / cb.lessThanOrEqualTo 호출
 */
@ExtendWith(MockKExtension::class)
class ModerationAuditLogServiceTest {

    @MockK lateinit var moderationAuditLogRepository: ModerationAuditLogRepository
    @MockK lateinit var userRepository: UserRepository
    private val objectMapper = ObjectMapper()

    private lateinit var service: ModerationAuditLogService

    @BeforeEach
    fun setUp() {
        service = ModerationAuditLogService(
            moderationAuditLogRepository,
            userRepository,
            objectMapper,
        )
    }

    // ── record ───────────────────────────────────────────────────────────────

    @Test
    fun `record - 모든 필드 직렬화 + actor lookup + save`() {
        val actor = createUser(99L, nickname = "admin")
        every { userRepository.findById(99L) } returns Optional.of(actor)
        val captured = slot<ModerationAuditLog>()
        every { moderationAuditLogRepository.save(capture(captured)) } answers {
            captured.captured.also {
                ReflectionTestUtils.setField(it, "id", 1L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
            }
        }

        service.record(
            actorId = 99L,
            action = ModerationAuditAction.TARGET_HIDDEN,
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
            beforeValue = mapOf("hidden" to false),
            afterValue = mapOf("hidden" to true),
            reason = "정책 위반",
        )

        assertThat(captured.captured.actor).isSameAs(actor)
        assertThat(captured.captured.action).isEqualTo(ModerationAuditAction.TARGET_HIDDEN)
        assertThat(captured.captured.targetType).isEqualTo(ReportTargetType.REVIEW)
        assertThat(captured.captured.targetId).isEqualTo(50L)
        assertThat(captured.captured.beforeValue).contains("\"hidden\":false")
        assertThat(captured.captured.afterValue).contains("\"hidden\":true")
        assertThat(captured.captured.reason).isEqualTo("정책 위반")
    }

    @Test
    fun `record - String 값은 JSON 으로 감싸지 않고 그대로 저장`() {
        val actor = createUser(99L)
        every { userRepository.findById(99L) } returns Optional.of(actor)
        val captured = slot<ModerationAuditLog>()
        every { moderationAuditLogRepository.save(capture(captured)) } answers { captured.captured }

        service.record(
            actorId = 99L,
            action = ModerationAuditAction.TARGET_HIDDEN,
            beforeValue = "기존 사유",
            afterValue = "새 사유",
        )

        assertThat(captured.captured.beforeValue).isEqualTo("기존 사유")
        assertThat(captured.captured.afterValue).isEqualTo("새 사유")
    }

    @Test
    fun `record - null 값은 null 그대로 저장`() {
        val actor = createUser(99L)
        every { userRepository.findById(99L) } returns Optional.of(actor)
        val captured = slot<ModerationAuditLog>()
        every { moderationAuditLogRepository.save(capture(captured)) } answers { captured.captured }

        service.record(actorId = 99L, action = ModerationAuditAction.CHANNEL_UNBANNED)

        assertThat(captured.captured.beforeValue).isNull()
        assertThat(captured.captured.afterValue).isNull()
        assertThat(captured.captured.reason).isNull()
        assertThat(captured.captured.targetType).isNull()
        assertThat(captured.captured.targetId).isNull()
    }

    @Test
    fun `record - actor 미존재 시 UserNotFoundException + save 호출 안 됨`() {
        every { userRepository.findById(999L) } returns Optional.empty()

        assertThrows<UserNotFoundException> {
            service.record(actorId = 999L, action = ModerationAuditAction.TARGET_HIDDEN)
        }
        verify(exactly = 0) { moderationAuditLogRepository.save(any()) }
    }

    @Test
    fun `record - reason 이 500자 초과면 잘려서 저장`() {
        val actor = createUser(99L)
        every { userRepository.findById(99L) } returns Optional.of(actor)
        val captured = slot<ModerationAuditLog>()
        every { moderationAuditLogRepository.save(capture(captured)) } answers { captured.captured }
        val longReason = "x".repeat(600)

        service.record(actorId = 99L, action = ModerationAuditAction.TARGET_HIDDEN, reason = longReason)

        assertThat(captured.captured.reason).hasSize(500)
    }

    // ── list - Specification wiring (PR62) ───────────────────────────────────

    @Test
    fun `list - 필터 없으면 빈 Specification + createdAt DESC pageable`() {
        val actor = createUser(99L)
        val pageableSlot = slot<Pageable>()
        every {
            moderationAuditLogRepository.findAll(any<Specification<ModerationAuditLog>>(), capture(pageableSlot))
        } returns PageImpl(listOf(buildLog(actor)), Pageable.ofSize(20), 1)

        service.list(page = 0, size = 20)

        // 정렬은 createdAt DESC 고정.
        val sort = pageableSlot.captured.sort.getOrderFor("createdAt")
        assertThat(sort).isNotNull
        assertThat(sort!!.direction).isEqualTo(Sort.Direction.DESC)
    }

    @Test
    fun `list - action 필터는 cb_equal(action, value) 호출`() {
        val spec = capturedSpecFor {
            service.list(page = 0, size = 20, action = ModerationAuditAction.TARGET_HIDDEN)
        }
        val (root, cb) = buildCriteriaMocks()

        spec.toPredicate(root, mockk(relaxed = true), cb)

        verify {
            cb.equal(any<Path<ModerationAuditAction>>(), ModerationAuditAction.TARGET_HIDDEN)
        }
    }

    @Test
    fun `list - targetType + targetId 모두 주면 두 predicates 호출`() {
        val spec = capturedSpecFor {
            service.list(
                page = 0, size = 20,
                targetType = ReportTargetType.REVIEW,
                targetId = 50L,
            )
        }
        val (root, cb) = buildCriteriaMocks()

        spec.toPredicate(root, mockk(relaxed = true), cb)

        verify { cb.equal(any<Path<ReportTargetType>>(), ReportTargetType.REVIEW) }
        verify { cb.equal(any<Path<Long>>(), 50L) }
    }

    @Test
    fun `list - actorId 필터는 root_get(actor)_get(id) 경로로 cb_equal 호출 (PR62)`() {
        val spec = capturedSpecFor {
            service.list(page = 0, size = 20, actorId = 7L)
        }
        val (root, cb) = buildCriteriaMocks()

        spec.toPredicate(root, mockk(relaxed = true), cb)

        verify { cb.equal(any<Path<Long>>(), 7L) }
    }

    @Test
    fun `list - from datetime 은 greaterThanOrEqualTo 호출 (PR62)`() {
        val spec = capturedSpecFor {
            service.list(page = 0, size = 20, from = "2026-05-17T08:30:00")
        }
        val (root, cb) = buildCriteriaMocks()

        spec.toPredicate(root, mockk(relaxed = true), cb)

        verify {
            cb.greaterThanOrEqualTo(any<Path<LocalDateTime>>(), LocalDateTime.parse("2026-05-17T08:30:00"))
        }
    }

    @Test
    fun `list - to date-only 는 23_59_59_999999999 로 확장해 lessThanOrEqualTo 호출 (PR62)`() {
        val spec = capturedSpecFor {
            service.list(page = 0, size = 20, to = "2026-05-17")
        }
        val (root, cb) = buildCriteriaMocks()

        spec.toPredicate(root, mockk(relaxed = true), cb)

        verify {
            cb.lessThanOrEqualTo(
                any<Path<LocalDateTime>>(),
                LocalDateTime.of(2026, 5, 17, 23, 59, 59, 999_999_999),
            )
        }
    }

    @Test
    fun `list - from date-only 는 00_00 로 확장해 greaterThanOrEqualTo 호출 (PR62)`() {
        val spec = capturedSpecFor {
            service.list(page = 0, size = 20, from = "2026-05-17")
        }
        val (root, cb) = buildCriteriaMocks()

        spec.toPredicate(root, mockk(relaxed = true), cb)

        verify {
            cb.greaterThanOrEqualTo(
                any<Path<LocalDateTime>>(),
                LocalDateTime.of(2026, 5, 17, 0, 0, 0, 0),
            )
        }
    }

    @Test
    fun `list - 복합 필터 (action + actorId + from + to) 모두 predicate 추가 (PR62)`() {
        val spec = capturedSpecFor {
            service.list(
                page = 0, size = 20,
                action = ModerationAuditAction.THRESHOLD_UPDATED,
                actorId = 7L,
                from = "2026-05-01",
                to = "2026-05-17",
            )
        }
        val (root, cb) = buildCriteriaMocks()

        spec.toPredicate(root, mockk(relaxed = true), cb)

        verify { cb.equal(any<Path<ModerationAuditAction>>(), ModerationAuditAction.THRESHOLD_UPDATED) }
        verify { cb.equal(any<Path<Long>>(), 7L) }
        verify {
            cb.greaterThanOrEqualTo(
                any<Path<LocalDateTime>>(),
                LocalDateTime.of(2026, 5, 1, 0, 0, 0, 0),
            )
        }
        verify {
            cb.lessThanOrEqualTo(
                any<Path<LocalDateTime>>(),
                LocalDateTime.of(2026, 5, 17, 23, 59, 59, 999_999_999),
            )
        }
    }

    // ── parseRangeBoundary 직접 단위 테스트 ──────────────────────────────────

    @Test
    fun `parseRangeBoundary - null과 공백은 null`() {
        assertThat(service.parseRangeBoundary(null, endOfDay = false)).isNull()
        assertThat(service.parseRangeBoundary("", endOfDay = false)).isNull()
        assertThat(service.parseRangeBoundary("   ", endOfDay = true)).isNull()
    }

    @Test
    fun `parseRangeBoundary - datetime 은 endOfDay 와 무관하게 그대로 파싱`() {
        val t = "2026-05-17T08:30:15"
        assertThat(service.parseRangeBoundary(t, endOfDay = false))
            .isEqualTo(LocalDateTime.parse(t))
        assertThat(service.parseRangeBoundary(t, endOfDay = true))
            .isEqualTo(LocalDateTime.parse(t))
    }

    @Test
    fun `parseRangeBoundary - date-only 는 endOfDay 에 따라 자정 또는 일 끝`() {
        assertThat(service.parseRangeBoundary("2026-05-17", endOfDay = false))
            .isEqualTo(LocalDateTime.of(2026, 5, 17, 0, 0, 0, 0))
        assertThat(service.parseRangeBoundary("2026-05-17", endOfDay = true))
            .isEqualTo(LocalDateTime.of(2026, 5, 17, 23, 59, 59, 999_999_999))
    }

    @Test
    fun `parseRangeBoundary - 파싱 실패는 DateTimeParseException`() {
        assertThrows<DateTimeParseException> {
            service.parseRangeBoundary("not-a-date", endOfDay = false)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * 서비스의 list() 한 번 호출에서 findAll 에 전달된 Specification 을 캡처. 호출자가 list() 의
     * 인자를 다양하게 줘 가며 합성 결과를 검증할 수 있게 한다.
     */
    private fun capturedSpecFor(block: () -> Unit): Specification<ModerationAuditLog> {
        val specSlot = slot<Specification<ModerationAuditLog>>()
        every {
            moderationAuditLogRepository.findAll(capture(specSlot), any<Pageable>())
        } returns PageImpl(emptyList(), Pageable.ofSize(20), 0)
        block()
        return specSlot.captured
    }

    /**
     * Specification.toPredicate 호출에 필요한 최소 mock. relaxed=true 라 모든 메서드가 mock 을
     * 반환하므로 spec 본문이 NPE 없이 끝까지 실행된다.
     */
    private fun buildCriteriaMocks(): Pair<Root<ModerationAuditLog>, CriteriaBuilder> {
        val root: Root<ModerationAuditLog> = mockk(relaxed = true)
        val cb: CriteriaBuilder = mockk(relaxed = true)
        // and(...) 가 Predicate 를 반환해야 spec 본문이 정상 종료.
        every { cb.and(*anyVararg<Predicate>()) } returns mockk(relaxed = true)
        every { cb.conjunction() } returns mockk(relaxed = true)
        return root to cb
    }

    private fun createUser(id: Long, nickname: String = "user$id"): User =
        User("admin$id@test.com", "encoded", nickname, "0101234${id.toString().padStart(4, '0')}")
            .apply {
                ReflectionTestUtils.setField(this, "id", id)
                updateRole(UserRole.ADMIN)
            }

    private fun buildLog(actor: User): ModerationAuditLog =
        ModerationAuditLog(
            actor = actor,
            action = ModerationAuditAction.TARGET_HIDDEN,
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
            beforeValue = null,
            afterValue = null,
            reason = "사유",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 1L)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        }

    @Suppress("unused")
    private fun unusedCriteriaQueryRef(q: CriteriaQuery<*>) = q
}
