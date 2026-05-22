package com.contenido.domain.event.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.entity.ChannelSubscription
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.event.dto.CreateEventRequest
import com.contenido.domain.event.dto.UpdateEventRequest
import com.contenido.domain.event.entity.ContentType
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.ticket.service.TicketService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.event.ContentSyncEvent
import com.contenido.global.exception.*
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockKExtension::class)
class EventServiceTest {

    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var eventParticipationRepository: EventParticipationRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var channelMemberRepository: ChannelMemberRepository
    @MockK lateinit var channelSubscriptionRepository: ChannelSubscriptionRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var notificationService: NotificationService
    @MockK lateinit var ticketService: TicketService
    @MockK lateinit var ticketRepository: TicketRepository
    @MockK lateinit var paymentAttemptRepository: com.contenido.domain.payment.repository.PaymentAttemptRepository
    @MockK lateinit var reviewRepository: com.contenido.domain.review.repository.ReviewRepository
    @MockK(relaxed = true) lateinit var regionRepository: com.contenido.domain.region.repository.RegionRepository
    @MockK(relaxed = true) lateinit var interestRepository: com.contenido.domain.interest.repository.InterestRepository
    @MockK(relaxed = true) lateinit var eventInterestRepository: com.contenido.domain.interest.repository.EventInterestRepository
    @MockK lateinit var publisher: ApplicationEventPublisher

    private lateinit var eventService: EventService

    @BeforeEach
    fun setUp() {
        eventService = EventService(
            eventRepository = eventRepository,
            eventParticipationRepository = eventParticipationRepository,
            channelRepository = channelRepository,
            channelMemberRepository = channelMemberRepository,
            channelSubscriptionRepository = channelSubscriptionRepository,
            userRepository = userRepository,
            notificationService = notificationService,
            ticketService = ticketService,
            ticketRepository = ticketRepository,
            paymentAttemptRepository = paymentAttemptRepository,
            reviewRepository = reviewRepository,
            regionRepository = regionRepository,
            interestRepository = interestRepository,
            eventInterestRepository = eventInterestRepository,
            publisher = publisher,
        )
        // PR47: 모든 EventResponse-반환 경로가 rating 을 조회한다. 후기 0건 기본 stub.
        every { reviewRepository.averageRatingByEventId(any()) } returns null
        every { reviewRepository.countByEvent(any()) } returns 0L
        every { reviewRepository.aggregateByEventIds(any()) } returns emptyList()
        every { notificationService.notify(any(), any(), any(), any(), any(), any()) } just Runs
        every { publisher.publishEvent(any<ContentSyncEvent>()) } just Runs
        every { channelSubscriptionRepository.findByChannel(any()) } returns emptyList()
        // STAFF 가 없는 채널이 기본. STAFF 전파 테스트만 개별 stub.
        every { channelMemberRepository.findByChannel(any()) } returns emptyList()
        every { ticketService.issueFreeTicket(any(), any()) } returns 1L
    }

    // ── createEvent ───────────────────────────────────────────────────────────

