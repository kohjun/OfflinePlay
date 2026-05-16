package com.contenido.domain.review.repository

import com.contenido.domain.event.entity.Event
import com.contenido.domain.review.entity.Review
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface ReviewRepository : JpaRepository<Review, Long> {

    /**
     * 이벤트별 후기 목록 — 최신순, 자동 숨김 제외. PR51 이후 사용자 조회 진입점.
     * Admin/작성자 본인용 전체 조회는 [findByEventOrderByCreatedAtDesc] 그대로 사용.
     */
    fun findByEventAndHiddenAtIsNullOrderByCreatedAtDesc(event: Event, pageable: Pageable): Page<Review>

    /** 이벤트별 후기 목록 전체 — Admin / 본인 진입용. */
    fun findByEventOrderByCreatedAtDesc(event: Event, pageable: Pageable): Page<Review>

    /** 본인 후기 단건 조회 — UI 에서 "이미 후기 작성했는지" 판별 + 수정 진입. */
    fun findByEventAndAuthor(event: Event, author: User): Optional<Review>

    /** PR53 — 작성자 본인의 자동 숨김 후기. Creator Studio "숨김 처리된 콘텐츠" 섹션. */
    fun findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(author: User): List<Review>

    /** PR55 — Admin moderation queue 빌드. 작성자 무관, 모든 hidden review. */
    fun findByHiddenAtIsNotNullOrderByHiddenAtDesc(): List<Review>

    /** 한 이벤트의 평균 별점. 후기가 없으면 null. */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.event.id = :eventId")
    fun averageRatingByEventId(@Param("eventId") eventId: Long): Double?

    /** 한 이벤트의 후기 수. */
    fun countByEvent(event: Event): Long

    /**
     * 다수 이벤트의 (eventId, average, count) 묶음 — Channel detail / Explore 목록 등에서 N+1 회피.
     * eventIds 가 비어 있으면 빈 리스트 반환 (caller 가 호출 전에 가드).
     */
    @Query("""
        SELECT r.event.id, AVG(r.rating), COUNT(r)
        FROM Review r
        WHERE r.event.id IN :eventIds
        GROUP BY r.event.id
    """)
    fun aggregateByEventIds(@Param("eventIds") eventIds: Collection<Long>): List<Array<Any>>

    /**
     * 채널 전체 (해당 채널 모든 이벤트) 평균 별점. 후기가 하나도 없으면 null.
     * 채널 상세 hero / Channel 카드에 노출.
     */
    @Query("""
        SELECT AVG(r.rating) FROM Review r
        WHERE r.event.channel.id = :channelId
    """)
    fun averageRatingByChannelId(@Param("channelId") channelId: Long): Double?

    /** 채널 전체 후기 수. */
    @Query("""
        SELECT COUNT(r) FROM Review r
        WHERE r.event.channel.id = :channelId
    """)
    fun countByChannelId(@Param("channelId") channelId: Long): Long

    /**
     * 다수 채널의 (channelId, average, count) 묶음 — Channel 목록 응답이 N+1 없이 별점을 채우기 위함.
     * channelIds 가 비어 있으면 caller 가 호출 전에 가드.
     */
    @Query("""
        SELECT r.event.channel.id, AVG(r.rating), COUNT(r)
        FROM Review r
        WHERE r.event.channel.id IN :channelIds
        GROUP BY r.event.channel.id
    """)
    fun aggregateByChannelIds(@Param("channelIds") channelIds: Collection<Long>): List<Array<Any>>
}
