package com.contenido.domain.ticket.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.BuyerCannotCheckInException
import com.contenido.global.exception.InvalidCheckInCodeException
import com.contenido.global.exception.TicketNotFoundException
import com.contenido.global.exception.TicketNotPaidException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
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
class TicketServiceTest {

    @MockK lateinit var ticketRepository: TicketRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var channelMemberRepository: ChannelMemberRepository
    @MockK lateinit var notificationService: NotificationService

    private lateinit var service: TicketService

    @BeforeEach
    fun setUp() {
        service = TicketService(
            ticketRepository,
            eventRepository,
            userRepository,
            channelMemberRepository,
            notificationService,
        )
        every { notificationService.notify(any(), any(), any(), any(), any(), any()) } just Runs
    }

    @Test
    fun `getTicketDetail buyer 본인은 조회 성공 + checkInCode 생성`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L, nickname = "참가자")
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)

        val detail = service.getTicketDetail(viewerId = 2L, ticketId = 555L)

        assertThat(detail.ticketId).isEqualTo(555L)
        assertThat(detail.ticketStatus).isEqualTo(TicketStatus.PAID)
        assertThat(detail.eventId).isEqualTo(100L)
        assertThat(detail.eventTitle).isEqualTo("이벤트100")
        assertThat(detail.channelId).isEqualTo(10L)
        assertThat(detail.buyerId).isEqualTo(2L)
        assertThat(detail.buyerNickname).isEqualTo("참가자")
        assertThat(detail.checkInCode).isEqualTo("CONTENIDO-555-100")
    }

    @Test
    fun `getTicketDetail ADMIN 은 buyer 가 아니어도 조회 가능`() {
        val admin = createUser(id = 9L, role = UserRole.ADMIN, nickname = "관리자")
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(id = 10L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val ticket = createTicket(id = 555L, event = event, buyer = buyer)

        every { userRepository.findById(9L) } returns Optional.of(admin)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)

        val detail = service.getTicketDetail(viewerId = 9L, ticketId = 555L)

        assertThat(detail.buyerId).isEqualTo(2L) // ticket 의 실제 buyer
    }

    @Test
    fun `getTicketDetail 다른 일반 사용자는 UnauthorizedException`() {
        val intruder = createUser(id = 3L, role = UserRole.PARTICIPANT)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(id = 10L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val ticket = createTicket(id = 555L, event = event, buyer = buyer)

        every { userRepository.findById(3L) } returns Optional.of(intruder)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)

        assertThrows<UnauthorizedException> { service.getTicketDetail(viewerId = 3L, ticketId = 555L) }
    }

    @Test
    fun `getTicketDetail 없는 ticketId 는 TicketNotFoundException`() {
        val viewer = createUser(id = 2L)
        every { userRepository.findById(2L) } returns Optional.of(viewer)
        every { ticketRepository.findById(999L) } returns Optional.empty()

        assertThrows<TicketNotFoundException> { service.getTicketDetail(viewerId = 2L, ticketId = 999L) }
    }

    @Test
    fun `getTicketDetail 없는 viewer 는 UserNotFoundException`() {
        every { userRepository.findById(404L) } returns Optional.empty()

        assertThrows<UserNotFoundException> { service.getTicketDetail(viewerId = 404L, ticketId = 1L) }
    }

    // ── checkInTicket ─────────────────────────────────────────────────────────

    @Test
    fun `checkInTicket owner 가 체크인하면 PAID → USED + usedAt 세팅 + buyer 알림`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { channelMemberRepository.existsByChannelAndUser(channel, owner) } returns true

        val result = service.checkInTicket(viewerId = 1L, ticketId = 555L)

        assertThat(result.ticketStatus).isEqualTo(TicketStatus.USED)
        assertThat(ticket.status).isEqualTo(TicketStatus.USED)
        assertThat(ticket.usedAt).isNotNull
        assertThat(result.usedAt).isNotNull
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(2L),
                type = NotificationType.TICKET_CHECKED_IN,
                title = any(),
                message = any(),
                targetType = "tickets",
                targetId = 555L,
            )
        }
    }

    @Test
    fun `checkInTicket ADMIN 도 체크인 가능`() {
        val admin = createUser(id = 9L, role = UserRole.ADMIN)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = createUser(id = 1L, role = UserRole.CREATOR))
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(9L) } returns Optional.of(admin)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        // ADMIN 은 channelMember 체크 우회 — 호출되지 않아야 한다.

        val result = service.checkInTicket(viewerId = 9L, ticketId = 555L)

        assertThat(result.ticketStatus).isEqualTo(TicketStatus.USED)
    }

    @Test
    fun `checkInTicket buyer 본인이 호출하면 BuyerCannotCheckInException`() {
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(id = 10L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)

        assertThrows<BuyerCannotCheckInException> { service.checkInTicket(viewerId = 2L, ticketId = 555L) }
        // 상태는 변하지 않아야 한다.
        assertThat(ticket.status).isEqualTo(TicketStatus.PAID)
    }

    @Test
    fun `checkInTicket owner row 가 없어도 channel owner 본인은 fallback 으로 허용`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        // 레거시 채널이라 ChannelMember 가 없을 수 있음 — fallback 동작 확인.
        every { channelMemberRepository.existsByChannelAndUser(channel, owner) } returns false

        val result = service.checkInTicket(viewerId = 1L, ticketId = 555L)

        assertThat(result.ticketStatus).isEqualTo(TicketStatus.USED)
    }

    @Test
    fun `checkInTicket STAFF 멤버도 체크인 허용`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val staff = createUser(id = 7L, role = UserRole.PARTICIPANT)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(7L) } returns Optional.of(staff)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { channelMemberRepository.existsByChannelAndUser(channel, staff) } returns true

        val result = service.checkInTicket(viewerId = 7L, ticketId = 555L)

        assertThat(result.ticketStatus).isEqualTo(TicketStatus.USED)
    }

    @Test
    fun `checkInTicket 다른 일반 사용자는 UnauthorizedException`() {
        val intruder = createUser(id = 3L, role = UserRole.PARTICIPANT)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = createUser(id = 1L, role = UserRole.CREATOR))
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(3L) } returns Optional.of(intruder)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { channelMemberRepository.existsByChannelAndUser(channel, intruder) } returns false

        assertThrows<UnauthorizedException> { service.checkInTicket(viewerId = 3L, ticketId = 555L) }
        assertThat(ticket.status).isEqualTo(TicketStatus.PAID)
    }

    @Test
    fun `checkInTicket 실패하면 알림 발송 없음`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.USED)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { channelMemberRepository.existsByChannelAndUser(channel, owner) } returns true

        assertThrows<TicketNotPaidException> { service.checkInTicket(viewerId = 1L, ticketId = 555L) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    // ── issueFreeTicket ───────────────────────────────────────────────────────

    @Test
    fun `issueFreeTicket 성공 시 PAID 티켓 저장 + buyer 에게 TICKET_ISSUED 알림`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { ticketRepository.save(any()) } answers {
            val arg = firstArg<Ticket>()
            ReflectionTestUtils.setField(arg, "id", 999L)
            ReflectionTestUtils.setField(arg, "purchasedAt", LocalDateTime.now())
            ReflectionTestUtils.setField(arg, "updatedAt", LocalDateTime.now())
            arg
        }

        val ticketId = service.issueFreeTicket(userId = 2L, eventId = 100L)

        assertThat(ticketId).isEqualTo(999L)
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(2L),
                type = NotificationType.TICKET_ISSUED,
                title = any(),
                message = any(),
                targetType = "tickets",
                targetId = 999L,
            )
        }
    }

    @Test
    fun `issueFreeTicket 알림 실패해도 ticketId 는 반환된다`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val event = createEvent(id = 100L, channel = createChannel(id = 10L, owner = owner))

        every { userRepository.findById(2L) } returns Optional.of(buyer)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { ticketRepository.save(any()) } answers {
            val arg = firstArg<Ticket>()
            ReflectionTestUtils.setField(arg, "id", 999L)
            ReflectionTestUtils.setField(arg, "purchasedAt", LocalDateTime.now())
            ReflectionTestUtils.setField(arg, "updatedAt", LocalDateTime.now())
            arg
        }
        every {
            notificationService.notify(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("SSE outage")

        val ticketId = service.issueFreeTicket(userId = 2L, eventId = 100L)
        assertThat(ticketId).isEqualTo(999L)
    }

    @Test
    fun `checkInTicket 이미 USED 면 TicketNotPaidException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.USED)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { channelMemberRepository.existsByChannelAndUser(channel, owner) } returns true

        assertThrows<TicketNotPaidException> { service.checkInTicket(viewerId = 1L, ticketId = 555L) }
    }

    @Test
    fun `checkInTicket REFUNDED 면 TicketNotPaidException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.REFUNDED)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { channelMemberRepository.existsByChannelAndUser(channel, owner) } returns true

        assertThrows<TicketNotPaidException> { service.checkInTicket(viewerId = 1L, ticketId = 555L) }
    }

    @Test
    fun `checkInTicket CANCELED 면 TicketNotPaidException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.CANCELED)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { channelMemberRepository.existsByChannelAndUser(channel, owner) } returns true

        assertThrows<TicketNotPaidException> { service.checkInTicket(viewerId = 1L, ticketId = 555L) }
    }

    // ── getEventCheckIns ──────────────────────────────────────────────────────

    @Test
    fun `getEventCheckIns owner 호출 시 issued checkedIn notCheckedIn 정확히 집계`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyerA = createUser(id = 2L, nickname = "A")
        val buyerB = createUser(id = 3L, nickname = "B")
        val buyerC = createUser(id = 4L, nickname = "C")
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val tPaid = createTicket(id = 500L, event = event, buyer = buyerA, status = TicketStatus.PAID, purchasedAt = LocalDateTime.now().minusHours(2))
        val tUsed = createTicket(id = 501L, event = event, buyer = buyerB, status = TicketStatus.USED, purchasedAt = LocalDateTime.now().minusHours(3))
        val tCanceled = createTicket(id = 502L, event = event, buyer = buyerC, status = TicketStatus.CANCELED, purchasedAt = LocalDateTime.now().minusHours(1))

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { channelMemberRepository.existsByChannelAndUser(channel, owner) } returns true
        every { ticketRepository.findByEvent(event) } returns listOf(tPaid, tUsed, tCanceled)

        val result = service.getEventCheckIns(viewerId = 1L, eventId = 100L)

        assertThat(result.eventId).isEqualTo(100L)
        assertThat(result.issuedCount).isEqualTo(3)
        assertThat(result.checkedInCount).isEqualTo(1) // USED
        assertThat(result.notCheckedInCount).isEqualTo(1) // PAID only
        assertThat(result.tickets).hasSize(3)
        assertThat(result.tickets.map { it.buyerNickname }).containsExactlyInAnyOrder("A", "B", "C")
    }

    @Test
    fun `getEventCheckIns owner row 가 없어도 channel owner 본인은 fallback 허용`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { channelMemberRepository.existsByChannelAndUser(channel, owner) } returns false
        every { ticketRepository.findByEvent(event) } returns emptyList()

        val result = service.getEventCheckIns(viewerId = 1L, eventId = 100L)
        assertThat(result.issuedCount).isEqualTo(0)
    }

    @Test
    fun `getEventCheckIns ADMIN 도 조회 가능`() {
        val admin = createUser(id = 9L, role = UserRole.ADMIN)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(9L) } returns Optional.of(admin)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { ticketRepository.findByEvent(event) } returns emptyList()

        val result = service.getEventCheckIns(viewerId = 9L, eventId = 100L)
        assertThat(result.eventId).isEqualTo(100L)
    }

    @Test
    fun `getEventCheckIns 권한 없으면 UnauthorizedException`() {
        val intruder = createUser(id = 9L, role = UserRole.PARTICIPANT)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(9L) } returns Optional.of(intruder)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { channelMemberRepository.existsByChannelAndUser(channel, intruder) } returns false

        assertThrows<UnauthorizedException> { service.getEventCheckIns(viewerId = 9L, eventId = 100L) }
    }

    // ── checkInByCode ─────────────────────────────────────────────────────────

    @Test
    fun `checkInByCode 정상 코드로 owner 가 체크인 성공`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { userRepository.findById(1L) } returns Optional.of(owner)

        val result = service.checkInByCode(viewerId = 1L, rawCode = "CONTENIDO-555-100")

        assertThat(result.ticketStatus).isEqualTo(TicketStatus.USED)
    }

    @Test
    fun `checkInByCode 코드 앞뒤 공백 허용 (trim)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { userRepository.findById(1L) } returns Optional.of(owner)

        val result = service.checkInByCode(viewerId = 1L, rawCode = "  CONTENIDO-555-100  ")
        assertThat(result.ticketStatus).isEqualTo(TicketStatus.USED)
    }

    @Test
    fun `checkInByCode 형식이 깨졌으면 InvalidCheckInCodeException`() {
        assertThrows<InvalidCheckInCodeException> {
            service.checkInByCode(viewerId = 1L, rawCode = "not-a-code")
        }
    }

    @Test
    fun `checkInByCode eventId 가 ticket 의 event 와 다르면 InvalidCheckInCodeException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { ticketRepository.findById(555L) } returns Optional.of(ticket)

        assertThrows<InvalidCheckInCodeException> {
            // 코드의 eventId 가 999 인데 실제 ticket.event.id 는 100 — mismatch.
            service.checkInByCode(viewerId = 1L, rawCode = "CONTENIDO-555-999")
        }
    }

    @Test
    fun `checkInByCode buyer 본인 코드 사용은 BuyerCannotCheckInException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { userRepository.findById(2L) } returns Optional.of(buyer)

        assertThrows<BuyerCannotCheckInException> {
            service.checkInByCode(viewerId = 2L, rawCode = "CONTENIDO-555-100")
        }
    }

    @Test
    fun `checkInByCode 이미 USED 면 TicketNotPaidException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val buyer = createUser(id = 2L)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.USED)

        every { ticketRepository.findById(555L) } returns Optional.of(ticket)
        every { userRepository.findById(1L) } returns Optional.of(owner)

        assertThrows<TicketNotPaidException> {
            service.checkInByCode(viewerId = 1L, rawCode = "CONTENIDO-555-100")
        }
    }

    @Test
    fun `checkInByCode 존재하지 않는 ticketId 는 InvalidCheckInCodeException`() {
        every { ticketRepository.findById(999L) } returns Optional.empty()

        assertThrows<InvalidCheckInCodeException> {
            service.checkInByCode(viewerId = 1L, rawCode = "CONTENIDO-999-100")
        }
    }

    @Test
    fun `checkInTicket 없는 ticketId 는 TicketNotFoundException`() {
        val viewer = createUser(id = 1L, role = UserRole.CREATOR)
        every { userRepository.findById(1L) } returns Optional.of(viewer)
        every { ticketRepository.findById(999L) } returns Optional.empty()

        assertThrows<TicketNotFoundException> { service.checkInTicket(viewerId = 1L, ticketId = 999L) }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun createUser(
            id: Long = 1L,
            role: UserRole = UserRole.PARTICIPANT,
            nickname: String = "user$id",
        ): User {
            val u = User("u$id@test.com", "encoded", nickname, "01012345678").apply { updateRole(role) }
            ReflectionTestUtils.setField(u, "id", id)
            ReflectionTestUtils.setField(u, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(u, "updatedAt", LocalDateTime.now())
            return u
        }

        fun createChannel(id: Long, owner: User): Channel {
            val c = Channel(owner, "채널$id", "설명", ChannelCategory.MUSIC)
            ReflectionTestUtils.setField(c, "id", id)
            ReflectionTestUtils.setField(c, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(c, "updatedAt", LocalDateTime.now())
            return c
        }

        fun createEvent(id: Long, channel: Channel): Event {
            val e = Event(
                channel = channel,
                title = "이벤트$id",
                description = "desc",
                location = "Seoul",
                mainImageUrl = "https://example.com/img.jpg",
                startAt = LocalDateTime.of(2026, 6, 15, 19, 0),
                endAt = LocalDateTime.of(2026, 6, 15, 21, 0),
                maxParticipants = 10,
                participationFee = 0L,
                refundPolicy = "환불 정책",
                detailContent = "detail",
                status = EventStatus.UPCOMING,
            )
            ReflectionTestUtils.setField(e, "id", id)
            ReflectionTestUtils.setField(e, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(e, "updatedAt", LocalDateTime.now())
            return e
        }

        fun createTicket(
            id: Long,
            event: Event,
            buyer: User,
            status: TicketStatus = TicketStatus.PAID,
            purchasedAt: LocalDateTime = LocalDateTime.now(),
        ): Ticket {
            val t = Ticket(event = event, buyer = buyer, price = event.participationFee, status = status)
            ReflectionTestUtils.setField(t, "id", id)
            ReflectionTestUtils.setField(t, "purchasedAt", purchasedAt)
            ReflectionTestUtils.setField(t, "updatedAt", purchasedAt)
            return t
        }
    }
}
