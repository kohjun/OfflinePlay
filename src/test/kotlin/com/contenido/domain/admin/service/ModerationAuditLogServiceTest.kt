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
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR61 — audit log 기록/조회 동작 검증.
 *
 *  - record() : 모든 입력 + JSON 직렬화 + nickname 매핑
 *  - record() actor 미존재 → UserNotFoundException
 *  - list()   : 필터 조합별로 올바른 repository 메서드 호출
 */
@ExtendWith(MockKExtension::class)
class ModerationAuditLogServiceTest {

    @MockK lateinit var moderationAuditLogRepository: ModerationAuditLogRepository
    @MockK lateinit var userRepository: UserRepository
    // 실제 ObjectMapper 를 사용 — 직렬화 결과까지 검증해야 의미가 있다.
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
        // 비-String 값은 JSON 직렬화.
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

        // String 은 따옴표로 감싼 JSON 이 아니라 raw string.
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

    // ── list filters ──────────────────────────────────────────────────────────

    @Test
    fun `list - 필터 없으면 findAllByOrderByCreatedAtDesc`() {
        val actor = createUser(99L)
        every {
            moderationAuditLogRepository.findAllByOrderByCreatedAtDesc(any())
        } returns pageOf(buildLog(actor))

        val result = service.list(page = 0, size = 20)

        assertThat(result.content).hasSize(1)
        verify(exactly = 1) { moderationAuditLogRepository.findAllByOrderByCreatedAtDesc(any()) }
    }

    @Test
    fun `list - action 필터만 있으면 findByActionOrderByCreatedAtDesc`() {
        val actor = createUser(99L)
        every {
            moderationAuditLogRepository.findByActionOrderByCreatedAtDesc(any(), any())
        } returns pageOf(buildLog(actor))

        service.list(page = 0, size = 20, action = ModerationAuditAction.TARGET_HIDDEN)

        verify(exactly = 1) {
            moderationAuditLogRepository.findByActionOrderByCreatedAtDesc(
                ModerationAuditAction.TARGET_HIDDEN, any(),
            )
        }
    }

    @Test
    fun `list - targetType + targetId 함께 주면 단일 row 필터 메서드 호출`() {
        val actor = createUser(99L)
        every {
            moderationAuditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(any(), any(), any())
        } returns pageOf(buildLog(actor))

        service.list(page = 0, size = 20, targetType = ReportTargetType.REVIEW, targetId = 50L)

        verify(exactly = 1) {
            moderationAuditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                ReportTargetType.REVIEW, 50L, any(),
            )
        }
    }

    @Test
    fun `list - action + target 전체 필터는 가장 좁은 메서드 호출`() {
        val actor = createUser(99L)
        every {
            moderationAuditLogRepository
                .findByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(any(), any(), any(), any())
        } returns pageOf(buildLog(actor))

        service.list(
            page = 0, size = 20,
            action = ModerationAuditAction.TARGET_HIDDEN,
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
        )

        verify(exactly = 1) {
            moderationAuditLogRepository.findByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                ModerationAuditAction.TARGET_HIDDEN, ReportTargetType.REVIEW, 50L, any(),
            )
        }
    }

    @Test
    fun `list - 응답에 actorNickname 매핑`() {
        val actor = createUser(99L, nickname = "moderator")
        every {
            moderationAuditLogRepository.findAllByOrderByCreatedAtDesc(any())
        } returns pageOf(buildLog(actor))

        val result = service.list(page = 0, size = 20)

        assertThat(result.content[0].actorNickname).isEqualTo("moderator")
        assertThat(result.content[0].actorId).isEqualTo(99L)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

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

    private fun pageOf(vararg rows: ModerationAuditLog) =
        PageImpl(rows.toList(), Pageable.ofSize(20), rows.size.toLong())
}
