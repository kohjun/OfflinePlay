package com.contenido.domain.event.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.event.dto.CreateEventAnnouncementRequest
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventAnnouncement
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventAnnouncementRepository
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
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
 * PR141 — EventAnnouncementService 단위 테스트.
 *
 * 검증:
 *  - owner 가 공지를 작성하면 active 참가자에게 EVENT_ANNOUNCEMENT 알림이 발송된다.
 *  - non-owner 가 작성 시도하면 Unauthorized.
 *  - CANCELED / REJECTED participation 은 수신자에서 제외.
 *  - 유료 이벤트에서 ticket 이 CANCELED / REFUNDED 이면 제외, PAID/USED/PARTIALLY_REFUNDED 는 포함.
 */
@ExtendWith(MockKExtension::class)
class EventAnnouncementServiceTest {

    @MockK(relaxed = true) lateinit var announcementRepository: EventAnnouncementRepository
    @MockK(relaxed = true) lateinit var readRepository: com.contenido.domain.event.repository.EventAnnouncementReadRepository
    @MockK(relaxed = true) lateinit var imageRepository: com.contenido.domain.event.repository.EventAnnouncementImageRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var participationRepository: EventParticipationRepository
    @MockK lateinit var ticketRepository: TicketRepository
    @MockK lateinit var channelMemberRepository: ChannelMemberRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK(relaxed = true) lateinit var notificationService: NotificationService

    private lateinit var service: EventAnnouncementService

    @BeforeEach
    fun setUp() {
        service = EventAnnouncementService(
            announcementRepository = announcementRepository,
            readRepository = readRepository,
            imageRepository = imageRepository,
            eventRepository = eventRepository,
            participationRepository = participationRepository,
            ticketRepository = ticketRepository,
            channelMemberRepository = channelMemberRepository,
            userRepository = userRepository,
            notificationService = notificationService,
        )
    }

