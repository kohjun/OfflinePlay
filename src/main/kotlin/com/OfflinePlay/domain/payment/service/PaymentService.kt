package com.contenido.domain.payment.service

import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.payment.dto.PaymentConfirmRequest
import com.contenido.domain.payment.dto.PaymentConfirmResponse
import com.contenido.domain.payment.dto.PaymentPrepareResponse
import com.contenido.domain.payment.dto.PaymentWebhookRequest
import com.contenido.domain.payment.dto.RefundTicketRequest
import com.contenido.domain.payment.dto.RefundTicketResponse
import com.contenido.domain.payment.entity.PaymentAttempt
import com.contenido.domain.payment.entity.PaymentStatus
import com.contenido.domain.payment.gateway.PaymentGateway
import com.contenido.domain.payment.gateway.PaymentGatewayConfirmRequest
import com.contenido.domain.payment.gateway.PaymentGatewayConfirmResult
import com.contenido.domain.payment.gateway.PaymentGatewayRefundRequest
import com.contenido.domain.payment.gateway.PaymentGatewayRefundResult
import com.contenido.domain.payment.repository.PaymentAttemptRepository
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.ticket.service.TicketService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
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
import com.contenido.global.exception.PaymentNotRefundableException
import com.contenido.global.exception.RefundDeadlinePassedException
import com.contenido.global.exception.RefundFailedException
import com.contenido.global.exception.TicketAlreadyRefundedException
import com.contenido.global.exception.TicketAlreadyUsedException
import com.contenido.global.exception.TicketNotFoundException
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
 * 미해결 트레이드오프:
 *  - 유료 흐름에는 EventParticipation row 가 confirm 성공 시점에 생성된다 (ensureApprovedParticipation).
 *    webhook 만 도착하는 흐름(클라이언트가 confirm 콜백 전에 떠난 경우) 에서는 EventParticipation
 *    이 누락될 수 있어 후속 PR 에서 webhook PAID 분기에도 동일 보장 추가 필요.
 *  - 정원 검증은 prepare/confirm 시점에만 — 동시에 둘 이상이 READY 가 된 뒤 둘 다 confirm
 *    도착하면 마지막 한 명이 EventFullException 으로 차단되지만 PG 결제는 이미 일어났다.
 *    환불 자동화 또는 PaymentAttempt READY 카운트 합산 검증이 후속 PR 후보.
 *  - webhook signature 검증은 controller 진입 직전에 [PaymentWebhookSignatureVerifier] 가
 *    수행한다 (PR42 hardening 으로 prod 강제 + fail-fast 부팅 가드 도입).
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
    private val notificationService: NotificationService,
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
     * Signature 검증은 컨트롤러 진입 직전에 [PaymentWebhookSignatureVerifier] 가 처리한다 —
     * 이 메서드는 검증된 body 만 받는다고 가정.
     */
    @Transactional
    fun handleWebhook(request: PaymentWebhookRequest) {
        val attempt = paymentAttemptRepository.findByIdempotencyKey(request.idempotencyKey)
            .orElseThrow { PaymentAttemptNotFoundException() }

        // REFUNDED webhook 은 PAID 상태에서만 의미가 있다 — 별도 분기로 처리.
        if (request.status == PaymentStatus.REFUNDED) {
            handleRefundedWebhook(attempt)
            return
        }

        // 이미 처리된 시도면 멱등으로 종료 (PAID/FAILED/CANCELED 모두 재처리 X).
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
            PaymentStatus.REFUNDED -> { /* handled above */ }
            PaymentStatus.READY -> {
                log.warn("[handleWebhook] webhook with READY status — ignoring, attemptId={}", attempt.id)
            }
        }
    }

    /**
     * REFUNDED webhook 처리. PaymentAttempt.status 는 PAID 그대로 유지하고
     * `refundedAt` 으로 환불 시점을 기록한다 — Ticket 의 REFUNDED 가 권위 있는 상태.
     *
     * 멱등 가드:
     *  - attempt.status != PAID → 환불 webhook 은 결제 안 된 attempt 에 의미 없음. skip.
     *  - attempt.refundedAt != null → 이미 환불 처리됨. skip.
     *  - ticket 이 null → confirm 이 끝나지 않은 비정상 상태. 운영 알람만 남기고 skip.
     */
    private fun handleRefundedWebhook(attempt: PaymentAttempt) {
        if (attempt.status != PaymentStatus.PAID) {
            log.warn(
                "[handleWebhook] REFUNDED for non-PAID attemptId={} status={} — skip",
                attempt.id, attempt.status,
            )
            return
        }
        if (attempt.refundedAt != null) {
            log.info("[handleWebhook] REFUNDED already processed attemptId={}, skip", attempt.id)
            return
        }
        val ticket = attempt.ticket
        if (ticket == null) {
            log.warn(
                "[handleWebhook] REFUNDED webhook but ticket is null attemptId={} — operations alert",
                attempt.id,
            )
            return
        }
        if (ticket.status != TicketStatus.PAID) {
            // 이미 USED 면 webhook 으로 강제 환불하면 안 됨 — 운영 도구 개입 필요.
            log.warn(
                "[handleWebhook] REFUNDED webhook but ticketStatus={} attemptId={} — skip",
                ticket.status, attempt.id,
            )
            return
        }
        markRefundedInternal(attempt, ticket, reason = "PG_WEBHOOK")
    }

    /**
     * 사용자/owner/ADMIN 환불 요청. PG refund 호출 + Ticket REFUNDED 전환 +
     * 정원 -- + EventParticipation CANCELED 전환.
     *
     * 권한:
     *  - ticket.buyer 본인 — 본인이 셀프 환불 요청
     *  - 채널 owner — 환불 정책상 owner 가 환불 허용 (예: 이벤트 자체 취소)
     *  - ADMIN     — 운영 개입
     *  - 그 외 (STAFF 포함) → [UnauthorizedException]
     *
     * 상태 분기:
     *  - PAID  : 정상 환불 진행
     *  - USED  : [TicketAlreadyUsedException] (체크인 후 환불은 운영 도구로 별도)
     *  - REFUNDED : 이미 환불됨 — gateway 재호출 없이 기존 정보로 멱등 응답
     *  - CANCELED : [PaymentNotRefundableException] (참가 취소된 티켓은 환불 대상 아님)
     *
     * PaymentAttempt 가 PAID 가 아니거나 providerPaymentKey 가 비어 있으면 환불 불가
     * ([PaymentNotRefundableException]) — 결제 완료 시점 정보 없이는 PG 에 환불 요청 못함.
     *
     * 시간 가드 (PR43 MVP):
     *  - 이벤트 시작 시각 이후 환불 요청은 [RefundDeadlinePassedException] 으로 차단.
     *    ADMIN 도 본 메서드로는 막힌다 — 노쇼/행사 취소 보상은 별도 ADMIN 전용 운영 도구로.
     *  - 기획자/주최자 측 행사 취소로 인한 강제 환불은 후속 PR 에서 `force=true` 또는 별도 endpoint.
     */
    @Transactional
    fun refundPaymentByTicket(
        actorId: Long,
        ticketId: Long,
        request: RefundTicketRequest,
    ): RefundTicketResponse {
        val actor = userRepository.findById(actorId).orElseThrow { UserNotFoundException() }
        val ticket = ticketRepository.findById(ticketId).orElseThrow { TicketNotFoundException() }

        if (!canRequestRefund(actor, ticket)) throw UnauthorizedException()

        when (ticket.status) {
            TicketStatus.USED -> throw TicketAlreadyUsedException()
            TicketStatus.CANCELED -> throw PaymentNotRefundableException("취소된 티켓은 환불 대상이 아닙니다.")
            TicketStatus.REFUNDED -> {
                // 멱등: 기존 PaymentAttempt 정보로 응답한다.
                val attempt = paymentAttemptRepository.findByTicket(ticket)
                    .orElseThrow { PaymentNotRefundableException("연결된 결제 시도를 찾을 수 없습니다.") }
                return attempt.toRefundResponse(ticket)
            }
            TicketStatus.PAID -> { /* 진행 */ }
        }

        // PR43: 이벤트 시작 시각 이후 환불 차단. USED 가드와 함께 "행사가 시작되면 환불 불가"
        // 정책의 양면. 시작 직후 USED 마킹 전에 들어오는 요청도 여기서 막힌다.
        if (!ticket.event.startAt.isAfter(LocalDateTime.now())) {
            throw RefundDeadlinePassedException()
        }

        val attempt = paymentAttemptRepository.findByTicket(ticket)
            .orElseThrow { PaymentNotRefundableException("연결된 결제 시도를 찾을 수 없습니다.") }
        if (attempt.status != PaymentStatus.PAID) {
            throw PaymentNotRefundableException("PAID 상태인 결제만 환불 가능합니다.")
        }
        // 이미 환불 처리된 attempt 면 멱등 응답 (ticket.status 가 정상이라면 데이터 불일치이지만 안전 우선).
        if (attempt.refundedAt != null) {
            throw TicketAlreadyRefundedException()
        }
        val providerKey = attempt.providerPaymentKey?.takeIf { it.isNotBlank() }
            ?: throw PaymentNotRefundableException("PG 결제 키가 없어 환불할 수 없습니다.")

        val reason = request.reason?.trim().orEmpty().ifBlank { "USER_REQUEST" }
        val result = paymentGateway.refund(
            PaymentGatewayRefundRequest(
                providerPaymentKey = providerKey,
                amount = attempt.amount,
                reason = reason,
            )
        )

        return when (result) {
            is PaymentGatewayRefundResult.Success -> {
                markRefundedInternal(attempt, ticket, reason)
                attempt.toRefundResponse(ticket)
            }
            is PaymentGatewayRefundResult.Failure -> {
                log.warn(
                    "[refund] gateway rejected ticketId={} code={} msg={}",
                    ticket.id, result.code, result.message,
                )
                throw RefundFailedException(result.code, result.message)
            }
        }
    }

    private fun canRequestRefund(actor: User, ticket: Ticket): Boolean {
        if (actor.role == UserRole.ADMIN) return true
        if (ticket.buyer.id == actor.id) return true
        if (ticket.event.channel.owner.id == actor.id) return true
        return false
    }

    /**
     * 환불 후 상태 정리:
     *  - PaymentAttempt.refundedAt + refundReason 기록 (status 는 PAID 유지)
     *  - Ticket.status PAID → REFUNDED
     *  - Event.currentParticipants -- (단, participation 이 이미 terminal 이면 skip — 정원이
     *    그 시점에 이미 한 번 빠졌다고 보고 이중 감소 방지)
     *  - EventParticipation 이 있으면 CANCELED 로 전환 (이미 CANCELED 면 no-op)
     *  - PR81: buyer 에게 REFUND_COMPLETED 알림 (best-effort, 알림 실패가 환불을 막지 않음)
     *
     * 한 attempt 에 한 번만 호출되도록 호출처가 `refundedAt == null` 가드.
     * PR78 — 정원 가드: ACTIVE(PENDING/APPROVED) 였던 경우에만 decreaseParticipant. terminal
     * (CANCELED/REJECTED) 이면 이미 카운트에서 빠진 상태로 간주 (또는 애초에 카운트되지 않음).
     * participation 자체가 없으면 정상 PAID 흐름으로 들어온 것으로 보고 감소.
     */
    private fun markRefundedInternal(attempt: PaymentAttempt, ticket: Ticket, reason: String) {
        attempt.markRefunded(reason)
        ticket.refund()
        val participation = eventParticipationRepository
            .findByEventAndParticipant(attempt.event, attempt.buyer)
            .orElse(null)
        val wasActive = participation == null ||
            participation.status == ParticipationStatus.PENDING ||
            participation.status == ParticipationStatus.APPROVED
        if (wasActive) {
            attempt.event.decreaseParticipant()
        }
        if (participation != null && participation.status != ParticipationStatus.CANCELED) {
            participation.cancel()
        }
        // PR81 — buyer 에게 환불 완료 알림. REFUNDED 멱등 분기는 markRefundedInternal 을 타지
        // 않으므로 중복 알림이 발생하지 않는다. 알림 실패는 환불 트랜잭션을 막지 않는다.
        runCatching {
            notificationService.notify(
                receiverIds = listOf(attempt.buyer.id),
                type = NotificationType.REFUND_COMPLETED,
                title = "환불이 완료되었어요",
                message = "${attempt.event.title} 환불이 처리되었습니다.",
                targetType = "tickets",
                targetId = ticket.id,
            )
        }.onFailure { e ->
            log.warn("[refund] buyer notify failed: {}", e.message)
        }
    }

    private fun PaymentAttempt.toRefundResponse(ticket: Ticket): RefundTicketResponse {
        return RefundTicketResponse(
            ticketId = ticket.id,
            ticketStatus = ticket.status,
            paymentAttemptId = id,
            provider = provider,
            amount = amount,
            refundedAt = (refundedAt ?: LocalDateTime.now()).toString(),
            providerPaymentKey = providerPaymentKey,
        )
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
