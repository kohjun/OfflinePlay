package com.contenido.domain.interest.entity

import jakarta.persistence.*
import java.io.Serializable

/**
 * PR147 — user_interests 다대다 join entity.
 *
 * `@ManyToMany` 컬렉션 대신 명시 entity 로 둬서 User 의 auth hot path 를 건드리지 않는다.
 * RecommendationService (PR148) 와 InterestService 가 직접 본 entity 를 조회/upsert 한다.
 */
@Entity
@Table(
    name = "user_interests",
    indexes = [
        Index(name = "idx_user_interests_interest", columnList = "interest_id"),
    ],
)
@IdClass(UserInterestId::class)
class UserInterest(

    @Id
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Id
    @Column(name = "interest_id", nullable = false)
    val interestId: Long,
)

/** 복합 PK 식별자. data class 라 equals/hashCode 자동 생성 — JPA `@IdClass` 요구사항 충족. */
data class UserInterestId(
    val userId: Long = 0,
    val interestId: Long = 0,
) : Serializable
