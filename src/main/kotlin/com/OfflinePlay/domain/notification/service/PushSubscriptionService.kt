package com.contenido.domain.notification.service

import com.contenido.domain.notification.dto.PushSubscriptionRequest
import com.contenido.domain.notification.dto.PushSubscriptionResponse
import com.contenido.domain.notification.entity.UserPushSubscription
import com.contenido.domain.notification.repository.UserPushSubscriptionRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.InvalidPushSubscriptionException
import com.contenido.global.exception.UserNotFoundException
import java.security.MessageDigest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR139 — Web Push 구독을 저장/조회/해제한다. 발송은 본 PR 범위 밖 (PR140).
 *
 * 정책:
 *  - 같은 (user, endpoint) 가 다시 들어오면 credential 만 갱신하고 enable. 새 row 생성 X.
 *  - endpoint_hash 는 SHA-256 hex (64자) — UNIQUE 인덱스 키로 사용. endpoint 자체를 인덱스
 *    키로 쓰지 않는 이유는 PG/벤더 URL 길이가 매우 길어질 수 있기 때문이다.
 *  - 해지는 hard delete — 사용자의 명시적 의도를 backend 가 보존한다. 410/expired 같은
 *    self-healing 은 PR140 이 `disable()` (soft) 로 처리한다.
 */
@Service
@Transactional(readOnly = true)
class PushSubscriptionService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: UserPushSubscriptionRepository,
) {

    /**
     * upsert. 같은 endpoint 가 이미 있으면 credential 만 갱신하고 enable.
     * payload 가 너무 짧으면 [IllegalArgumentException] (Controller 가 400 매핑).
     */
    @Transactional
    fun subscribe(userId: Long, request: PushSubscriptionRequest): PushSubscriptionResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        validate(request)

        val hash = sha256Hex(request.endpoint)
        val existing = subscriptionRepository.findByUserAndEndpointHash(user, hash)
        val saved = if (existing != null) {
            existing.endpoint = request.endpoint
            existing.refreshCredentials(
                p256dh = request.keys.p256dh,
                auth = request.keys.auth,
                userAgent = request.userAgent,
            )
            existing
        } else {
            subscriptionRepository.save(
                UserPushSubscription(
                    user = user,
                    endpoint = request.endpoint,
                    endpointHash = hash,
                    p256dh = request.keys.p256dh,
                    auth = request.keys.auth,
                    userAgent = request.userAgent,
                    enabled = true,
                    lastSeenAt = java.time.LocalDateTime.now(),
                ),
            )
        }
        return saved.toResponse()
    }

    /**
     * 사용자 명시 해지 — row 삭제. endpoint 가 다른 사용자에게 묶여 있다면 (이론상 거의 없음) 건드리지 않는다.
     */
    @Transactional
    fun unsubscribe(userId: Long, endpoint: String): Int {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val hash = sha256Hex(endpoint)
        return subscriptionRepository.deleteByUserAndEndpointHash(user, hash).toInt()
    }

    fun listMine(userId: Long): List<PushSubscriptionResponse> {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        return subscriptionRepository.findByUser(user)
            .sortedByDescending { it.updatedAt }
            .map { it.toResponse() }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun validate(request: PushSubscriptionRequest) {
        val endpoint = request.endpoint.trim()
        if (endpoint.length !in 10..2048) {
            throw InvalidPushSubscriptionException("endpoint 길이가 올바르지 않습니다.")
        }
        if (!endpoint.startsWith("https://")) {
            throw InvalidPushSubscriptionException("endpoint 는 https URL 이어야 합니다.")
        }
        if (request.keys.p256dh.isBlank()) {
            throw InvalidPushSubscriptionException("p256dh 키가 비어 있습니다.")
        }
        if (request.keys.auth.isBlank()) {
            throw InvalidPushSubscriptionException("auth 키가 비어 있습니다.")
        }
    }

    private fun UserPushSubscription.toResponse() = PushSubscriptionResponse(
        id = id,
        enabled = enabled,
        userAgent = userAgent,
        lastSeenAt = lastSeenAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun sha256Hex(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
