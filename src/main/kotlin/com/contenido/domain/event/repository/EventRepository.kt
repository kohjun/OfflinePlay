package com.contenido.domain.event.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface EventRepository : JpaRepository<Event, Long> {

    fun findByChannelOrderByStartAtDesc(channel: Channel, pageable: Pageable): Page<Event>

    fun findByStatus(status: EventStatus, pageable: Pageable): Page<Event>
}
