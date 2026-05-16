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
     * Explore 페이지용 다중 속성 LIKE/범위 검색. 모든 파라미터가 null/blank 면 전체 반환.
     * Elasticsearch 의존 없이 항상 동작한다.
     *
     * 필터 축 (PR45 확장):
     *  - keyword: title/description LIKE (case-insensitive)
     *  - category / contentType: 정확 매칭
     *  - location: LIKE (예: "서울", "강남")
     *  - minFee / maxFee: 참가비 범위 (참여비 0=무료 도 포함). 둘 다 null 이면 무시.
     *  - startFrom / startTo: 시작 시각 범위 (null 가능). 기본은 controller 가 now() 부터.
     *  - excludeClosed: true 면 status='CLOSED' 제외
     *  - excludeFull:   true 면 currentParticipants >= maxParticipants 제외
     */
    @Query("""
        SELECT e FROM Event e
        WHERE (:keyword IS NULL OR :keyword = ''
               OR LOWER(e.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(e.description) LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:category IS NULL OR e.channel.category = :category)
          AND (:contentType IS NULL OR e.contentType = :contentType)
          AND (:location IS NULL OR :location = ''
               OR LOWER(e.location) LIKE LOWER(CONCAT('%', :location, '%')))
          AND (:minFee IS NULL OR e.participationFee >= :minFee)
          AND (:maxFee IS NULL OR e.participationFee <= :maxFee)
          AND (:startFrom IS NULL OR e.startAt >= :startFrom)
          AND (:startTo IS NULL OR e.startAt < :startTo)
          AND (:excludeClosed = false OR e.status <> 'CLOSED')
          AND (:excludeFull = false OR e.currentParticipants < e.maxParticipants)
        ORDER BY e.startAt ASC
    """)
    fun searchForExplore(
        @Param("keyword") keyword: String?,
        @Param("category") category: ChannelCategory?,
        @Param("contentType") contentType: ContentType?,
        @Param("location") location: String?,
        @Param("minFee") minFee: Long?,
        @Param("maxFee") maxFee: Long?,
        @Param("startFrom") startFrom: LocalDateTime?,
        @Param("startTo") startTo: LocalDateTime?,
        @Param("excludeClosed") excludeClosed: Boolean,
        @Param("excludeFull") excludeFull: Boolean,
        pageable: Pageable,
    ): Page<Event>
}
