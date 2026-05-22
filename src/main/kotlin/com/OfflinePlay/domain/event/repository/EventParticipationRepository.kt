package com.contenido.domain.event.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface EventParticipationRepository : JpaRepository<EventParticipation, Long> {

    /** (event, participant) 조합은 unique 이므로 0 또는 1행만 반환된다. */
    fun findByEventAndParticipant(event: Event, participant: User): Optional<EventParticipation>

    /** 활성 상태(PENDING/APPROVED)인 신청이 이미 있는지 확인할 때 사용. */
    fun existsByEventAndParticipantAndStatusIn(
        event: Event,
        participant: User,
        statuses: Collection<ParticipationStatus>,
    ): Boolean

    /** 기획자 신청자 관리 페이지: 신청 시각 내림차순. */
    fun findByEventOrderByJoinedAtDesc(event: Event): List<EventParticipation>

    /** 참가자 본인 신청 이력: 신청 시각 내림차순. */
    fun findByParticipantOrderByJoinedAtDesc(participant: User): List<EventParticipation>

    /** 참가자 본인 신청 이력 페이징. MyPage "내 신청/티켓" 섹션이 사용. */
    fun findByParticipantOrderByJoinedAtDesc(
        participant: User,
        pageable: Pageable,
    ): Page<EventParticipation>

    /** 이벤트의 특정 상태(예: APPROVED) 신청 수. 정원/통계 계산용. */
    fun countByEventAndStatus(event: Event, status: ParticipationStatus): Long

    /**
     * 채널 산하 모든 이벤트의 신청 상태별 카운트를 한 번에 집계한다.
     * Creator Studio 가 이벤트별 N+1 카운트 쿼리 대신 단일 grouped query 로 호출한다.
     *
     * 반환: 각 row 는 `[eventId: Long, status: ParticipationStatus, count: Long]`.
     * (JPA 표준 Tuple projection 대신 Array<Any> 사용 — Kotlin 매핑이 단순함.)
     */
    @Query(
        """
        SELECT p.event.id, p.status, COUNT(p)
        FROM EventParticipation p
        WHERE p.event.channel = :channel
        GROUP BY p.event.id, p.status
        """,
    )
    fun countByChannelGroupedByStatus(@Param("channel") channel: Channel): List<Array<Any>>

    // ── 기존 호출부 호환용 ───────────────────────────────────────────────────────
    fun existsByEventAndParticipant(event: Event, participant: User): Boolean

    fun countByEvent(event: Event): Long

    fun countByEventIn(events: List<Event>): Long

    fun deleteByEventAndParticipant(event: Event, participant: User)

    /** PR145 — 사용자가 참가했던 이벤트 수 (status 무관). Trust Snapshot 의 participatedEventCount. */
    fun countByParticipantId(participantId: Long): Long

    /** PR145 — 사용자의 APPROVED 참가 횟수만. 추후 분리 표시가 필요할 때 사용. */
    fun countByParticipantIdAndStatus(participantId: Long, status: ParticipationStatus): Long
}
