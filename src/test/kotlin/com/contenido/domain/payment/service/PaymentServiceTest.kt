package com.contenido.domain.payment.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.payment.dto.PaymentConfirmRequest
import com.contenido.domain.payment.dto.PaymentWebhookRequest
import com.contenido.domain.payment.dto.RefundTicketRequest
import com.contenido.domain.payment.entity.PaymentAttempt
import com.contenido.domain.payment.entity.PaymentProvider
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
import com.contenido.global.exception.EventFullException
import com.contenido.global.exception.FreeEventCannotPreparePaymentException
import com.contenido.global.exception.InvalidPaymentAmountException
import com.contenido.global.exception.InvalidPaymentOrderIdException
import com.contenido.global.exception.InvalidPaymentStateException
import com.contenido.global.exception.OwnerCannotApplyException
import com.contenido.global.exception.PaymentAttemptNotFoundException
import com.contenido.global.exception.PaymentConfirmFailedException
import com.contenido.global.exception.PaymentNotRefundableException
import com.contenido.global.exception.RefundFailedException
import com.contenido.global.exception.TicketAlreadyRefundedException
import com.contenido.global.exception.TicketAlreadyUsedException
import com.contenido.global.exception.TicketNotFoundException
import com.contenido.global.exception.UnauthorizedException
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockKExtension::class)
class PaymentServiceTest {

    @MockK lateinit var paymentAttemptRepository: PaymentAttemptRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var ticketRepository: TicketRepository
    @MockK lateinit var ticketService: TicketService
    @MockK lateinit var paymentGateway: PaymentGateway
    @MockK lateinit var eventParticipationRepository: EventParticipationRepository
    @MockK lateinit var notificationService: com.contenido.domain.notification.service.NotificationService
    @MockK lateinit var moderationAuditLogService: com.contenido.domain.admin.service.ModerationAuditLogService

    private lateinit var service: PaymentService

