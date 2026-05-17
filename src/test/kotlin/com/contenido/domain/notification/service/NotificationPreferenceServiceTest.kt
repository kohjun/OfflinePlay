package com.contenido.domain.notification.service

import com.contenido.domain.notification.dto.UpdateNotificationPreferenceItem
import com.contenido.domain.notification.dto.UpdateNotificationPreferencesRequest
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.entity.UserNotificationPreference
import com.contenido.domain.notification.repository.UserNotificationPreferenceRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR95 — NotificationPreferenceService 의 단위 테스트.
 *
 * 핵심 정책:
 *  - row 가 없는 NotificationType 은 응답에서 enabled=true
 *  - patch 는 type 별 upsert + 기존 row 는 update, 없는 type 은 insert
 *  - request 에 누락된 type 은 기존 값 유지
 *  - isEnabled 는 row 없음 / 조회 예외 모두 true 로 fallback (fail-open)
 */
@ExtendWith(MockKExtension::class)
class NotificationPreferenceServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var preferenceRepository: UserNotificationPreferenceRepository

    private lateinit var service: NotificationPreferenceService

    private val USER_ID: Long = 1L
    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        service = NotificationPreferenceService(userRepository, preferenceRepository)
        user = User("u1@test.com", "pwd", "user1", "01011112222").apply {
            ReflectionTestUtils.setField(this, "id", USER_ID)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }
        every { userRepository.findById(USER_ID) } returns Optional.of(user)
    }

    @Test
    fun `getMyPreferences row 가 없으면 모든 type 이 enabled true`() {
        every { preferenceRepository.findByUserId(USER_ID) } returns emptyList()

        val response = service.getMyPreferences(USER_ID)

        val all = NotificationType.values().toList()
        assertThat(response).hasSize(all.size)
        assertThat(response.map { it.type }).containsExactlyInAnyOrderElementsOf(all)
        assertThat(response.all { it.enabled }).isTrue()
    }

    @Test
    fun `getMyPreferences 일부 row 가 false 면 해당 type 만 false`() {
        val savedFalse = preferenceFixture(NotificationType.NEW_COMMENT, enabled = false)
        every { preferenceRepository.findByUserId(USER_ID) } returns listOf(savedFalse)

        val response = service.getMyPreferences(USER_ID)

        val newCommentRow = response.first { it.type == NotificationType.NEW_COMMENT }
        assertThat(newCommentRow.enabled).isFalse()
        val others = response.filter { it.type != NotificationType.NEW_COMMENT }
        assertThat(others.all { it.enabled }).isTrue()
    }

    @Test
    fun `updateMyPreferences 신규 type 은 insert 되고 기존 type 은 update 된다`() {
        // 기존 NEW_COMMENT=true 만 있음.
        val existing = preferenceFixture(NotificationType.NEW_COMMENT, enabled = true)
        every { preferenceRepository.findByUser(user) } returns listOf(existing)
        every { preferenceRepository.findByUserId(USER_ID) } returns listOf(existing)
        val saveSlot = slot<UserNotificationPreference>()
        every { preferenceRepository.save(capture(saveSlot)) } answers { saveSlot.captured }

        val request = UpdateNotificationPreferencesRequest(
            listOf(
                UpdateNotificationPreferenceItem(NotificationType.NEW_COMMENT, false),  // update existing
                UpdateNotificationPreferenceItem(NotificationType.NEW_LIKE, false),     // insert new
            )
        )

        service.updateMyPreferences(USER_ID, request)

        // 기존 row 가 in-place update — false 로 바뀜.
        assertThat(existing.enabled).isFalse()
        // 새 row 가 save 됨 (NEW_LIKE, false).
        verify(exactly = 1) { preferenceRepository.save(any()) }
        assertThat(saveSlot.captured.notificationType).isEqualTo(NotificationType.NEW_LIKE)
        assertThat(saveSlot.captured.enabled).isFalse()
    }

    @Test
    fun `updateMyPreferences request 에 없는 type 은 건드리지 않는다`() {
        val keptTrue = preferenceFixture(NotificationType.NEW_COMMENT, enabled = true)
        every { preferenceRepository.findByUser(user) } returns listOf(keptTrue)
        every { preferenceRepository.findByUserId(USER_ID) } returns listOf(keptTrue)

        // 다른 type 만 갱신.
        val request = UpdateNotificationPreferencesRequest(
            listOf(UpdateNotificationPreferenceItem(NotificationType.NEW_LIKE, false))
        )
        every { preferenceRepository.save(any()) } answers { firstArg() }

        service.updateMyPreferences(USER_ID, request)

        // NEW_COMMENT 는 그대로 true.
        assertThat(keptTrue.enabled).isTrue()
    }

    @Test
    fun `updateMyPreferences 같은 type 중복은 마지막 값으로 upsert`() {
        every { preferenceRepository.findByUser(user) } returns emptyList()
        every { preferenceRepository.findByUserId(USER_ID) } returns emptyList()
        val saveSlot = slot<UserNotificationPreference>()
        every { preferenceRepository.save(capture(saveSlot)) } answers { saveSlot.captured }

        val request = UpdateNotificationPreferencesRequest(
            listOf(
                UpdateNotificationPreferenceItem(NotificationType.NEW_LIKE, true),
                UpdateNotificationPreferenceItem(NotificationType.NEW_LIKE, false),  // 마지막 값
            )
        )

        service.updateMyPreferences(USER_ID, request)

        verify(exactly = 1) { preferenceRepository.save(any()) }
        assertThat(saveSlot.captured.enabled).isFalse()
    }

    @Test
    fun `isEnabled row 없으면 true`() {
        every {
            preferenceRepository.findByUserIdAndNotificationType(USER_ID, NotificationType.NEW_COMMENT)
        } returns null

        assertThat(service.isEnabled(USER_ID, NotificationType.NEW_COMMENT)).isTrue()
    }

    @Test
    fun `isEnabled row 가 false 면 false`() {
        val row = preferenceFixture(NotificationType.NEW_COMMENT, enabled = false)
        every {
            preferenceRepository.findByUserIdAndNotificationType(USER_ID, NotificationType.NEW_COMMENT)
        } returns row

        assertThat(service.isEnabled(USER_ID, NotificationType.NEW_COMMENT)).isFalse()
    }

    @Test
    fun `isEnabled 조회 예외 시 fail-open 으로 true`() {
        every {
            preferenceRepository.findByUserIdAndNotificationType(any(), any())
        } throws RuntimeException("db down")

        assertThat(service.isEnabled(USER_ID, NotificationType.NEW_COMMENT)).isTrue()
    }

    // ── PR104 — updatedAt 표시 ───────────────────────────────────────────────

    @Test
    fun `getMyPreferences row 없는 type 은 updatedAt null`() {
        every { preferenceRepository.findByUserId(USER_ID) } returns emptyList()

        val response = service.getMyPreferences(USER_ID)

        assertThat(response.all { it.updatedAt == null }).isTrue()
    }

    @Test
    fun `getMyPreferences row 있는 type 은 row updatedAt 그대로 반환`() {
        val savedAt = LocalDateTime.of(2026, 5, 1, 12, 0)
        val row = preferenceFixture(NotificationType.NEW_COMMENT, enabled = false, updatedAt = savedAt)
        every { preferenceRepository.findByUserId(USER_ID) } returns listOf(row)

        val response = service.getMyPreferences(USER_ID)

        val newComment = response.first { it.type == NotificationType.NEW_COMMENT }
        assertThat(newComment.updatedAt).isEqualTo(savedAt)
        val others = response.filter { it.type != NotificationType.NEW_COMMENT }
        assertThat(others.all { it.updatedAt == null }).isTrue()
    }

    @Test
    fun `updateMyPreferences 후 변경된 type 은 updatedAt 이 갱신된다`() {
        val oldAt = LocalDateTime.now().minusDays(7)
        val existing = preferenceFixture(NotificationType.NEW_COMMENT, enabled = true, updatedAt = oldAt)
        every { preferenceRepository.findByUser(user) } returns listOf(existing)
        every { preferenceRepository.findByUserId(USER_ID) } returns listOf(existing)

        val request = UpdateNotificationPreferencesRequest(
            listOf(UpdateNotificationPreferenceItem(NotificationType.NEW_COMMENT, false))
        )

        val before = LocalDateTime.now()
        val response = service.updateMyPreferences(USER_ID, request)
        val after = LocalDateTime.now()

        val newComment = response.first { it.type == NotificationType.NEW_COMMENT }
        // entity.update() 가 LocalDateTime.now() 를 직접 set 하므로 호출 전후 사이 값이어야 한다.
        assertThat(newComment.updatedAt).isNotNull
        assertThat(newComment.updatedAt!!).isAfterOrEqualTo(before).isBeforeOrEqualTo(after)
        // 변경 결과로 enabled 도 false 로 바뀌어야 한다 (회귀 가드).
        assertThat(newComment.enabled).isFalse()
    }

    @Test
    fun `updateMyPreferences request 에 없는 type 은 기존 updatedAt 유지`() {
        val keptAt = LocalDateTime.of(2026, 4, 1, 9, 30)
        val keptRow = preferenceFixture(NotificationType.NEW_COMMENT, enabled = true, updatedAt = keptAt)
        every { preferenceRepository.findByUser(user) } returns listOf(keptRow)
        every { preferenceRepository.findByUserId(USER_ID) } returns listOf(keptRow)
        every { preferenceRepository.save(any()) } answers { firstArg() }

        // 다른 type 만 갱신 — keptRow 는 건드리지 않는다.
        val request = UpdateNotificationPreferencesRequest(
            listOf(UpdateNotificationPreferenceItem(NotificationType.NEW_LIKE, false))
        )

        val response = service.updateMyPreferences(USER_ID, request)

        val keptResp = response.first { it.type == NotificationType.NEW_COMMENT }
        assertThat(keptResp.updatedAt).isEqualTo(keptAt)
        assertThat(keptResp.enabled).isTrue()
    }

    /**
     * 본 엔티티는 `lateinit var updatedAt`. 운영에서는 JPA 의 `@CreatedDate / @LastModifiedDate`
     * 리스너가 자동 채워 주지만 unit test 에서는 ReflectionTestUtils 로 직접 박는다.
     */
    private fun preferenceFixture(
        type: NotificationType,
        enabled: Boolean,
        updatedAt: LocalDateTime = LocalDateTime.now().minusDays(1),
    ): UserNotificationPreference {
        val now = LocalDateTime.now()
        return UserNotificationPreference(user = user, notificationType = type, enabled = enabled).apply {
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", updatedAt)
        }
    }
}
