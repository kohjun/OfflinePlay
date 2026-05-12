package com.contenido.domain.channel.service

import com.contenido.domain.channel.dto.CreateChannelRequest
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.entity.ChannelSubscription
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.event.ChannelSyncEvent
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
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockKExtension::class)
class ChannelServiceTest {

    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var channelMemberRepository: ChannelMemberRepository
    @MockK lateinit var channelSubscriptionRepository: ChannelSubscriptionRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var publisher: ApplicationEventPublisher

    private lateinit var channelService: ChannelService

    @BeforeEach
    fun setUp() {
        channelService = ChannelService(
            channelRepository = channelRepository,
            channelMemberRepository = channelMemberRepository,
            channelSubscriptionRepository = channelSubscriptionRepository,
            userRepository = userRepository,
            publisher = publisher,
        )
        every { publisher.publishEvent(any<ChannelSyncEvent>()) } just Runs
        // Owner-as-member 라인이 항상 한 번씩 실행되므로 기본 동작을 미리 stub.
        every { channelMemberRepository.existsByChannelAndUser(any(), any()) } returns false
        every { channelMemberRepository.save(any()) } answers { firstArg() }
    }

    // ── createChannel ─────────────────────────────────────────────────────────

    @Test
    fun `createChannel 성공`() {
        val creator = createUser(id = 1L, role = UserRole.CREATOR)
        val request = CreateChannelRequest("채널명", "설명", ChannelCategory.MUSIC)
        val savedChannel = createChannel(id = 1L, owner = creator)

        every { userRepository.findById(1L) } returns Optional.of(creator)
        every { channelRepository.existsByOwner(creator) } returns false
        every { channelRepository.save(any()) } returns savedChannel

        val result = channelService.createChannel(1L, request)

        assertThat(result.name).isEqualTo("Test Channel")
        assertThat(result.ownerNickname).isEqualTo("testUser")
        verify {
            publisher.publishEvent(match<ChannelSyncEvent> { it.channelId == savedChannel.id })
        }
    }

    @Test
    fun `createChannel 성공 시 owner 가 ChannelMember OWNER 로 자동 등록`() {
        val creator = createUser(id = 1L, role = UserRole.CREATOR)
        val request = CreateChannelRequest("채널명", "설명", ChannelCategory.MUSIC)
        val savedChannel = createChannel(id = 1L, owner = creator)
        val captured = slot<ChannelMember>()

        every { userRepository.findById(1L) } returns Optional.of(creator)
        every { channelRepository.existsByOwner(creator) } returns false
        every { channelRepository.save(any()) } returns savedChannel
        every { channelMemberRepository.existsByChannelAndUser(savedChannel, creator) } returns false
        every { channelMemberRepository.save(capture(captured)) } answers { firstArg() }

        channelService.createChannel(1L, request)

        verify(exactly = 1) { channelMemberRepository.save(any<ChannelMember>()) }
        assertThat(captured.captured.role).isEqualTo(ChannelMemberRole.OWNER)
        assertThat(captured.captured.channel).isEqualTo(savedChannel)
        assertThat(captured.captured.user).isEqualTo(creator)
    }

    @Test
    fun `createChannel PARTICIPANT 롤 예외`() {
        val participant = createUser(id = 1L, role = UserRole.PARTICIPANT)
        every { userRepository.findById(1L) } returns Optional.of(participant)

        assertThrows<NotCreatorException> {
            channelService.createChannel(1L, CreateChannelRequest("채널명", "설명", ChannelCategory.MUSIC))
        }
        verify(exactly = 0) { channelRepository.save(any()) }
    }

    @Test
    fun `createChannel 중복 채널 예외`() {
        val creator = createUser(id = 1L, role = UserRole.CREATOR)
        every { userRepository.findById(1L) } returns Optional.of(creator)
        every { channelRepository.existsByOwner(creator) } returns true

        assertThrows<DuplicateChannelException> {
            channelService.createChannel(1L, CreateChannelRequest("채널명", "설명", ChannelCategory.MUSIC))
        }
    }

    // ── subscribe ─────────────────────────────────────────────────────────────

    @Test
    fun `subscribe 성공`() {
        val subscriber = createUser(id = 2L)
        val channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR))

        every { userRepository.findById(2L) } returns Optional.of(subscriber)
        every { channelRepository.findById(1L) } returns Optional.of(channel)
        every { channelSubscriptionRepository.existsBySubscriberAndChannel(subscriber, channel) } returns false
        every { channelSubscriptionRepository.save(any()) } returns mockk()

        channelService.subscribe(2L, 1L)

        assertThat(channel.subscriberCount).isEqualTo(1L)
        verify { channelSubscriptionRepository.save(any<ChannelSubscription>()) }
    }

    @Test
    fun `subscribe 이미 구독 예외`() {
        val subscriber = createUser(id = 2L)
        val channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR))

        every { userRepository.findById(2L) } returns Optional.of(subscriber)
        every { channelRepository.findById(1L) } returns Optional.of(channel)
        every { channelSubscriptionRepository.existsBySubscriberAndChannel(subscriber, channel) } returns true

        assertThrows<AlreadySubscribedException> { channelService.subscribe(2L, 1L) }
    }

    // ── unsubscribe ───────────────────────────────────────────────────────────

    @Test
    fun `unsubscribe 성공`() {
        val subscriber = createUser(id = 2L)
        val channel = createChannel(id = 1L, owner = createUser(id = 1L, role = UserRole.CREATOR))
            .also { ReflectionTestUtils.setField(it, "subscriberCount", 1L) }

        every { userRepository.findById(2L) } returns Optional.of(subscriber)
        every { channelRepository.findById(1L) } returns Optional.of(channel)
        every { channelSubscriptionRepository.existsBySubscriberAndChannel(subscriber, channel) } returns true
        every { channelSubscriptionRepository.deleteBySubscriberAndChannel(subscriber, channel) } just Runs

        channelService.unsubscribe(2L, 1L)

        assertThat(channel.subscriberCount).isEqualTo(0L)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun createUser(
            id: Long = 1L,
            role: UserRole = UserRole.PARTICIPANT,
            nickname: String = "testUser",
        ): User {
            val user = User("test@test.com", "encodedPassword", nickname, "01012345678")
                .apply { updateRole(role) }
            ReflectionTestUtils.setField(user, "id", id)
            ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(user, "updatedAt", LocalDateTime.now())
            return user
        }

        fun createChannel(
            id: Long = 1L,
            owner: User,
            name: String = "Test Channel",
            category: ChannelCategory = ChannelCategory.MUSIC,
        ): Channel {
            val channel = Channel(owner, name, "Test Description", category)
            ReflectionTestUtils.setField(channel, "id", id)
            ReflectionTestUtils.setField(channel, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(channel, "updatedAt", LocalDateTime.now())
            return channel
        }
    }
}
