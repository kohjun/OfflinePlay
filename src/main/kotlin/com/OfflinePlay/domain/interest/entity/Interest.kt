package com.contenido.domain.interest.entity

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * PR147 — 관심사 카탈로그.
 *
 *  - slug : 영문 stable identifier (UNIQUE). 운영 중 label 만 바뀔 수 있고 slug 는 불변.
 *  - category : ACTIVITY / CULTURE / FOOD / GAME / GROWTH / TRAVEL / SOCIAL (V16 seed 정의).
 *  - display_order : 같은 category 안에서 노출 순서.
 *
 * 사용자/이벤트와의 다대다 join 은 `user_interests` / `event_interests` (UserProfile / Event 측에서
 * `@ManyToMany` 로 노출).
 */
@Entity
@Table(
    name = "interests",
    uniqueConstraints = [UniqueConstraint(name = "uk_interests_slug", columnNames = ["slug"])],
    indexes = [
        Index(name = "idx_interests_category_order", columnList = "category, display_order"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class Interest(

    @Column(nullable = false, length = 50)
    val slug: String,

    @Column(nullable = false, length = 50)
    var label: String,

    @Column(nullable = false, length = 30)
    var category: String,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set
}
