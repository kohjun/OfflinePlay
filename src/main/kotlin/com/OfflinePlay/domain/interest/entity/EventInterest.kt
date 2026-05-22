package com.contenido.domain.interest.entity

import jakarta.persistence.*
import java.io.Serializable

/**
 * PR147 — event_interests 다대다 join entity. UserInterest 와 같은 패턴.
 */
@Entity
@Table(
    name = "event_interests",
    indexes = [
        Index(name = "idx_event_interests_interest", columnList = "interest_id"),
    ],
)
@IdClass(EventInterestId::class)
class EventInterest(

    @Id
    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Id
    @Column(name = "interest_id", nullable = false)
    val interestId: Long,
)

data class EventInterestId(
    val eventId: Long = 0,
    val interestId: Long = 0,
) : Serializable
