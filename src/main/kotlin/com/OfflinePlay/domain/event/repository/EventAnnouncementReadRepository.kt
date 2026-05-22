package com.contenido.domain.event.repository

import com.contenido.domain.event.entity.EventAnnouncementRead
import com.contenido.domain.event.entity.EventAnnouncementReadId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface EventAnnouncementReadRepository : JpaRepository<EventAnnouncementRead, EventAnnouncementReadId> {

    /** PR151 — 사용자의 특정 이벤트 read 묶음. announcement list 응답에 read 여부를 채우는 데 사용. */
    @Query(
        """
        SELECT r FROM EventAnnouncementRead r
        WHERE r.userId = :userId
          AND r.announcementId IN :announcementIds
        """,
    )
    fun findByUserIdAndAnnouncementIdIn(
        @Param("userId") userId: Long,
        @Param("announcementIds") announcementIds: Collection<Long>,
    ): List<EventAnnouncementRead>

    /**
     * PR151 — 한 이벤트에서 사용자가 아직 read 하지 않은 공지 수.
     *
     *  - LEFT JOIN 대신 NOT EXISTS subquery 로 단순화 (announcement 가 적고 read 가 더 적어 비용 OK).
     *  - 공지 row 가 LATER pinned/edit 되어도 read row 가 있으면 unread 가 아님.
     */
    @Query(
        """
        SELECT COUNT(a) FROM EventAnnouncement a
        WHERE a.event.id = :eventId
          AND a.id NOT IN (
            SELECT r.announcementId FROM EventAnnouncementRead r
            WHERE r.userId = :userId
          )
        """,
    )
    fun countUnreadByEventIdAndUserId(
        @Param("eventId") eventId: Long,
        @Param("userId") userId: Long,
    ): Long
}
