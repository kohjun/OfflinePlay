package com.contenido.domain.payment.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.payment.dto.PaymentWebhookRequest
import com.contenido.domain.payment.entity.PaymentAttempt
import com.contenido.domain.payment.entity.PaymentProvider
import com.contenido.domain.payment.entity.PaymentStatus
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
import com.contenido.global.exception.OwnerCannotApplyException
import com.contenido.global.exception.PaymentAttemptNotFoundException
import io.mockk.every
import io.mockk.impl.annotations.MockK
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

    private lateinit var service: PaymentService

    @BeforeEach
    fun setUp() {
        service = PaymentService(
            paymentAttemptRepository,
            eventRepository,
            userRepository,
            ticketRepository,
            ticketService,
        )
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
}
