package com.contenido.domain.creator.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.NotCreatorException
import com.contenido.global.exception.UserNotFoundException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockKExtension::class)
class CreatorStudioServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var eventParticipationRepository: EventParticipationRepository

    private lateinit var service: CreatorStudioService

    @BeforeEach
    fun setUp() {
        service = CreatorStudioService(
            userRepository = userRepository,
            channelRepository = channelRepository,
            eventRepository = eventRepository,
            eventParticipationRepository = eventParticipationRepository,
        )
    }

    // ── 권한 ───────────────────────────────────────────────────────────────────

    @Test
    fun `getStudio 사용자 없으면 UserNotFoundException`() {
        every { userRepository.findById(99L) } returns Optional.empty()
        assertThrows<UserNotFoundException> { service.getStudio(99L) }
    }

    @Test
    fun `getStudio 탈퇴한 사용자면 DeletedUserException`() {
        val user = createUser(id = 1L, role = UserRole.CREATOR).also { it.softDelete() }
        every { userRepository.findById(1L) } returns Optional.of(user)
        assertThrows<DeletedUserException> { service.getStudio(1L) }
    }

    @Test
    fun `getStudio PARTICIPANT 는 NotCreatorException`() {
        val user = createUser(id = 2L, role = UserRole.PARTICIPANT)
        every { userRepository.findById(2L) } returns Optional.of(user)
        assertThrows<NotCreatorException> { service.getStudio(2L) }
    }

    // ── 채널 없음 ──────────────────────────────────────────────────────────────

    @Test
    fun `getStudio 채널이 없으면 channel = null and events = empty`() {
        val creator = createUser(id = 1L, role = UserRole.CREATOR)
        every { userRepository.findById(1L) } returns Optional.of(creator)
        every { channelRepository.findByOwner(creator) } returns Optional.empty()

        val result = service.getStudio(1L)

        assertThat(result.channel).isNull()
        assertThat(result.events).isEmpty()
        assertThat(result.summary.totalEvents).isEqualTo(0)
        assertThat(result.summary.pendingApplicants).isEqualTo(0L)
        assertThat(result.summary.approvedParticipants).isEqualTo(0L)
        assertThat(result.summary.subscriberCount).isEqualTo(0L)
    }

    // ── 채널 + 이벤트 + 카운트 ────────────────────────────────────────────────

    @Test
    fun `getStudio 채널과 이벤트 + status 별 카운트 매핑`() {
        val creator = createUser(id = 1L, role = UserRole.CREATOR, nickname = "기획자")
        val channel = createChannel(id = 10L, owner = creator)
        ReflectionTestUtils.setField(channel, "subscriberCount", 42L)
        val eventA = createEvent(
            id = 100L, channel = channel, title = "이벤트 A",
            startAt = LocalDateTime.of(2026, 6, 15, 19, 0),
            currentParticipants = 3, maxParticipants = 10,
        )
        val eventB = createEvent(
            id = 101L, channel = channel, title = "이벤트 B",
            startAt = LocalDateTime.of(2026, 7, 1, 20, 0),
            currentParticipants = 5, maxParticipants = 10,
        )

        every { userRepository.findById(1L) } returns Optional.of(creator)
        every { channelRepository.findByOwner(creator) } returns Optional.of(channel)
        every { eventRepository.findByChannel(channel) } returns listOf(eventA, eventB)

        // grouped query 결과: 100/PENDING 2, 100/APPROVED 3, 100/REJECTED 1, 101/PENDING 4, 101/APPROVED 5
        every { eventParticipationRepository.countByChannelGroupedByStatus(channel) } returns listOf(
            arrayOf<Any>(100L, ParticipationStatus.PENDING, 2L),
            arrayOf<Any>(100L, ParticipationStatus.APPROVED, 3L),
            arrayOf<Any>(100L, ParticipationStatus.REJECTED, 1L),
            arrayOf<Any>(101L, ParticipationStatus.PENDING, 4L),
            arrayOf<Any>(101L, ParticipationStatus.APPROVED, 5L),
        )

        val result = service.getStudio(1L)

        // 채널 매핑
        assertThat(result.channel).isNotNull
        assertThat(result.channel!!.id).isEqualTo(10L)
        assertThat(result.channel!!.subscriberCount).isEqualTo(42L)
        assertThat(result.channel!!.ownerNickname).isEqualTo("기획자")

        // 이벤트는 startAt 내림차순 — 7월 이벤트(B) 먼저
        assertThat(result.events.map { it.id }).containsExactly(101L, 100L)

        val rB = result.events[0]
        assertThat(rB.pendingCount).isEqualTo(4L)
        assertThat(rB.approvedCount).isEqualTo(5L)
        assertThat(rB.rejectedCount).isEqualTo(0L)
        assertThat(rB.canceledCount).isEqualTo(0L)

        val rA = result.events[1]
        assertThat(rA.pendingCount).isEqualTo(2L)
        assertThat(rA.approvedCount).isEqualTo(3L)
        assertThat(rA.rejectedCount).isEqualTo(1L)
        assertThat(rA.canceledCount).isEqualTo(0L)

        // summary 누적
        assertThat(result.summary.totalEvents).isEqualTo(2)
        assertThat(result.summary.pendingApplicants).isEqualTo(6L) // 2 + 4
        assertThat(result.summary.approvedParticipants).isEqualTo(8L) // 3 + 5
        assertThat(result.summary.subscriberCount).isEqualTo(42L)
    }

    @Test
    fun `getStudio 이벤트는 있지만 신청 0건일 때 카운트는 0으로 채워진다`() {
        val creator = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = creator)
        val event = createEvent(id = 200L, channel = channel, title = "신규 이벤트")

        every { userRepository.findById(1L) } returns Optional.of(creator)
        every { channelRepository.findByOwner(creator) } returns Optional.of(channel)
        every { eventRepository.findByChannel(channel) } returns listOf(event)
        every { eventParticipationRepository.countByChannelGroupedByStatus(channel) } returns emptyList()

        val result = service.getStudio(1L)

        assertThat(result.events).hasSize(1)
        val r = result.events[0]
        assertThat(r.pendingCount).isEqualTo(0L)
        assertThat(r.approvedCount).isEqualTo(0L)
        assertThat(r.rejectedCount).isEqualTo(0L)
        assertThat(r.canceledCount).isEqualTo(0L)
        assertThat(result.summary.pendingApplicants).isEqualTo(0L)
    }

    @Test
    fun `getStudio 는 호출자 본인 소유 채널만 조회한다`() {
        val creator = createUser(id = 1L, role = UserRole.CREATOR)
        val captured = slot<User>()
        every { userRepository.findById(1L) } returns Optional.of(creator)
        every { channelRepository.findByOwner(capture(captured)) } returns Optional.empty()

        service.getStudio(1L)

        // findByOwner 에 전달된 User 가 인증 사용자(id=1L) 본인이어야 한다 — 다른 기획자 채널은 조회되지 않는다.
        assertThat(captured.captured.id).isEqualTo(1L)
    }

    @Test
    fun `getStudio ADMIN 도 호출 가능`() {
        val admin = createUser(id = 1L, role = UserRole.ADMIN)
        every { userRepository.findById(1L) } returns Optional.of(admin)
        every { channelRepository.findByOwner(admin) } returns Optional.empty()

        val result = service.getStudio(1L)

        assertThat(result.channel).isNull()
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun createUser(
            id: Long = 1L,
            role: UserRole = UserRole.CREATOR,
            nickname: String = "user$id",
        ): User {
            val user = User("user$id@test.com", "encoded", nickname, "01012345678")
                .apply { updateRole(role) }
            ReflectionTestUtils.setField(user, "id", id)
            ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(user, "updatedAt", LocalDateTime.now())
            return user
        }

        fun createChannel(id: Long, owner: User): Channel {
            val channel = Channel(owner, "테스트 채널", "설명", ChannelCategory.MUSIC)
            ReflectionTestUtils.setField(channel, "id", id)
            ReflectionTestUtils.setField(channel, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(channel, "updatedAt", LocalDateTime.now())
            return channel
        }

        fun createEvent(
            id: Long,
            channel: Channel,
            title: String = "이벤트",
            startAt: LocalDateTime = LocalDateTime.now().plusDays(1),
            currentParticipants: Int = 0,
            maxParticipants: Int = 10,
            status: EventStatus = EventStatus.UPCOMING,
        ): Event {
            val event = Event(
                channel = channel,
                title = title,
                description = "desc",
                location = "Seoul",
                mainImageUrl = "https://example.com/img.jpg",
                startAt = startAt,
                endAt = startAt.plusHours(2),
                maxParticipants = maxParticipants,
                participationFee = 0L,
                refundPolicy = "전액 환불",
                detailContent = "detail",
                status = status,
            )
            ReflectionTestUtils.setField(event, "id", id)
            ReflectionTestUtils.setField(event, "currentParticipants", currentParticipants)
            ReflectionTestUtils.setField(event, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(event, "updatedAt", LocalDateTime.now())
            return event
        }
    }
}