    @Test
    fun `owner 가 작성하면 APPROVED 참가자에게 EVENT_ANNOUNCEMENT 발송`() {
        val owner = createUser(1L)
        val channel = createChannel(owner = owner)
        val event = createEvent(channel = channel, fee = 0L)  // 무료 이벤트
        val p1 = createUser(10L)
        val p2 = createUser(11L)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(event.id) } returns Optional.of(event)
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns listOf(
            participation(event, p1, ParticipationStatus.APPROVED),
            participation(event, p2, ParticipationStatus.APPROVED),
        )
        val savedSlot = slot<EventAnnouncement>()
        every { announcementRepository.save(capture(savedSlot)) } answers {
            savedSlot.captured.also {
                ReflectionTestUtils.setField(it, "id", 555L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }

        val response = service.create(
            1L,
            event.id,
            CreateEventAnnouncementRequest(title = "안내", content = "내용"),
        )

        assertThat(response.id).isEqualTo(555L)
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = match { it.containsAll(listOf(10L, 11L)) && it.size == 2 },
                type = NotificationType.EVENT_ANNOUNCEMENT,
                title = "[공지] ${event.title}",
                message = "안내",
                targetType = "events",
                targetId = event.id,
            )
        }
    }

    @Test
    fun `non-owner 가 작성 시도하면 UnauthorizedException`() {
        val owner = createUser(1L)
        val stranger = createUser(99L)
        val channel = createChannel(owner = owner)
        val event = createEvent(channel = channel)
        every { userRepository.findById(99L) } returns Optional.of(stranger)
        every { eventRepository.findById(event.id) } returns Optional.of(event)
        every { channelMemberRepository.findByChannelAndUser(channel, stranger) } returns Optional.empty()

        assertThatThrownBy {
            service.create(99L, event.id, CreateEventAnnouncementRequest("t", "c"))
        }.isInstanceOf(UnauthorizedException::class.java)
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `STAFF 는 작성 가능`() {
        val owner = createUser(1L)
        val staff = createUser(2L)
        val channel = createChannel(owner = owner)
        val event = createEvent(channel = channel, fee = 0L)
        every { userRepository.findById(2L) } returns Optional.of(staff)
        every { eventRepository.findById(event.id) } returns Optional.of(event)
        every { channelMemberRepository.findByChannelAndUser(channel, staff) } returns Optional.of(
            ChannelMember(channel = channel, user = staff, role = ChannelMemberRole.STAFF),
        )
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns emptyList()
        every { announcementRepository.save(any()) } answers {
            firstArg<EventAnnouncement>().also {
                ReflectionTestUtils.setField(it, "id", 1L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }

        service.create(2L, event.id, CreateEventAnnouncementRequest("t", "c"))

        verify(exactly = 1) { announcementRepository.save(any()) }
    }

    @Test
    fun `CANCELED REJECTED 참가자는 수신에서 제외`() {
        val owner = createUser(1L)
        val channel = createChannel(owner = owner)
        val event = createEvent(channel = channel, fee = 0L)
        val approved = createUser(10L)
        val canceled = createUser(11L)
        val rejected = createUser(12L)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(event.id) } returns Optional.of(event)
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns listOf(
            participation(event, approved, ParticipationStatus.APPROVED),
            participation(event, canceled, ParticipationStatus.CANCELED),
            participation(event, rejected, ParticipationStatus.REJECTED),
        )
        every { announcementRepository.save(any()) } answers {
            firstArg<EventAnnouncement>().also {
                ReflectionTestUtils.setField(it, "id", 1L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }

        service.create(1L, event.id, CreateEventAnnouncementRequest("t", "c"))

        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = match { it == listOf(10L) },
                type = NotificationType.EVENT_ANNOUNCEMENT,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = event.id,
            )
        }
    }

    @Test
    fun `유료 이벤트 — CANCELED 또는 REFUNDED 티켓 보유자는 수신 제외`() {
        val owner = createUser(1L)
        val channel = createChannel(owner = owner)
        val event = createEvent(channel = channel, fee = 10_000L)  // 유료
        val paidUser = createUser(20L)
        val usedUser = createUser(21L)
        val partialUser = createUser(22L)
        val canceledUser = createUser(23L)
        val refundedUser = createUser(24L)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(event.id) } returns Optional.of(event)
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns listOf(
            paidUser, usedUser, partialUser, canceledUser, refundedUser,
        ).map { participation(event, it, ParticipationStatus.APPROVED) }
        every { ticketRepository.findByEventAndBuyerIdIn(event, any()) } returns listOf(
            ticket(event, paidUser, TicketStatus.PAID),
            ticket(event, usedUser, TicketStatus.USED),
            ticket(event, partialUser, TicketStatus.PARTIALLY_REFUNDED),
            ticket(event, canceledUser, TicketStatus.CANCELED),
            ticket(event, refundedUser, TicketStatus.REFUNDED),
        )
        every { announcementRepository.save(any()) } answers {
            firstArg<EventAnnouncement>().also {
                ReflectionTestUtils.setField(it, "id", 1L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }

        service.create(1L, event.id, CreateEventAnnouncementRequest("t", "c"))

        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = match { it.toSet() == setOf(20L, 21L, 22L) },
                type = NotificationType.EVENT_ANNOUNCEMENT,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = event.id,
            )
        }
    }

    @Test
    fun `list — APPROVED 참가자만 조회 가능, 그 외는 Unauthorized`() {
        val owner = createUser(1L)
        val stranger = createUser(99L)
        val channel = createChannel(owner = owner)
        val event = createEvent(channel = channel, fee = 0L)
        every { userRepository.findById(99L) } returns Optional.of(stranger)
        every { eventRepository.findById(event.id) } returns Optional.of(event)
        every { channelMemberRepository.findByChannelAndUser(channel, stranger) } returns Optional.empty()
        every { participationRepository.findByEventAndParticipant(event, stranger) } returns Optional.empty()

        assertThatThrownBy { service.list(99L, event.id) }
            .isInstanceOf(UnauthorizedException::class.java)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun createUser(id: Long, role: UserRole = UserRole.PARTICIPANT): User =
        User("u$id@test.com", "pwd", "nick$id", "010-0000-000$id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "role", role)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun createChannel(id: Long = 100L, owner: User): Channel =
        Channel(owner, "ch-$id", "desc", ChannelCategory.MUSIC).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun createEvent(
        id: Long = 200L,
        channel: Channel,
        fee: Long = 0L,
    ): Event = Event(
        channel = channel,
        title = "ev-$id",
        description = "d",
        location = "l",
        mainImageUrl = "img",
        startAt = LocalDateTime.now().plusDays(1),
        endAt = LocalDateTime.now().plusDays(2),
        maxParticipants = 100,
        participationFee = fee,
        refundPolicy = "rp",
        detailContent = "dc",
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
    }

    private fun participation(
        event: Event,
        participant: User,
        status: ParticipationStatus,
    ): EventParticipation = EventParticipation(event = event, participant = participant).apply {
        this.status = status
        ReflectionTestUtils.setField(this, "id", participant.id * 1000)
    }

    private fun ticket(event: Event, buyer: User, status: TicketStatus): Ticket =
        Ticket(event = event, buyer = buyer, price = event.participationFee, status = status).apply {
            ReflectionTestUtils.setField(this, "id", buyer.id * 10)
            ReflectionTestUtils.setField(this, "purchasedAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }
}
