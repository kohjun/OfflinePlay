package com.contenido.domain.payment.repository

import com.contenido.domain.event.entity.Event
import com.contenido.domain.payment.entity.PaymentAttempt
import com.contenido.domain.payment.entity.PaymentStatus
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.Optional

interface PaymentAttemptRepository : JpaRepository<PaymentAttempt, Long> {

    /** webhook 처리 진입점 — idempotencyKey(= orderId) 로 시도를 찾는다. */
    fun findByIdempotencyKey(idempotencyKey: String): Optional<PaymentAttempt>

    /**
     * 같은 (event, buyer) 에 READY 상태로 살아있는 prepare 가 있는지 확인.
     * prepare 멱등을 위해 첫 호출에서 만든 row 를 두 번째 호출이 그대로 재사용한다.
     */
    fun findFirstByEventAndBuyerAndStatusOrderByCreatedAtDesc(
        event: Event,
        buyer: User,
        status: PaymentStatus,
    ): Optional<PaymentAttempt>

    /**
     * Ticket → 발급한 PaymentAttempt 역추적. 한 Ticket 은 한 PaymentAttempt 만이 PAID 결과로
     * 연결되므로 결과가 둘 이상 나오는 경우는 데이터 정합성 오류로 본다 — service 레이어에서
     * 0 또는 1 행만 사용한다.
     */
    fun findByTicket(ticket: Ticket): Optional<PaymentAttempt>

    /**
     * 다수 Ticket → 한 번 쿼리로 묶음 조회. MY 결제 내역 화면(getMyParticipations)이
     * 페이지 내 모든 티켓에 대한 PaymentAttempt 를 N+1 없이 가져오기 위함.
     *
     * 무료 티켓 등 PaymentAttempt 가 연결되지 않은 ticket 은 결과에 포함되지 않는다 —
     * 호출처가 `Map<ticketId, PaymentAttempt>` 로 변환해 zip.
     */
    fun findByTicketIn(tickets: Collection<Ticket>): List<PaymentAttempt>

    /**
     * PR153 — 채널 단위 매출/환불 집계. event 별로 grouping 한 raw row 를 반환한다.
     *
     * 정책:
     *  - 결제 시도가 PAID 또는 PARTIALLY_REFUNDED 일 때만 매출 row 에 포함 (READY/FAILED/CANCELED 제외).
     *  - gross         = SUM(amount)            : 사용자가 실제 결제한 총액
     *  - refunded      = SUM(refundedAmount)    : 누적 환불액 (전액 환불 + 부분 환불)
     *  - partialRefund = SUM(refundedAmount where status=PARTIALLY_REFUNDED)
     *  - fullRefundCnt = COUNT(refundedAmount=amount AND status=PAID 인 row)
     *  - paidCount     = 매출 row 자체 갯수 (이 이벤트의 결제 건수)
     *
     * 날짜 필터는 PaymentAttempt.createdAt 기준 (결제 시도 시작 시각). nullable.
     *
     * 반환 row: [eventId(Long), eventTitle(String), gross(Long), refunded(Long),
     *           partialRefund(Long), fullRefundCount(Long), paidCount(Long)]
     */
    @Query(
        """
        SELECT p.event.id, p.event.title,
            COALESCE(SUM(CASE WHEN p.status IN ('PAID', 'PARTIALLY_REFUNDED') THEN p.amount ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN p.status IN ('PAID', 'PARTIALLY_REFUNDED') THEN p.refundedAmount ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN p.status = 'PARTIALLY_REFUNDED' THEN p.refundedAmount ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN p.status = 'PAID' AND p.refundedAmount > 0 AND p.refundedAmount = p.amount THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN p.status IN ('PAID', 'PARTIALLY_REFUNDED') THEN 1 ELSE 0 END), 0)
        FROM PaymentAttempt p
        WHERE p.event.channel.id = :channelId
          AND (:from IS NULL OR p.createdAt >= :from)
          AND (:to IS NULL OR p.createdAt < :to)
        GROUP BY p.event.id, p.event.title
        """,
    )
    fun aggregateChannelAnalytics(
        @Param("channelId") channelId: Long,
        @Param("from") from: LocalDateTime?,
        @Param("to") to: LocalDateTime?,
    ): List<Array<Any>>
}
