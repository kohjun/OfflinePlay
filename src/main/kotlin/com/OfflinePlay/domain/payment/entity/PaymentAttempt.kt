package com.contenido.domain.payment.entity

import com.contenido.domain.event.entity.Event
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * 유료 이벤트 결제 시도 (한 row = 한 prepare 호출의 수명).
 *
 * 수명:
 *  1. `POST /api/v1/events/{eventId}/payments/prepare` → READY 로 생성.
 *  2. 클라이언트가 [idempotencyKey] 를 orderId 로 사용해 PG SDK 호출.
 *  3. PG 가 `POST /api/v1/payments/webhook` 로 결과 통지 →
 *     - 성공: PAID 로 전환 + [ticket] 발급 + [providerPaymentKey] 세팅
 *     - 실패: FAILED
 *     - 사용자 취소: CANCELED
 *
 * 설계 메모:
 *  - [idempotencyKey] 는 unique. webhook 이 재시도로 중복 도착해도 같은 row 를 멱등 처리한다.
 *  - [providerPaymentKey] 는 PG 가 부여하는 결제 키 (Toss `paymentKey`, PortOne `imp_uid` 등).
 *    webhook 이 도착하기 전엔 null.
 *  - [ticket] 은 webhook PAID 처리 시 [com.contenido.domain.ticket.service.TicketService] 가
 *    발급한 Ticket 으로 채워진다. READY/FAILED/CANCELED 동안엔 null.
 *  - 정책/전이 다이어그램 상세: docs/payment-refund-policy.md
 */
@Entity
@Table(
    name = "payment_attempts",
    indexes = [
        Index(name = "idx_payment_attempts_event", columnList = "event_id"),
        Index(name = "idx_payment_attempts_buyer", columnList = "buyer_id"),
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_payment_attempts_idempotency_key", columnNames = ["idempotency_key"]),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class PaymentAttempt(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: Event,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    val buyer: User,

    @Column(name = "idempotency_key", nullable = false, length = 64)
    val idempotencyKey: String,

    @Column(nullable = false)
    val amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PaymentStatus = PaymentStatus.READY,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var provider: PaymentProvider = PaymentProvider.NONE,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    var ticket: Ticket? = null,

    @Column(name = "provider_payment_key", length = 128)
    var providerPaymentKey: String? = null,

    @Column(name = "refunded_at")
    var refundedAt: LocalDateTime? = null,

    @Column(name = "refund_reason", length = 500)
    var refundReason: String? = null,

    /**
     * PR117 — 누적 환불 금액. default 0 (DB) / 0 (생성 시). 부분 환불 호출마다 누적되며,
     * 누적 금액이 [amount] 에 도달하면 [markFullyRefunded] 가 호출되어 fully refunded 상태로 전이.
     */
    @Column(name = "refunded_amount", nullable = false)
    var refundedAmount: Long = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set

    fun markPaid(ticket: Ticket, providerPaymentKey: String?, provider: PaymentProvider) {
        this.status = PaymentStatus.PAID
        this.ticket = ticket
        this.providerPaymentKey = providerPaymentKey
        this.provider = provider
    }

    fun markFailed(provider: PaymentProvider) {
        this.status = PaymentStatus.FAILED
        this.provider = provider
    }

    fun markCanceled() {
        this.status = PaymentStatus.CANCELED
    }

    /**
     * 환불 완료 처리 (전액). PR117 부터 [refundedAmount] 도 [amount] 로 설정 — fully refunded
     * 표현이 (status=PAID 유지, refundedAt!=null, refundedAmount==amount) 의 3-tuple 로 일관된다.
     *
     * 한 PaymentAttempt 에 두 번 호출되지 않도록 호출자가 [refundedAt] null 체크 + remaining
     * refundable amount 검증 (PR117: cumulative partial → full transition 도 이 메서드로 진입).
     */
    fun markRefunded(reason: String, at: LocalDateTime = LocalDateTime.now()) {
        markFullyRefunded(reason, at)
    }

    /**
     * PR117 — 부분 환불 누적. status 를 PARTIALLY_REFUNDED 로 전환하고 [refundedAmount] 에
     * [deltaAmount] 를 더한다. [refundedAt] 은 매번 갱신되고, [refundReason] 도 마지막 사유로
     * 덮어쓴다 — 운영자가 마지막 환불 사유를 즉시 볼 수 있게.
     *
     * 호출자가 [deltaAmount] 범위 (1..[remainingRefundableAmount]) 를 검증해야 한다. 본 메서드는
     * 음수/0 또는 remaining 초과를 막지 않는다.
     */
    fun markPartiallyRefunded(deltaAmount: Long, reason: String, at: LocalDateTime = LocalDateTime.now()) {
        this.refundedAmount += deltaAmount
        this.refundedAt = at
        this.refundReason = reason.take(500)
        this.status = PaymentStatus.PARTIALLY_REFUNDED
    }

    /**
     * PR117 — 전액 환불 (또는 부분 환불 누적 합이 [amount] 에 도달한 경우의 cascade) 처리.
     *  - [refundedAmount] 를 [amount] 로 set (이전 값에 더하지 않고 덮어씀 — fully refunded 표현).
     *  - status 는 PAID 로 유지 (기존 PR42 정책 — Ticket.REFUNDED 가 권위 있는 상태).
     *  - [refundedAt] / [refundReason] 갱신.
     */
    fun markFullyRefunded(reason: String, at: LocalDateTime = LocalDateTime.now()) {
        this.refundedAmount = this.amount
        this.refundedAt = at
        this.refundReason = reason.take(500)
        this.status = PaymentStatus.PAID
    }

    /**
     * PR117 — 남은 환불 가능 금액. 음수가 될 수 없다 (계산 상 확인용).
     */
    fun remainingRefundableAmount(): Long = (amount - refundedAmount).coerceAtLeast(0)
}
