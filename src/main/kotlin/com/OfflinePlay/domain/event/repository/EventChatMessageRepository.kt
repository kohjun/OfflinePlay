package com.contenido.domain.event.repository

import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventChatMessage
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * PR160 — 채팅 메시지 페이지네이션 + cursor 기반 히스토리.
 *
 * deleted_at IS NULL 인 row 만 조회 — soft delete 정책. (현 PR 에서 delete UI 는 없지만 future-proof.)
 */
interface EventChatMessageRepository : JpaRepository<EventChatMessage, Long> {

    /** PR160 — 최신 N 건 (created_at DESC). frontend 가 reverse 해서 시간 오름차순으로 렌더. */
    @Query(
        """
        SELECT m FROM EventChatMessage m
        WHERE m.event = :event
          AND m.deletedAt IS NULL
        ORDER BY m.createdAt DESC, m.id DESC
        """,
    )
    fun findRecentByEvent(@Param("event") event: Event, pageable: Pageable): List<EventChatMessage>

    /**
     * PR160 — `before` cursor 페이징. `before` 보다 createdAt 이 더 과거인 row 를 가져온다.
     * 같은 createdAt 의 동률은 id desc 로 깨뜨려서 안정 페이징 보장.
     */
    @Query(
        """
        SELECT m FROM EventChatMessage m
        WHERE m.event = :event
          AND m.deletedAt IS NULL
          AND (m.createdAt < :beforeCreatedAt
               OR (m.createdAt = :beforeCreatedAt AND m.id < :beforeId))
        ORDER BY m.createdAt DESC, m.id DESC
        """,
    )
    fun findBeforeByEvent(
        @Param("event") event: Event,
        @Param("beforeCreatedAt") beforeCreatedAt: java.time.LocalDateTime,
        @Param("beforeId") beforeId: Long,
        pageable: Pageable,
    ): List<EventChatMessage>
}
