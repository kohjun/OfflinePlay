package com.contenido.domain.ticket.repository

import com.contenido.domain.event.entity.Event
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TicketRepository : JpaRepository<Ticket, Long> {

    fun findByBuyer(buyer: User): List<Ticket>

    fun findByEvent(event: Event): List<Ticket>

    fun existsByEventAndBuyerAndStatusIn(
        event: Event,
        buyer: User,
        statuses: Collection<TicketStatus>,
    ): Boolean

    /**
     * 한 사용자가 여러 이벤트에 대해 가진 티켓을 한 번에 조회한다.
     * MyPage 의 "내 신청/티켓" 섹션이 참가 이력 페이지의 eventId 목록을 모아 N+1 없이 호출한다.
     */
    @Query("SELECT t FROM Ticket t WHERE t.buyer = :buyer AND t.event.id IN :eventIds")
    fun findByBuyerAndEventIdIn(
        @Param("buyer") buyer: User,
        @Param("eventIds") eventIds: Collection<Long>,
    ): List<Ticket>

    /**
     * 한 이벤트에 대해 여러 참가자의 티켓을 한 번에 조회한다.
     * 신청자 관리 화면이 APPROVED 신청자 묶음을 ticket 과 zip 하기 위해 사용 — N+1 회피.
     */
    @Query("SELECT t FROM Ticket t WHERE t.event = :event AND t.buyer.id IN :buyerIds")
    fun findByEventAndBuyerIdIn(
        @Param("event") event: Event,
        @Param("buyerIds") buyerIds: Collection<Long>,
    ): List<Ticket>

    /** PR145 — 사용자가 USED 상태로 체크인 완료한 티켓 수. Trust Snapshot 의 checkedInCount. */
    fun countByBuyerIdAndStatus(buyerId: Long, status: TicketStatus): Long
}
