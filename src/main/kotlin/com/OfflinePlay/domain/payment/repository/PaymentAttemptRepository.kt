package com.contenido.domain.payment.repository

import com.contenido.domain.event.entity.Event
import com.contenido.domain.payment.entity.PaymentAttempt
import com.contenido.domain.payment.entity.PaymentStatus
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
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
}
