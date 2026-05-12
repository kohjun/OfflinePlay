package com.contenido.domain.notification.service

import com.contenido.domain.notification.dto.NotificationResponse
import com.contenido.domain.notification.entity.NotificationType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class SseEmitterServiceTest {

    private val objectMapper = ObjectMapper().registerModule(JavaTimeModule())
    private lateinit var service: SseEmitterService

    @BeforeEach
    fun setUp() {
        service = SseEmitterService(objectMapper)
    }

    @Test
    fun `connect 시 emitter 등록`() {
        service.connect(1L)
        assertThat(service.activeCount()).isEqualTo(1)
        assertThat(service.isConnected(1L)).isTrue()
    }

    @Test
    fun `같은 user 의 재연결은 단일 emitter 만 유지`() {
        val first = service.connect(1L)
        val second = service.connect(1L)

        assertThat(service.activeCount()).isEqualTo(1)
        assertThat(first).isNotSameAs(second)
        assertThat(service.isConnected(1L)).isTrue()
    }

    @Test
    fun `disconnect 시 emitter 제거`() {
        service.connect(1L)
        service.disconnect(1L)

        assertThat(service.activeCount()).isEqualTo(0)
        assertThat(service.isConnected(1L)).isFalse()
    }

    @Test
    fun `연결되지 않은 user 에게 sendToUser 호출은 no-op`() {
        // emitter 없는 상태에서 호출해도 예외 없이 통과
        service.sendToUser(99L, sampleNotification(99L))
        assertThat(service.activeCount()).isEqualTo(0)
    }

    @Test
    fun `여러 user 가 동시에 연결되면 모두 추적`() {
        service.connect(1L)
        service.connect(2L)
        service.connect(3L)

        assertThat(service.activeCount()).isEqualTo(3)
        assertThat(service.isConnected(2L)).isTrue()

        service.disconnect(2L)

        assertThat(service.activeCount()).isEqualTo(2)
        assertThat(service.isConnected(2L)).isFalse()
        assertThat(service.isConnected(1L)).isTrue()
        assertThat(service.isConnected(3L)).isTrue()
    }

    private fun sampleNotification(id: Long) = NotificationResponse(
        id = id,
        type = NotificationType.NEW_EVENT,
        title = "test",
        message = "test message",
        targetType = "events",
        targetId = 10L,
        isRead = false,
        createdAt = LocalDateTime.now(),
    )
}
