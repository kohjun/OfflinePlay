package com.contenido.domain.payment.service

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.service.ModerationAuditLogService
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
import com.contenido.global.exception.InvalidRefundAmountException
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
    /**
     * PR122 — 일반 사용자/owner/ADMIN 환불 성공 시 audit log 기록용. `forceRefundByAdmin` 는 본 service 가
     * audit 을 기록하지 않고 호출자(AdminPaymentService) 가 `TICKET_FORCED_REFUNDED` 를 기록한다 —
     * 중복 audit 방지. webhook 흐름도 본 service 가 audit 을 만들지 않는다 (PG-driven, actor 없음).
     */
    private val moderationAuditLogService: ModerationAuditLogService,
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
            // PR117 — PARTIALLY_REFUNDED 는 webhook 입력 값이 아니다 (사용자/owner/ADMIN 동기 요청
            // 흐름에서만 발생). 안전상 들어와도 무시.
            PaymentStatus.PARTIALLY_REFUNDED -> {
                log.warn(
                    "[handleWebhook] PARTIALLY_REFUNDED webhook is not supported, attemptId={} — skip",
                    attempt.id,
                )
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
     * 사용자/owner/ADMIN 환불 요청. PG refund 호출 + Ticket 상태 전환 + (전액 환불 시)
     * 정원 -- + EventParticipation CANCELED.
     *
     * 권한:
     *  - ticket.buyer 본인 — 본인이 셀프 환불 요청
     *  - 채널 owner — 환불 정책상 owner 가 환불 허용 (예: 이벤트 자체 취소)
     *  - ADMIN     — 운영 개입
     *  - 그 외 (STAFF 포함) → [UnauthorizedException]
     *
     * 상태 분기 (ticket.status):
     *  - PAID                : 정상 환불 진행
     *  - PARTIALLY_REFUNDED  : PR117 — 부분 환불 진행 중. 남은 환불 가능 금액 내에서 추가 환불 가능.
     *  - USED                : [TicketAlreadyUsedException] (체크인 후 환불은 운영 도구로 별도)
     *  - REFUNDED            : 이미 전액 환불됨 — gateway 재호출 없이 기존 정보로 멱등 응답
     *  - CANCELED            : [PaymentNotRefundableException] (참가 취소된 티켓은 환불 대상 아님)
     *
     * PaymentAttempt 가 PAID/PARTIALLY_REFUNDED 아니거나 providerPaymentKey 가 비어 있으면 환불 불가.
     *
     * 시간 가드 (PR43): 이벤트 시작 시각 이후 환불 요청은 [RefundDeadlinePassedException] 으로 차단.
     * 일반 환불 경로는 PR117 의 부분 환불에서도 동일하게 적용. ADMIN 운영 우회는 별도
     * [forceRefundByAdmin] 사용.
     *
     * PR117 — 부분 환불:
     *  - [RefundTicketRequest.amount] 가 null 이면 남은 환불 가능 금액 전체 (기존 전액 환불 동작).
     *  - 지정 시 1 <= amount <= remainingRefundableAmount. 범위 위반은 [InvalidRefundAmountException].
     *  - 누적 환불액이 amount 미만이면 partial cascade (참가/정원 무영향).
     *  - 누적 환불액이 amount 에 도달하면 full cascade (참가 CANCELED + 정원--).
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
            TicketStatus.PAID, TicketStatus.PARTIALLY_REFUNDED -> { /* 진행 */ }
        }

        // PR43: 이벤트 시작 시각 이후 환불 차단. USED 가드와 함께 "행사가 시작되면 환불 불가"
        // 정책의 양면. 시작 직후 USED 마킹 전에 들어오는 요청도 여기서 막힌다.
        if (!ticket.event.startAt.isAfter(LocalDateTime.now())) {
            throw RefundDeadlinePassedException()
        }

        val attempt = paymentAttemptRepository.findByTicket(ticket)
            .orElseThrow { PaymentNotRefundableException("연결된 결제 시도를 찾을 수 없습니다.") }
        if (attempt.status != PaymentStatus.PAID && attempt.status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw PaymentNotRefundableException("PAID 상태인 결제만 환불 가능합니다.")
        }
        val providerKey = attempt.providerPaymentKey?.takeIf { it.isNotBlank() }
            ?: throw PaymentNotRefundableException("PG 결제 키가 없어 환불할 수 없습니다.")
        val remaining = attempt.remainingRefundableAmount()
        if (remaining <= 0L) throw TicketAlreadyRefundedException()

        // PR117 — 금액 결정. null 이면 remaining 전체 (전액 환불). 지정 시 1..remaining 범위 검증.
        val refundAmount = request.amount ?: remaining
        if (refundAmount < 1L) {
            throw InvalidRefundAmountException("환불 금액은 1원 이상이어야 합니다.")
        }
        if (refundAmount > remaining) {
            throw InvalidRefundAmountException("환불 금액은 남은 환불 가능 금액(${remaining}원)을 초과할 수 없습니다.")
        }
        val willBeFullyRefunded = refundAmount == remaining

        // PR122 — audit 기록용 before snapshot. cascade 후 ticket/attempt 가 바뀌므로 호출 전에 저장.
        val beforeTicketStatus = ticket.status
        val beforePaymentStatus = attempt.status
        val beforeRefundedAmount = attempt.refundedAmount
        val beforeRemainingAmount = remaining

        val reason = request.reason?.trim().orEmpty().ifBlank { "USER_REQUEST" }
        val result = paymentGateway.refund(
            PaymentGatewayRefundRequest(
                providerPaymentKey = providerKey,
                amount = refundAmount,
                reason = reason,
            )
        )

        return when (result) {
            is PaymentGatewayRefundResult.Success -> {
                if (willBeFullyRefunded) {
                    // 누적 환불이 결제 금액에 도달 — 기존 전액 환불 cascade.
                    markRefundedInternal(attempt, ticket, reason)
                } else {
                    applyPartialRefund(attempt, ticket, refundAmount, reason)
                }
                // PR122 — 일반 사용자/owner/ADMIN 환불 audit 기록. 같은 트랜잭션이라 audit 실패 시
                // 환불도 rollback (admin forced refund 와 동일한 정책).
                recordUserRefundAudit(
                    actorId = actorId,
                    ticket = ticket,
                    attempt = attempt,
                    refundAmount = refundAmount,
                    fullRefund = willBeFullyRefunded,
                    beforeTicketStatus = beforeTicketStatus,
                    beforePaymentStatus = beforePaymentStatus,
                    beforeRefundedAmount = beforeRefundedAmount,
                    beforeRemainingAmount = beforeRemainingAmount,
                    reason = reason,
                )
                attempt.toRefundResponse(ticket)
            }
            is PaymentGatewayRefundResult.Failure -> {
                log.warn(
                    "[refund] gateway rejected ticketId={} amount={} code={} msg={}",
                    ticket.id, refundAmount, result.code, result.message,
                )
                throw RefundFailedException(result.code, result.message)
            }
        }
    }

    /**
     * PR122 — 일반 사용자/owner/ADMIN 환불 audit log 1건 기록.
     *
     *  - action :
     *    - fullRefund=true  → [ModerationAuditAction.PAYMENT_REFUNDED] (cascade)
     *    - fullRefund=false → [ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED]
     *  - targetType / targetId = null (ReportTargetType 에 TICKET 없음 — admin forced refund 와 동일).
     *  - beforeValue JSON : 환불 직전 상태 (cascade 영향을 받기 전 snapshot).
     *  - afterValue JSON  : 환불 후 결과 + 이번 호출의 refundAmount + fullRefund 플래그.
     *  - reason : `refundPaymentByTicket` 가 service 진입 시 trim + USER_REQUEST default 처리된 값.
     *
     * ADMIN forced refund (`forceRefundByAdmin`) 는 본 메서드를 호출하지 않는다 — 호출자(AdminPaymentService)
     * 가 별도로 [ModerationAuditAction.TICKET_FORCED_REFUNDED] audit 1건만 기록 (PR106).
     */
    private fun recordUserRefundAudit(
        actorId: Long,
        ticket: Ticket,
        attempt: PaymentAttempt,
        refundAmount: Long,
        fullRefund: Boolean,
        beforeTicketStatus: TicketStatus,
        beforePaymentStatus: PaymentStatus,
        beforeRefundedAmount: Long,
        beforeRemainingAmount: Long,
        reason: String,
    ) {
        val action = if (fullRefund) ModerationAuditAction.PAYMENT_REFUNDED
        else ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED
        moderationAuditLogService.record(
            actorId = actorId,
            action = action,
            targetType = null,
            targetId = null,
            beforeValue = mapOf(
                "ticketStatusBefore" to beforeTicketStatus.name,
                "paymentStatusBefore" to beforePaymentStatus.name,
                "refundedAmountBefore" to beforeRefundedAmount,
                "remainingRefundableAmountBefore" to beforeRemainingAmount,
            ),
            afterValue = mapOf(
                "ticketId" to ticket.id,
                "paymentAttemptId" to attempt.id,
                "eventId" to attempt.event.id,
                "refundAmount" to refundAmount,
                "refundedAmount" to attempt.refundedAmount,
                "remainingRefundableAmount" to attempt.remainingRefundableAmount(),
                "ticketStatus" to ticket.status.name,
                "paymentStatus" to attempt.status.name,
                "fullRefund" to fullRefund,
            ),
            reason = reason,
        )
    }

    private fun canRequestRefund(actor: User, ticket: Ticket): Boolean {
        if (actor.role == UserRole.ADMIN) return true
        if (ticket.buyer.id == actor.id) return true
        if (ticket.event.channel.owner.id == actor.id) return true
        return false
    }

    /**
     * PR106 — ADMIN 전용 강제 환불.
     *
     * 일반 [refundPaymentByTicket] 와 다른 점:
     *  - **deadline 검사 없음** — 이벤트 시작 후 / USED 티켓도 환불 허용 (노쇼 보상, 행사 취소 등
     *    운영 케이스 대응)
     *  - **권한 = ADMIN 만** — buyer / channel owner 는 본 흐름으로 호출 불가 (UnauthorizedException)
     *  - PR134 — 부분 강제 환불 지원. [amount] null 이면 remaining 전액 환불 (기존 동작), 지정 시
     *    1 <= amount <= remainingRefundableAmount. amount == remaining → full cascade,
     *    amount < remaining → partial cascade (참가/정원 유지). 범위 위반은 [InvalidRefundAmountException].
     *
     * 공통:
     *  - PG `paymentGateway.refund` 호출 (Toss 또는 Mock)
     *  - amount == remaining → [markRefundedInternal] 재사용 (ticket REFUNDED, participation
     *    CANCELED, `event.currentParticipants` 감소, REFUND_COMPLETED 알림)
     *  - amount < remaining → [applyPartialRefund] 재사용 (ticket/attempt PARTIALLY_REFUNDED,
     *    참가/정원 무영향)
     *
     * 거부 조건:
     *  - actor 가 ADMIN 아님 → [UnauthorizedException]
     *  - ticket 미존재 → [TicketNotFoundException]
     *  - ticket.status == REFUNDED → [TicketAlreadyRefundedException] (실수 방지, 멱등 응답 X)
     *  - ticket.status == CANCELED → [PaymentNotRefundableException]
     *  - paymentAttempt 미존재 / status != PAID → [PaymentNotRefundableException]
     *  - providerPaymentKey 비어 있음 → [PaymentNotRefundableException]
     *  - paymentAttempt.refundedAt != null → [TicketAlreadyRefundedException]
     *  - PG gateway 실패 → [RefundFailedException]
     *
     * audit log 기록은 호출자(AdminPaymentService)가 별도 책임. 본 메서드는 환불 cascade 만 담당.
     */
    @Transactional
    fun forceRefundByAdmin(
        adminUserId: Long,
        ticketId: Long,
        reason: String,
        amount: Long? = null,
    ): RefundTicketResponse {
        val actor = userRepository.findById(adminUserId).orElseThrow { UserNotFoundException() }
        if (actor.role != UserRole.ADMIN) throw UnauthorizedException()

        val ticket = ticketRepository.findById(ticketId).orElseThrow { TicketNotFoundException() }
        when (ticket.status) {
            TicketStatus.REFUNDED -> throw TicketAlreadyRefundedException()
            TicketStatus.CANCELED -> throw PaymentNotRefundableException("취소된 티켓은 환불 대상이 아닙니다.")
            // PR117 — PARTIALLY_REFUNDED 도 허용. remaining 만큼을 추가 환불해 REFUNDED 로 cascade.
            TicketStatus.PAID, TicketStatus.USED, TicketStatus.PARTIALLY_REFUNDED -> { /* 진행 — deadline 검사 생략 */ }
        }

        val attempt = paymentAttemptRepository.findByTicket(ticket)
            .orElseThrow { PaymentNotRefundableException("연결된 결제 시도를 찾을 수 없습니다.") }
        if (attempt.status != PaymentStatus.PAID && attempt.status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw PaymentNotRefundableException("PAID 상태인 결제만 환불 가능합니다.")
        }
        // PR117 — 이미 fully refunded (refundedAmount == amount) 면 차단. partial 진행 중인 attempt
        // 는 refundedAt != null 이라도 remaining > 0 이면 추가 환불 가능.
        val remaining = attempt.remainingRefundableAmount()
        if (remaining <= 0L) throw TicketAlreadyRefundedException()
        val providerKey = attempt.providerPaymentKey?.takeIf { it.isNotBlank() }
            ?: throw PaymentNotRefundableException("PG 결제 키가 없어 환불할 수 없습니다.")

        // PR134 — 부분 강제 환불. amount null 이면 remaining 전액 (기존 동작).
        val refundAmount = amount ?: remaining
        if (refundAmount < 1L) {
            throw InvalidRefundAmountException("환불 금액은 1원 이상이어야 합니다.")
        }
        if (refundAmount > remaining) {
            throw InvalidRefundAmountException("환불 금액은 남은 환불 가능 금액(${remaining}원)을 초과할 수 없습니다.")
        }
        val willBeFullyRefunded = refundAmount == remaining

        val trimmedReason = reason.trim()
        val result = paymentGateway.refund(
            PaymentGatewayRefundRequest(
                providerPaymentKey = providerKey,
                amount = refundAmount,
                reason = trimmedReason,
            )
        )
        return when (result) {
            is PaymentGatewayRefundResult.Success -> {
                if (willBeFullyRefunded) {
                    // remaining 전액 환불 → full cascade.
                    markRefundedInternal(attempt, ticket, trimmedReason)
                } else {
                    // PR134 — 부분 강제 환불. 참가/정원 유지, ticket/attempt PARTIALLY_REFUNDED.
                    applyPartialRefund(attempt, ticket, refundAmount, trimmedReason)
                }
                attempt.toRefundResponse(ticket)
            }
            is PaymentGatewayRefundResult.Failure -> {
                log.warn(
                    "[forceRefundByAdmin] gateway rejected ticketId={} amount={} code={} msg={}",
                    ticket.id, refundAmount, result.code, result.message,
                )
                throw RefundFailedException(result.code, result.message)
            }
        }
    }

    /**
     * PR117 — 부분 환불 적용. 누적 환불 금액만 증가시키고 ticket/attempt status 를
     * PARTIALLY_REFUNDED 로 갱신한다. 정원(`currentParticipants`) / participation 상태는 **변경하지
     * 않는다** — 부분 환불은 참가 자격을 유지한 채 일부 금액만 돌려주는 운영 의미이다.
     *
     * buyer 에게 REFUND_COMPLETED 알림을 발송한다 (full cascade 와 동일 NotificationType 재사용,
     * 메시지에 "부분 환불 ₩N" 표기). 알림 실패는 환불 트랜잭션을 막지 않는다.
     */
    private fun applyPartialRefund(
        attempt: PaymentAttempt,
        ticket: Ticket,
        deltaAmount: Long,
        reason: String,
    ) {
        attempt.markPartiallyRefunded(deltaAmount, reason)
        ticket.markPartiallyRefunded()
        runCatching {
            notificationService.notify(
                receiverIds = listOf(attempt.buyer.id),
                type = NotificationType.REFUND_COMPLETED,
                title = "부분 환불이 처리되었어요",
                message = "${attempt.event.title} ₩${"%,d".format(deltaAmount)} 부분 환불이 처리되었습니다.",
                targetType = "tickets",
                targetId = ticket.id,
            )
        }.onFailure { e ->
            log.warn("[refund] partial buyer notify failed: {}", e.message)
        }
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
     * 한 attempt 에 한 번만 호출되도록 호출처가 remaining > 0 가드.
     * PR78 — 정원 가드: ACTIVE(PENDING/APPROVED) 였던 경우에만 decreaseParticipant. terminal
     * (CANCELED/REJECTED) 이면 이미 카운트에서 빠진 상태로 간주 (또는 애초에 카운트되지 않음).
     * participation 자체가 없으면 정상 PAID 흐름으로 들어온 것으로 보고 감소.
     *
     * PR117 — partial refund 누적이 amount 에 도달한 경우의 cascade 진입점도 동일하게 본 메서드.
     * [PaymentAttempt.markRefunded] 가 내부적으로 [PaymentAttempt.markFullyRefunded] 를 호출해
     * refundedAmount = amount, status = PAID, refundedAt set 을 한 번에 정리한다.
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
            refundedAmount = refundedAmount,
            remainingRefundableAmount = remainingRefundableAmount(),
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

        // PR120 — PARTIALLY_REFUNDED 도 active 로 취급. 부분 환불 후 같은 buyer 가 같은 event 에
        // 다시 prepare 호출하면 AlreadyJoined — 부분 환불은 참가 자격을 유지하므로 (PR117 정책).
        val hasLiveTicket = ticketRepository.existsByEventAndBuyerAndStatusIn(
            event = event,
            buyer = buyer,
            statuses = listOf(TicketStatus.PAID, TicketStatus.USED, TicketStatus.PARTIALLY_REFUNDED),
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
