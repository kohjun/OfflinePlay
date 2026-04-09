package com.contenido.domain.stats.service

import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.stats.dto.ChannelStatsResponse
import com.contenido.domain.stats.dto.EventStatItem
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class ChannelStatsService(
    private val channelRepository: ChannelRepository,
    private val channelSubscriptionRepository: ChannelSubscriptionRepository,
    private val postRepository: PostRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
) {

    fun getChannelStats(userId: Long, channelId: Long): ChannelStatsResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val channel = channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }

        if (channel.owner.id != userId) throw UnauthorizedException()

        val startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay()

        val totalSubscribers = channelSubscriptionRepository.countByChannel(channel)
        val newSubscribersThisMonth = channelSubscriptionRepository
            .countByChannelAndCreatedAtGreaterThanEqual(channel, startOfMonth)
        val totalViewCount = postRepository.sumViewCountByChannel(channel)
        val totalLikeCount = postRepository.sumLikeCountByChannel(channel)

        val events = eventRepository.findByChannel(channel)
        val eventStats = events.map { event ->
            val rate = event.maxParticipants?.let { max ->
                if (max > 0) event.currentParticipants.toDouble() / max else null
            }
            EventStatItem(
                eventId = event.id,
                eventTitle = event.title,
                currentParticipants = event.currentParticipants,
                maxParticipants = event.maxParticipants,
                participationRate = rate,
            )
        }

        return ChannelStatsResponse(
            channelId = channel.id,
            channelName = channel.name,
            totalSubscribers = totalSubscribers,
            newSubscribersThisMonth = newSubscribersThisMonth,
            totalViewCount = totalViewCount,
            totalLikeCount = totalLikeCount,
            eventStats = eventStats,
        )
    }
}
