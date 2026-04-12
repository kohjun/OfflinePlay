package com.contenido.domain.notification.service

import com.contenido.domain.notification.dto.NotificationResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

@Service
class SseEmitterService(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // userId → SseEmitter
    private val emitters = ConcurrentHashMap<Long, SseEmitter>()

    companion object {
        private const val TIMEOUT_MS = 30 * 60 * 1000L  // 30분
        private const val EVENT_NAME = "notification"
        private const val CONNECT_EVENT = "connect"
    }

    fun connect(userId: Long): SseEmitter {
        // 기존 연결 정리
        emitters[userId]?.complete()

        val emitter = SseEmitter(TIMEOUT_MS)
        emitters[userId] = emitter

        // 연결 수립 확인용 초기 이벤트 (빈 데이터 전송 없으면 일부 클라이언트가 연결 인식 못함)
        runCatching {
            emitter.send(
                SseEmitter.event()
                    .name(CONNECT_EVENT)
                    .data("connected")
            )
        }.onFailure {
            emitters.remove(userId)
            return@onFailure
        }

        emitter.onCompletion { emitters.remove(userId) }
        emitter.onTimeout { emitters.remove(userId) }
        emitter.onError { emitters.remove(userId) }

        log.debug("[SSE] User $userId connected (active: ${emitters.size})")
        return emitter
    }

    fun disconnect(userId: Long) {
        emitters.remove(userId)?.complete()
        log.debug("[SSE] User $userId disconnected")
    }

    fun sendToUser(userId: Long, notification: NotificationResponse) {
        val emitter = emitters[userId] ?: return
        runCatching {
            emitter.send(
                SseEmitter.event()
                    .name(EVENT_NAME)
                    .data(objectMapper.writeValueAsString(notification))
            )
        }.onFailure { e ->
            log.debug("[SSE] Failed to send to user $userId, removing emitter: ${e.message}")
            emitters.remove(userId)
            emitter.completeWithError(e)
        }
    }
}
