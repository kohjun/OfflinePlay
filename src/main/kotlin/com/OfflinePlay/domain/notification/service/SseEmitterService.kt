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

    /**
     * PR160 — 임의 SSE event 를 user 묶음에 broadcast. 채팅처럼 NotificationResponse 형식이 아닌
     * 별도 payload (예: EventChatMessageResponse) 도 같은 채널로 흘려보낸다.
     *
     *  - eventName 은 SSE event 의 `event:` field. frontend `EventSource.addEventListener(eventName, ...)`
     *    가 정확히 매칭해야 수신한다.
     *  - userIds 가 비어 있으면 no-op. 같은 emitter 가 여러 번 send 받아도 안전 (ConcurrentHashMap).
     *  - 개별 send 실패는 swallow + emitter 정리 — broadcast 자체가 partial 실패로 break 되지 않는다.
     */
    fun broadcast(userIds: Collection<Long>, eventName: String, payload: Any) {
        if (userIds.isEmpty()) return
        val serialized = runCatching { objectMapper.writeValueAsString(payload) }
            .getOrElse {
                log.warn("[SSE] broadcast payload 직렬화 실패 event={} err={}", eventName, it.message)
                return
            }
        userIds.forEach { userId ->
            val emitter = emitters[userId] ?: return@forEach
            runCatching {
                emitter.send(SseEmitter.event().name(eventName).data(serialized))
            }.onFailure { e ->
                log.debug("[SSE] broadcast {} 전송 실패 userId={} err={}", eventName, userId, e.message)
                emitters.remove(userId)
                emitter.completeWithError(e)
            }
        }
    }
}
