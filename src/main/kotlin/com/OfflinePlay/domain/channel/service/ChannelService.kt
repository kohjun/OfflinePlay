package com.contenido.domain.channel.service

import com.contenido.domain.channel.dto.ChannelDetailResponse
import com.contenido.domain.channel.dto.ChannelResponse
import com.contenido.domain.channel.dto.CreateChannelRequest
import com.contenido.domain.channel.dto.UpdateChannelRequest
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.entity.ChannelSubscription
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.event.ChannelSyncEvent
import com.contenido.global.exception.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ChannelService(
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelSubscriptionRepository: ChannelSubscriptionRepository,
    private val userRepository: UserRepository,
    private val reviewRepository: ReviewRepository,
    private val publisher: ApplicationEventPublisher,
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

        // 채널 owner를 ChannelMember(OWNER)로 자동 등록한다. 추후 STAFF 영입 흐름이
        // 들어와도 동일 테이블에서 다루도록 한다. existsBy* 검사는 idempotency용 방어선.
        if (!channelMemberRepository.existsByChannelAndUser(channel, user)) {
            channelMemberRepository.save(
                ChannelMember(channel = channel, user = user, role = ChannelMemberRole.OWNER)
            )
        }

        publisher.publishEvent(ChannelSyncEvent(channel.id))
        return channel.toResponse()
    }

    fun getChannel(channelId: Long, userId: Long?): ChannelDetailResponse {
        val channel = findChannel(channelId)
        // PR51 — 자동 숨김된 채널 단건 조회는 NotFound 로 처리.
        if (channel.isHidden) throw ChannelNotFoundException()

        val isSubscribed = userId?.let { uid ->
            val user = userRepository.findById(uid).orElse(null)
            user?.let { channelSubscriptionRepository.existsBySubscriberAndChannel(it, channel) } ?: false
        } ?: false

        // PR47 — 채널 hero 에 노출할 평균 별점 + 후기 수.
        val averageRating = reviewRepository.averageRatingByChannelId(channelId)
        val reviewCount = reviewRepository.countByChannelId(channelId)

        return channel.toDetailResponse(isSubscribed, averageRating, reviewCount)
    }

    @Transactional
    fun updateChannel(userId: Long, channelId: Long, request: UpdateChannelRequest): ChannelResponse {
        findActiveUser(userId)

        val channel = findChannel(channelId)

        if (channel.owner.id != userId) throw UnauthorizedContentAccessException()

        request.name?.let { channel.name = it }
        request.description?.let { channel.description = it }
        request.thumbnailUrl?.let { channel.thumbnailUrl = it }

        publisher.publishEvent(ChannelSyncEvent(channel.id))
        return channel.toResponse()
    }

    fun getChannelsByCategory(category: ChannelCategory, page: Int, size: Int): Page<ChannelResponse> {
        val pageable = PageRequest.of(page, size)
        // PR51 — 자동 숨김된 채널은 카테고리 목록에서 제외.
        val channels = channelRepository.findByCategoryAndHiddenAtIsNullOrderBySubscriberCountDesc(category, pageable)
        val ratingMap = ratingsByChannelIds(channels.content.map { it.id })
        return channels.map { ch ->
            val r = ratingMap[ch.id]
            ch.toResponse(averageRating = r?.first, reviewCount = r?.second ?: 0L)
        }
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
        val subs = channelSubscriptionRepository.findBySubscriber(user, pageable)
        val ratingMap = ratingsByChannelIds(subs.content.map { it.channel.id })
        return subs.map { sub ->
            val r = ratingMap[sub.channel.id]
            sub.channel.toResponse(averageRating = r?.first, reviewCount = r?.second ?: 0L)
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun findChannel(channelId: Long): Channel =
        channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }

    private fun Channel.toResponse(
        averageRating: Double? = null,
        reviewCount: Long = 0L,
    ) = ChannelResponse(
        id = id,
        ownerId = owner.id,
        ownerNickname = owner.nickname,
        name = name,
        description = description,
        category = category,
        categoryDisplayName = category.displayName,
        thumbnailUrl = thumbnailUrl,
        subscriberCount = subscriberCount,
        createdAt = createdAt,
        averageRating = averageRating,
        reviewCount = reviewCount,
    )

    private fun Channel.toDetailResponse(
        isSubscribed: Boolean,
        averageRating: Double? = null,
        reviewCount: Long = 0L,
    ) = ChannelDetailResponse(
        id = id,
        ownerId = owner.id,
        ownerNickname = owner.nickname,
        name = name,
        description = description,
        category = category,
        categoryDisplayName = category.displayName,
        thumbnailUrl = thumbnailUrl,
        subscriberCount = subscriberCount,
        createdAt = createdAt,
        isSubscribed = isSubscribed,
        averageRating = averageRating,
        reviewCount = reviewCount,
    )

    /**
     * PR47 — channelId 묶음에 대한 (averageRating, reviewCount) 매핑을 batch 로 조회.
     * 후기가 0건인 채널은 결과에 포함되지 않으므로 caller 가 null 처리.
     * channelIds 가 비어 있으면 즉시 빈 map.
     */
    private fun ratingsByChannelIds(channelIds: List<Long>): Map<Long, Pair<Double?, Long>> {
        if (channelIds.isEmpty()) return emptyMap()
        return reviewRepository.aggregateByChannelIds(channelIds).associate { row ->
            val id = (row[0] as Number).toLong()
            val avg = (row[1] as? Number)?.toDouble()
            val cnt = (row[2] as Number).toLong()
            id to (avg to cnt)
        }
    }
}
