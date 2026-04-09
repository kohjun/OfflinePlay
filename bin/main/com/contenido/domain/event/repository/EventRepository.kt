package com.contenido.domain.event.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface EventRepository : JpaRepository<Event, Long> {

    fun findByChannelOrderByStartAtDesc(channel: Channel, pageable: Pageable): Page<Event>

    fun findByStatus(status: EventStatus, pageable: Pageable): Page<Event>
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Event e 
        SET e.status = 'ONGOING' 
        WHERE e.status = 'UPCOMING' 
        AND e.startAt <= :now 
        AND e.endAt > :now
    """)
    fun updateStatusToOngoing(now: LocalDateTime): Int

    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Event e 
        SET e.status = 'CLOSED' 
        WHERE e.status IN ('UPCOMING', 'ONGOING') 
        AND e.endAt <= :now
    """)
    fun updateStatusToClosed(now: LocalDateTime): Int
}
