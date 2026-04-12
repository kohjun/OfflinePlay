package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminChannelResponse
import com.contenido.domain.admin.dto.AdminUserResponse
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.report.dto.ReportResponse
import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AlreadyBannedException
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminService(
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
    private val reportRepository: ReportRepository,
) {

    fun getUsers(page: Int, size: Int): Page<AdminUserResponse> =
        userRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
            .map { it.toAdminResponse() }

    fun getUser(userId: Long): AdminUserResponse =
        userRepository.findById(userId).orElseThrow { UserNotFoundException() }.toAdminResponse()

    @Transactional
    fun banUser(userId: Long): AdminUserResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw AlreadyBannedException()
        user.softDelete()
        return user.toAdminResponse()
    }

    fun getChannels(page: Int, size: Int): Page<AdminChannelResponse> =
        channelRepository.findAll(PageRequest.of(page, size))
            .map { it.toAdminResponse() }

    @Transactional
    fun banChannel(channelId: Long): AdminChannelResponse {
        val channel = channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }
        if (!channel.isActive) throw AlreadyBannedException()
        channel.deactivate()
        return channel.toAdminResponse()
    }

    fun getReports(page: Int, size: Int): Page<ReportResponse> =
        reportRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
            .map { it.toResponse() }

    // ── private ──────────────────────────────────────────────────────────────

    private fun User.toAdminResponse() = AdminUserResponse(
        id = id,
        email = email,
        nickname = nickname,
        role = role,
        isDeleted = isDeleted,
        createdAt = createdAt,
    )

    private fun Channel.toAdminResponse() = AdminChannelResponse(
        id = id,
        name = name,
        ownerNickname = owner.nickname,
        category = category,
        categoryDisplayName = category.displayName,
        subscriberCount = subscriberCount,
        isActive = isActive,
        createdAt = createdAt,
    )

    private fun Report.toResponse() = ReportResponse(
        id = id,
        reporterNickname = reporter.nickname,
        targetType = targetType,
        targetId = targetId,
        reason = reason,
        status = status,
        createdAt = createdAt,
    )
}
