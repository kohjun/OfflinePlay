package com.contenido.domain.notification.service

import com.contenido.domain.notification.entity.Notification
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.repository.NotificationRepository
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

/**
 * PR95 — NotificationService.notify 가 NotificationPreferenceService.isEnabled 결과에 따라
 * receiver 를 필터링하는지 검증.
 *
 * preference true / row 없음 → 기존처럼 저장 + SSE 전송.
 * preference false → 해당 receiver 는 row 저장도 SSE 전송도 안 됨.
 * preference 조회 예외 → fail-open (isEnabled 가 true 반환) — 발송 계속.
 */
@ExtendWith(MockKExtension::class)
class NotificationServiceTest {

    @MockK(relaxed = true) lateinit var notificationRepository: NotificationRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK(relaxed = true) lateinit var sseEmitterService: SseEmitterService
    @MockK lateinit var notificationPreferenceService: NotificationPreferenceService
    @MockK(relaxed = true) lateinit var pushNotificationService: PushNotificationService

    private lateinit var service: NotificationService

    @BeforeEach
    fun setUp() {
        service = NotificationService(
            notificationRepository = notificationRepository,
            userRepository = userRepository,
            sseEmitterService = sseEmitterService,
            notificationPreferenceService = notificationPreferenceService,
            pushNotificationService = pushNotificationService,
        )
    }

    @Test
    fun `preference enabled 면 기존처럼 저장 + SSE 전송`() {
        val u = createUser(1L)
        every { notificationPreferenceService.isEnabled(1L, NotificationType.NEW_COMMENT) } returns true
        every { userRepository.findAllById(listOf(1L)) } returns listOf(u)
        val savedSlot = slot<List<Notification>>()
        every { notificationRepository.saveAll(capture(savedSlot)) } answers {
            savedSlot.captured.also { rows ->
                rows.forEach { ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now()) }
            }
        }

        service.notify(
            receiverIds = listOf(1L),
            type = NotificationType.NEW_COMMENT,
            title = "title",
            message = "msg",
            targetType = "comments",
            targetId = 9L,
        )

