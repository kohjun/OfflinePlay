package com.contenido.domain.notification.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * PR95 — 사용자별 NotificationType 수신 선호 설정.
 *
 * 정책:
 *  - (user_id, notification_type) UNIQUE — 같은 type 에 대해 1 row.
 *  - row 가 없으면 enabled = true 로 간주 (NotificationPreferenceService 가 fallback).
 *  - 기록 변경 자체는 moderation_audit_logs 와 무관 (개인 설정 영역).
 */
@Entity
@Table(
    name = "user_notification_preferences",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_notification_preferences_user_type",
            columnNames = ["user_id", "notification_type"],
        ),
    ],
    indexes = [
        Index(name = "idx_user_notification_preferences_user", columnList = "user_id"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class UserNotificationPreference(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    val notificationType: NotificationType,

    @Column(nullable = false)
    var enabled: Boolean = true,
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

    fun update(enabled: Boolean) {
        this.enabled = enabled
        // PR104 — @LastModifiedDate 는 @PreUpdate flush 시점에 fire 하므로 같은 트랜잭션
        // 안에서 다시 읽을 때 갱신된 updatedAt 이 보이도록 명시 갱신. 이후 @PreUpdate 가
        // 추가로 한 번 더 set 해도 결과 timestamp 는 거의 동일한 now() 이라 문제 없음.
        this.updatedAt = LocalDateTime.now()
    }
}
