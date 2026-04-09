package com.contenido.domain.channel.service

import com.contenido.domain.channel.dto.ChannelDetailResponse
import com.contenido.domain.channel.dto.ChannelResponse
import com.contenido.domain.channel.dto.CreateChannelRequest
import com.contenido.domain.channel.dto.UpdateChannelRequest
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelSubscription
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.search.service.SearchSyncService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ChannelService(
    private val channelRepository: ChannelRepository,
    private val channelSubscriptionRepository: ChannelSubscriptionRepository,
    private val userRepository: UserRepository,
    private val searchSyncService: SearchSyncService,
) {

    @Transactional
    fun createChannel(userId: Long, request: CreateChannelRequest): ChannelResponse {
        val user = findActiveUser(userId)

        if (user.role != UserRole.CREATOR) throw NotCreatorException()
        if (channelRepository.existsByOwner(user)) throw DuplicateChannelException()

        val channel = channelRepository.save(
            Channel(
                owner = user,
                name = request.name,
                description = request.description,
                category = request.category,
                thumbnailUrl = request.thumbnailUrl,
            )
        )

        searchSyncService.syncChannel(channel)
        return channel.toResponse()
    }

    fun getChannel(channelId: Long, userId: Long?): ChannelDetailResponse {
        val channel = findChannel(channelId)

        val isSubscribed = userId?.let { uid ->
            val user = userRepository.findById(uid).orElse(null)
            user?.let { channelSubscriptionRepository.existsBySubscriberAndChannel(it, channel) } ?: false
        } ?: false

        return channel.toDetailResponse(isSubscribed)
    }

    @Transactional
    fun updateChannel(userId: Long, channelId: Long, request: UpdateChannelRequest): ChannelResponse {
        findActiveUser(userId)

        val channel = findChannel(channelId)

        if (channel.owner.id != userId) throw UnauthorizedContentAccessException()

        request.name?.let { channel.name = it }
        request.description?.let { channel.description = it }
        request.thumbnailUrl?.let { channel.thumbnailUrl = it }

        searchSyncService.syncChannel(channel)
        return channel.toResponse()
    }

    fun getChannelsByCategory(category: ChannelCategory, page: Int, size: Int): Page<ChannelResponse> {
        val pageable = PageRequest.of(page, size)
        return channelRepository.findByCategoryOrderBySubscriberCountDesc(category, pageable)
            .map { it.toResponse() }
    }

    @Transactional
    fun subscribe(userId: Long, channelId: Long) {
        val user = findActiveUser(userId)
        val channel = findChannel(channelId)

        if (channelSubscriptionRepository.existsBySubscriberAndChannel(user, channel)) {
            throw AlreadySubscribedException()
        }

        channelSubscriptionRepository.save(ChannelSubscription(subscriber = user, channel = channel))
        channel.increaseSubscriber()
    }

    @Transactional
    fun unsubscribe(userId: Long, channelId: Long) {
        val user = findActiveUser(userId)
        val channel = findChannel(channelId)

        if (!channelSubscriptionRepository.existsBySubscriberAndChannel(user, channel)) {
            throw NotSubscribedException()
        }

        channelSubscriptionRepository.deleteBySubscriberAndChannel(user, channel)
        channel.decreaseSubscriber()
    }

    fun getMySubscriptions(userId: Long, page: Int, size: Int): Page<ChannelResponse> {
        val user = findActiveUser(userId)
        val pageable = PageRequest.of(page, size)
        return channelSubscriptionRepository.findBySubscriber(user, pageable)
            .map { it.channel.toResponse() }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun findChannel(channelId: Long): Channel =
        channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }

    private fun Channel.toResponse() = ChannelResponse(
        id = id,
        ownerNickname = owner.nickname,
        name = name,
        description = description,
        category = category,
        categoryDisplayName = category.displayName,
        thumbnailUrl = thumbnailUrl,
        subscriberCount = subscriberCount,
        createdAt = createdAt,
    )

    private fun Channel.toDetailResponse(isSubscribed: Boolean) = ChannelDetailResponse(
        id = id,
        ownerNickname = owner.nickname,
        name = name,
        description = description,
        category = category,
        categoryDisplayName = category.displayName,
        thumbnailUrl = thumbnailUrl,
        subscriberCount = subscriberCount,
        createdAt = createdAt,
        isSubscribed = isSubscribed,
    )
}
