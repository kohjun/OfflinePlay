package com.contenido.domain.event.repository

import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository

interface EventParticipationRepository : JpaRepository<EventParticipation, Long> {

    fun existsByEventAndParticipant(event: Event, participant: User): Boolean

    fun countByEvent(event: Event): Long

    fun countByEventIn(events: List<Event>): Long

    fun deleteByEventAndParticipant(event: Event, participant: User)
}
