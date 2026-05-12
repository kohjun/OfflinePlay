package com.contenido.domain.event.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.event.entity.ContentType
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface EventRepository : JpaRepository<Event, Long> {

    fun findByChannelOrderByStartAtDesc(channel: Channel, pageable: Pageable): Page<Event>

    fun findByStatus(status: EventStatus, pageable: Pageable): Page<Event>

    fun findByChannel(channel: Channel): List<Event>

    @Query("select count(e) from Event e where e.channel.owner = :owner")
    fun countByChannelOwner(@Param("owner") owner: User): Long

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Event e SET e.likeCount = e.likeCount + :delta WHERE e.id = :id")
    fun updateLikeCount(@Param("id") id: Long, @Param("delta") delta: Int)

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

    /**
     * Explore 페이지용 LIKE 검색. 모든 파라미터가 null/blank 면 전체를 반환한다.
     * Elasticsearch 의존 없이 항상 동작한다.
     */
    @Query("""
        SELECT e FROM Event e
        WHERE (:keyword IS NULL OR :keyword = ''
               OR LOWER(e.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(e.description) LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:category IS NULL OR e.channel.category = :category)
          AND (:contentType IS NULL OR e.contentType = :contentType)
        ORDER BY e.startAt DESC
    """)
    fun searchForExplore(
        @Param("keyword") keyword: String?,
        @Param("category") category: ChannelCategory?,
        @Param("contentType") contentType: ContentType?,
        pageable: Pageable,
    ): Page<Event>
}