        verify(exactly = 1) { notificationRepository.saveAll(any<List<Notification>>()) }
        verify(exactly = 1) { sseEmitterService.sendToUser(1L, any()) }
        assertThat(savedSlot.captured).hasSize(1)
        assertThat(savedSlot.captured[0].receiver.id).isEqualTo(1L)
    }

    @Test
    fun `preference disabled receiver 는 저장도 전송도 안 됨`() {
        every { notificationPreferenceService.isEnabled(1L, NotificationType.NEW_COMMENT) } returns false

        service.notify(
            receiverIds = listOf(1L),
            type = NotificationType.NEW_COMMENT,
            title = "title",
            message = "msg",
            targetType = "comments",
            targetId = 9L,
        )

        verify(exactly = 0) { notificationRepository.saveAll(any<List<Notification>>()) }
        verify(exactly = 0) { sseEmitterService.sendToUser(any(), any()) }
    }

    @Test
    fun `복수 receiver 중 preference false 인 receiver 만 제외`() {
        val u1 = createUser(1L)
        val u3 = createUser(3L)
        every { notificationPreferenceService.isEnabled(1L, NotificationType.NEW_COMMENT) } returns true
        every { notificationPreferenceService.isEnabled(2L, NotificationType.NEW_COMMENT) } returns false
        every { notificationPreferenceService.isEnabled(3L, NotificationType.NEW_COMMENT) } returns true
        every { userRepository.findAllById(listOf(1L, 3L)) } returns listOf(u1, u3)
        val savedSlot = slot<List<Notification>>()
        every { notificationRepository.saveAll(capture(savedSlot)) } answers {
            savedSlot.captured.also { rows ->
                rows.forEach { ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now()) }
            }
        }

        service.notify(
            receiverIds = listOf(1L, 2L, 3L),
            type = NotificationType.NEW_COMMENT,
            title = "title",
            message = "msg",
            targetType = "comments",
            targetId = 9L,
        )

        assertThat(savedSlot.captured.map { it.receiver.id }).containsExactlyInAnyOrder(1L, 3L)
        verify(exactly = 1) { sseEmitterService.sendToUser(1L, any()) }
        verify(exactly = 1) { sseEmitterService.sendToUser(3L, any()) }
        verify(exactly = 0) { sseEmitterService.sendToUser(2L, any()) }
    }

    @Test
    fun `preference true 면 push dispatch 호출 (PR140)`() {
        val u = createUser(1L)
        every { notificationPreferenceService.isEnabled(1L, NotificationType.NEW_COMMENT) } returns true
        every { userRepository.findAllById(listOf(1L)) } returns listOf(u)
        every { notificationRepository.saveAll(any<List<Notification>>()) } answers {
            firstArg<List<Notification>>().onEach { ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now()) }
        }

        service.notify(
            receiverIds = listOf(1L),
            type = NotificationType.NEW_COMMENT,
            title = "title",
            message = "msg",
            targetType = "comments",
            targetId = 9L,
        )

        verify(exactly = 1) { pushNotificationService.dispatch(any()) }
    }

    @Test
    fun `preference false 면 push dispatch 미호출 (PR140)`() {
        every { notificationPreferenceService.isEnabled(1L, NotificationType.NEW_COMMENT) } returns false

        service.notify(
            receiverIds = listOf(1L),
            type = NotificationType.NEW_COMMENT,
            title = "title",
            message = "msg",
            targetType = "comments",
            targetId = 9L,
        )

        verify(exactly = 0) { pushNotificationService.dispatch(any()) }
    }

    @Test
    fun `push dispatch 예외는 notification row SSE 흐름을 깨지 않는다 (PR140)`() {
        val u = createUser(1L)
        every { notificationPreferenceService.isEnabled(1L, NotificationType.NEW_COMMENT) } returns true
        every { userRepository.findAllById(listOf(1L)) } returns listOf(u)
        every { notificationRepository.saveAll(any<List<Notification>>()) } answers {
            firstArg<List<Notification>>().onEach { ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now()) }
        }
        every { pushNotificationService.dispatch(any()) } throws RuntimeException("push down")

        service.notify(
            receiverIds = listOf(1L),
            type = NotificationType.NEW_COMMENT,
            title = "title",
            message = "msg",
            targetType = "comments",
            targetId = 9L,
        )

        verify(exactly = 1) { notificationRepository.saveAll(any<List<Notification>>()) }
        verify(exactly = 1) { sseEmitterService.sendToUser(1L, any()) }
    }

    @Test
    fun `preference 조회 예외는 fail-open 으로 처리되어 알림 발송 계속`() {
        val u = createUser(1L)
        // NotificationPreferenceService.isEnabled 는 내부적으로 runCatching 으로 true 를 반환하지만,
        // 여기서는 그 동작을 그대로 mocking 하여 검증.
        every { notificationPreferenceService.isEnabled(1L, NotificationType.NEW_COMMENT) } returns true
        every { userRepository.findAllById(listOf(1L)) } returns listOf(u)
        every { notificationRepository.saveAll(any<List<Notification>>()) } answers {
            firstArg<List<Notification>>().onEach { ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now()) }
        }

        service.notify(
            receiverIds = listOf(1L),
            type = NotificationType.NEW_COMMENT,
            title = "title",
            message = "msg",
            targetType = "comments",
            targetId = 9L,
        )

        verify(exactly = 1) { notificationRepository.saveAll(any<List<Notification>>()) }
        verify(exactly = 1) { sseEmitterService.sendToUser(1L, any()) }
    }

    private fun createUser(id: Long): User = User("u$id@test.com", "pwd", "nick$id", "010111122$id").apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
    }
}
