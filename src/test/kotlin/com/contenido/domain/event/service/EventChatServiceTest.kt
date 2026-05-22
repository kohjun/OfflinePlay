package com.contenido.domain.event.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.event.dto.SendEventChatMessageRequest
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventChatMessage
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventChatMessageRepository
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.notification.service.SseEmitterService
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.EventChatAnnouncementForbiddenException
import com.contenido.global.exception.EventRoomAccessDeniedException
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
 * PR160 — EventChatService 단위 테스트.
 *  - 권한 가드 (owner OK / APPROVED+ticket OK / non-participant 403 / CANCELED ticket 403)
 *  - 일반 메시지: SSE broadcast, push 없음
 *  - 공지 메시지: owner 만 가능, push 발송 (본인 제외)
 *  - 일반 사용자가 isAnnouncement=true → 403
 */
@ExtendWith(MockKExtension::class)
class EventChatServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var participationRepository: EventParticipationRepository
    @MockK lateinit var ticketRepository: TicketRepository
    @MockK lateinit var channelMemberRepository: ChannelMemberRepository
    @MockK(relaxed = true) lateinit var chatRepository: EventChatMessageRepository
    @MockK(relaxed = true) lateinit var notificationService: NotificationService
    @MockK(relaxed = true) lateinit var sseEmitterService: SseEmitterService

    private lateinit var service: EventChatService

    @BeforeEach
    fun setUp() {
        service = EventChatService(
            userRepository = userRepository,
            eventRepository = eventRepository,
            participationRepository = participationRepository,
            ticketRepository = ticketRepository,
            channelMemberRepository = channelMemberRepository,
            chatRepository = chatRepository,
            notificationService = notificationService,
            sseEmitterService = sseEmitterService,
        )
    }

    @Test
    fun `owner 는 무조건 입장 가능 + 일반 메시지 broadcast 만 push 없음`() {
        val owner = user(1L)
        val participant = user(10L)
        val channel = channel(100L, owner)
        val event = event(1000L, channel, fee = 0L)
        stubBasic(owner, event)
        stubRoomMembers(event, owner, listOf(participant))
        every { chatRepository.save(any()) } answers {
            firstArg<EventChatMessage>().also {
                ReflectionTestUtils.setField(it, "id", 50L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
            }
        }

        val response = service.send(1L, 1000L, SendEventChatMessageRequest(content = "안녕"))

        assertThat(response.content).isEqualTo("안녕")
        assertThat(response.isAnnouncement).isFalse()
        verify(exactly = 1) { sseEmitterService.broadcast(any(), "event-chat", any()) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `APPROVED 참가자 + 무료 이벤트 — 일반 메시지 송신 가능`() {
        val owner = user(1L)
        val participant = user(10L)
        val channel = channel(100L, owner)
        val event = event(1000L, channel, fee = 0L)
        stubBasic(participant, event)
        every { channelMemberRepository.findByChannelAndUser(channel, participant) } returns Optional.empty()
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(
                event, participant, listOf(ParticipationStatus.APPROVED),
            )
        } returns true
        stubRoomMembers(event, owner, listOf(participant))
        every { chatRepository.save(any()) } answers {
            firstArg<EventChatMessage>().also {
                ReflectionTestUtils.setField(it, "id", 51L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
            }
        }

        val response = service.send(10L, 1000L, SendEventChatMessageRequest(content = "잘 부탁해요"))

        assertThat(response.senderId).isEqualTo(10L)
    }

    @Test
    fun `PENDING 참가자는 입장 거부`() {
        val owner = user(1L)
        val stranger = user(99L)
        val channel = channel(100L, owner)
        val event = event(1000L, channel, fee = 0L)
        stubBasic(stranger, event)
        every { channelMemberRepository.findByChannelAndUser(channel, stranger) } returns Optional.empty()
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(
                event, stranger, listOf(ParticipationStatus.APPROVED),
            )
        } returns false

        assertThatThrownBy {
            service.send(99L, 1000L, SendEventChatMessageRequest(content = "끼워줘"))
        }.isInstanceOf(EventRoomAccessDeniedException::class.java)
    }

    @Test
    fun `유료 이벤트 — APPROVED 인데 ticket CANCELED 면 입장 거부`() {
        val owner = user(1L)
        val participant = user(10L)
        val channel = channel(100L, owner)
        val event = event(1000L, channel, fee = 25_000L)
        stubBasic(participant, event)
        every { channelMemberRepository.findByChannelAndUser(channel, participant) } returns Optional.empty()
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(
                event, participant, listOf(ParticipationStatus.APPROVED),
            )
        } returns true
        every { ticketRepository.findByEventAndBuyerIdIn(event, listOf(10L)) } returns listOf(
            ticket(event, participant, TicketStatus.CANCELED),
        )

        assertThatThrownBy {
            service.send(10L, 1000L, SendEventChatMessageRequest(content = "왜 안 돼"))
        }.isInstanceOf(EventRoomAccessDeniedException::class.java)
    }

    @Test
    fun `유료 이벤트 — APPROVED + PAID ticket 은 입장 OK`() {
        val owner = user(1L)
        val participant = user(10L)
        val channel = channel(100L, owner)
        val event = event(1000L, channel, fee = 25_000L)
        stubBasic(participant, event)
        every { channelMemberRepository.findByChannelAndUser(channel, participant) } returns Optional.empty()
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(
                event, participant, listOf(ParticipationStatus.APPROVED),
            )
        } returns true
        every { ticketRepository.findByEventAndBuyerIdIn(event, listOf(10L)) } returns listOf(
            ticket(event, participant, TicketStatus.PAID),
        )
        stubRoomMembers(event, owner, listOf(participant))
        every { chatRepository.save(any()) } answers {
            firstArg<EventChatMessage>().also {
                ReflectionTestUtils.setField(it, "id", 60L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
            }
        }

        val response = service.send(10L, 1000L, SendEventChatMessageRequest(content = "결제했어요"))
        assertThat(response.senderId).isEqualTo(10L)
    }

    @Test
    fun `일반 참가자가 isAnnouncement=true 시도하면 EventChatAnnouncementForbiddenException`() {
        val owner = user(1L)
        val participant = user(10L)
        val channel = channel(100L, owner)
        val event = event(1000L, channel, fee = 0L)
        stubBasic(participant, event)
        every { channelMemberRepository.findByChannelAndUser(channel, participant) } returns Optional.empty()
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(
                event, participant, listOf(ParticipationStatus.APPROVED),
            )
        } returns true

        assertThatThrownBy {
            service.send(10L, 1000L, SendEventChatMessageRequest(content = "공지!", isAnnouncement = true))
        }.isInstanceOf(EventChatAnnouncementForbiddenException::class.java)
    }

    @Test
    fun `owner 공지 메시지 — SSE broadcast + push 발송 (본인 제외)`() {
        val owner = user(1L)
        val p1 = user(10L)
        val p2 = user(11L)
        val channel = channel(100L, owner)
        val event = event(1000L, channel, fee = 0L)
        stubBasic(owner, event)
        stubRoomMembers(event, owner, listOf(p1, p2))
        every { chatRepository.save(any()) } answers {
            firstArg<EventChatMessage>().also {
                ReflectionTestUtils.setField(it, "id", 70L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
            }
        }
        val receiversSlot = slot<List<Long>>()
        every {
            notificationService.notify(
                receiverIds = capture(receiversSlot),
                type = NotificationType.EVENT_ANNOUNCEMENT,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = event.id,
            )
        } returns Unit

        service.send(1L, 1000L, SendEventChatMessageRequest(content = "공연 시간 변경", isAnnouncement = true))

        // 본인(owner id=1) 은 제외, p1·p2 만 push 수신
        assertThat(receiversSlot.captured).containsExactlyInAnyOrder(10L, 11L)
        verify(exactly = 1) { sseEmitterService.broadcast(any(), "event-chat", any()) }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun stubBasic(actor: User, event: Event) {
        every { userRepository.findById(actor.id) } returns Optional.of(actor)
        every { eventRepository.findById(event.id) } returns Optional.of(event)
    }

    /**
     * `roomMemberUserIds` 가 owner + STAFF(없음) + APPROVED 참가자 묶음을 계산할 때 필요한 stub 들.
     */
    private fun stubRoomMembers(event: Event, owner: User, approvedParticipants: List<User>) {
        every { channelMemberRepository.findByChannel(event.channel) } returns emptyList()
        every { participationRepository.findByEventOrderByJoinedAtDesc(event) } returns
            approvedParticipants.map { participation(event, it, ParticipationStatus.APPROVED) }
        if (event.participationFee > 0L && approvedParticipants.isNotEmpty()) {
            every {
                ticketRepository.findByEventAndBuyerIdIn(event, approvedParticipants.map { it.id })
            } returns approvedParticipants.map { ticket(event, it, TicketStatus.PAID) }
        }
    }

    private fun user(id: Long, role: UserRole = UserRole.PARTICIPANT): User =
        User("u$id@test.com", "pwd", "닉네임$id", "01000000$id").apply {
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

    private fun event(id: Long, channel: Channel, fee: Long): Event = Event(
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

    private fun participation(event: Event, participant: User, status: ParticipationStatus): EventParticipation =
        EventParticipation(event = event, participant = participant).apply {
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
