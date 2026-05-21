package com.contenido.domain.notification.service

import com.contenido.domain.notification.dto.PushSubscriptionKeysRequest
import com.contenido.domain.notification.dto.PushSubscriptionRequest
import com.contenido.domain.notification.entity.UserPushSubscription
import com.contenido.domain.notification.repository.UserPushSubscriptionRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.InvalidPushSubscriptionException
import com.contenido.global.exception.UserNotFoundException
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
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR139 — Web Push 구독 저장/upsert/해제 단위 테스트.
 */
@ExtendWith(MockKExtension::class)
class PushSubscriptionServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var subscriptionRepository: UserPushSubscriptionRepository

    private lateinit var service: PushSubscriptionService

    @BeforeEach
    fun setUp() {
        service = PushSubscriptionService(userRepository, subscriptionRepository)
    }

    @Test
    fun `처음 보는 endpoint 는 새 row 로 저장`() {
        val user = createUser(7L)
        val request = subRequest(endpoint = "https://fcm.googleapis.com/fcm/send/abc-1")
        every { userRepository.findById(7L) } returns Optional.of(user)
        every { subscriptionRepository.findByUserAndEndpointHash(user, any()) } returns null
        val savedSlot = slot<UserPushSubscription>()
        every { subscriptionRepository.save(capture(savedSlot)) } answers {
            val captured = savedSlot.captured
            ReflectionTestUtils.setField(captured, "id", 100L)
            ReflectionTestUtils.setField(captured, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(captured, "updatedAt", LocalDateTime.now())
            captured
        }

        val result = service.subscribe(7L, request)

        assertThat(result.id).isEqualTo(100L)
        assertThat(result.enabled).isTrue()
        assertThat(savedSlot.captured.endpoint).isEqualTo("https://fcm.googleapis.com/fcm/send/abc-1")
        assertThat(savedSlot.captured.endpointHash).hasSize(64)
        assertThat(savedSlot.captured.p256dh).isEqualTo("p256dh-value")
        assertThat(savedSlot.captured.auth).isEqualTo("auth-value")
    }

    @Test
    fun `같은 endpoint 재등록은 새 row 를 만들지 않고 credential 만 갱신`() {
        val user = createUser(7L)
        val endpoint = "https://fcm.googleapis.com/fcm/send/abc-1"
        val existing = UserPushSubscription(
            user = user,
            endpoint = endpoint,
            endpointHash = PushSubscriptionService.sha256Hex(endpoint),
            p256dh = "old-p256dh",
            auth = "old-auth",
            userAgent = "OldUA",
            enabled = false,  // 이전 발송 실패로 disabled 였다고 가정
        ).also {
            ReflectionTestUtils.setField(it, "id", 50L)
            ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now().minusDays(1))
            ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now().minusDays(1))
        }
        every { userRepository.findById(7L) } returns Optional.of(user)
        every { subscriptionRepository.findByUserAndEndpointHash(user, any()) } returns existing

        val result = service.subscribe(
            7L,
            subRequest(
                endpoint = endpoint,
                p256dh = "new-p256dh",
                auth = "new-auth",
                userAgent = "NewUA",
            ),
        )

        assertThat(result.id).isEqualTo(50L)
        assertThat(existing.p256dh).isEqualTo("new-p256dh")
        assertThat(existing.auth).isEqualTo("new-auth")
        assertThat(existing.userAgent).isEqualTo("NewUA")
        assertThat(existing.enabled).isTrue()
        assertThat(existing.lastSeenAt).isNotNull()
        verify(exactly = 0) { subscriptionRepository.save(any()) }
    }

    @Test
    fun `unsubscribe 는 매칭되는 row 를 삭제하고 삭제 건수를 반환`() {
        val user = createUser(7L)
        every { userRepository.findById(7L) } returns Optional.of(user)
        every { subscriptionRepository.deleteByUserAndEndpointHash(user, any()) } returns 1L

        val removed = service.unsubscribe(7L, "https://fcm.googleapis.com/fcm/send/abc-1")

        assertThat(removed).isEqualTo(1)
        verify(exactly = 1) { subscriptionRepository.deleteByUserAndEndpointHash(user, any()) }
    }

    @Test
    fun `unsubscribe — 없는 endpoint 면 0 을 반환`() {
        val user = createUser(7L)
        every { userRepository.findById(7L) } returns Optional.of(user)
        every { subscriptionRepository.deleteByUserAndEndpointHash(user, any()) } returns 0L

        val removed = service.unsubscribe(7L, "https://example.com/missing")

        assertThat(removed).isEqualTo(0)
    }

    @Test
    fun `https 가 아닌 endpoint 는 InvalidPushSubscriptionException`() {
        val user = createUser(7L)
        every { userRepository.findById(7L) } returns Optional.of(user)

        assertThatThrownBy {
            service.subscribe(
                7L,
                subRequest(endpoint = "http://insecure.example.com/push"),
            )
        }.isInstanceOf(InvalidPushSubscriptionException::class.java)
    }

    @Test
    fun `endpoint 가 너무 짧으면 InvalidPushSubscriptionException`() {
        val user = createUser(7L)
        every { userRepository.findById(7L) } returns Optional.of(user)

        assertThatThrownBy {
            service.subscribe(7L, subRequest(endpoint = "https://"))
        }.isInstanceOf(InvalidPushSubscriptionException::class.java)
    }

    @Test
    fun `keys 누락 — p256dh blank 면 InvalidPushSubscriptionException`() {
        val user = createUser(7L)
        every { userRepository.findById(7L) } returns Optional.of(user)

        assertThatThrownBy {
            service.subscribe(
                7L,
                subRequest(p256dh = "   "),
            )
        }.isInstanceOf(InvalidPushSubscriptionException::class.java)
    }

    @Test
    fun `존재하지 않는 사용자면 UserNotFoundException`() {
        every { userRepository.findById(99L) } returns Optional.empty()

        assertThatThrownBy {
            service.subscribe(99L, subRequest())
        }.isInstanceOf(UserNotFoundException::class.java)
    }

    @Test
    fun `listMine — updatedAt 내림차순 정렬`() {
        val user = createUser(7L)
        val older = sub(user, "https://fcm.googleapis.com/fcm/send/older", updated = LocalDateTime.now().minusDays(2))
        val newer = sub(user, "https://fcm.googleapis.com/fcm/send/newer", updated = LocalDateTime.now())
        every { userRepository.findById(7L) } returns Optional.of(user)
        every { subscriptionRepository.findByUser(user) } returns listOf(older, newer)

        val list = service.listMine(7L)

        assertThat(list.map { it.id }).containsExactly(newer.id, older.id)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun subRequest(
        endpoint: String = "https://fcm.googleapis.com/fcm/send/sample",
        p256dh: String = "p256dh-value",
        auth: String = "auth-value",
        userAgent: String? = "UA/test",
    ) = PushSubscriptionRequest(
        endpoint = endpoint,
        keys = PushSubscriptionKeysRequest(p256dh = p256dh, auth = auth),
        userAgent = userAgent,
    )

    private fun createUser(id: Long): User =
        User("u$id@test.com", "pwd", "nick$id", "010111122$id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun sub(user: User, endpoint: String, updated: LocalDateTime): UserPushSubscription =
        UserPushSubscription(
            user = user,
            endpoint = endpoint,
            endpointHash = PushSubscriptionService.sha256Hex(endpoint),
            p256dh = "p",
            auth = "a",
        ).apply {
            ReflectionTestUtils.setField(this, "id", endpoint.hashCode().toLong())
            ReflectionTestUtils.setField(this, "createdAt", updated.minusDays(1))
            ReflectionTestUtils.setField(this, "updatedAt", updated)
        }
}