    @BeforeEach
    fun setUp() {
        service = PaymentService(
            paymentAttemptRepository,
            eventRepository,
            userRepository,
            ticketRepository,
            ticketService,
            paymentGateway,
            eventParticipationRepository,
            notificationService,
            moderationAuditLogService,
        )
        // PR81 — refund cascade 가 buyer 에게 REFUND_COMPLETED 알림을 보낸다. 기본 stub.
        every { notificationService.notify(any(), any(), any(), any(), any(), any()) } just Runs
        // PR122 — 일반 사용자 환불 audit. record() 는 ModerationAuditLog 를 반환하지만 호출자가 무시.
        every {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), any(), any())
        } returns io.mockk.mockk(relaxed = true)
    }

    // ── preparePayment ───────────────────────────────────────────────────────────

    @Test
    fun `preparePayment 성공 시 READY PaymentAttempt 반환`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel, fee = 30_000L, maxParticipants = 10)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every {
            ticketRepository.existsByEventAndBuyerAndStatusIn(event, buyer, any())
        } returns false
        every {
            paymentAttemptRepository.findFirstByEventAndBuyerAndStatusOrderByCreatedAtDesc(
                event, buyer, PaymentStatus.READY,
            )
        } returns Optional.empty()
        val savedSlot = slot<PaymentAttempt>()
        every { paymentAttemptRepository.save(capture(savedSlot)) } answers {
            savedSlot.captured.also { ReflectionTestUtils.setField(it, "id", 777L) }
        }

        val response = service.preparePayment(userId = 2L, eventId = 100L)

        assertThat(response.paymentAttemptId).isEqualTo(777L)
        assertThat(response.eventId).isEqualTo(100L)
        assertThat(response.amount).isEqualTo(30_000L)
        assertThat(response.orderName).isEqualTo("이벤트100")
        assertThat(response.idempotencyKey).isNotBlank()
        assertThat(response.status).isEqualTo(PaymentStatus.READY)
    }

    @Test
    fun `preparePayment 0원 이벤트는 FreeEventCannotPreparePaymentException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 0L)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)

        assertThrows<FreeEventCannotPreparePaymentException> {
            service.preparePayment(userId = 2L, eventId = 100L)
        }
    }

    @Test
    fun `preparePayment 정원 가득이면 EventFullException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner),
            fee = 30_000L, maxParticipants = 1, currentParticipants = 1,
        )

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { ticketRepository.existsByEventAndBuyerAndStatusIn(event, buyer, any()) } returns false

        assertThrows<EventFullException> {
            service.preparePayment(userId = 2L, eventId = 100L)
        }
    }

    @Test
    fun `preparePayment owner 본인은 OwnerCannotApplyException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)

        assertThrows<OwnerCannotApplyException> {
            service.preparePayment(userId = 1L, eventId = 100L)
        }
    }

    @Test
    fun `preparePayment 이미 PAID 티켓 있으면 AlreadyJoinedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every {
            ticketRepository.existsByEventAndBuyerAndStatusIn(event, buyer, any())
        } returns true

        assertThrows<AlreadyJoinedException> {
            service.preparePayment(userId = 2L, eventId = 100L)
        }
    }

    @Test
    fun `preparePayment 같은 user+event 에 READY 가 살아있으면 동일 row 반환(멱등)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val existing = createPaymentAttempt(
            id = 999L, event = event, buyer = buyer,
            idempotencyKey = "existing-key", amount = 30_000L, status = PaymentStatus.READY,
        )

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { ticketRepository.existsByEventAndBuyerAndStatusIn(event, buyer, any()) } returns false
        every {
            paymentAttemptRepository.findFirstByEventAndBuyerAndStatusOrderByCreatedAtDesc(
                event, buyer, PaymentStatus.READY,
            )
        } returns Optional.of(existing)

        val response = service.preparePayment(userId = 2L, eventId = 100L)

        assertThat(response.paymentAttemptId).isEqualTo(999L)
        assertThat(response.idempotencyKey).isEqualTo("existing-key")
        // 새 row 가 저장되지 않았는지 검증.
        verify(exactly = 0) { paymentAttemptRepository.save(any()) }
    }

    @Test
    fun `preparePayment 이미 시작된 이벤트는 EventAlreadyStartedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner),
            fee = 30_000L,
            startAt = LocalDateTime.now().minusHours(1),
            endAt = LocalDateTime.now().plusHours(2),
        )

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)

        assertThrows<EventAlreadyStartedException> {
            service.preparePayment(userId = 2L, eventId = 100L)
        }
    }

    // ── handleWebhook ────────────────────────────────────────────────────────────

    @Test
    fun `handleWebhook PAID 처리 시 Ticket 발급 + PaymentAttempt PAID + 정원 증가`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(owner = owner)
        val event = createEvent(id = 100L, channel = channel, fee = 30_000L, currentParticipants = 3)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "key-PAID", amount = 30_000L, status = PaymentStatus.READY,
        )
        val issuedTicket = createTicket(id = 999L, event = event, buyer = buyer)

        every {
            paymentAttemptRepository.findByIdempotencyKey("key-PAID")
        } returns Optional.of(attempt)
        every {
            ticketService.issuePaidTicket(userId = 2L, eventId = 100L, paidAmount = 30_000L)
        } returns issuedTicket

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "key-PAID",
                providerPaymentKey = "toss_payment_key_abc",
                amount = 30_000L,
                status = PaymentStatus.PAID,
                provider = PaymentProvider.TOSS,
            )
        )

        assertThat(attempt.status).isEqualTo(PaymentStatus.PAID)
        assertThat(attempt.ticket).isEqualTo(issuedTicket)
        assertThat(attempt.providerPaymentKey).isEqualTo("toss_payment_key_abc")
        assertThat(attempt.provider).isEqualTo(PaymentProvider.TOSS)
        assertThat(event.currentParticipants).isEqualTo(4)
        verify(exactly = 1) { ticketService.issuePaidTicket(2L, 100L, 30_000L) }
    }

    @Test
    fun `handleWebhook 같은 idempotencyKey 로 PAID 가 두 번 와도 ticket 은 한 번만 발급`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        // 첫 호출 후 이미 PAID 가 된 attempt 를 두 번째 호출이 본다.
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "key-DUP", amount = 30_000L, status = PaymentStatus.PAID,
        )

        every {
            paymentAttemptRepository.findByIdempotencyKey("key-DUP")
        } returns Optional.of(attempt)

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "key-DUP",
                providerPaymentKey = "anything",
                amount = 30_000L,
                status = PaymentStatus.PAID,
                provider = PaymentProvider.TOSS,
            )
        )

        // ticket 재발급도 정원 증가도 없어야 한다.
        verify(exactly = 0) { ticketService.issuePaidTicket(any(), any(), any()) }
    }

    @Test
    fun `handleWebhook 존재하지 않는 idempotencyKey 는 PaymentAttemptNotFoundException`() {
        every { paymentAttemptRepository.findByIdempotencyKey("ghost") } returns Optional.empty()

        assertThrows<PaymentAttemptNotFoundException> {
            service.handleWebhook(
                PaymentWebhookRequest(
                    idempotencyKey = "ghost",
                    amount = 30_000L,
                    status = PaymentStatus.PAID,
                )
            )
        }
    }

    @Test
    fun `handleWebhook PAID 인데 amount 가 prepare 와 다르면 InvalidPaymentAmountException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "key-AMT", amount = 30_000L, status = PaymentStatus.READY,
        )

        every { paymentAttemptRepository.findByIdempotencyKey("key-AMT") } returns Optional.of(attempt)

        assertThrows<InvalidPaymentAmountException> {
            service.handleWebhook(
                PaymentWebhookRequest(
                    idempotencyKey = "key-AMT",
                    amount = 99_999L,  // prepare 30_000 != webhook 99_999
                    status = PaymentStatus.PAID,
                )
            )
        }

        // 금액 검증 단계에서 throw — ticket 발급/PAID 전환 없음.
        assertThat(attempt.status).isEqualTo(PaymentStatus.READY)
        verify(exactly = 0) { ticketService.issuePaidTicket(any(), any(), any()) }
    }

    @Test
    fun `handleWebhook FAILED 처리 시 PaymentAttempt FAILED 로 전환 + ticket 발급 없음`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "key-FAIL", amount = 30_000L, status = PaymentStatus.READY,
        )

        every { paymentAttemptRepository.findByIdempotencyKey("key-FAIL") } returns Optional.of(attempt)

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "key-FAIL",
                amount = 30_000L,
                status = PaymentStatus.FAILED,
                provider = PaymentProvider.TOSS,
            )
        )

        assertThat(attempt.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(attempt.provider).isEqualTo(PaymentProvider.TOSS)
        verify(exactly = 0) { ticketService.issuePaidTicket(any(), any(), any()) }
    }

    @Test
    fun `handleWebhook CANCELED 처리 시 PaymentAttempt CANCELED 로 전환`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "key-CXL", amount = 30_000L, status = PaymentStatus.READY,
        )

        every { paymentAttemptRepository.findByIdempotencyKey("key-CXL") } returns Optional.of(attempt)

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "key-CXL",
                amount = 0L,
                status = PaymentStatus.CANCELED,
            )
        )

        assertThat(attempt.status).isEqualTo(PaymentStatus.CANCELED)
        verify(exactly = 0) { ticketService.issuePaidTicket(any(), any(), any()) }
    }

    // ── confirmPayment ───────────────────────────────────────────────────────────

    @Test
    fun `confirmPayment 성공 시 Ticket 발급 + PaymentAttempt PAID + 정원 ++ + EventParticipation APPROVED`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 3)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-1", amount = 30_000L, status = PaymentStatus.READY,
        )
        val issuedTicket = createTicket(id = 999L, event = event, buyer = buyer)

        every { paymentAttemptRepository.findById(555L) } returns Optional.of(attempt)
        every {
            paymentGateway.confirm(any())
        } returns PaymentGatewayConfirmResult.Success(
            provider = PaymentProvider.TOSS,
            providerPaymentKey = "toss_real_key_xyz",
            approvedAt = "2026-05-16T00:00:00+09:00",
        )
        every { ticketService.issuePaidTicket(2L, 100L, 30_000L) } returns issuedTicket
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns Optional.empty()
        val savedParticipationSlot = slot<EventParticipation>()
        every { eventParticipationRepository.save(capture(savedParticipationSlot)) } answers {
            savedParticipationSlot.captured
        }

        val response = service.confirmPayment(
            userId = 2L,
            paymentAttemptId = 555L,
            request = PaymentConfirmRequest(
                paymentKey = "client-side-key",
                orderId = "order-1",
                amount = 30_000L,
            ),
        )

        assertThat(response.status).isEqualTo(PaymentStatus.PAID)
        assertThat(response.ticketId).isEqualTo(999L)
        assertThat(response.providerPaymentKey).isEqualTo("toss_real_key_xyz")
        assertThat(response.approvedAt).isEqualTo("2026-05-16T00:00:00+09:00")
        assertThat(attempt.status).isEqualTo(PaymentStatus.PAID)
        assertThat(attempt.provider).isEqualTo(PaymentProvider.TOSS)
        assertThat(event.currentParticipants).isEqualTo(4)
        assertThat(savedParticipationSlot.captured.status).isEqualTo(ParticipationStatus.APPROVED)
    }

    @Test
    fun `confirmPayment Gateway Failure 시 PaymentAttempt FAILED + PaymentConfirmFailedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-fail", amount = 30_000L, status = PaymentStatus.READY,
        )

        every { paymentAttemptRepository.findById(555L) } returns Optional.of(attempt)
        every { paymentGateway.confirm(any()) } returns PaymentGatewayConfirmResult.Failure(
            provider = PaymentProvider.TOSS,
            code = "PAY_INVALID_CARD",
            message = "유효하지 않은 카드입니다.",
        )

        val ex = assertThrows<PaymentConfirmFailedException> {
            service.confirmPayment(
                userId = 2L, paymentAttemptId = 555L,
                request = PaymentConfirmRequest("k", "order-fail", 30_000L),
            )
        }
        assertThat(ex.code).isEqualTo("PAY_INVALID_CARD")
        assertThat(attempt.status).isEqualTo(PaymentStatus.FAILED)
        verify(exactly = 0) { ticketService.issuePaidTicket(any(), any(), any()) }
        verify(exactly = 0) { eventParticipationRepository.save(any()) }
    }

    @Test
    fun `confirmPayment amount 불일치는 InvalidPaymentAmountException (gateway 호출 X)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-1", amount = 30_000L, status = PaymentStatus.READY,
        )

        every { paymentAttemptRepository.findById(555L) } returns Optional.of(attempt)

        assertThrows<InvalidPaymentAmountException> {
            service.confirmPayment(
                userId = 2L, paymentAttemptId = 555L,
                request = PaymentConfirmRequest("k", "order-1", 99_999L),
            )
        }

        verify(exactly = 0) { paymentGateway.confirm(any()) }
        assertThat(attempt.status).isEqualTo(PaymentStatus.READY)
    }

    @Test
    fun `confirmPayment orderId 불일치는 InvalidPaymentOrderIdException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-real", amount = 30_000L, status = PaymentStatus.READY,
        )

        every { paymentAttemptRepository.findById(555L) } returns Optional.of(attempt)

        assertThrows<InvalidPaymentOrderIdException> {
            service.confirmPayment(
                userId = 2L, paymentAttemptId = 555L,
                request = PaymentConfirmRequest("k", "order-fake", 30_000L),
            )
        }
        verify(exactly = 0) { paymentGateway.confirm(any()) }
    }

    @Test
    fun `confirmPayment 이미 PAID 면 gateway 재호출 없이 멱등 응답`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val alreadyTicket = createTicket(id = 888L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-dup", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", alreadyTicket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_existing")
            ReflectionTestUtils.setField(this, "provider", PaymentProvider.TOSS)
        }

        every { paymentAttemptRepository.findById(555L) } returns Optional.of(attempt)

        val response = service.confirmPayment(
            userId = 2L, paymentAttemptId = 555L,
            request = PaymentConfirmRequest("any-key", "order-dup", 30_000L),
        )

        assertThat(response.status).isEqualTo(PaymentStatus.PAID)
        assertThat(response.ticketId).isEqualTo(888L)
        assertThat(response.providerPaymentKey).isEqualTo("toss_existing")
        verify(exactly = 0) { paymentGateway.confirm(any()) }
        verify(exactly = 0) { ticketService.issuePaidTicket(any(), any(), any()) }
    }

    @Test
    fun `confirmPayment FAILED 상태에서는 InvalidPaymentStateException (재시도 X)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-x", amount = 30_000L, status = PaymentStatus.FAILED,
        )

        every { paymentAttemptRepository.findById(555L) } returns Optional.of(attempt)

        assertThrows<InvalidPaymentStateException> {
            service.confirmPayment(
                userId = 2L, paymentAttemptId = 555L,
                request = PaymentConfirmRequest("k", "order-x", 30_000L),
            )
        }
    }

    @Test
    fun `confirmPayment buyer 본인이 아니면 UnauthorizedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val intruder = createUser(id = 3L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-1", amount = 30_000L, status = PaymentStatus.READY,
        )

        every { paymentAttemptRepository.findById(555L) } returns Optional.of(attempt)

        assertThrows<UnauthorizedException> {
            service.confirmPayment(
                userId = intruder.id, paymentAttemptId = 555L,
                request = PaymentConfirmRequest("k", "order-1", 30_000L),
            )
        }
        verify(exactly = 0) { paymentGateway.confirm(any()) }
    }

    @Test
    fun `confirmPayment 정원이 prepare 이후 가득 차면 EventFullException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner),
            fee = 30_000L, maxParticipants = 1, currentParticipants = 1,
        )
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-1", amount = 30_000L, status = PaymentStatus.READY,
        )

        every { paymentAttemptRepository.findById(555L) } returns Optional.of(attempt)

        assertThrows<EventFullException> {
            service.confirmPayment(
                userId = 2L, paymentAttemptId = 555L,
                request = PaymentConfirmRequest("k", "order-1", 30_000L),
            )
        }
        verify(exactly = 0) { paymentGateway.confirm(any()) }
    }

    @Test
    fun `confirmPayment 기존 PENDING EventParticipation 이 있으면 APPROVED 로 전환`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-1", amount = 30_000L, status = PaymentStatus.READY,
        )
        val existingParticipation = EventParticipation(event = event, participant = buyer).apply {
            status = ParticipationStatus.PENDING
        }

        every { paymentAttemptRepository.findById(555L) } returns Optional.of(attempt)
        every { paymentGateway.confirm(any()) } returns PaymentGatewayConfirmResult.Success(
            provider = PaymentProvider.NONE, providerPaymentKey = "mock-k",
        )
        every { ticketService.issuePaidTicket(2L, 100L, 30_000L) } returns
            createTicket(id = 999L, event = event, buyer = buyer)
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.of(existingParticipation)

        service.confirmPayment(
            userId = 2L, paymentAttemptId = 555L,
            request = PaymentConfirmRequest("k", "order-1", 30_000L),
        )

        // 기존 row 가 APPROVED 로 전환, 새 row save 호출 없음.
        assertThat(existingParticipation.status).isEqualTo(ParticipationStatus.APPROVED)
        verify(exactly = 0) { eventParticipationRepository.save(any()) }
    }

    @Test
    fun `confirmPayment PaymentAttempt 없으면 PaymentAttemptNotFoundException`() {
        every { paymentAttemptRepository.findById(404L) } returns Optional.empty()

        assertThrows<PaymentAttemptNotFoundException> {
            service.confirmPayment(
                userId = 2L, paymentAttemptId = 404L,
                request = PaymentConfirmRequest("k", "anything", 30_000L),
            )
        }
    }

    // ── refundPaymentByTicket ────────────────────────────────────────────────────

    @Test
    fun `refundPaymentByTicket buyer 본인 성공 → ticket REFUNDED + attempt refundedAt + 정원 -- + EventParticipation CANCELED`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-r1", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "provider", PaymentProvider.TOSS)
        }
        val participation = createParticipation(event, buyer, ParticipationStatus.APPROVED)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS,
            providerPaymentKey = "toss_paid_key",
            canceledAt = "2026-05-16T00:00:00+09:00",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.of(participation)

        val response = service.refundPaymentByTicket(
            actorId = 2L, ticketId = 999L,
            request = RefundTicketRequest(reason = "변심"),
        )

        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
        assertThat(response.amount).isEqualTo(30_000L)
        assertThat(ticket.status).isEqualTo(TicketStatus.REFUNDED)
        assertThat(attempt.refundedAt).isNotNull
        assertThat(attempt.refundReason).isEqualTo("변심")
        assertThat(event.currentParticipants).isEqualTo(4)
        assertThat(participation.status).isEqualTo(ParticipationStatus.CANCELED)
    }

    @Test
    fun `refundPaymentByTicket 채널 owner 도 환불 가능`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 3)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-r2", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.NONE, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.empty()

        val response = service.refundPaymentByTicket(
            actorId = 1L, ticketId = 999L, request = RefundTicketRequest(reason = "이벤트 취소"),
        )
        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
    }

    @Test
    fun `refundPaymentByTicket 무관 사용자는 UnauthorizedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val outsider = createUser(id = 3L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)

        every { userRepository.findById(3L) } returns Optional.of(outsider)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)

        assertThrows<UnauthorizedException> {
            service.refundPaymentByTicket(
                actorId = 3L, ticketId = 999L, request = RefundTicketRequest(),
            )
        }
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    @Test
    fun `refundPaymentByTicket USED 티켓은 TicketAlreadyUsedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.USED)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)

        assertThrows<TicketAlreadyUsedException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())
        }
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    @Test
    fun `refundPaymentByTicket REFUNDED 티켓은 멱등 응답 (gateway 호출 X)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.REFUNDED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-r3", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "refundedAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "refundReason", "이전 환불")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)

        val response = service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())
        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    @Test
    fun `refundPaymentByTicket CANCELED 티켓은 PaymentNotRefundableException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.CANCELED)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)

        assertThrows<PaymentNotRefundableException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())
        }
    }

    @Test
    fun `refundPaymentByTicket gateway Failure 시 RefundFailedException + ticket PAID 유지`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 3)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-rfail", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Failure(
            provider = PaymentProvider.TOSS,
            code = "ALREADY_CANCELED_PAYMENT",
            message = "이미 환불 처리된 결제입니다.",
        )

        val ex = assertThrows<RefundFailedException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())
        }
        assertThat(ex.code).isEqualTo("ALREADY_CANCELED_PAYMENT")
        // 상태/카운트 변화 없음
        assertThat(ticket.status).isEqualTo(TicketStatus.PAID)
        assertThat(attempt.refundedAt).isNull()
        assertThat(event.currentParticipants).isEqualTo(3)
    }

    @Test
    fun `refundPaymentByTicket providerPaymentKey 가 없으면 PaymentNotRefundableException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-nokey", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            // providerPaymentKey 비워 둠
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)

        assertThrows<PaymentNotRefundableException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())
        }
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    @Test
    fun `refundPaymentByTicket 티켓 미존재는 TicketNotFoundException`() {
        val buyer = createUser(id = 2L)
        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(404L) } returns Optional.empty()

        assertThrows<TicketNotFoundException> {
            service.refundPaymentByTicket(2L, 404L, RefundTicketRequest())
        }
    }

    @Test
    fun `refundPaymentByTicket participation 이 이미 CANCELED 면 정원 추가 감소 없음 (PR78)`() {
        // 시나리오: 사용자가 participation 을 직접 취소(무료 흐름) 후, 동일 ticket attempt 가
        // 어떤 이유로 환불 시도. PR78 의 wasActive 가드가 이중 감소를 막아야 한다.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 4)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-pr78a", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }
        val participation = createParticipation(event, buyer, ParticipationStatus.CANCELED)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.of(participation)

        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())

        // ticket 은 REFUNDED, attempt refunded 처리 — 그러나 정원/participation 상태는 유지.
        assertThat(ticket.status).isEqualTo(TicketStatus.REFUNDED)
        assertThat(attempt.refundedAt).isNotNull
        assertThat(event.currentParticipants).isEqualTo(4) // 감소 없음
        assertThat(participation.status).isEqualTo(ParticipationStatus.CANCELED) // 그대로
    }

    @Test
    fun `refundPaymentByTicket REFUNDED 재호출은 정원 추가 감소 없음 (PR78 idempotent)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        // 이전 환불로 이미 정원이 한 번 빠진 상태 (5 → 4) 를 가정.
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 4)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.REFUNDED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-pr78b", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "refundedAt", LocalDateTime.now())
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)

        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())

        // 멱등 응답 — gateway 미호출, 정원 변화 없음, participation 조회/변경도 없음.
        assertThat(event.currentParticipants).isEqualTo(4)
        verify(exactly = 0) { paymentGateway.refund(any()) }
        verify(exactly = 0) { eventParticipationRepository.findByEventAndParticipant(any(), any()) }
    }

    @Test
    fun `refundPaymentByTicket 성공 시 buyer 에게 REFUND_COMPLETED 알림 1회 발송 (PR81)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 3)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-pr81a", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns Optional.empty()

        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())

        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(2L),
                type = com.contenido.domain.notification.entity.NotificationType.REFUND_COMPLETED,
                title = "환불이 완료되었어요",
                message = any(),
                targetType = "tickets",
                targetId = 999L,
            )
        }
    }

    @Test
    fun `refundPaymentByTicket REFUNDED 멱등 재호출은 알림 발송 안 함 (PR81)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.REFUNDED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-pr81b", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "refundedAt", LocalDateTime.now())
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)

        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())

        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `refundPaymentByTicket 알림 실패가 환불을 막지 않음 (PR81 best-effort)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-pr81c", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns Optional.empty()
        every {
            notificationService.notify(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("SSE outage")

        // 환불 자체는 성공해야 한다.
        val response = service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())
        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
        assertThat(ticket.status).isEqualTo(TicketStatus.REFUNDED)
    }

    // ── PR117 — refundPaymentByTicket 부분 환불 ─────────────────────────────────

    @Test
    fun `refundPaymentByTicket 부분 환불 성공 → PARTIALLY_REFUNDED + refundedAmount 누적 + 정원·participation 변화 없음`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-part1", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "provider", PaymentProvider.TOSS)
        }
        val participation = createParticipation(event, buyer, ParticipationStatus.APPROVED)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        val refundReq = slot<PaymentGatewayRefundRequest>()
        every { paymentGateway.refund(capture(refundReq)) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )

        val response = service.refundPaymentByTicket(
            actorId = 2L, ticketId = 999L,
            request = RefundTicketRequest(reason = "일부 보상", amount = 10_000L),
        )

        // 응답 / 엔티티 상태
        assertThat(response.ticketStatus).isEqualTo(TicketStatus.PARTIALLY_REFUNDED)
        assertThat(response.amount).isEqualTo(30_000L)
        assertThat(response.refundedAmount).isEqualTo(10_000L)
        assertThat(response.remainingRefundableAmount).isEqualTo(20_000L)
        assertThat(ticket.status).isEqualTo(TicketStatus.PARTIALLY_REFUNDED)
        assertThat(attempt.refundedAmount).isEqualTo(10_000L)
        assertThat(attempt.status).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED)
        assertThat(attempt.refundedAt).isNotNull
        // gateway 에 deltaAmount 만 전달
        assertThat(refundReq.captured.amount).isEqualTo(10_000L)
        // 부분 환불은 participation / capacity 무변경
        assertThat(event.currentParticipants).isEqualTo(5)
        // participation 조회 자체가 일어나지 않아야 한다 (full cascade 의 책임)
        verify(exactly = 0) { eventParticipationRepository.findByEventAndParticipant(any(), any()) }
        // buyer 에게 partial 알림 발송
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(2L),
                type = com.contenido.domain.notification.entity.NotificationType.REFUND_COMPLETED,
                title = "부분 환불이 처리되었어요",
                message = any(),
                targetType = "tickets",
                targetId = 999L,
            )
        }
        // participation row 변경 없음 (위 조회를 안 했으니 자명하지만 명시)
        assertThat(participation.status).isEqualTo(ParticipationStatus.APPROVED)
    }

    @Test
    fun `refundPaymentByTicket 부분 환불 후 남은 금액 전체 환불 → REFUNDED + 정원 감소 + participation CANCELED`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        // 1차 부분 환불이 이미 끝난 상태로 세팅 (refundedAmount=10_000, PARTIALLY_REFUNDED).
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.PARTIALLY_REFUNDED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-part2", amount = 30_000L, status = PaymentStatus.PARTIALLY_REFUNDED,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "provider", PaymentProvider.TOSS)
            ReflectionTestUtils.setField(this, "refundedAmount", 10_000L)
            ReflectionTestUtils.setField(this, "refundedAt", LocalDateTime.now().minusMinutes(10))
            ReflectionTestUtils.setField(this, "refundReason", "1차 보상")
        }
        val participation = createParticipation(event, buyer, ParticipationStatus.APPROVED)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        val refundReq = slot<PaymentGatewayRefundRequest>()
        every { paymentGateway.refund(capture(refundReq)) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.of(participation)

        // amount 생략 → 남은 20_000 전체 환불 = full cascade
        val response = service.refundPaymentByTicket(
            actorId = 2L, ticketId = 999L,
            request = RefundTicketRequest(reason = "전액 보상"),
        )

        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
        assertThat(response.refundedAmount).isEqualTo(30_000L)
        assertThat(response.remainingRefundableAmount).isEqualTo(0L)
        // gateway 에는 잔여 20_000 만 전달 (이미 환불한 10_000 은 제외)
        assertThat(refundReq.captured.amount).isEqualTo(20_000L)
        assertThat(ticket.status).isEqualTo(TicketStatus.REFUNDED)
        assertThat(attempt.refundedAmount).isEqualTo(30_000L)
        // full cascade — 정원 감소 + participation CANCELED
        assertThat(event.currentParticipants).isEqualTo(4)
        assertThat(participation.status).isEqualTo(ParticipationStatus.CANCELED)
        // 알림 — full refund 카피
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(2L),
                type = com.contenido.domain.notification.entity.NotificationType.REFUND_COMPLETED,
                title = "환불이 완료되었어요",
                message = any(),
                targetType = "tickets",
                targetId = 999L,
            )
        }
    }

    @Test
    fun `refundPaymentByTicket 부분 환불 amount=0 은 InvalidRefundAmountException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-part0", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)

        assertThrows<com.contenido.global.exception.InvalidRefundAmountException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = 0L))
        }
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    @Test
    fun `refundPaymentByTicket 부분 환불 amount=-1 은 InvalidRefundAmountException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-partneg", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)

        assertThrows<com.contenido.global.exception.InvalidRefundAmountException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = -1L))
        }
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    @Test
    fun `refundPaymentByTicket 부분 환불 amount 가 remaining 초과면 InvalidRefundAmountException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.PARTIALLY_REFUNDED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-partover", amount = 30_000L, status = PaymentStatus.PARTIALLY_REFUNDED,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "refundedAmount", 20_000L)
            ReflectionTestUtils.setField(this, "refundedAt", LocalDateTime.now())
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)

        // remaining 은 10_000, 15_000 요청 → 초과 차단
        assertThrows<com.contenido.global.exception.InvalidRefundAmountException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = 15_000L))
        }
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    @Test
    fun `refundPaymentByTicket amount null 은 기존 전액 환불 동작 회귀`() {
        // PR117 default 동작 — amount 미지정 시 남은 환불 가능 금액 전부 = 전액 환불 cascade.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-null", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        val refundReq = slot<PaymentGatewayRefundRequest>()
        every { paymentGateway.refund(capture(refundReq)) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns Optional.empty()

        val response = service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())

        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
        assertThat(response.refundedAmount).isEqualTo(30_000L)
        assertThat(response.remainingRefundableAmount).isEqualTo(0L)
        assertThat(refundReq.captured.amount).isEqualTo(30_000L) // 전액
        assertThat(event.currentParticipants).isEqualTo(4) // cascade
    }

    // ── PR120 — 부분 환불 회귀 가드 ──────────────────────────────────────────

    @Test
    fun `PR120 — 부분 환불 여러 번 누적 후 마지막에 full cascade`() {
        // 30_000 결제 → 5_000 + 5_000 + 20_000 세 번 환불. 마지막 호출에서 cascade.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-pr120a", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }
        val participation = createParticipation(event, buyer, ParticipationStatus.APPROVED)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.of(participation)

        // 1차 — 5_000
        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = 5_000L, reason = "1차"))
        assertThat(ticket.status).isEqualTo(TicketStatus.PARTIALLY_REFUNDED)
        assertThat(attempt.refundedAmount).isEqualTo(5_000L)
        assertThat(event.currentParticipants).isEqualTo(5)
        assertThat(participation.status).isEqualTo(ParticipationStatus.APPROVED)

        // 2차 — 5_000 (누적 10_000)
        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = 5_000L, reason = "2차"))
        assertThat(ticket.status).isEqualTo(TicketStatus.PARTIALLY_REFUNDED)
        assertThat(attempt.refundedAmount).isEqualTo(10_000L)
        assertThat(event.currentParticipants).isEqualTo(5)
        assertThat(participation.status).isEqualTo(ParticipationStatus.APPROVED)

        // 3차 — 20_000 (누적 30_000 = 결제 금액 → full cascade)
        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = 20_000L, reason = "3차"))
        assertThat(ticket.status).isEqualTo(TicketStatus.REFUNDED)
        assertThat(attempt.refundedAmount).isEqualTo(30_000L)
        assertThat(event.currentParticipants).isEqualTo(4) // 마지막 cascade 에서만 감소
        assertThat(participation.status).isEqualTo(ParticipationStatus.CANCELED)
    }

    @Test
    fun `PR120 — 전액 환불 후 추가 환불 호출은 멱등 응답 (gateway 미호출)`() {
        // 이미 REFUNDED 인 ticket 에 다시 refund 호출 → 기존 정보 그대로 응답, gateway 재호출 X.
        // PR42 기존 정책 — 부분 환불 도입 후에도 회귀 없는지 확인.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 4)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.REFUNDED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-pr120b", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "refundedAmount", 30_000L)
            ReflectionTestUtils.setField(this, "refundedAt", LocalDateTime.now())
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)

        // 부분 환불 요청도, 전액 환불 요청도 모두 멱등 — gateway 미호출.
        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = 5_000L))
        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())

        verify(exactly = 0) { paymentGateway.refund(any()) }
        assertThat(ticket.status).isEqualTo(TicketStatus.REFUNDED)
        assertThat(attempt.refundedAmount).isEqualTo(30_000L)
        assertThat(event.currentParticipants).isEqualTo(4)
    }

    @Test
    fun `PR120 — PARTIALLY_REFUNDED 티켓 보유자가 같은 event 에 다시 prepare 시도 → AlreadyJoinedException`() {
        // PR117 의 정책 (부분 환불은 참가 자격 유지) 의 회귀 가드. PR120 에서 validatePrepareable
        // 의 active statuses 에 PARTIALLY_REFUNDED 가 포함됐는지 확인.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, maxParticipants = 10)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        // PARTIALLY_REFUNDED 도 active 로 본다 — existsByEventAndBuyerAndStatusIn 가 true 반환.
        every {
            ticketRepository.existsByEventAndBuyerAndStatusIn(event, buyer, any())
        } returns true

        assertThrows<AlreadyJoinedException> {
            service.preparePayment(userId = 2L, eventId = 100L)
        }
        // 같은 (event, buyer) READY 가 있는지 확인하기 전에 AlreadyJoined 가 먼저 던져진다.
        verify(exactly = 0) {
            paymentAttemptRepository.findFirstByEventAndBuyerAndStatusOrderByCreatedAtDesc(any(), any(), any())
        }
    }

    @Test
    fun `PR120 — preparePayment 의 active statuses 에 PARTIALLY_REFUNDED 포함`() {
        // existsByEventAndBuyerAndStatusIn 호출 시 전달되는 statuses 인자를 캡처해 검증.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, maxParticipants = 10)
        val statusesSlot = slot<Collection<TicketStatus>>()

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every {
            ticketRepository.existsByEventAndBuyerAndStatusIn(event, buyer, capture(statusesSlot))
        } returns false
        every {
            paymentAttemptRepository.findFirstByEventAndBuyerAndStatusOrderByCreatedAtDesc(event, buyer, PaymentStatus.READY)
        } returns Optional.empty()
        every { paymentAttemptRepository.save(any<PaymentAttempt>()) } answers {
            firstArg<PaymentAttempt>().also { ReflectionTestUtils.setField(it, "id", 1L) }
        }

        service.preparePayment(userId = 2L, eventId = 100L)

        assertThat(statusesSlot.captured)
            .containsExactlyInAnyOrder(TicketStatus.PAID, TicketStatus.USED, TicketStatus.PARTIALLY_REFUNDED)
    }

    @Test
    fun `PR120 — forceRefundByAdmin 가 PARTIALLY_REFUNDED 티켓의 remaining 만 cancel 호출 후 REFUNDED cascade`() {
        // PR117 의 admin path 확장 — PARTIALLY_REFUNDED 도 받지만 항상 한 번에 remaining 전액을 환불.
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner),
            fee = 30_000L, currentParticipants = 5,
            // 시작 후 — admin path 는 deadline 무시.
            startAt = LocalDateTime.now().minusHours(1),
            endAt = LocalDateTime.now().plusHours(1),
        )
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.PARTIALLY_REFUNDED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-pr120e", amount = 30_000L, status = PaymentStatus.PARTIALLY_REFUNDED,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "provider", PaymentProvider.TOSS)
            ReflectionTestUtils.setField(this, "refundedAmount", 10_000L)
            ReflectionTestUtils.setField(this, "refundedAt", LocalDateTime.now().minusMinutes(5))
        }
        val participation = createParticipation(event, buyer, ParticipationStatus.APPROVED)

        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        val refundReq = slot<PaymentGatewayRefundRequest>()
        every { paymentGateway.refund(capture(refundReq)) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.of(participation)

        val response = service.forceRefundByAdmin(
            adminUserId = 99L, ticketId = 999L, reason = "이벤트 취소 보상",
        )

        // gateway 에는 remaining (20_000) 만 전달
        assertThat(refundReq.captured.amount).isEqualTo(20_000L)
        // ticket / attempt / event 모두 fully refunded 상태로 cascade
        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
        assertThat(response.refundedAmount).isEqualTo(30_000L)
        assertThat(response.remainingRefundableAmount).isEqualTo(0L)
        assertThat(ticket.status).isEqualTo(TicketStatus.REFUNDED)
        assertThat(attempt.refundedAmount).isEqualTo(30_000L)
        assertThat(event.currentParticipants).isEqualTo(4)
        assertThat(participation.status).isEqualTo(ParticipationStatus.CANCELED)
    }

    @Test
    fun `refundPaymentByTicket 부분 환불 후 PARTIALLY_REFUNDED 티켓에 추가 부분 환불 가능`() {
        // 두 번 partial — refundedAmount 가 누적되고 ticket 은 PARTIALLY_REFUNDED 유지.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.PARTIALLY_REFUNDED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-part3", amount = 30_000L, status = PaymentStatus.PARTIALLY_REFUNDED,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "refundedAmount", 10_000L)
            ReflectionTestUtils.setField(this, "refundedAt", LocalDateTime.now().minusMinutes(5))
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )

        val response = service.refundPaymentByTicket(
            actorId = 2L, ticketId = 999L,
            request = RefundTicketRequest(reason = "2차 보상", amount = 5_000L),
        )

        assertThat(response.ticketStatus).isEqualTo(TicketStatus.PARTIALLY_REFUNDED)
        assertThat(response.refundedAmount).isEqualTo(15_000L)
        assertThat(response.remainingRefundableAmount).isEqualTo(15_000L)
        assertThat(ticket.status).isEqualTo(TicketStatus.PARTIALLY_REFUNDED)
        assertThat(attempt.status).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED)
        // capacity / participation 무변경
        assertThat(event.currentParticipants).isEqualTo(5)
        verify(exactly = 0) { eventParticipationRepository.findByEventAndParticipant(any(), any()) }
    }

    // ── PR122 — 일반 사용자 환불 audit 기록 ──────────────────────────────────

    @Test
    fun `PR122 — 부분 환불 성공 시 PAYMENT_PARTIALLY_REFUNDED audit 1건 actor=buyer + afterValue JSON`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-audit1", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        val actionSlot = slot<com.contenido.domain.admin.entity.ModerationAuditAction>()
        val actorSlot = slot<Long>()
        val afterValueSlot = slot<Any>()
        val beforeValueSlot = slot<Any>()
        every {
            moderationAuditLogService.record(
                actorId = capture(actorSlot),
                action = capture(actionSlot),
                targetType = any(),
                targetId = any(),
                beforeValue = capture(beforeValueSlot),
                afterValue = capture(afterValueSlot),
                reason = any(),
            )
        } returns io.mockk.mockk(relaxed = true)

        service.refundPaymentByTicket(
            actorId = 2L, ticketId = 999L,
            request = RefundTicketRequest(reason = "부분 환불", amount = 10_000L),
        )

        verify(exactly = 1) {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), any(), any())
        }
        assertThat(actionSlot.captured)
            .isEqualTo(com.contenido.domain.admin.entity.ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED)
        assertThat(actorSlot.captured).isEqualTo(2L) // buyer
        @Suppress("UNCHECKED_CAST")
        val after = afterValueSlot.captured as Map<String, Any?>
        assertThat(after["ticketId"]).isEqualTo(999L)
        assertThat(after["paymentAttemptId"]).isEqualTo(555L)
        assertThat(after["eventId"]).isEqualTo(100L)
        assertThat(after["refundAmount"]).isEqualTo(10_000L)
        assertThat(after["refundedAmount"]).isEqualTo(10_000L)
        assertThat(after["remainingRefundableAmount"]).isEqualTo(20_000L)
        assertThat(after["ticketStatus"]).isEqualTo("PARTIALLY_REFUNDED")
        assertThat(after["paymentStatus"]).isEqualTo("PARTIALLY_REFUNDED")
        assertThat(after["fullRefund"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        val before = beforeValueSlot.captured as Map<String, Any?>
        assertThat(before["ticketStatusBefore"]).isEqualTo("PAID")
        assertThat(before["paymentStatusBefore"]).isEqualTo("PAID")
        assertThat(before["refundedAmountBefore"]).isEqualTo(0L)
        assertThat(before["remainingRefundableAmountBefore"]).isEqualTo(30_000L)
    }

    @Test
    fun `PR122 — 전액 환불 성공 시 PAYMENT_REFUNDED audit 1건 actor=buyer + fullRefund=true`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-audit2", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns Optional.empty()
        val actionSlot = slot<com.contenido.domain.admin.entity.ModerationAuditAction>()
        val afterValueSlot = slot<Any>()
        every {
            moderationAuditLogService.record(any(), capture(actionSlot), any(), any(), any(), capture(afterValueSlot), any())
        } returns io.mockk.mockk(relaxed = true)

        // amount 미지정 → 전액 환불
        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(reason = "전액 환불"))

        assertThat(actionSlot.captured)
            .isEqualTo(com.contenido.domain.admin.entity.ModerationAuditAction.PAYMENT_REFUNDED)
        @Suppress("UNCHECKED_CAST")
        val after = afterValueSlot.captured as Map<String, Any?>
        assertThat(after["fullRefund"]).isEqualTo(true)
        assertThat(after["refundAmount"]).isEqualTo(30_000L)
        assertThat(after["refundedAmount"]).isEqualTo(30_000L)
        assertThat(after["remainingRefundableAmount"]).isEqualTo(0L)
        assertThat(after["ticketStatus"]).isEqualTo("REFUNDED")
        // paymentStatus 는 markFullyRefunded 가 PAID 로 set (PR42 기존 모델)
        assertThat(after["paymentStatus"]).isEqualTo("PAID")
    }

    @Test
    fun `PR122 — 부분 환불 후 최종 full 도달 시 마지막 호출은 PAYMENT_REFUNDED audit`() {
        // 1차 partial = PAYMENT_PARTIALLY_REFUNDED, 2차에서 remaining 전체 환불 = PAYMENT_REFUNDED.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-audit3", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }
        val participation = createParticipation(event, buyer, ParticipationStatus.APPROVED)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.of(participation)
        val actionCaptures = mutableListOf<com.contenido.domain.admin.entity.ModerationAuditAction>()
        every {
            moderationAuditLogService.record(any(), capture(actionCaptures), any(), any(), any(), any(), any())
        } returns io.mockk.mockk(relaxed = true)

        // 1차 — 10_000 부분 환불
        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = 10_000L))
        // 2차 — 잔여 20_000 (amount 생략) 전체 환불 = cascade
        service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())

        assertThat(actionCaptures).containsExactly(
            com.contenido.domain.admin.entity.ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED,
            com.contenido.domain.admin.entity.ModerationAuditAction.PAYMENT_REFUNDED,
        )
    }

    @Test
    fun `PR122 — PG failure 시 audit 미기록 (rollback)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-audit4", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Failure(
            provider = PaymentProvider.TOSS, code = "GATEWAY_FAIL", message = "PG rejected",
        )

        assertThrows<RefundFailedException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = 5_000L))
        }
        verify(exactly = 0) {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `PR122 — InvalidRefundAmountException 시 audit 미기록`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-audit5", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)

        assertThrows<com.contenido.global.exception.InvalidRefundAmountException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest(amount = 0L))
        }
        verify(exactly = 0) {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    @Test
    fun `PR122 — forceRefundByAdmin 호출 시 PaymentService 가 PAYMENT_REFUNDED audit 을 기록하지 않음`() {
        // AdminPaymentService 가 별도로 TICKET_FORCED_REFUNDED audit 을 기록 — 본 service 는 audit 안 함.
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner),
            fee = 30_000L, currentParticipants = 5,
            startAt = LocalDateTime.now().minusHours(1),
            endAt = LocalDateTime.now().plusHours(1),
        )
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.USED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-audit6", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns Optional.empty()

        service.forceRefundByAdmin(adminUserId = 99L, ticketId = 999L, reason = "운영 환불")

        verify(exactly = 0) {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ── PR106 — forceRefundByAdmin (ADMIN 강제 환불) ─────────────────────────────

    @Test
    fun `forceRefundByAdmin USED 티켓도 전액 환불 + 정원 -- + REFUND_COMPLETED 알림`() {
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner),
            fee = 30_000L, currentParticipants = 5,
            startAt = LocalDateTime.now().minusHours(2), // 시작 후
            endAt = LocalDateTime.now().minusMinutes(30),
        )
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.USED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-force", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
            ReflectionTestUtils.setField(this, "provider", PaymentProvider.TOSS)
        }
        val participation = createParticipation(event, buyer, ParticipationStatus.APPROVED)

        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.TOSS,
            providerPaymentKey = "toss_paid_key",
            canceledAt = "2026-05-18T12:00:00+09:00",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.of(participation)
        every {
            notificationService.notify(any(), any(), any(), any(), any(), any())
        } just Runs

        val response = service.forceRefundByAdmin(adminUserId = 99L, ticketId = 999L, reason = "행사 취소 보상")

        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
        assertThat(ticket.status).isEqualTo(TicketStatus.REFUNDED)
        assertThat(attempt.refundedAt).isNotNull
        assertThat(attempt.refundReason).isEqualTo("행사 취소 보상")
        // USED 티켓도 환불 가능 — deadline 검사 우회.
        assertThat(event.currentParticipants).isEqualTo(4)
        assertThat(participation.status).isEqualTo(ParticipationStatus.CANCELED)
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(2L),
                type = com.contenido.domain.notification.entity.NotificationType.REFUND_COMPLETED,
                title = any(),
                message = any(),
                targetType = "tickets",
                targetId = 999L,
            )
        }
    }

    @Test
    fun `forceRefundByAdmin PAID 티켓도 환불 가능 + 시작 후 이벤트도 통과`() {
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner),
            fee = 30_000L, currentParticipants = 7,
            startAt = LocalDateTime.now().minusHours(1),
            endAt = LocalDateTime.now().plusHours(1),
        )
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.PAID)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-force-paid", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Success(
            provider = PaymentProvider.NONE, providerPaymentKey = "toss_paid_key",
        )
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns Optional.empty()
        every { notificationService.notify(any(), any(), any(), any(), any(), any()) } just Runs

        val response = service.forceRefundByAdmin(99L, 999L, "운영 강제 환불")

        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
        assertThat(event.currentParticipants).isEqualTo(6)
    }

    @Test
    fun `forceRefundByAdmin REFUNDED 티켓은 멱등 응답이 아니라 TicketAlreadyRefundedException 으로 차단`() {
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.REFUNDED)

        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)

        assertThrows<TicketAlreadyRefundedException> {
            service.forceRefundByAdmin(99L, 999L, "재시도")
        }
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    @Test
    fun `forceRefundByAdmin CANCELED 티켓은 PaymentNotRefundableException`() {
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.CANCELED)

        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)

        assertThrows<PaymentNotRefundableException> {
            service.forceRefundByAdmin(99L, 999L, "운영 사유")
        }
    }

    @Test
    fun `forceRefundByAdmin 결제 attempt 없으면 PaymentNotRefundableException`() {
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.empty()

        assertThrows<PaymentNotRefundableException> {
            service.forceRefundByAdmin(99L, 999L, "운영 사유")
        }
    }

    @Test
    fun `forceRefundByAdmin ADMIN 이 아니면 UnauthorizedException`() {
        val notAdmin = createUser(id = 2L, role = UserRole.PARTICIPANT)
        every { userRepository.findById(2L) } returns Optional.of(notAdmin)

        assertThrows<UnauthorizedException> {
            service.forceRefundByAdmin(2L, 999L, "사유")
        }
        verify(exactly = 0) { ticketRepository.findById(any()) }
    }

    @Test
    fun `forceRefundByAdmin gateway Failure 시 RefundFailedException + ticket 상태 보존`() {
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.USED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-force-fail", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }

        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)
        every { paymentAttemptRepository.findByTicket(ticket) } returns Optional.of(attempt)
        every { paymentGateway.refund(any()) } returns PaymentGatewayRefundResult.Failure(
            provider = PaymentProvider.NONE, code = "GATEWAY_ERROR", message = "PG 거절",
        )

        assertThrows<RefundFailedException> {
            service.forceRefundByAdmin(99L, 999L, "운영 사유")
        }
        // 실패 시 상태 그대로.
        assertThat(ticket.status).isEqualTo(TicketStatus.USED)
        assertThat(attempt.refundedAt).isNull()
    }

    @Test
    fun `refundPaymentByTicket 이벤트 이미 시작됐으면 RefundDeadlinePassedException (gateway 호출 X)`() {
        // PR43 가드: PAID 티켓이라도 event.startAt 이 현재보다 과거면 환불 불가.
        // ADMIN 도 동일 — ADMIN 우회는 별도 운영 도구로 처리.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L,
            channel = createChannel(owner = owner),
            fee = 30_000L,
            startAt = LocalDateTime.now().minusHours(1),  // 1 시간 전 시작
            endAt = LocalDateTime.now().plusHours(1),
        )
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(999L) } returns Optional.of(ticket)

        assertThrows<com.contenido.global.exception.RefundDeadlinePassedException> {
            service.refundPaymentByTicket(2L, 999L, RefundTicketRequest())
        }

        // gateway 호출 흔적이 없어야 한다 — 시간 가드는 PG 호출 전에 일어남.
        verify(exactly = 0) { paymentGateway.refund(any()) }
    }

    // ── handleWebhook REFUNDED ───────────────────────────────────────────────────

    @Test
    fun `handleWebhook REFUNDED 정상 처리 시 ticket REFUNDED + 정원 -- + EventParticipation CANCELED`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-wrf", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "providerPaymentKey", "toss_paid_key")
        }
        val participation = createParticipation(event, buyer, ParticipationStatus.APPROVED)

        every { paymentAttemptRepository.findByIdempotencyKey("order-wrf") } returns Optional.of(attempt)
        every { eventParticipationRepository.findByEventAndParticipant(event, buyer) } returns
            Optional.of(participation)

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "order-wrf",
                providerPaymentKey = "toss_paid_key",
                amount = 30_000L,
                status = PaymentStatus.REFUNDED,
                provider = PaymentProvider.TOSS,
            )
        )

        assertThat(ticket.status).isEqualTo(TicketStatus.REFUNDED)
        assertThat(attempt.refundedAt).isNotNull
        assertThat(event.currentParticipants).isEqualTo(4)
        assertThat(participation.status).isEqualTo(ParticipationStatus.CANCELED)
    }

    @Test
    fun `handleWebhook REFUNDED 중복 webhook 은 멱등 (skip, 카운트 변화 없음)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 4)
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.REFUNDED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "order-wdup", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply {
            ReflectionTestUtils.setField(this, "ticket", ticket)
            ReflectionTestUtils.setField(this, "refundedAt", LocalDateTime.now())
        }

        every { paymentAttemptRepository.findByIdempotencyKey("order-wdup") } returns Optional.of(attempt)

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "order-wdup", amount = 30_000L, status = PaymentStatus.REFUNDED,
            )
        )

        assertThat(event.currentParticipants).isEqualTo(4) // 변화 없음
    }

    // ── PR42 hardening: webhook 멱등성 보강 ────────────────────────────────────

    @Test
    fun `handleWebhook FAILED 가 이미 PAID 인 attempt 에 오면 skip (confirm 후 늦은 FAILED webhook race)`() {
        // 시나리오: 클라이언트 confirm 으로 이미 PAID 처리됐는데 PG 가 뒤늦게 FAILED webhook 을 보내는 경우.
        // attempt.status != READY 분기로 빠져야 하며, PAID → FAILED 로 뒤집히지 않아야 한다.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 5,
        )
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.PAID)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "race-FAIL", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply { ReflectionTestUtils.setField(this, "ticket", ticket) }

        every { paymentAttemptRepository.findByIdempotencyKey("race-FAIL") } returns Optional.of(attempt)

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "race-FAIL", amount = 30_000L, status = PaymentStatus.FAILED,
            )
        )

        // 이미 PAID 였던 attempt 가 그대로 PAID, ticket 도 그대로, 정원도 그대로.
        assertThat(attempt.status).isEqualTo(PaymentStatus.PAID)
        assertThat(ticket.status).isEqualTo(TicketStatus.PAID)
        assertThat(event.currentParticipants).isEqualTo(5)
        verify(exactly = 0) { ticketService.issuePaidTicket(any(), any(), any()) }
    }

    @Test
    fun `handleRefundedWebhook attempt 가 PAID 가 아니면 skip (FAILED attempt 에 REFUNDED webhook)`() {
        // 시나리오: 결제가 FAILED 로 끝났는데 PG 가 잘못된 REFUNDED webhook 을 보내는 경우.
        // ticket 도 없고 환불할 대상도 없으므로 안전하게 skip 해야 한다.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 3,
        )
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "bad-refund", amount = 30_000L, status = PaymentStatus.FAILED,
        )

        every { paymentAttemptRepository.findByIdempotencyKey("bad-refund") } returns Optional.of(attempt)

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "bad-refund", amount = 30_000L, status = PaymentStatus.REFUNDED,
            )
        )

        assertThat(attempt.refundedAt).isNull()
        assertThat(event.currentParticipants).isEqualTo(3) // 변화 없음
    }

    @Test
    fun `handleRefundedWebhook attempt 는 PAID 인데 ticket 이 null 이면 skip (비정상 상태)`() {
        // 시나리오: confirm 진행 중 중단 등으로 attempt.status=PAID 인데 ticket 이 연결 안 된 상태.
        // 실제로는 일어나면 안 되지만, 일어났을 때 NullPointerException 같은 cascade 실패 대신 skip.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 3,
        )
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "no-ticket", amount = 30_000L, status = PaymentStatus.PAID,
        )
        // ticket 은 의도적으로 세팅하지 않음 → null.

        every { paymentAttemptRepository.findByIdempotencyKey("no-ticket") } returns Optional.of(attempt)

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "no-ticket", amount = 30_000L, status = PaymentStatus.REFUNDED,
            )
        )

        assertThat(attempt.refundedAt).isNull()
        assertThat(event.currentParticipants).isEqualTo(3) // 변화 없음
    }

    @Test
    fun `handleRefundedWebhook ticket 이 USED 상태면 skip (체크인 후 환불 webhook 강제 차단)`() {
        // 시나리오: 사용자가 이미 체크인(USED)한 티켓에 대해 PG 측에서 REFUNDED webhook 이 도착.
        // 운영 도구로 별도 처리해야 할 케이스이므로 webhook 으로는 자동 환불하지 않는다.
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(
            id = 100L, channel = createChannel(owner = owner), fee = 30_000L, currentParticipants = 7,
        )
        val ticket = createTicket(id = 999L, event = event, buyer = buyer, status = TicketStatus.USED)
        val attempt = createPaymentAttempt(
            id = 555L, event = event, buyer = buyer,
            idempotencyKey = "used-refund", amount = 30_000L, status = PaymentStatus.PAID,
        ).apply { ReflectionTestUtils.setField(this, "ticket", ticket) }

        every { paymentAttemptRepository.findByIdempotencyKey("used-refund") } returns Optional.of(attempt)

        service.handleWebhook(
            PaymentWebhookRequest(
                idempotencyKey = "used-refund", amount = 30_000L, status = PaymentStatus.REFUNDED,
            )
        )

        assertThat(attempt.refundedAt).isNull()
        assertThat(ticket.status).isEqualTo(TicketStatus.USED) // 변화 없음
        assertThat(event.currentParticipants).isEqualTo(7) // 변화 없음
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun createUser(
        id: Long,
        nickname: String = "user$id",
        role: UserRole = UserRole.PARTICIPANT,
    ): User = User("u$id@test.com", "encoded", nickname, "010-$id").apply {
        ReflectionTestUtils.setField(this, "id", id)
        updateRole(role)
    }

    private fun createChannel(
        id: Long = 10L,
        owner: User,
        name: String = "채널$id",
    ): Channel = Channel(owner, name, "설명", ChannelCategory.MUSIC).apply {
        ReflectionTestUtils.setField(this, "id", id)
    }

    private fun createEvent(
        id: Long,
        channel: Channel,
        fee: Long = 0L,
        maxParticipants: Int = 10,
        currentParticipants: Int = 0,
        startAt: LocalDateTime = LocalDateTime.now().plusDays(1),
        endAt: LocalDateTime = LocalDateTime.now().plusDays(1).plusHours(2),
        status: EventStatus = EventStatus.UPCOMING,
    ): Event = Event(
        channel = channel,
        title = "이벤트$id",
        description = "desc",
        location = "서울",
        mainImageUrl = "https://example.com/$id.jpg",
        startAt = startAt,
        endAt = endAt,
        maxParticipants = maxParticipants,
        participationFee = fee,
        refundPolicy = "전액",
        detailContent = "detail",
        currentParticipants = currentParticipants,
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        this.status = status
    }

    private fun createTicket(
        id: Long,
        event: Event,
        buyer: User,
        status: TicketStatus = TicketStatus.PAID,
    ): Ticket = Ticket(event = event, buyer = buyer, price = event.participationFee, status = status).apply {
        ReflectionTestUtils.setField(this, "id", id)
    }

    private fun createPaymentAttempt(
        id: Long,
        event: Event,
        buyer: User,
        idempotencyKey: String,
        amount: Long,
        status: PaymentStatus = PaymentStatus.READY,
    ): PaymentAttempt = PaymentAttempt(
        event = event,
        buyer = buyer,
        idempotencyKey = idempotencyKey,
        amount = amount,
        status = status,
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
    }

    private fun createParticipation(
        event: Event,
        buyer: User,
        status: ParticipationStatus,
    ): EventParticipation = EventParticipation(event = event, participant = buyer).apply {
        this.status = status
    }
}
