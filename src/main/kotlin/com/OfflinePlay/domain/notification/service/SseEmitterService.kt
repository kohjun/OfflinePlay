package com.contenido.domain.notification.service

import com.contenido.domain.notification.dto.NotificationResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory SSE emitter registry.
 *
 * One emitter per user. On reconnect, the previous emitter is completed and replaced.
 *
 * TODO(multi-instance): this implementation only fans out to users connected to *this*
 * JVM. In a horizontally scaled deployment, swap [emitters] for a Redis Pub/Sub channel
 * (or message broker topic) so that any instance can receive a notification and forward
 * it to whichever instance currently holds the user's emitter.
 */
@Service
class SseEmitterService(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val emitters = ConcurrentHashMap<Long, SseEmitter>()

    companion object {
        private const val TIMEOUT_MS = 30 * 60 * 1000L  // 30 min
        private const val EVENT_NAME = "notification"
        private const val CONNECT_EVENT = "connect"
    }

    fun connect(userId: Long): SseEmitter {
        // Drop any previous connection for this user so we never keep two emitters open.
        emitters[userId]?.complete()

        val emitter = SseEmitter(TIMEOUT_MS)
        emitters[userId] = emitter

        // Initial event: confirms the connection to the client and helps proxies/browsers
        // recognize the stream as open. Without an immediate write some clients block on
        // first byte and never fire `onopen`.
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

        log.debug("[SSE] User {} connected (active: {})", userId, emitters.size)
        return emitter
    }

    fun disconnect(userId: Long) {
        emitters.remove(userId)?.complete()
        log.debug("[SSE] User {} disconnected", userId)
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
            log.debug("[SSE] Failed to send to user {}, removing emitter: {}", userId, e.message)
            emitters.remove(userId)
            emitter.completeWithError(e)
        }
    }

    /** Number of currently active emitters. Exposed for monitoring / tests. */
    fun activeCount(): Int = emitters.size

    /** Returns true if the given user currently has an active emitter on this instance. */
    fun isConnected(userId: Long): Boolean = emitters.containsKey(userId)
}
