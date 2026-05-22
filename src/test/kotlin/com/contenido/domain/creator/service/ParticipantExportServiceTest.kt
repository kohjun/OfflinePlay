package com.contenido.domain.creator.service

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.service.ModerationAuditLogService
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.payment.entity.PaymentAttempt
import com.contenido.domain.payment.entity.PaymentStatus
import com.contenido.domain.payment.repository.PaymentAttemptRepository
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UnauthorizedException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR154 — ParticipantExportService 단위 테스트.
 *  - 권한 가드 (owner OK, non-owner 403, STAFF OK, ADMIN OK)
 *  - 빈 신청자 → 헤더만
 *  - phone masking
 *  - audit row 생성
 */
@ExtendWith(MockKExtension::class)
class ParticipantExportServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var participationRepository: EventParticipationRepository
    @MockK lateinit var ticketRepository: TicketRepository
    @MockK lateinit var paymentAttemptRepository: PaymentAttemptRepository
    @MockK lateinit var channelMemberRepository: ChannelMemberRepository
    @MockK(relaxed = true) lateinit var auditLogService: ModerationAuditLogService

    private lateinit var service: ParticipantExportService

    @BeforeEach
    fun setUp() {
        service = ParticipantExportService(
            userRepository = userRepository,
            eventRepository = eventRepository,
            participationRepository = participationRepository,
            ticketRepository = ticketRepository,
            paymentAttemptRepository = paymentAttemptRepository,
            channelMemberRepository = channelMemberRepository,
            auditLogService = auditLogService,
        )
    }

    @Test
    fun `owner — 빈 신청자도 헤더만 반환`() {
        val owner = user(1L)
        val channel = channel(10L, owner)
        val event = event(100L, channel)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns emptyList()

        val csv = service.exportCsv(1L, 100L)

        assertThat(csv.trim()).isEqualTo(ParticipantExportService.HEADER)
        verify(exactly = 1) {
            auditLogService.record(
                actorId = 1L,
                action = ModerationAuditAction.PARTICIPANT_EXPORTED,
                targetType = any(),
                targetId = any(),
                beforeValue = any(),
                afterValue = any(),
                reason = any(),
            )
        }
    }

    @Test
    fun `participants 행에 phone masking + paid amount`() {
        val owner = user(1L)
        val channel = channel(10L, owner)
        val event = event(100L, channel)
        val buyer = user(20L, phone = "01012345678")
        val participation = participation(event, buyer, ParticipationStatus.APPROVED)
        val ticket = ticket(event, buyer, TicketStatus.PAID).apply {
            ReflectionTestUtils.setField(this, "id", 50L)
        }
        val attempt = attempt(event, buyer, ticket, amount = 25_000L, refunded = 0L)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns listOf(participation)
        every { ticketRepository.findByEventAndBuyerIdIn(event, listOf(20L)) } returns listOf(ticket)
        every { paymentAttemptRepository.findByTicketIn(any()) } returns listOf(attempt)

        val csv = service.exportCsv(1L, 100L)

        val lines = csv.lines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).isEqualTo(ParticipantExportService.HEADER)
        val cells = lines[1].split(",")
        assertThat(cells[0]).isEqualTo("20")           // participantId
        assertThat(cells[1]).isEqualTo("닉네임20")      // nickname
        assertThat(cells[2]).isEqualTo("010-****-5678") // phoneMasked
        assertThat(cells[3]).isEqualTo("APPROVED")
        assertThat(cells[4]).isEqualTo("PAID")
        assertThat(cells[5]).isEqualTo("25000")
        assertThat(cells[6]).isEqualTo("0")
    }

    @Test
    fun `non-owner non-STAFF non-ADMIN 은 UnauthorizedException`() {
        val owner = user(1L)
        val stranger = user(99L)
        val channel = channel(10L, owner)
        val event = event(100L, channel)
        every { userRepository.findById(99L) } returns Optional.of(stranger)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { channelMemberRepository.findByChannelAndUser(channel, stranger) } returns Optional.empty()

        assertThatThrownBy { service.exportCsv(99L, 100L) }
            .isInstanceOf(UnauthorizedException::class.java)
    }

    @Test
    fun `STAFF 허용`() {
        val owner = user(1L)
        val staff = user(2L)
        val channel = channel(10L, owner)
        val event = event(100L, channel)
        every { userRepository.findById(2L) } returns Optional.of(staff)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { channelMemberRepository.findByChannelAndUser(channel, staff) } returns Optional.of(
            ChannelMember(channel = channel, user = staff, role = ChannelMemberRole.STAFF),
        )
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns emptyList()

        val csv = service.exportCsv(2L, 100L)

        assertThat(csv).contains(ParticipantExportService.HEADER)
    }

    @Test
    fun `ADMIN 허용`() {
        val owner = user(1L)
        val admin = user(99L, role = UserRole.ADMIN)
        val channel = channel(10L, owner)
        val event = event(100L, channel)
        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns emptyList()

        val csv = service.exportCsv(99L, 100L)

        assertThat(csv).contains(ParticipantExportService.HEADER)
    }

    @Test
    fun `audit row 가 PARTICIPANT_EXPORTED 액션으로 1건 기록`() {
        val owner = user(1L)
        val channel = channel(10L, owner)
        val event = event(100L, channel)
        val buyer = user(20L)
        val participation = participation(event, buyer, ParticipationStatus.APPROVED)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns listOf(participation)
        every { ticketRepository.findByEventAndBuyerIdIn(any(), any()) } returns emptyList()
        every { paymentAttemptRepository.findByTicketIn(any()) } returns emptyList()
        val afterSlot = slot<Any>()
        every {
            auditLogService.record(
                actorId = any(),
                action = ModerationAuditAction.PARTICIPANT_EXPORTED,
                targetType = any(),
                targetId = any(),
                beforeValue = any(),
                afterValue = capture(afterSlot),
                reason = any(),
            )
        } returns io.mockk.mockk(relaxed = true)

        service.exportCsv(1L, 100L)

        val captured = afterSlot.captured
        assertThat(captured).isInstanceOf(Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val asMap = captured as Map<String, Any>
        assertThat(asMap["eventId"]).isEqualTo(100L)
        assertThat(asMap["channelId"]).isEqualTo(10L)
        assertThat(asMap["exportedRowCount"]).isEqualTo(1)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun user(id: Long, role: UserRole = UserRole.PARTICIPANT, phone: String = "01000000$id"): User =
        User("u$id@test.com", "pwd", "닉네임$id", phone).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "role", role)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun channel(id: Long, owner: User): Channel =
        Channel(owner, "ch-$id", "desc", ChannelCategory.MUSIC).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun event(id: Long, channel: Channel): Event = Event(
        channel = channel,
        title = "ev",
        description = "d",
        location = "l",
        mainImageUrl = "img",
        startAt = LocalDateTime.now().plusDays(1),
        endAt = LocalDateTime.now().plusDays(2),
        maxParticipants = 100,
        participationFee = 25_000L,
        refundPolicy = "rp",
        detailContent = "dc",
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
    }

    private fun participation(event: Event, participant: User, status: ParticipationStatus): EventParticipation =
        EventParticipation(event = event, participant = participant).apply {
            this.status = status
            ReflectionTestUtils.setField(this, "id", participant.id * 1000)
        }

    private fun ticket(event: Event, buyer: User, status: TicketStatus): Ticket =
        Ticket(event = event, buyer = buyer, price = event.participationFee, status = status).apply {
            ReflectionTestUtils.setField(this, "purchasedAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun attempt(
        event: Event,
        buyer: User,
        ticket: Ticket,
        amount: Long,
        refunded: Long,
    ): PaymentAttempt = PaymentAttempt(
        event = event,
        buyer = buyer,
        idempotencyKey = "key-${ticket.id}",
        amount = amount,
        status = PaymentStatus.PAID,
        ticket = ticket,
        refundedAmount = refunded,
    ).apply {
        ReflectionTestUtils.setField(this, "id", ticket.id * 10)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
    }
}
