package com.contenido.domain.notification.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * PR139 — 브라우저 Web Push 구독 한 건.
 *
 * 정책:
 *  - (user_id, endpoint_hash) UNIQUE — 같은 endpoint 재등록은 upsert (PushSubscriptionService 가 담당).
 *  - 같은 사용자라도 브라우저/디바이스마다 endpoint 가 달라 여러 row 가 허용된다.
 *  - `enabled = false` 는 발송 시도에서 410/expired 가 반환됐을 때 soft-disable. UI 가 직접
 *    해지하면 row 를 삭제 (hard delete) — 사용자의 의도와 backend self-healing 을 구분.
 *  - `endpointHash` 는 endpoint 의 SHA-256 hex (64자). 동일 endpoint 비교 + UNIQUE 인덱스용.
 */
@Entity
@Table(
    name = "user_push_subscriptions",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_push_subscriptions_user_endpoint",
            columnNames = ["user_id", "endpoint_hash"],
        ),
    ],
    indexes = [
        Index(name = "idx_user_push_subscriptions_user_enabled", columnList = "user_id, enabled"),
        Index(name = "idx_user_push_subscriptions_endpoint_hash", columnList = "endpoint_hash"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class UserPushSubscription(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false, columnDefinition = "TEXT")
    var endpoint: String,

    @Column(name = "endpoint_hash", nullable = false, length = 64)
    var endpointHash: String,

    @Column(nullable = false, length = 255)
    var p256dh: String,

    @Column(nullable = false, length = 255)
    var auth: String,

    @Column(name = "user_agent", length = 500)
    var userAgent: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "last_seen_at")
    var lastSeenAt: LocalDateTime? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set

    /** 기존 row 를 새 credential 로 갱신 (같은 endpoint 재등록). */
    fun refreshCredentials(
        p256dh: String,
        auth: String,
        userAgent: String?,
    ) {
        this.p256dh = p256dh
        this.auth = auth
        this.userAgent = userAgent
        this.enabled = true
        this.lastSeenAt = LocalDateTime.now()
    }

    /** PR140 이 발송 실패(410/expired) 시 호출. UI 차원의 해지가 아니라 backend self-healing. */
    fun disable() {
        this.enabled = false
    }

    fun touchSeen() {
        this.lastSeenAt = LocalDateTime.now()
    }
}
