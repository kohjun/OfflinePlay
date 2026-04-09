package com.contenido.domain.event.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.event.dto.CreateEventRequest
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.search.service.SearchSyncService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockKExtension::class)
class EventServiceTest {

    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var eventParticipationRepository: EventParticipationRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var channelSubscriptionRepository: ChannelSubscriptionRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var notificationService: NotificationService
    @MockK lateinit var searchSyncService: SearchSyncService

    private lateinit var eventService: EventService

    @BeforeEach
    fun setUp() {
        eventService = EventService(
            eventRepository = eventRepository,
            eventParticipationRepository = eventParticipationRepository,
            channelRepository = channelRepository,
            channelSubscriptionRepository = channelSubscriptionRepository,
            userRepository = userRepository,
            notificationService = notificationService,
            searchSyncService = searchSyncService,
        )
        every { notificationService.notify(any(), any(), any(), any(), any(), any()) } just Runs
        every { searchSyncService.syncEvent(any()) } just Runs
        every { channelSubscriptionRepository.findByChannel(any()) } returns emptyList()
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
        assertThat(result.title).isEqualTo("Test Event")
        verify { searchSyncService.syncEvent(savedEvent) }
    }

    @Test
    fun `createEvent 채널 소유자 아님 예외`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val otherUser = createUser(id = 2L, role = UserRole.CREATOR)
        val channel = createChannel(id = 1L, owner = owner)  // owner=1

        every { userRepository.findById(2L) } returns Optional.of(otherUser)
        every { channelRepository.findById(1L) } returns Optional.of(channel)

        assertThrows<UnauthorizedException> {
            eventService.createEvent(2L, 1L, createEventRequest())
        }
    }

    // ── joinEvent ─────────────────────────────────────────────────────────────

    @Test
    fun `joinEvent 성공`() {
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.existsByEventAndParticipant(event, participant) } returns false
        every { eventParticipationRepository.save(any()) } returns mockk()

        eventService.joinEvent(2L, 1L)

        assertThat(event.currentParticipants).isEqualTo(1)
        verify { eventParticipationRepository.save(any<EventParticipation>()) }
    }

    @Test
    fun `joinEvent 인원 초과 예외`() {
        val participant = createUser(id = 2L)
        // maxParticipants=1, currentParticipants=1 → isFull() = true
        val event = createEvent(
            id = 1L,
            channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)),
            maxParticipants = 1,
            currentParticipants = 1,
        )

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.existsByEventAndParticipant(event, participant) } returns false

        assertThrows<EventFullException> { eventService.joinEvent(2L, 1L) }
    }

    @Test
    fun `joinEvent 이미 참여 예외`() {
        val participant = createUser(id = 2L)
        val event = createEvent(id = 1L, channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR)))

        every { userRepository.findById(2L) } returns Optional.of(participant)
        every { eventRepository.findById(1L) } returns Optional.of(event)
        every { eventParticipationRepository.existsByEventAndParticipant(event, participant) } returns true

        assertThrows<AlreadyJoinedException> { eventService.joinEvent(2L, 1L) }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun createUser(id: Long = 1L, role: UserRole = UserRole.PARTICIPANT): User {
            val user = User("user${id}@test.com", "encodedPassword", "user$id", role, "01012345678")
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

        fun createEvent(
            id: Long = 1L,
            channel: Channel,
            maxParticipants: Int? = null,
            currentParticipants: Int = 0,
        ): Event {
            val event = Event(
                channel = channel,
                title = "Test Event",
                description = "Test Description",
                startAt = LocalDateTime.now().plusDays(1),
                endAt = LocalDateTime.now().plusDays(2),
                maxParticipants = maxParticipants,
                status = EventStatus.UPCOMING,
            )
            ReflectionTestUtils.setField(event, "id", id)
            ReflectionTestUtils.setField(event, "currentParticipants", currentParticipants)
            ReflectionTestUtils.setField(event, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(event, "updatedAt", LocalDateTime.now())
            return event
        }

        fun createEventRequest() = CreateEventRequest(
            title = "Test Event",
            description = "Test Description",
            startAt = LocalDateTime.now().plusDays(1),
            endAt = LocalDateTime.now().plusDays(2),
            maxParticipants = null,
        )
    }
}
