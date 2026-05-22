package com.contenido.domain.event.repository

import com.contenido.domain.event.entity.EventAnnouncementImage
import org.springframework.data.jpa.repository.JpaRepository

interface EventAnnouncementImageRepository : JpaRepository<EventAnnouncementImage, Long> {

    fun findByAnnouncementIdOrderByDisplayOrderAsc(announcementId: Long): List<EventAnnouncementImage>

    /** PR152 — 여러 announcement 의 image 묶음 (N+1 회피). */
    fun findByAnnouncementIdInOrderByAnnouncementIdAscDisplayOrderAsc(
        announcementIds: Collection<Long>,
    ): List<EventAnnouncementImage>
}
