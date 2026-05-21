package com.contenido.domain.notification.service

import com.contenido.domain.notification.entity.Notification
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.entity.UserPushSubscription
import com.contenido.domain.notification.repository.UserPushSubscriptionRepository
import com.contenido.domain.user.entity.User
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
 * PR140 — PushNotificationService 가:
 *  - active 구독을 묶음 조회하고 receiver 별로 fan-out 한다.
 *  - disabled 구독은 발송 대상에서 제외 (repository 가 enabled=true 만 반환).
 *  - 410/expired 응답은 subscription.disable() 호출.
 *  - 라이브러리 예외는 swallow 되어 다음 구독으로 진행.
 */
@ExtendWith(MockKExtension::class)
class PushNotificationServiceTest {

    @MockK lateinit var subscriptionRepository: UserPushSubscriptionRepository
    @MockK lateinit var sender: WebPushSender

    private lateinit var service: PushNotificationService
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        service = PushNotificationService(subscriptionRepository, sender, objectMapper)
    }

    @Test
    fun `payload 에 title body type targetType targetId url notificationId 가 포함`() {
        val n = createNotification(id = 7L, type = NotificationType.NEW_EVENT, targetType = "events", targetId = 42L)

        val payload = service.encodePayload(n)
        val decoded = objectMapper.readValue(payload, Map::class.java)

        assertThat(decoded["title"]).isEqualTo(n.title)
        assertThat(decoded["body"]).isEqualTo(n.message)
        assertThat(decoded["type"]).isEqualTo("NEW_EVENT")
        assertThat(decoded["targetType"]).isEqualTo("events")
        assertThat(decoded["targetId"]).isEqualTo(42)
        assertThat(decoded["url"]).isEqualTo("/events/42")
        assertThat(decoded["notificationId"]).isEqualTo(7)
    }

    @Test
    fun `dispatch — 활성 구독에만 발송`() {
        val user = createUser(1L)
        val n = createNotification(id = 9L, user = user)
        val sub = createSubscription(id = 100L, user = user, enabled = true)
        every { subscriptionRepository.findByUserIdInAndEnabledTrue(listOf(1L)) } returns listOf(sub)
        every { sender.send(any(), any(), any(), any()) } returns WebPushSendResult(statusCode = 201)
        every { subscriptionRepository.findById(100L) } returns Optional.of(sub)

        service.dispatch(listOf(n))

        verify(exactly = 1) { sender.send(sub.endpoint, sub.p256dh, sub.auth, any()) }
    }

    @Test
    fun `dispatch — 구독이 없으면 sender 호출 안 함`() {
        val user = createUser(1L)
        val n = createNotification(id = 9L, user = user)
        every { subscriptionRepository.findByUserIdInAndEnabledTrue(listOf(1L)) } returns emptyList()

        service.dispatch(listOf(n))

        verify(exactly = 0) { sender.send(any(), any(), any(), any()) }
    }

    @Test
    fun `dispatch — 410 응답은 구독 disable`() {
        val user = createUser(1L)
        val n = createNotification(id = 9L, user = user)
        val sub = createSubscription(id = 100L, user = user, enabled = true)
        every { subscriptionRepository.findByUserIdInAndEnabledTrue(listOf(1L)) } returns listOf(sub)
        every { sender.send(any(), any(), any(), any()) } returns WebPushSendResult(statusCode = 410)
        val disableSlot = slot<Long>()
        every { subscriptionRepository.findById(capture(disableSlot)) } returns Optional.of(sub)

        service.dispatch(listOf(n))

        assertThat(disableSlot.captured).isEqualTo(100L)
        assertThat(sub.enabled).isFalse()
    }

    @Test
    fun `dispatch — 404 응답도 구독 disable`() {
        val user = createUser(1L)
        val n = createNotification(id = 9L, user = user)
        val sub = createSubscription(id = 100L, user = user, enabled = true)
        every { subscriptionRepository.findByUserIdInAndEnabledTrue(listOf(1L)) } returns listOf(sub)
        every { sender.send(any(), any(), any(), any()) } returns WebPushSendResult(statusCode = 404)
        every { subscriptionRepository.findById(100L) } returns Optional.of(sub)

        service.dispatch(listOf(n))

        assertThat(sub.enabled).isFalse()
    }

    @Test
    fun `dispatch — sender 예외는 swallow 되고 다음 구독으로 진행`() {
        val user = createUser(1L)
        val n = createNotification(id = 9L, user = user)
        val sub1 = createSubscription(id = 100L, user = user, enabled = true, endpoint = "https://fcm.googleapis.com/fcm/send/A")
        val sub2 = createSubscription(id = 101L, user = user, enabled = true, endpoint = "https://fcm.googleapis.com/fcm/send/B")
        every { subscriptionRepository.findByUserIdInAndEnabledTrue(listOf(1L)) } returns listOf(sub1, sub2)
        every { sender.send(sub1.endpoint, any(), any(), any()) } throws RuntimeException("network blip")
        every { sender.send(sub2.endpoint, any(), any(), any()) } returns WebPushSendResult(statusCode = 201)
        every { subscriptionRepository.findById(101L) } returns Optional.of(sub2)

        service.dispatch(listOf(n))

        verify(exactly = 1) { sender.send(sub2.endpoint, any(), any(), any()) }
    }

    @Test
    fun `dispatch — repository 예외는 swallow 되고 전체 흐름 종료`() {
        val user = createUser(1L)
        val n = createNotification(id = 9L, user = user)
        every { subscriptionRepository.findByUserIdInAndEnabledTrue(listOf(1L)) } throws RuntimeException("DB down")

        service.dispatch(listOf(n))

        verify(exactly = 0) { sender.send(any(), any(), any(), any()) }
    }

    @Test
    fun `dispatch — disabled sender(VAPID 없음) 면 구독 disable 시키지 않는다`() {
        val user = createUser(1L)
        val n = createNotification(id = 9L, user = user)
        val sub = createSubscription(id = 100L, user = user, enabled = true)
        every { subscriptionRepository.findByUserIdInAndEnabledTrue(listOf(1L)) } returns listOf(sub)
        every { sender.send(any(), any(), any(), any()) } returns WebPushSendResult.disabled()

        service.dispatch(listOf(n))

        // disabled 응답이라도 구독은 그대로. expired 가 아니라 운영자 환경 미설정이라서.
        assertThat(sub.enabled).isTrue()
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun createUser(id: Long): User =
        User("u$id@test.com", "pwd", "nick$id", "010-0000-000$id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun createNotification(
        id: Long = 1L,
        user: User = createUser(1L),
        type: NotificationType = NotificationType.NEW_EVENT,
        targetType: String = "events",
        targetId: Long = 42L,
    ): Notification = Notification(
        receiver = user,
        type = type,
        title = "title-$id",
        message = "msg-$id",
        targetType = targetType,
        targetId = targetId,
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
    }

    private fun createSubscription(
        id: Long,
        user: User,
        endpoint: String = "https://fcm.googleapis.com/fcm/send/sample-$id",
        enabled: Boolean = true,
    ): UserPushSubscription = UserPushSubscription(
        user = user,
        endpoint = endpoint,
        endpointHash = PushSubscriptionService.sha256Hex(endpoint),
        p256dh = "p256dh-$id",
        auth = "auth-$id",
        enabled = enabled,
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
    }
}
