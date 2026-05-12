package com.contenido.domain.channel.service

import com.contenido.domain.channel.dto.AddStaffRequest
import com.contenido.domain.channel.dto.ChannelMemberResponse
import com.contenido.domain.channel.entity.Channel
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
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 채널 운영팀(OWNER + STAFF) 관리 진입점. 채널 소유자 또는 ADMIN 만 STAFF 추가/삭제 가능.
 *
 *  - OWNER 본인은 삭제할 수 없다.
 *  - ADMIN 계정은 STAFF 로 추가할 수 없다 (역할 분리).
 *  - STAFF 추가가 중복이면 [AlreadyChannelMemberException].
 *  - 자동으로 OWNER 가 누락된 레거시 채널을 가입시키진 않는다 — 별도 마이그레이션 작업.
 */
@Service
@Transactional(readOnly = true)
class ChannelMemberService(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val userRepository: UserRepository,
) {

    fun listMembers(viewerId: Long, channelId: Long): List<ChannelMemberResponse> {
        val viewer = findActiveUser(viewerId)
        val channel = findChannel(channelId)
        ensureCanManage(viewer, channel)

        return channelMemberRepository.findByChannel(channel)
            // OWNER 가 항상 맨 앞에 오도록 정렬.
            .sortedWith(compareBy({ it.role.ordinal }, { it.joinedAt }))
            .map { it.toResponse() }
    }

    @Transactional
    fun addStaff(viewerId: Long, channelId: Long, request: AddStaffRequest): ChannelMemberResponse {
        val viewer = findActiveUser(viewerId)
        val channel = findChannel(channelId)
        ensureCanManage(viewer, channel)

        val candidate = userRepository.findByEmail(request.email)
            .orElseThrow { UserNotFoundException() }
        if (candidate.isDeleted) throw DeletedUserException()
        if (candidate.role == UserRole.ADMIN) throw CannotAddAdminAsStaffException()
        if (channelMemberRepository.existsByChannelAndUser(channel, candidate)) {
            throw AlreadyChannelMemberException()
        }

        val saved = channelMemberRepository.save(
            ChannelMember(channel = channel, user = candidate, role = ChannelMemberRole.STAFF),
        )
        return saved.toResponse()
    }

    @Transactional
    fun removeMember(viewerId: Long, channelId: Long, memberId: Long) {
        val viewer = findActiveUser(viewerId)
        val channel = findChannel(channelId)
        ensureCanManage(viewer, channel)

        val member = channelMemberRepository.findById(memberId)
            .orElseThrow { ChannelMemberNotFoundException() }
        if (member.channel.id != channel.id) throw ChannelMemberNotFoundException()
        if (member.role == ChannelMemberRole.OWNER) throw CannotRemoveOwnerException()

        channelMemberRepository.delete(member)
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun findChannel(channelId: Long): Channel =
        channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }

    /** OWNER 또는 ADMIN 만 운영팀을 보고/관리할 수 있다. */
    private fun ensureCanManage(viewer: User, channel: Channel) {
        if (viewer.role == UserRole.ADMIN) return
        if (channel.owner.id == viewer.id) return
        throw UnauthorizedException()
    }

    private fun ChannelMember.toResponse() = ChannelMemberResponse(
        id = id,
        userId = user.id,
        nickname = user.nickname,
        email = user.email,
        role = role,
        joinedAt = joinedAt,
    )
}
