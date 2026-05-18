package com.contenido.domain.ticket.entity

import com.contenido.domain.event.entity.Event
import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * 참가비 티켓.
 *
 * 결제 PG 연동은 이번 단계에 포함하지 않는다. 향후 결제 모듈이 들어오면
 * Ticket을 발행/취소/환불하는 트랜잭션의 진입점이 된다.
 *
 * 설계 메모:
 *  - `event_id, buyer_id` 조합은 동시 신청을 막아야 하지만 환불 후 재구매 케이스가
 *    있으므로 unique 제약은 두지 않고 서비스 레이어에서 active 상태 중복만 검증한다.
 *  - price는 발행 시점 Event.participationFee 스냅샷. 이후 이벤트 참가비가 변경되어도
 *    티켓 금액은 고정.
 */
@Entity
@Table(
    name = "tickets",
    indexes = [
        Index(name = "idx_tickets_event", columnList = "event_id"),
        Index(name = "idx_tickets_buyer", columnList = "buyer_id"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class Ticket(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: Event,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    val buyer: User,

    @Column(nullable = false)
    val price: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: TicketStatus = TicketStatus.PAID,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "purchased_at", nullable = false, updatable = false)
    lateinit var purchasedAt: LocalDateTime
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set

    /**
     * 현장에서 스태프가 체크인 처리한 시각. PAID → USED 로 전환될 때 세팅된다.
     * USED 상태가 아니면 의미가 없다 (null).
     */
    @Column(name = "used_at")
    var usedAt: LocalDateTime? = null
        protected set

    fun markUsed(now: LocalDateTime = LocalDateTime.now()) {
        status = TicketStatus.USED
        usedAt = now
    }

    fun cancel() {
        status = TicketStatus.CANCELED
    }

    fun refund() {
        status = TicketStatus.REFUNDED
    }

    /**
     * PR117 — 부분 환불 진행 중 표시. PaymentAttempt.refundedAmount 가 amount 미만일 때 호출.
     * 누적 환불액이 결제 금액에 도달하면 [refund] 로 전이된다 (별도 호출).
     *
     * 호출자가 USED/CANCELED/REFUNDED 가드를 미리 검증해야 한다 (PaymentService).
     */
    fun markPartiallyRefunded() {
        status = TicketStatus.PARTIALLY_REFUNDED
    }
}
