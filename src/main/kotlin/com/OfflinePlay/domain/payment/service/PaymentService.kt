package com.contenido.domain.payment.service

import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.payment.dto.PaymentConfirmRequest
import com.contenido.domain.payment.dto.PaymentConfirmResponse
import com.contenido.domain.payment.dto.PaymentPrepareResponse
import com.contenido.domain.payment.dto.PaymentWebhookRequest
import com.contenido.domain.payment.entity.PaymentAttempt
import com.contenido.domain.payment.entity.PaymentStatus
import com.contenido.domain.payment.gateway.PaymentGateway
import com.contenido.domain.payment.gateway.PaymentGatewayConfirmRequest
import com.contenido.domain.payment.gateway.PaymentGatewayConfirmResult
import com.contenido.domain.payment.repository.PaymentAttemptRepository
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.ticket.service.TicketService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AlreadyJoinedException
import com.contenido.global.exception.EventAlreadyStartedException
import com.contenido.global.exception.EventClosedException
import com.contenido.global.exception.EventFullException
import com.contenido.global.exception.EventNotFoundException
import com.contenido.global.exception.FreeEventCannotPreparePaymentException
import com.contenido.global.exception.InvalidPaymentAmountException
import com.contenido.global.exception.InvalidPaymentOrderIdException
import com.contenido.global.exception.InvalidPaymentStateException
import com.contenido.global.exception.OwnerCannotApplyException
import com.contenido.global.exception.PaymentAttemptNotFoundException
import com.contenido.global.exception.PaymentConfirmFailedException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 유료 이벤트 결제 흐름의 도메인 진입점.
 *
 * 두 메서드만 노출한다:
 *  - [preparePayment] : 결제 페이지 진입 직전. PaymentAttempt(READY) 를 만들고
 *    클라이언트가 PG SDK 호출에 쓸 idempotencyKey/금액/orderName 을 돌려준다.
 *  - [handleWebhook]  : PG 가 결제 결과를 통지할 때. idempotencyKey 로 PaymentAttempt 를
 *    찾고 멱등 처리 + 성공 시 Ticket 발급 + 정원 증가.
 *
 * 미해결 트레이드오프 (PR40 이후 다룸):
 *  - 유료 흐름에는 EventParticipation row 가 생기지 않는다. 신청자 관리 페이지·통계가
 *    무료 흐름과 일관되려면 webhook PAID 시 EventParticipation(APPROVED) 동기 생성 또는
 *    `Ticket` 기반으로 신청자 목록 재구성이 필요하다.
 *  - 정원 검증은 prepare 시점에만 — 동시에 둘 이상이 READY 가 된 뒤 둘 다 webhook 도착하면
 *    초과 가능. 후속 PR 에서 (1) PaymentAttempt READY 수를 합쳐서 정원 검증하거나
 *    (2) webhook 시점에서도 한 번 더 검증하는 식으로 보강.
 *  - provider 별 signature/HMAC 검증은 TODO. [handleWebhook] 진입 전 별도 filter 또는
 *    service 안의 첫 단계에서 추가한다.
 *
 * 정책/전이 다이어그램 상세: docs/payment-refund-policy.md
 */
