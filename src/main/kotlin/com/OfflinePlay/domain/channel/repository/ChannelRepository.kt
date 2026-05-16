package com.contenido.domain.channel.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface ChannelRepository : JpaRepository<Channel, Long> {

    fun findByOwner(owner: User): Optional<Channel>

    /** PR53 — owner 본인의 자동 숨김 채널. */
    fun findByOwnerAndHiddenAtIsNotNullOrderByHiddenAtDesc(owner: User): List<Channel>

    /** PR55 — Admin moderation queue 빌드. 소유자 무관, 모든 hidden 채널. */
    fun findByHiddenAtIsNotNullOrderByHiddenAtDesc(): List<Channel>

    /** PR57 — analytics 범위 조회. */
    fun findByHiddenAtBetween(
        from: java.time.LocalDateTime,
        to: java.time.LocalDateTime,
    ): List<Channel>

    fun existsByOwner(owner: User): Boolean

    fun countByOwner(owner: User): Long

    @Query("select sum(c.subscriberCount) from Channel c where c.owner = :owner")
    fun sumSubscriberCountByOwner(@Param("owner") owner: User): Long?

    fun findByCategoryOrderBySubscriberCountDesc(category: ChannelCategory, pageable: Pageable): Page<Channel>

    /** PR51 — 자동 숨김 제외. 사용자 카테고리 페이지의 채널 목록 진입점. */
    fun findByCategoryAndHiddenAtIsNullOrderBySubscriberCountDesc(
        category: ChannelCategory,
        pageable: Pageable,
    ): Page<Channel>

    /**
     * Explore 페이지용 LIKE 검색. 셋 다 null/blank 면 전체를 반환한다.
     * Elasticsearch 의존 없이 항상 동작한다.
     */
    @Query("""
        SELECT c FROM Channel c
        WHERE (:keyword IS NULL OR :keyword = ''
               OR LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(c.description) LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:category IS NULL OR c.category = :category)
        ORDER BY c.subscriberCount DESC, c.id DESC
    """)
    fun searchForExplore(
        @Param("keyword") keyword: String?,
        @Param("category") category: ChannelCategory?,
        pageable: Pageable,
    ): Page<Channel>
}
