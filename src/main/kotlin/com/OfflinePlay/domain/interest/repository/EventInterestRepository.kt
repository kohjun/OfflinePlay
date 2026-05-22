package com.contenido.domain.interest.repository

import com.contenido.domain.interest.entity.EventInterest
import com.contenido.domain.interest.entity.EventInterestId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface EventInterestRepository : JpaRepository<EventInterest, EventInterestId> {

    fun findByEventId(eventId: Long): List<EventInterest>

    /** PR148 — 여러 event 의 interest 묶음을 한 번에 fetch (N+1 회피). */
    fun findByEventIdIn(eventIds: Collection<Long>): List<EventInterest>

    /** PR148 — 추천 score 계산. interest_id 가 매칭되는 event_id 목록. */
    fun findByInterestIdIn(interestIds: Collection<Long>): List<EventInterest>

    @Modifying
    @Query("DELETE FROM EventInterest ei WHERE ei.eventId = :eventId")
    fun deleteByEventId(@Param("eventId") eventId: Long): Int
}
