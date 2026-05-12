package com.contenido.domain.channel.service

import com.contenido.domain.channel.dto.AddStaffRequest
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AlreadyChannelMemberException
import com.contenido.global.exception.CannotAddAdminAsStaffException
import com.contenido.global.exception.CannotRemoveOwnerException
import com.contenido.global.exception.ChannelMemberNotFoundException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
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
class ChannelMemberServiceTest {

    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var channelMemberRepository: ChannelMemberRepository
    @MockK lateinit var userRepository: UserRepository

    private lateinit var service: ChannelMemberService

    @BeforeEach
    fun setUp() {
        service = ChannelMemberService(channelRepository, channelMemberRepository, userRepository)
    }

    // ── listMembers ──────────────────────────────────────────────────────────

    @Test
    fun `listMembers owner 호출 시 OWNER 먼저 정렬되어 반환`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR, nickname = "기획자")
        val staff = createUser(id = 2L, role = UserRole.PARTICIPANT, nickname = "스태프")
        val channel = createChannel(id = 10L, owner = owner)
        val ownerMember = createMember(id = 100L, channel = channel, user = owner, role = ChannelMemberRole.OWNER, joinedAt = LocalDateTime.now().minusDays(2))
        val staffMember = createMember(id = 101L, channel = channel, user = staff, role = ChannelMemberRole.STAFF, joinedAt = LocalDateTime.now().minusDays(1))

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { channelMemberRepository.findByChannel(channel) } returns listOf(staffMember, ownerMember)

        val result = service.listMembers(viewerId = 1L, channelId = 10L)

        assertThat(result).hasSize(2)
        assertThat(result[0].role).isEqualTo(ChannelMemberRole.OWNER)
        assertThat(result[0].nickname).isEqualTo("기획자")
        assertThat(result[1].role).isEqualTo(ChannelMemberRole.STAFF)
    }

    @Test
    fun `listMembers 비owner 일반 사용자는 UnauthorizedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val intruder = createUser(id = 99L, role = UserRole.PARTICIPANT)
        val channel = createChannel(id = 10L, owner = owner)

        every { userRepository.findById(99L) } returns Optional.of(intruder)
        every { channelRepository.findById(10L) } returns Optional.of(channel)

        assertThrows<UnauthorizedException> { service.listMembers(viewerId = 99L, channelId = 10L) }
    }

    @Test
    fun `listMembers ADMIN 도 조회 허용`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val admin = createUser(id = 9L, role = UserRole.ADMIN)
        val channel = createChannel(id = 10L, owner = owner)
        val ownerMember = createMember(id = 100L, channel = channel, user = owner, role = ChannelMemberRole.OWNER)

        every { userRepository.findById(9L) } returns Optional.of(admin)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { channelMemberRepository.findByChannel(channel) } returns listOf(ownerMember)

        val result = service.listMembers(viewerId = 9L, channelId = 10L)
        assertThat(result).hasSize(1)
    }

    // ── addStaff ─────────────────────────────────────────────────────────────

    @Test
    fun `addStaff owner 가 일반 사용자를 STAFF 로 추가 성공`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val candidate = createUser(id = 2L, role = UserRole.PARTICIPANT, nickname = "신입")
        val channel = createChannel(id = 10L, owner = owner)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { userRepository.findByEmail("staff@test.com") } returns Optional.of(candidate)
        every { channelMemberRepository.existsByChannelAndUser(channel, candidate) } returns false
        every { channelMemberRepository.save(any()) } answers {
            val arg = firstArg<ChannelMember>()
            ReflectionTestUtils.setField(arg, "id", 200L)
            ReflectionTestUtils.setField(arg, "joinedAt", LocalDateTime.now())
            arg
        }

        val result = service.addStaff(viewerId = 1L, channelId = 10L, request = AddStaffRequest("staff@test.com"))

        assertThat(result.id).isEqualTo(200L)
        assertThat(result.userId).isEqualTo(2L)
        assertThat(result.role).isEqualTo(ChannelMemberRole.STAFF)
    }

    @Test
    fun `addStaff 이미 멤버면 AlreadyChannelMemberException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val candidate = createUser(id = 2L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { userRepository.findByEmail("staff@test.com") } returns Optional.of(candidate)
        every { channelMemberRepository.existsByChannelAndUser(channel, candidate) } returns true

        assertThrows<AlreadyChannelMemberException> {
            service.addStaff(1L, 10L, AddStaffRequest("staff@test.com"))
        }
        verify(exactly = 0) { channelMemberRepository.save(any()) }
    }

    @Test
    fun `addStaff ADMIN 계정은 CannotAddAdminAsStaffException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val admin = createUser(id = 5L, role = UserRole.ADMIN)
        val channel = createChannel(id = 10L, owner = owner)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { userRepository.findByEmail("admin@test.com") } returns Optional.of(admin)

        assertThrows<CannotAddAdminAsStaffException> {
            service.addStaff(1L, 10L, AddStaffRequest("admin@test.com"))
        }
    }

    @Test
    fun `addStaff 존재하지 않는 이메일은 UserNotFoundException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { userRepository.findByEmail("nope@test.com") } returns Optional.empty()

        assertThrows<UserNotFoundException> {
            service.addStaff(1L, 10L, AddStaffRequest("nope@test.com"))
        }
    }

    @Test
    fun `addStaff 비owner 가 호출하면 UnauthorizedException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val intruder = createUser(id = 99L, role = UserRole.PARTICIPANT)
        val channel = createChannel(id = 10L, owner = owner)

        every { userRepository.findById(99L) } returns Optional.of(intruder)
        every { channelRepository.findById(10L) } returns Optional.of(channel)

        assertThrows<UnauthorizedException> {
            service.addStaff(99L, 10L, AddStaffRequest("x@test.com"))
        }
    }

    // ── removeMember ─────────────────────────────────────────────────────────

    @Test
    fun `removeMember owner 가 STAFF 를 삭제 성공`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val staff = createUser(id = 2L, role = UserRole.PARTICIPANT)
        val channel = createChannel(id = 10L, owner = owner)
        val staffMember = createMember(id = 200L, channel = channel, user = staff, role = ChannelMemberRole.STAFF)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { channelMemberRepository.findById(200L) } returns Optional.of(staffMember)
        every { channelMemberRepository.delete(staffMember) } returns Unit

        service.removeMember(viewerId = 1L, channelId = 10L, memberId = 200L)

        verify(exactly = 1) { channelMemberRepository.delete(staffMember) }
    }

    @Test
    fun `removeMember OWNER 는 CannotRemoveOwnerException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val ownerMember = createMember(id = 100L, channel = channel, user = owner, role = ChannelMemberRole.OWNER)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { channelMemberRepository.findById(100L) } returns Optional.of(ownerMember)

        assertThrows<CannotRemoveOwnerException> { service.removeMember(1L, 10L, 100L) }
        verify(exactly = 0) { channelMemberRepository.delete(any()) }
    }

    @Test
    fun `removeMember 다른 채널 멤버 id 는 ChannelMemberNotFoundException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val staff = createUser(id = 2L, role = UserRole.PARTICIPANT)
        val channel = createChannel(id = 10L, owner = owner)
        val otherChannel = createChannel(id = 11L, owner = createUser(id = 3L, role = UserRole.CREATOR))
        val foreignMember = createMember(id = 999L, channel = otherChannel, user = staff, role = ChannelMemberRole.STAFF)

        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { channelMemberRepository.findById(999L) } returns Optional.of(foreignMember)

        assertThrows<ChannelMemberNotFoundException> { service.removeMember(1L, 10L, 999L) }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    companion object {
        fun createUser(id: Long, role: UserRole, nickname: String = "user$id"): User {
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

        fun createMember(
            id: Long,
            channel: Channel,
            user: User,
            role: ChannelMemberRole,
            joinedAt: LocalDateTime = LocalDateTime.now(),
        ): ChannelMember {
            val m = ChannelMember(channel = channel, user = user, role = role)
            ReflectionTestUtils.setField(m, "id", id)
            ReflectionTestUtils.setField(m, "joinedAt", joinedAt)
            return m
        }
    }
}
