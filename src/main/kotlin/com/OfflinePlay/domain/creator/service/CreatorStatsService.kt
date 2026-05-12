package com.contenido.domain.creator.service

import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.content.repository.ContentRepository
import com.contenido.domain.creator.dto.CreatorStatsResponse
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CreatorStatsService(
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
    private val eventRepository: EventRepository,
    private val contentRepository: ContentRepository,
) {

    fun getStats(userId: Long): CreatorStatsResponse {
        val creator = userRepository.findById(userId).orElseThrow { UserNotFoundException() }

        return CreatorStatsResponse(
            channelCount = channelRepository.countByOwner(creator),
            eventCount = eventRepository.countByChannelOwner(creator),
            contentCount = contentRepository.countByCreator(creator),
            subscriberCount = channelRepository.sumSubscriberCountByOwner(creator) ?: 0L,
        )
    }
}