    @Test
    fun `createEvent 성공`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val request = createEventRequest()
        val savedEvent = createEvent(id = 1L, channel = channel)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(1L) } returns Optional.of(channel)
        every { eventRepository.save(any()) } returns savedEvent

        val result = eventService.createEvent(1L, 1L, request)

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.channelId).isEqualTo(1L)
        assertThat(result.channelOwnerId).isEqualTo(1L)
        assertThat(result.title).isEqualTo("Test Event")
        verify {
            publisher.publishEvent(match<ContentSyncEvent> {
                it.sourceType == "EVENT" && it.entityId == savedEvent.id
            })
        }
    }

    @Test
    fun `createEvent 채널 소유자 아님 예외`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val otherUser = createUser(id = 2L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)

        every { userRepository.findById(2L) } returns Optional.of(otherUser)
        every { channelRepository.findById(1L) } returns Optional.of(channel)

        assertThrows<UnauthorizedException> {
            eventService.createEvent(2L, 1L, createEventRequest())
        }
    }

    @Test
    fun `createEvent endAt 이 startAt 보다 빠르면 InvalidEventDateRangeException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val now = LocalDateTime.now()
        val request = createEventRequest().copy(
            startAt = now.plusDays(2),
            endAt = now.plusDays(1),
        )

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(1L) } returns Optional.of(channel)

        assertThrows<InvalidEventDateRangeException> {
            eventService.createEvent(1L, 1L, request)
        }
        verify(exactly = 0) { eventRepository.save(any()) }
    }

    // ── cloneEvent (PR155) ───────────────────────────────────────────────────

    @Test
    fun `cloneEvent — owner 가 새 시각으로 복제하면 원본 metadata 보존`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val source = createEvent(id = 100L, channel = channel).apply {
            title = "원본 이벤트"
            participationFee = 25_000L
            maxParticipants = 50
        }
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(source)
        val savedSlot = slot<Event>()
        every { eventRepository.save(capture(savedSlot)) } answers {
            savedSlot.captured.also {
                ReflectionTestUtils.setField(it, "id", 999L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }
        every { channelSubscriptionRepository.findByChannel(channel) } returns emptyList()

        val newStart = LocalDateTime.now().plusDays(30)
        val newEnd = newStart.plusHours(3)
        val result = eventService.cloneEvent(
            1L, 100L,
            com.contenido.domain.event.dto.CloneEventRequest(startAt = newStart, endAt = newEnd),
        )

        assertThat(result.id).isEqualTo(999L)
        assertThat(savedSlot.captured.title).isEqualTo("원본 이벤트")
        assertThat(savedSlot.captured.participationFee).isEqualTo(25_000L)
        assertThat(savedSlot.captured.maxParticipants).isEqualTo(50)
        assertThat(savedSlot.captured.startAt).isEqualTo(newStart)
        assertThat(savedSlot.captured.endAt).isEqualTo(newEnd)
        // currentParticipants 는 0 reset
        assertThat(savedSlot.captured.currentParticipants).isEqualTo(0)
    }

    @Test
    fun `cloneEvent — non-owner non-ADMIN 은 Unauthorized`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val stranger = createUser(id = 99L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val source = createEvent(id = 100L, channel = channel)
        every { userRepository.findById(99L) } returns Optional.of(stranger)
        every { eventRepository.findById(100L) } returns Optional.of(source)

        assertThrows<UnauthorizedException> {
            eventService.cloneEvent(
                99L, 100L,
                com.contenido.domain.event.dto.CloneEventRequest(
                    startAt = LocalDateTime.now().plusDays(30),
                    endAt = LocalDateTime.now().plusDays(31),
                ),
            )
        }
        verify(exactly = 0) { eventRepository.save(any()) }
    }

    @Test
    fun `cloneEvent — endAt 이 startAt 보다 빠르면 InvalidEventDateRangeException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val source = createEvent(id = 100L, channel = channel)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(source)

        val now = LocalDateTime.now()
        assertThrows<InvalidEventDateRangeException> {
            eventService.cloneEvent(
                1L, 100L,
                com.contenido.domain.event.dto.CloneEventRequest(
                    startAt = now.plusDays(10),
                    endAt = now.plusDays(5),
                ),
            )
        }
        verify(exactly = 0) { eventRepository.save(any()) }
    }

    @Test
    fun `cloneEvent — ADMIN 도 복제 가능`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val channel = createChannel(id = 1L, owner = owner)
        val source = createEvent(id = 100L, channel = channel)
        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { eventRepository.findById(100L) } returns Optional.of(source)
        every { eventRepository.save(any()) } answers {
            firstArg<Event>().also {
                ReflectionTestUtils.setField(it, "id", 999L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
                ReflectionTestUtils.setField(it, "updatedAt", LocalDateTime.now())
            }
        }
        every { channelSubscriptionRepository.findByChannel(channel) } returns emptyList()

        val result = eventService.cloneEvent(
            99L, 100L,
            com.contenido.domain.event.dto.CloneEventRequest(
                startAt = LocalDateTime.now().plusDays(30),
                endAt = LocalDateTime.now().plusDays(31),
            ),
        )

        assertThat(result.id).isEqualTo(999L)
    }

    @Test
    fun `createEvent NEW_EVENT 알림 — 구독자에게 발송 (PR142)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val subscriberA = createUser(id = 10L, role = UserRole.PARTICIPANT)
        val subscriberB = createUser(id = 11L, role = UserRole.PARTICIPANT)
        val channel = createChannel(id = 1L, owner = owner)
        val savedEvent = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(1L) } returns Optional.of(channel)
        every { eventRepository.save(any()) } returns savedEvent
        every { channelSubscriptionRepository.findByChannel(channel) } returns listOf(
            ChannelSubscription(subscriber = subscriberA, channel = channel),
            ChannelSubscription(subscriber = subscriberB, channel = channel),
        )

        eventService.createEvent(1L, 1L, createEventRequest())

        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = match { it.toSet() == setOf(10L, 11L) },
                type = NotificationType.NEW_EVENT,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = 100L,
            )
        }
    }

    @Test
    fun `createEvent NEW_EVENT — 채널 owner 가 자기 채널 구독자에 포함돼도 본인은 제외 (PR142)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val realSubscriber = createUser(id = 10L, role = UserRole.PARTICIPANT)
        val channel = createChannel(id = 1L, owner = owner)
        val savedEvent = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(1L) } returns Optional.of(channel)
        every { eventRepository.save(any()) } returns savedEvent
        // owner 가 본인 채널을 구독한 비정상 상태 (방어 코드 검증).
        every { channelSubscriptionRepository.findByChannel(channel) } returns listOf(
            ChannelSubscription(subscriber = owner, channel = channel),
            ChannelSubscription(subscriber = realSubscriber, channel = channel),
        )

        eventService.createEvent(1L, 1L, createEventRequest())

        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = match { it == listOf(10L) },
                type = NotificationType.NEW_EVENT,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = 100L,
            )
        }
    }

    @Test
    fun `createEvent NEW_EVENT — 구독자가 없으면 빈 receiver 목록으로 notify (NotificationService 가 즉시 return)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val savedEvent = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(1L) } returns Optional.of(channel)
        every { eventRepository.save(any()) } returns savedEvent
        every { channelSubscriptionRepository.findByChannel(channel) } returns emptyList()

        eventService.createEvent(1L, 1L, createEventRequest())

        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = match { it.isEmpty() },
                type = NotificationType.NEW_EVENT,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = 100L,
            )
        }
    }

    @Test
    fun `createEvent contentType 미지정이면 SPECIAL 로 보정 저장`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val request = createEventRequest().copy(contentType = null)
        val captured = slot<Event>()

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(1L) } returns Optional.of(channel)
        every { eventRepository.save(capture(captured)) } answers {
            val arg = firstArg<Event>()
            ReflectionTestUtils.setField(arg, "id", 99L)
            ReflectionTestUtils.setField(arg, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(arg, "updatedAt", LocalDateTime.now())
            arg
        }

        eventService.createEvent(1L, 1L, request)

        // null 이면 service 가 ContentType.SPECIAL 로 보정.
        assertThat(captured.captured.contentType).isEqualTo(ContentType.SPECIAL)
    }

    // ── updateEvent ───────────────────────────────────────────────────────────

    @Test
    fun `updateEvent owner 가 title content location 등 변경 성공`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { ticketRepository.findByEvent(event) } returns emptyList()

        val result = eventService.updateEvent(
            userId = 1L,
            eventId = 100L,
            request = UpdateEventRequest(
                title = "새 제목",
                location = "새 장소",
                contentType = ContentType.CLASSIC,
            ),
        )

        assertThat(result.title).isEqualTo("새 제목")
        assertThat(event.title).isEqualTo("새 제목")
        assertThat(event.location).isEqualTo("새 장소")
        assertThat(event.contentType).isEqualTo(ContentType.CLASSIC)
    }

    @Test
    fun `updateEvent owner 가 아니고 ADMIN 도 아니면 UnauthorizedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val intruder = createUser(id = 2L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(2L) } returns Optional.of(intruder)
        every { eventRepository.findById(100L) } returns Optional.of(event)

        assertThrows<UnauthorizedException> {
            eventService.updateEvent(2L, 100L, UpdateEventRequest(title = "x"))
        }
    }

    @Test
    fun `updateEvent ADMIN 도 수정 가능`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val admin = createUser(id = 9L, role = UserRole.ADMIN)
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)

        every { userRepository.findById(9L) } returns Optional.of(admin)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { ticketRepository.findByEvent(event) } returns emptyList()

        val result = eventService.updateEvent(9L, 100L, UpdateEventRequest(title = "admin edit"))
        assertThat(result.title).isEqualTo("admin edit")
    }

    @Test
    fun `updateEvent endAt 이 startAt 보다 빠르면 InvalidEventDateRangeException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val now = LocalDateTime.now()

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)

        assertThrows<InvalidEventDateRangeException> {
            eventService.updateEvent(
                1L, 100L,
                UpdateEventRequest(startAt = now.plusDays(2), endAt = now.plusDays(1)),
            )
        }
    }

    @Test
    fun `updateEvent maxParticipants 가 currentParticipants 보다 작으면 거부`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 100L, channel = channel, maxParticipants = 10, currentParticipants = 5)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)

        assertThrows<MaxParticipantsBelowCurrentException> {
            eventService.updateEvent(1L, 100L, UpdateEventRequest(maxParticipants = 3))
        }
        // 정원 변하지 않아야 한다.
        assertThat(event.maxParticipants).isEqualTo(10)
    }

    @Test
    fun `updateEvent participationFee 변경은 이미 발급된 티켓이 있으면 EventHasIssuedTicketsException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val buyer = createUser(id = 2L)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { ticketRepository.findByEvent(event) } returns listOf(ticket)

        assertThrows<EventHasIssuedTicketsException> {
            eventService.updateEvent(1L, 100L, UpdateEventRequest(participationFee = 5000L))
        }
        assertThat(event.participationFee).isEqualTo(0L)
    }

    @Test
    fun `updateEvent 동일한 participationFee 는 티켓이 있어도 허용`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        val buyer = createUser(id = 2L)
        val ticket = createTicket(id = 555L, event = event, buyer = buyer, status = TicketStatus.PAID)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { ticketRepository.findByEvent(event) } returns listOf(ticket)

        // 기존 participationFee=0 으로 그대로 보내면 변경 없음 — 통과.
        eventService.updateEvent(1L, 100L, UpdateEventRequest(participationFee = 0L, title = "ok"))
        assertThat(event.title).isEqualTo("ok")
    }

    // ── PR47: getEvent / getEvents 가 ReviewRepository 집계를 응답에 채운다 ──

    @Test
    fun `getEvent 단건 응답에 averageRating + reviewCount 가 채워진다`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 200L, channel = createChannel(id = 10L, owner = owner))

        every { eventRepository.findById(200L) } returns Optional.of(event)
        // setUp 의 기본 stub 은 null/0 — 이 케이스만 실제 값으로 override.
        every { reviewRepository.averageRatingByEventId(200L) } returns 4.6
        every { reviewRepository.countByEvent(event) } returns 17L

        val response = eventService.getEvent(200L)

        assertThat(response.averageRating).isEqualTo(4.6)
        assertThat(response.reviewCount).isEqualTo(17L)
    }

    @Test
    fun `getEvent 후기 0건이면 averageRating=null + reviewCount=0`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 201L, channel = createChannel(id = 11L, owner = owner))

        every { eventRepository.findById(201L) } returns Optional.of(event)
        // 기본 stub 그대로 (null / 0L).

        val response = eventService.getEvent(201L)

        assertThat(response.averageRating).isNull()
        assertThat(response.reviewCount).isZero()
    }

    @Test
    fun `getEvents 목록은 aggregateByEventIds batch 한 번으로 rating 매핑한다 (N+1 회피)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val e1 = createEvent(id = 100L, channel = channel)
        val e2 = createEvent(id = 101L, channel = channel)
        val e3 = createEvent(id = 102L, channel = channel)

        every { channelRepository.findById(10L) } returns Optional.of(channel)
        // PR51 — getEvents 가 hidden 제외 버전 사용.
        every {
            eventRepository.findByChannelAndHiddenAtIsNullOrderByStartAtDesc(channel, any())
        } returns PageImpl(listOf(e1, e2, e3), PageRequest.of(0, 20), 3)
        // batch: e1=4.0(5건), e2=2.0(3건), e3 은 후기 0건이라 결과 미포함.
        every { reviewRepository.aggregateByEventIds(listOf(100L, 101L, 102L)) } returns listOf(
            arrayOf<Any>(100L, 4.0, 5L),
            arrayOf<Any>(101L, 2.0, 3L),
        )

        val page = eventService.getEvents(10L, 0, 20)

        assertThat(page.content).hasSize(3)
        assertThat(page.content[0].averageRating).isEqualTo(4.0)
        assertThat(page.content[0].reviewCount).isEqualTo(5L)
        assertThat(page.content[1].averageRating).isEqualTo(2.0)
        assertThat(page.content[1].reviewCount).isEqualTo(3L)
        // e3 은 결과에 없으므로 default (null / 0).
        assertThat(page.content[2].averageRating).isNull()
        assertThat(page.content[2].reviewCount).isZero()
        // N+1 회피 검증 — 개별 averageRatingByEventId 호출이 없어야 한다.
        verify(exactly = 0) { reviewRepository.averageRatingByEventId(any()) }
    }

    // ── applyForEvent (PENDING) ───────────────────────────────────────────────

    @Test
    fun `applyForEvent 성공 시 PENDING 으로 저장되고 currentParticipants 는 그대로 + owner 에게 알림`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR, nickname = "기획자")
        val participant = createUser(id = 2L, nickname = "참가자")
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val captured = slot<EventParticipation>()

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.empty()
        every { eventParticipationRepository.save(capture(captured)) } answers {
            val arg = firstArg<EventParticipation>()
            ReflectionTestUtils.setField(arg, "id", 11L)
            arg
        }

        val result = eventService.applyForEvent(2L, 1L)

        assertThat(result.status).isEqualTo(ParticipationStatus.PENDING)
        assertThat(captured.captured.status).isEqualTo(ParticipationStatus.PENDING)
        // 승인 전이므로 정원 카운트는 증가하지 않는다.
        assertThat(event.currentParticipants).isEqualTo(0)
        // 채널 owner(id=1) 에게 새 참가 신청 알림이 가야 한다.
        verify {
            notificationService.notify(
                receiverIds = listOf(1L),
                type = NotificationType.PARTICIPATION_REQUESTED,
                title = "새 참가 신청",
                message = match { it.contains("참가자") && it.contains(event.title) },
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `applyForEvent 이미 PENDING 상태면 AlreadyJoinedException`() {
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val existing = createParticipation(event = event, participant = participant, status = ParticipationStatus.PENDING)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(existing)

        assertThrows<AlreadyJoinedException> { eventService.applyForEvent(2L, 1L) }
        verify(exactly = 0) { eventParticipationRepository.save(any()) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `applyForEvent 이미 APPROVED 상태면 AlreadyJoinedException (PR79 ACTIVE 가드)`() {
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val existing = createParticipation(event = event, participant = participant, status = ParticipationStatus.APPROVED)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(existing)

        assertThrows<AlreadyJoinedException> { eventService.applyForEvent(2L, 1L) }
        verify(exactly = 0) { eventParticipationRepository.save(any()) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `applyForEvent CLOSED 이벤트면 EventClosedException 이고 알림 없음`() {
        val participant = createUser(id = 2L)
        val event = createEvent(
            id = 1L,
            channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)),
            status = EventStatus.CLOSED,
        )

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)

        assertThrows<EventClosedException> { eventService.applyForEvent(2L, 1L) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `applyForEvent 정원이 찼으면 EventFullException 이고 알림 없음`() {
        val participant = createUser(id = 2L)
        val event = createEvent(
            id = 1L,
            channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)),
            maxParticipants = 1,
            currentParticipants = 1,
        )

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)

        assertThrows<EventFullException> { eventService.applyForEvent(2L, 1L) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `applyForEvent 채널 owner 본인이 신청하면 OwnerCannotApplyException 이고 알림 없음`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventRepository.findById(1L) } returns Optional.of(event)

        assertThrows<OwnerCannotApplyException> { eventService.applyForEvent(1L, 1L) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `applyForEvent REJECTED 상태에서 재신청하면 같은 행이 PENDING 으로 복구 + owner 알림`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L, nickname = "재신청자")
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val existing = createParticipation(
            event = event,
            participant = participant,
            status = ParticipationStatus.REJECTED,
            rejectReason = "사유",
        )

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(existing)

        val result = eventService.applyForEvent(2L, 1L)

        assertThat(result.status).isEqualTo(ParticipationStatus.PENDING)
        assertThat(existing.status).isEqualTo(ParticipationStatus.PENDING)
        assertThat(existing.rejectReason).isNull()
        verify(exactly = 0) { eventParticipationRepository.save(any()) }
        // 재신청도 새 신청으로 본다 — owner 알림이 한 번 가야 한다.
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(1L),
                type = NotificationType.PARTICIPATION_REQUESTED,
                title = "새 참가 신청",
                message = any(),
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `applyForEvent CANCELED 상태에서 재신청하면 owner 알림`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val existing = createParticipation(
            event = event,
            participant = participant,
            status = ParticipationStatus.CANCELED,
        )

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(existing)

        val result = eventService.applyForEvent(2L, 1L)

        assertThat(result.status).isEqualTo(ParticipationStatus.PENDING)
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(1L),
                type = NotificationType.PARTICIPATION_REQUESTED,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `applyForEvent 시 channel STAFF 도 PARTICIPATION_REQUESTED 알림을 받는다`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR, nickname = "오너")
        val staff = createUser(id = 7L, role = UserRole.PARTICIPANT, nickname = "스태프")
        val participant = createUser(id = 2L, nickname = "참가자")
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 1L, channel = channel)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.empty()
        every { eventParticipationRepository.save(any()) } answers {
            val arg = firstArg<EventParticipation>()
            ReflectionTestUtils.setField(arg, "id", 12L)
            arg
        }
        // owner = OWNER row, staff = STAFF row
        every { channelMemberRepository.findByChannel(channel) } returns listOf(
            createChannelMember(channel = channel, user = owner, role = ChannelMemberRole.OWNER),
            createChannelMember(channel = channel, user = staff, role = ChannelMemberRole.STAFF),
        )

        eventService.applyForEvent(2L, 1L)

        // owner(1) + staff(7) 모두 수신 — owner 중복 제거되어 한 번만 등장.
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(1L, 7L),
                type = NotificationType.PARTICIPATION_REQUESTED,
                title = "새 참가 신청",
                message = any(),
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `applyForEvent owner 가 ChannelMember 에도 들어있어도 receiverIds 에 중복되지 않는다`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 1L, channel = channel)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.empty()
        every { eventParticipationRepository.save(any()) } answers {
            val arg = firstArg<EventParticipation>()
            ReflectionTestUtils.setField(arg, "id", 13L)
            arg
        }
        // 채널 owner 가 ChannelMember row 에도 OWNER 로 들어있는 경우(흔한 케이스).
        every { channelMemberRepository.findByChannel(channel) } returns listOf(
            createChannelMember(channel = channel, user = owner, role = ChannelMemberRole.OWNER),
        )

        eventService.applyForEvent(2L, 1L)

        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(1L),
                type = NotificationType.PARTICIPATION_REQUESTED,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `applyForEvent 시 채널과 무관한 ADMIN 은 자동 전파 대상이 아니다`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 1L, channel = channel)
        // admin(id=99) 은 ChannelMember 에 들어있지 않음 → 알림 대상 아님.
        // (ADMIN 도 채널 STAFF 로 직접 합류해야 받을 수 있다.)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.empty()
        every { eventParticipationRepository.save(any()) } answers {
            val arg = firstArg<EventParticipation>()
            ReflectionTestUtils.setField(arg, "id", 14L)
            arg
        }
        every { channelMemberRepository.findByChannel(channel) } returns emptyList()

        eventService.applyForEvent(2L, 1L)

        // receiverIds 에 owner(1) 만 포함되어야 한다. 99 등 무관 사용자는 없다.
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = match { it == listOf(1L) },
                type = NotificationType.PARTICIPATION_REQUESTED,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `cancelMyApplication APPROVED 시 STAFF 도 PARTICIPATION_CANCELED 알림을 받는다`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val staff = createUser(id = 7L, role = UserRole.PARTICIPANT)
        val participant = createUser(id = 2L, nickname = "참가자")
        val channel = createChannel(id = 1L, owner = owner)
        val event = createEvent(id = 1L, channel = channel, currentParticipants = 3)
        val approved = createParticipation(
            id = 50L, event = event, participant = participant, status = ParticipationStatus.APPROVED,
        )

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(approved)
        every { ticketRepository.findByBuyerAndEventIdIn(participant, listOf(1L)) } returns emptyList()
        every { channelMemberRepository.findByChannel(channel) } returns listOf(
            createChannelMember(channel = channel, user = owner, role = ChannelMemberRole.OWNER),
            createChannelMember(channel = channel, user = staff, role = ChannelMemberRole.STAFF),
        )

        eventService.cancelMyApplication(2L, 1L)

        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(1L, 7L),
                type = NotificationType.PARTICIPATION_CANCELED,
                title = "참가자가 취소했어요",
                message = any(),
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `applyForEvent 알림 발송이 실패해도 신청은 성공`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.empty()
        every { eventParticipationRepository.save(any()) } answers {
            val arg = firstArg<EventParticipation>()
            ReflectionTestUtils.setField(arg, "id", 77L)
            arg
        }
        // 이 테스트만 notify 가 throw 하도록 덮어쓴다.
        every {
            notificationService.notify(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("SSE outage")

        val result = eventService.applyForEvent(2L, 1L)

        // 알림 실패가 신청을 막지 않아야 한다.
        assertThat(result.status).isEqualTo(ParticipationStatus.PENDING)
    }

    // ── cancelMyApplication ───────────────────────────────────────────────────

    @Test
    fun `cancelMyApplication PENDING 상태면 CANCELED 로 전환`() {
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val existing = createParticipation(event = event, participant = participant, status = ParticipationStatus.PENDING)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(existing)

        val result = eventService.cancelMyApplication(2L, 1L)

        assertThat(result.status).isEqualTo(ParticipationStatus.CANCELED)
        assertThat(existing.status).isEqualTo(ParticipationStatus.CANCELED)
    }

    @Test
    fun `cancelMyApplication 신청 이력 없으면 ParticipationNotFoundException`() {
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.empty()

        assertThrows<ParticipationNotFoundException> { eventService.cancelMyApplication(2L, 1L) }
    }

    @Test
    fun `cancelMyApplication REJECTED 상태면 ParticipationNotPendingException`() {
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val existing = createParticipation(event = event, participant = participant, status = ParticipationStatus.REJECTED)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(existing)

        assertThrows<ParticipationNotPendingException> { eventService.cancelMyApplication(2L, 1L) }
    }

    @Test
    fun `cancelMyApplication APPROVED 시작 전이면 CANCELED + currentParticipants 감소 + 티켓 CANCELED + owner 알림`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L, nickname = "참가자")
        val event = createEvent(
            id = 1L,
            channel = createChannel(id = 1L, owner = owner),
            currentParticipants = 5,
        )
        // 시작 시간은 createEvent fixture 가 now + 1 day 라서 OK.
        val approved = createParticipation(id = 50L, event = event, participant = participant, status = ParticipationStatus.APPROVED)
        val ticket = createTicket(id = 555L, event = event, buyer = participant, status = TicketStatus.PAID)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(approved)
        every { ticketRepository.findByBuyerAndEventIdIn(participant, listOf(1L)) } returns listOf(ticket)

        val result = eventService.cancelMyApplication(2L, 1L)

        assertThat(result.status).isEqualTo(ParticipationStatus.CANCELED)
        assertThat(approved.status).isEqualTo(ParticipationStatus.CANCELED)
        assertThat(event.currentParticipants).isEqualTo(4)
        assertThat(ticket.status).isEqualTo(TicketStatus.CANCELED)
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(1L),
                type = NotificationType.PARTICIPATION_CANCELED,
                title = "참가자가 취소했어요",
                message = any(),
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `cancelMyApplication APPROVED 인데 이미 시작된 이벤트면 EventAlreadyStartedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        // 시작 시간을 과거로 강제 변경.
        ReflectionTestUtils.setField(event, "startAt", LocalDateTime.now().minusHours(1))
        val approved = createParticipation(id = 50L, event = event, participant = participant, status = ParticipationStatus.APPROVED)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(approved)

        assertThrows<EventAlreadyStartedException> { eventService.cancelMyApplication(2L, 1L) }
        // 상태/카운트 변하지 않아야 한다.
        assertThat(approved.status).isEqualTo(ParticipationStatus.APPROVED)
    }

    @Test
    fun `cancelMyApplication APPROVED 인데 티켓이 USED 면 TicketAlreadyUsedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner), currentParticipants = 3)
        val approved = createParticipation(id = 50L, event = event, participant = participant, status = ParticipationStatus.APPROVED)
        val usedTicket = createTicket(id = 555L, event = event, buyer = participant, status = TicketStatus.USED)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(approved)
        every { ticketRepository.findByBuyerAndEventIdIn(participant, listOf(1L)) } returns listOf(usedTicket)

        assertThrows<TicketAlreadyUsedException> { eventService.cancelMyApplication(2L, 1L) }
        assertThat(approved.status).isEqualTo(ParticipationStatus.APPROVED)
        assertThat(event.currentParticipants).isEqualTo(3)
    }

    @Test
    fun `cancelMyApplication APPROVED 인데 티켓이 없으면 정원만 감소`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner), currentParticipants = 2)
        val approved = createParticipation(id = 50L, event = event, participant = participant, status = ParticipationStatus.APPROVED)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(approved)
        every { ticketRepository.findByBuyerAndEventIdIn(participant, listOf(1L)) } returns emptyList()

        eventService.cancelMyApplication(2L, 1L)

        assertThat(approved.status).isEqualTo(ParticipationStatus.CANCELED)
        assertThat(event.currentParticipants).isEqualTo(1)
    }

    // ── cancelMyApplication paid 가드 (PR76) ─────────────────────────────────

    @Test
    fun `cancelMyApplication 유료 APPROVED 는 PaidParticipationCancelRequiresRefundException 으로 거부되고 상태 변화 없음`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(
            id = 1L,
            channel = createChannel(id = 1L, owner = owner),
            currentParticipants = 5,
            participationFee = 5000L,
        )
        val approved = createParticipation(
            id = 50L, event = event, participant = participant, status = ParticipationStatus.APPROVED,
        )
        val ticket = createTicket(id = 555L, event = event, buyer = participant, status = TicketStatus.PAID)

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(approved)

        assertThrows<PaidParticipationCancelRequiresRefundException> { eventService.cancelMyApplication(2L, 1L) }

        // ticket / participation / 정원 / 알림 모두 변화 없음 (가드가 가장 먼저 throw).
        assertThat(approved.status).isEqualTo(ParticipationStatus.APPROVED)
        assertThat(event.currentParticipants).isEqualTo(5)
        assertThat(ticket.status).isEqualTo(TicketStatus.PAID)
        verify(exactly = 0) { ticketRepository.findByBuyerAndEventIdIn(any(), any()) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelMyApplication 유료 PENDING 은 결제 전이므로 기존대로 취소 허용`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(
            id = 1L,
            channel = createChannel(id = 1L, owner = owner),
            participationFee = 5000L,
        )
        val pending = createParticipation(
            id = 50L, event = event, participant = participant, status = ParticipationStatus.PENDING,
        )

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.of(pending)

        val result = eventService.cancelMyApplication(2L, 1L)

        assertThat(result.status).isEqualTo(ParticipationStatus.CANCELED)
        assertThat(pending.status).isEqualTo(ParticipationStatus.CANCELED)
    }

    // ── approveParticipation ──────────────────────────────────────────────────

    @Test
    fun `approveParticipation owner 가 승인하면 APPROVED + currentParticipants 증가 + 알림 발송`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val pending = createParticipation(
            id = 50L,
            event = event,
            participant = participant,
            status = ParticipationStatus.PENDING,
        )

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventParticipationRepository.findById(50L) } returns Optional.of(pending)

        val result = eventService.approveParticipation(1L, 1L, 50L)

        assertThat(result.status).isEqualTo(ParticipationStatus.APPROVED)
        assertThat(pending.status).isEqualTo(ParticipationStatus.APPROVED)
        assertThat(pending.reviewedAt).isNotNull
        assertThat(pending.reviewedBy?.id).isEqualTo(1L)
        assertThat(event.currentParticipants).isEqualTo(1)
        verify { ticketService.issueFreeTicket(2L, 1L) }
        verify {
            notificationService.notify(
                receiverIds = listOf(2L),
                type = NotificationType.PARTICIPATION_APPROVED,
                title = any(),
                message = any(),
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `approveParticipation 정원이 차 있으면 EventFullException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(
            id = 1L,
            channel = createChannel(id = 1L, owner = owner),
            maxParticipants = 1,
            currentParticipants = 1,
        )
        val pending = createParticipation(
            id = 50L,
            event = event,
            participant = participant,
            status = ParticipationStatus.PENDING,
        )

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventParticipationRepository.findById(50L) } returns Optional.of(pending)

        assertThrows<EventFullException> { eventService.approveParticipation(1L, 1L, 50L) }
        assertThat(pending.status).isEqualTo(ParticipationStatus.PENDING)
    }

    @Test
    fun `approveParticipation 비owner 가 호출하면 UnauthorizedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val intruder = createUser(id = 9L, role = UserRole.PARTICIPANT)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val pending = createParticipation(
            id = 50L,
            event = event,
            participant = participant,
            status = ParticipationStatus.PENDING,
        )

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(9L) } returns Optional.of(intruder)

        assertThrows<UnauthorizedException> { eventService.approveParticipation(9L, 1L, 50L) }
        verify(exactly = 0) { notificationService.notify(any(), any(), any(), any(), any(), any()) }
        // pending row 도 변경되지 않아야 한다.
        assertThat(pending.status).isEqualTo(ParticipationStatus.PENDING)
    }

    @Test
    fun `approveParticipation PENDING 이 아닌 상태면 ParticipationNotPendingException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val approved = createParticipation(
            id = 50L,
            event = event,
            participant = participant,
            status = ParticipationStatus.APPROVED,
        )

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventParticipationRepository.findById(50L) } returns Optional.of(approved)

        assertThrows<ParticipationNotPendingException> { eventService.approveParticipation(1L, 1L, 50L) }
    }

    // ── rejectParticipation ───────────────────────────────────────────────────

    @Test
    fun `rejectParticipation owner 가 거절하면 REJECTED + 사유 저장 + 알림 발송`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val pending = createParticipation(
            id = 50L,
            event = event,
            participant = participant,
            status = ParticipationStatus.PENDING,
        )

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventParticipationRepository.findById(50L) } returns Optional.of(pending)

        val result = eventService.rejectParticipation(1L, 1L, 50L, "정원이 작아요")

        assertThat(result.status).isEqualTo(ParticipationStatus.REJECTED)
        assertThat(pending.status).isEqualTo(ParticipationStatus.REJECTED)
        assertThat(pending.rejectReason).isEqualTo("정원이 작아요")
        assertThat(event.currentParticipants).isEqualTo(0)
        verify {
            notificationService.notify(
                receiverIds = listOf(2L),
                type = NotificationType.PARTICIPATION_REJECTED,
                title = any(),
                message = "정원이 작아요",
                targetType = "events",
                targetId = 1L,
            )
        }
    }

    @Test
    fun `rejectParticipation 비owner 호출 시 UnauthorizedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val intruder = createUser(id = 9L, role = UserRole.PARTICIPANT)
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val pending = createParticipation(
            id = 50L,
            event = event,
            participant = participant,
            status = ParticipationStatus.PENDING,
        )

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(9L) } returns Optional.of(intruder)

        assertThrows<UnauthorizedException> { eventService.rejectParticipation(9L, 1L, 50L, "no") }
        assertThat(pending.status).isEqualTo(ParticipationStatus.PENDING)
    }

    // ── listApplicants ────────────────────────────────────────────────────────

    @Test
    fun `listApplicants 비owner 호출 시 UnauthorizedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val intruder = createUser(id = 9L, role = UserRole.PARTICIPANT)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(9L) } returns Optional.of(intruder)

        assertThrows<UnauthorizedException> { eventService.listApplicants(9L, 1L) }
    }

    @Test
    fun `listApplicants owner 호출 시 신청자 목록 반환 + APPROVED 에는 ticket 매핑`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participantA = createUser(id = 2L, nickname = "유저A")
        val participantB = createUser(id = 3L, nickname = "유저B")
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val pA = createParticipation(id = 10L, event = event, participant = participantA, status = ParticipationStatus.PENDING)
        val pB = createParticipation(id = 11L, event = event, participant = participantB, status = ParticipationStatus.APPROVED)
        val ticketB = createTicket(id = 555L, event = event, buyer = participantB, status = TicketStatus.PAID)

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventParticipationRepository.findByEventOrderByJoinedAtDesc(event) } returns listOf(pA, pB)
        every { ticketRepository.findByEventAndBuyerIdIn(event, listOf(3L)) } returns listOf(ticketB)

        val result = eventService.listApplicants(1L, 1L)

        assertThat(result).hasSize(2)
        assertThat(result.map { it.nickname }).containsExactly("유저A", "유저B")
        assertThat(result.map { it.status }).containsExactly(ParticipationStatus.PENDING, ParticipationStatus.APPROVED)

        // PENDING 은 ticket null
        assertThat(result[0].ticketId).isNull()
        assertThat(result[0].ticketStatus).isNull()
        // APPROVED 는 ticket 매핑
        assertThat(result[1].ticketId).isEqualTo(555L)
        assertThat(result[1].ticketStatus).isEqualTo(TicketStatus.PAID)
    }

    @Test
    fun `listApplicants APPROVED 가 없으면 ticket 조회 자체를 안 한다`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val participantA = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val pending = createParticipation(id = 10L, event = event, participant = participantA, status = ParticipationStatus.PENDING)

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventParticipationRepository.findByEventOrderByJoinedAtDesc(event) } returns listOf(pending)

        val result = eventService.listApplicants(1L, 1L)

        assertThat(result).hasSize(1)
        assertThat(result[0].ticketId).isNull()
        verify(exactly = 0) { ticketRepository.findByEventAndBuyerIdIn(any(), any()) }
    }

    @Test
    fun `listApplicants 다른 이벤트 티켓이나 다른 참가자 티켓은 매핑되지 않는다`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val approvedBuyer = createUser(id = 2L, nickname = "승인자")
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = owner))
        val approved = createParticipation(id = 10L, event = event, participant = approvedBuyer, status = ParticipationStatus.APPROVED)

        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { eventParticipationRepository.findByEventOrderByJoinedAtDesc(event) } returns listOf(approved)
        // 쿼리는 (event = event, buyerIds = [2L]) 로만 호출되므로 다른 이벤트/참가자 티켓이
        // 섞일 수 있는 경로가 없다 — 정확한 인자로만 stub.
        every { ticketRepository.findByEventAndBuyerIdIn(event, listOf(2L)) } returns emptyList()

        val result = eventService.listApplicants(1L, 1L)

        assertThat(result).hasSize(1)
        assertThat(result[0].participantId).isEqualTo(2L)
        assertThat(result[0].ticketId).isNull()
    }

    // ── getMyParticipations (MY 페이지) ───────────────────────────────────────

    @Test
    fun `getMyParticipations 신청 이력 없으면 빈 페이지 반환`() {
        val user = createUser(id = 2L)
        every { userRepository.findById(2L) } returns Optional.of(user)
        every {
            eventParticipationRepository.findByParticipantOrderByJoinedAtDesc(user, any<Pageable>())
        } returns PageImpl(emptyList<EventParticipation>(), PageRequest.of(0, 20), 0)

        val result = eventService.getMyParticipations(2L, 0, 20)

        assertThat(result.content).isEmpty()
        assertThat(result.totalElements).isEqualTo(0)
        verify(exactly = 0) { ticketRepository.findByBuyerAndEventIdIn(any(), any()) }
    }

    @Test
    fun `getMyParticipations PENDING APPROVED REJECTED CANCELED 가 섞여 반환`() {
        val user = createUser(id = 2L)
        val ownerA = createUser(id = 1L, role = UserRole.CREATOR, nickname = "기획자A")
        val ownerB = createUser(id = 3L, role = UserRole.CREATOR, nickname = "기획자B")
        val eventA = createEvent(id = 10L, channel = createChannel(id = 10L, owner = ownerA))
        val eventB = createEvent(id = 11L, channel = createChannel(id = 11L, owner = ownerA))
        val eventC = createEvent(id = 12L, channel = createChannel(id = 12L, owner = ownerB))
        val eventD = createEvent(id = 13L, channel = createChannel(id = 13L, owner = ownerB))
        val pA = createParticipation(id = 100L, event = eventA, participant = user, status = ParticipationStatus.PENDING)
        val pB = createParticipation(id = 101L, event = eventB, participant = user, status = ParticipationStatus.APPROVED)
        val pC = createParticipation(
            id = 102L, event = eventC, participant = user,
            status = ParticipationStatus.REJECTED, rejectReason = "정원 마감",
        )
        val pD = createParticipation(id = 103L, event = eventD, participant = user, status = ParticipationStatus.CANCELED)

        every { userRepository.findById(2L) } returns Optional.of(user)
        every {
            eventParticipationRepository.findByParticipantOrderByJoinedAtDesc(user, any<Pageable>())
        } returns PageImpl(listOf(pA, pB, pC, pD), PageRequest.of(0, 20), 4)
        every {
            ticketRepository.findByBuyerAndEventIdIn(user, listOf(10L, 11L, 12L, 13L))
        } returns emptyList()

        val result = eventService.getMyParticipations(2L, 0, 20)

        assertThat(result.content).hasSize(4)
        assertThat(result.content.map { it.status }).containsExactly(
            ParticipationStatus.PENDING,
            ParticipationStatus.APPROVED,
            ParticipationStatus.REJECTED,
            ParticipationStatus.CANCELED,
        )
        val rejected = result.content[2]
        assertThat(rejected.rejectReason).isEqualTo("정원 마감")
        // 티켓이 없으므로 ticket 필드는 모두 null
        assertThat(result.content.all { it.ticketId == null && it.ticketStatus == null }).isTrue
    }

    @Test
    fun `getMyParticipations APPROVED 이고 ticket 이 있으면 ticket 정보 포함`() {
        val user = createUser(id = 2L)
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEvent(id = 10L, channel = createChannel(id = 10L, owner = owner))
        val p = createParticipation(id = 100L, event = event, participant = user, status = ParticipationStatus.APPROVED)
        val ticket = createTicket(id = 555L, event = event, buyer = user, status = TicketStatus.PAID)

        every { userRepository.findById(2L) } returns Optional.of(user)
        every {
            eventParticipationRepository.findByParticipantOrderByJoinedAtDesc(user, any<Pageable>())
        } returns PageImpl(listOf(p), PageRequest.of(0, 20), 1)
        every {
            ticketRepository.findByBuyerAndEventIdIn(user, listOf(10L))
        } returns listOf(ticket)
        // PR44: PaymentAttempt 배치 조회. 무료 시나리오라 빈 결과.
        every { paymentAttemptRepository.findByTicketIn(any()) } returns emptyList()

        val result = eventService.getMyParticipations(2L, 0, 20)

        val item = result.content.single()
        assertThat(item.status).isEqualTo(ParticipationStatus.APPROVED)
        assertThat(item.ticketId).isEqualTo(555L)
        assertThat(item.ticketStatus).isEqualTo(TicketStatus.PAID)
        assertThat(item.paymentAttemptId).isNull()
        assertThat(item.orderId).isNull()
        assertThat(item.paidAmount).isNull()
    }

    @Test
    fun `getMyParticipations 호출은 인증 사용자 본인 row 만 조회한다`() {
        val me = createUser(id = 2L)
        val capturedUser = slot<User>()
        every { userRepository.findById(2L) } returns Optional.of(me)
        every {
            eventParticipationRepository.findByParticipantOrderByJoinedAtDesc(capture(capturedUser), any<Pageable>())
        } returns PageImpl(emptyList<EventParticipation>(), PageRequest.of(0, 20), 0)

        eventService.getMyParticipations(2L, 0, 20)

        // 리포지토리에 전달된 user 객체가 인증 사용자(id=2L) 본인이어야 한다 — 다른 유저 row 가 섞이지 않는다.
        assertThat(capturedUser.captured.id).isEqualTo(2L)
    }

    // ── getMyParticipation (단건) ─────────────────────────────────────────────

    @Test
    fun `getMyParticipation 신청 이력 없으면 null`() {
        val user = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))

        every { userRepository.findById(2L) } returns Optional.of(user)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, user) } returns Optional.empty()

        val result = eventService.getMyParticipation(2L, 1L)

        assertThat(result).isNull()
        // PENDING 등 비-APPROVED 상태에서는 ticket 조회 자체를 안 한다.
        verify(exactly = 0) { ticketRepository.findByBuyerAndEventIdIn(any(), any()) }
    }

    @Test
    fun `getMyParticipation PENDING 이면 ticketId ticketStatus 는 null`() {
        val user = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val pending = createParticipation(id = 50L, event = event, participant = user, status = ParticipationStatus.PENDING)

        every { userRepository.findById(2L) } returns Optional.of(user)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, user) } returns Optional.of(pending)

        val result = eventService.getMyParticipation(2L, 1L)

        assertThat(result).isNotNull
        assertThat(result!!.status).isEqualTo(ParticipationStatus.PENDING)
        assertThat(result.ticketId).isNull()
        assertThat(result.ticketStatus).isNull()
        verify(exactly = 0) { ticketRepository.findByBuyerAndEventIdIn(any(), any()) }
    }

    @Test
    fun `getMyParticipation APPROVED 이고 티켓이 있으면 ticketId ticketStatus 포함`() {
        val user = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val approved = createParticipation(id = 50L, event = event, participant = user, status = ParticipationStatus.APPROVED)
        val ticket = createTicket(id = 777L, event = event, buyer = user, status = TicketStatus.PAID)

        every { userRepository.findById(2L) } returns Optional.of(user)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, user) } returns Optional.of(approved)
        every { ticketRepository.findByBuyerAndEventIdIn(user, listOf(1L)) } returns listOf(ticket)

        val result = eventService.getMyParticipation(2L, 1L)

        assertThat(result).isNotNull
        assertThat(result!!.status).isEqualTo(ParticipationStatus.APPROVED)
        assertThat(result.ticketId).isEqualTo(777L)
        assertThat(result.ticketStatus).isEqualTo(TicketStatus.PAID)
    }

    @Test
    fun `getMyParticipation APPROVED 인데 티켓이 없으면 ticket 필드는 null`() {
        val user = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val approved = createParticipation(id = 50L, event = event, participant = user, status = ParticipationStatus.APPROVED)

        every { userRepository.findById(2L) } returns Optional.of(user)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, user) } returns Optional.of(approved)
        every { ticketRepository.findByBuyerAndEventIdIn(user, listOf(1L)) } returns emptyList()

        val result = eventService.getMyParticipation(2L, 1L)

        assertThat(result!!.ticketId).isNull()
        assertThat(result.ticketStatus).isNull()
    }

    // ── joinEvent 레거시 alias ────────────────────────────────────────────────

    @Test
    fun `joinEvent 레거시 endpoint 는 새 applyForEvent 시맨틱으로 PENDING 저장`() {
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))
        val captured = slot<EventParticipation>()

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.findByEventAndParticipant(event, participant) } returns Optional.empty()
        every { eventParticipationRepository.save(capture(captured)) } answers {
            val arg = firstArg<EventParticipation>()
            ReflectionTestUtils.setField(arg, "id", 99L)
            arg
        }

        eventService.joinEvent(2L, 1L)

        // PENDING 저장, currentParticipants 변화 없음
        assertThat(captured.captured.status).isEqualTo(ParticipationStatus.PENDING)
        assertThat(event.currentParticipants).isEqualTo(0)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun createUser(
            id: Long = 1L,
            role: UserRole = UserRole.PARTICIPANT,
            nickname: String = "user$id",
        ): User {
            val user = User("user${id}@test.com", "encodedPassword", nickname, "01012345678")
                .apply { updateRole(role) }
            ReflectionTestUtils.setField(user, "id", id)
            ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(user, "updatedAt", LocalDateTime.now())
            return user
        }

        fun createChannel(id: Long = 1L, owner: User): Channel {
            val channel = Channel(owner, "Test Channel", "Test Description", ChannelCategory.MUSIC)
            ReflectionTestUtils.setField(channel, "id", id)
            ReflectionTestUtils.setField(channel, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(channel, "updatedAt", LocalDateTime.now())
            return channel
        }

        fun createChannelMember(
            channel: Channel,
            user: User,
            role: ChannelMemberRole = ChannelMemberRole.STAFF,
        ): ChannelMember {
            val m = ChannelMember(channel = channel, user = user, role = role)
            ReflectionTestUtils.setField(m, "joinedAt", LocalDateTime.now())
            return m
        }

        fun createEvent(
            id: Long = 1L,
            channel: Channel,
            maxParticipants: Int = 10,
            currentParticipants: Int = 0,
            status: EventStatus = EventStatus.UPCOMING,
            participationFee: Long = 0L,
        ): Event {
            val event = Event(
                channel = channel,
                title = "Test Event",
                description = "Test Description",
                location = "Seoul",
                mainImageUrl = "https://example.com/img.jpg",
                startAt = LocalDateTime.now().plusDays(1),
                endAt = LocalDateTime.now().plusDays(2),
                maxParticipants = maxParticipants,
                participationFee = participationFee,
                refundPolicy = "전액 환불",
                detailContent = "Test Detail",
                status = status,
            )
            ReflectionTestUtils.setField(event, "id", id)
            ReflectionTestUtils.setField(event, "currentParticipants", currentParticipants)
            ReflectionTestUtils.setField(event, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(event, "updatedAt", LocalDateTime.now())
            return event
        }

        fun createParticipation(
            id: Long = 1L,
            event: Event,
            participant: User,
            status: ParticipationStatus = ParticipationStatus.PENDING,
            rejectReason: String? = null,
        ): EventParticipation {
            val p = EventParticipation(event = event, participant = participant)
            ReflectionTestUtils.setField(p, "id", id)
            p.status = status
            p.rejectReason = rejectReason
            return p
        }

        fun createTicket(
            id: Long = 1L,
            event: Event,
            buyer: User,
            price: Long = 0L,
            status: TicketStatus = TicketStatus.PAID,
            purchasedAt: LocalDateTime = LocalDateTime.now(),
        ): Ticket {
            val t = Ticket(event = event, buyer = buyer, price = price, status = status)
            ReflectionTestUtils.setField(t, "id", id)
            ReflectionTestUtils.setField(t, "purchasedAt", purchasedAt)
            ReflectionTestUtils.setField(t, "updatedAt", purchasedAt)
            return t
        }

        fun createEventRequest() = CreateEventRequest(
            title = "Test Event",
            description = "Test Description",
            location = "Seoul",
            mainImageUrl = "https://example.com/img.jpg",
            startAt = LocalDateTime.now().plusDays(1),
            endAt = LocalDateTime.now().plusDays(2),
            maxParticipants = 10,
            participationFee = 0L,
            refundPolicy = "전액 환불",
            detailContent = "Test Detail",
        )
    }
}