@Service
@Transactional(readOnly = true)
class PaymentService(
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val ticketRepository: TicketRepository,
    private val ticketService: TicketService,
    private val paymentGateway: PaymentGateway,
    private val eventParticipationRepository: EventParticipationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 결제 준비. 이벤트가 유료(participationFee > 0)이고 정원/상태 검증을 통과하면
     * PaymentAttempt(READY) 를 생성하거나, 같은 (user, event) 의 살아있는 READY 가
     * 있으면 그 row 를 그대로 돌려준다(멱등).
     *
     * 거부 조건:
     *  - 이벤트 없음 → [EventNotFoundException]
     *  - 사용자 없음 → [UserNotFoundException]
     *  - 이벤트 CLOSED → [EventClosedException]
     *  - 이벤트 시작 시각 ≤ 현재 → [EventAlreadyStartedException]
     *  - 채널 owner 본인 시도 → [OwnerCannotApplyException]
     *  - 무료 이벤트(participationFee == 0) → [FreeEventCannotPreparePaymentException]
     *  - 이미 살아있는 티켓(PAID/USED) 보유 → [AlreadyJoinedException]
     *  - 정원 가득 → [EventFullException]
     */
    @Transactional
    fun preparePayment(userId: Long, eventId: Long): PaymentPrepareResponse {
        val buyer = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }

        validatePrepareable(event, buyer)

        // 멱등: 같은 (event, buyer) 의 READY 가 살아있으면 그대로 반환.
        val existing = paymentAttemptRepository
            .findFirstByEventAndBuyerAndStatusOrderByCreatedAtDesc(event, buyer, PaymentStatus.READY)
        if (existing.isPresent) {
            return existing.get().toPrepareResponse()
        }

        val attempt = paymentAttemptRepository.save(
            PaymentAttempt(
                event = event,
                buyer = buyer,
                idempotencyKey = newIdempotencyKey(),
                amount = event.participationFee,
            )
        )
        return attempt.toPrepareResponse()
    }

    /**
     * PG webhook 진입점.
     *
     * 멱등성:
     *  - idempotencyKey 로 PaymentAttempt 를 찾는다. 없으면 [PaymentAttemptNotFoundException].
     *  - 이미 PAID/FAILED/CANCELED 면 아무 일 하지 않고 정상 응답 (중복 webhook).
     *
     * 금액 검증:
     *  - request.amount != attempt.amount → [InvalidPaymentAmountException]. PAID 외 상태에서는 검증 X
     *    (FAILED/CANCELED webhook 은 금액 정보가 없거나 0 일 수 있음).
     *
     * 상태별 처리:
     *  - PAID     : [TicketService.issuePaidTicket] 으로 Ticket 발급 + PaymentAttempt.markPaid + 정원 증가
     *  - FAILED   : PaymentAttempt.markFailed (운영자 알림은 후속 PR)
     *  - CANCELED : PaymentAttempt.markCanceled
     *  - READY    : 잘못된 webhook — 정책상 들어올 수 없음. 들어오면 무시 (로그만).
     *
     * TODO(payment-integration): provider 별 signature/HMAC 검증을 진입 전에 추가.
     */
    @Transactional
    fun handleWebhook(request: PaymentWebhookRequest) {
        val attempt = paymentAttemptRepository.findByIdempotencyKey(request.idempotencyKey)
            .orElseThrow { PaymentAttemptNotFoundException() }

        // 이미 처리된 시도면 멱등으로 종료.
        if (attempt.status != PaymentStatus.READY) {
            log.info("[handleWebhook] already processed attemptId={} status={} — skip",
                attempt.id, attempt.status)
            return
        }

        when (request.status) {
            PaymentStatus.PAID -> {
                if (attempt.amount != request.amount) throw InvalidPaymentAmountException()
                val ticket = ticketService.issuePaidTicket(
                    userId = attempt.buyer.id,
                    eventId = attempt.event.id,
                    paidAmount = attempt.amount,
                )
                attempt.markPaid(ticket, request.providerPaymentKey, request.provider)
                attempt.event.increaseParticipant()
            }
            PaymentStatus.FAILED -> attempt.markFailed(request.provider)
            PaymentStatus.CANCELED -> attempt.markCanceled()
            PaymentStatus.READY -> {
                log.warn("[handleWebhook] webhook with READY status — ignoring, attemptId={}", attempt.id)
            }
        }
    }

    /**
     * 클라이언트 confirm — PG SDK 콜백으로 받은 paymentKey 를 백엔드가 PG 에 직접 confirm 호출한다.
     * webhook 으로도 똑같은 PAID 처리가 도착할 수 있으므로 두 진입점이 모두 멱등이어야 한다.
     *
     * 흐름:
     *  1. PaymentAttempt 조회 + buyer 본인 검증
     *  2. 이미 PAID 이면 멱등 응답 (기존 ticket 정보 포함, gateway 재호출 X)
     *  3. READY 가 아니면 [InvalidPaymentStateException] (FAILED/CANCELED 는 재시도 X)
     *  4. orderId/amount 검증 (스푸핑 방지)
     *  5. 재검증: CLOSED/시작후/owner 본인/정원 — prepare 와 confirm 사이 갭이 있을 수 있음
     *  6. [PaymentGateway.confirm] 호출
     *  7. Success → Ticket 발급 + PaymentAttempt PAID + 정원 ++ + EventParticipation APPROVED 보장
     *     Failure → PaymentAttempt FAILED + [PaymentConfirmFailedException]
     */
    @Transactional
    fun confirmPayment(
        userId: Long,
        paymentAttemptId: Long,
        request: PaymentConfirmRequest,
    ): PaymentConfirmResponse {
        val attempt = paymentAttemptRepository.findById(paymentAttemptId)
            .orElseThrow { PaymentAttemptNotFoundException() }

        // 1. buyer 본인 확인 (ADMIN 도 confirm 은 본인 흐름 아니므로 거부).
        if (attempt.buyer.id != userId) throw UnauthorizedException()

        // 2. 멱등: 이미 PAID 면 기존 결과를 그대로 응답한다 (gateway 재호출 X).
        if (attempt.status == PaymentStatus.PAID) {
            return attempt.toConfirmResponse(approvedAt = null)
        }

        // 3. READY 만 confirm 진입 가능.
        if (attempt.status != PaymentStatus.READY) {
            throw InvalidPaymentStateException()
        }

        // 4. orderId / amount 일치 검증 — 스푸핑된 confirm 차단.
        if (attempt.idempotencyKey != request.orderId) throw InvalidPaymentOrderIdException()
        if (attempt.amount != request.amount) throw InvalidPaymentAmountException()

        // 5. prepare 이후 상태 변화 재검증 (이벤트 상태/정원/owner).
        revalidateBeforeConfirm(attempt.event, attempt.buyer)

        // 6. PG 호출.
        val result = paymentGateway.confirm(
            PaymentGatewayConfirmRequest(
                orderId = attempt.idempotencyKey,
                paymentKey = request.paymentKey,
                amount = attempt.amount,
            )
        )

        return when (result) {
            is PaymentGatewayConfirmResult.Success -> {
                val ticket = ticketService.issuePaidTicket(
                    userId = attempt.buyer.id,
                    eventId = attempt.event.id,
                    paidAmount = attempt.amount,
                )
                attempt.markPaid(ticket, result.providerPaymentKey, result.provider)
                attempt.event.increaseParticipant()
                ensureApprovedParticipation(attempt.event, attempt.buyer)
                attempt.toConfirmResponse(approvedAt = result.approvedAt)
            }
            is PaymentGatewayConfirmResult.Failure -> {
                attempt.markFailed(result.provider)
                log.warn(
                    "[confirmPayment] gateway rejected attemptId={} code={} msg={}",
                    attempt.id, result.code, result.message,
                )
                throw PaymentConfirmFailedException(result.code, result.message)
            }
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────────

    /**
     * confirm 진입 직전 재검증. prepare 와 confirm 사이에 이벤트가 종료/시작되거나 정원이 차는
     * 변화를 잡는다. owner 본인 검증은 prepare 와 동일 규칙.
     */
    private fun revalidateBeforeConfirm(event: Event, buyer: User) {
        if (event.status == EventStatus.CLOSED) throw EventClosedException()
        if (!event.startAt.isAfter(LocalDateTime.now())) throw EventAlreadyStartedException()
        if (event.channel.owner.id == buyer.id) throw OwnerCannotApplyException()
        if (event.isFull()) throw EventFullException()
    }

    /**
     * 유료 결제 성공 시 EventParticipation 정합성 보장.
     *
     *  - 기존 PENDING/REJECTED/CANCELED row 가 있으면 [EventParticipation.approveByPayment] 로 APPROVED 전환.
     *  - 이미 APPROVED 면 no-op (멱등).
     *  - row 가 없으면 새로 만들어 APPROVED 로 저장.
     *
     * 신청자 관리 화면이 무료 흐름과 동일한 데이터 모델 위에서 동작하게 하기 위해 도입.
     */
    private fun ensureApprovedParticipation(event: Event, buyer: User) {
        val existing = eventParticipationRepository.findByEventAndParticipant(event, buyer)
        if (existing.isPresent) {
            val p = existing.get()
            if (p.status != ParticipationStatus.APPROVED) {
                p.approveByPayment()
            }
            return
        }
        eventParticipationRepository.save(
            EventParticipation(event = event, participant = buyer).apply {
                approveByPayment()
            }
        )
    }

    private fun PaymentAttempt.toConfirmResponse(approvedAt: String?): PaymentConfirmResponse =
        PaymentConfirmResponse(
            paymentAttemptId = id,
            status = status,
            provider = provider,
            amount = amount,
            ticketId = ticket?.id,
            providerPaymentKey = providerPaymentKey,
            approvedAt = approvedAt,
        )

    private fun validatePrepareable(event: Event, buyer: User) {
        if (event.status == EventStatus.CLOSED) throw EventClosedException()
        if (!event.startAt.isAfter(LocalDateTime.now())) throw EventAlreadyStartedException()
        if (event.channel.owner.id == buyer.id) throw OwnerCannotApplyException()
        if (event.participationFee <= 0L) throw FreeEventCannotPreparePaymentException()

        val hasLiveTicket = ticketRepository.existsByEventAndBuyerAndStatusIn(
            event = event,
            buyer = buyer,
            statuses = listOf(TicketStatus.PAID, TicketStatus.USED),
        )
        if (hasLiveTicket) throw AlreadyJoinedException()

        if (event.isFull()) throw EventFullException()
    }

    private fun newIdempotencyKey(): String = UUID.randomUUID().toString()

    private fun PaymentAttempt.toPrepareResponse(): PaymentPrepareResponse =
        PaymentPrepareResponse(
            paymentAttemptId = id,
            eventId = event.id,
            amount = amount,
            orderName = event.title,
            idempotencyKey = idempotencyKey,
            status = status,
        )
}
