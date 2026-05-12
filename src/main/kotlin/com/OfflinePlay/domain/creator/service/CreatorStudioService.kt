package com.contenido.domain.creator.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.creator.dto.CreatorStudioChannel
import com.contenido.domain.creator.dto.CreatorStudioEvent
import com.contenido.domain.creator.dto.CreatorStudioResponse
import com.contenido.domain.creator.dto.CreatorStudioSummary
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.NotCreatorException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * CREATOR/ADMIN 가 운영 홈에서 보는 묶음 데이터를 만든다.
 *
 *  - 채널 1건 (없으면 null)
 *  - 채널의 이벤트 (시작 시각 내림차순) + 각 이벤트의 PENDING/APPROVED/REJECTED/CANCELED 카운트
 *  - 4-tile summary
 *
 * 이벤트별 카운트는 단일 GROUP BY 쿼리로 가져와 N+1 을 피한다.
 */
@Service
@Transactional(readOnly = true)
class CreatorStudioService(
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
    private val eventRepository: EventRepository,
    private val eventParticipationRepository: EventParticipationRepository,
) {

    fun getStudio(userId: Long): CreatorStudioResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) throw NotCreatorException()

        val channel = channelRepository.findByOwner(user).orElse(null)
            ?: return CreatorStudioResponse(
                channel = null,
                events = emptyList(),
                summary = CreatorStudioSummary(0, 0L, 0L, 0L),
            )

        val events: List<Event> = eventRepository.findByChannel(channel)
            .sortedByDescending { it.startAt }

        val countMap = buildCountMap(channel)

        val eventDtos = events.map { e ->
            val byStatus = countMap[e.id] ?: emptyMap()
            CreatorStudioEvent(
                id = e.id,
                title = e.title,
                status = e.status,
                startAt = e.startAt,
                location = e.location,
                mainImageUrl = e.mainImageUrl,
                currentParticipants = e.currentParticipants,
                maxParticipants = e.maxParticipants,
                pendingCount = byStatus[ParticipationStatus.PENDING] ?: 0L,
                approvedCount = byStatus[ParticipationStatus.APPROVED] ?: 0L,
                rejectedCount = byStatus[ParticipationStatus.REJECTED] ?: 0L,
                canceledCount = byStatus[ParticipationStatus.CANCELED] ?: 0L,
            )
        }

        val totalPending = eventDtos.sumOf { it.pendingCount }
        val totalApproved = eventDtos.sumOf { it.approvedCount }

        return CreatorStudioResponse(
            channel = CreatorStudioChannel(
                id = channel.id,
                name = channel.name,
                description = channel.description,
                category = channel.category,
                categoryDisplayName = channel.category.displayName,
                thumbnailUrl = channel.thumbnailUrl,
                subscriberCount = channel.subscriberCount,
                ownerNickname = channel.owner.nickname,
            ),
            events = eventDtos,
            summary = CreatorStudioSummary(
                totalEvents = eventDtos.size,
                pendingApplicants = totalPending,
                approvedParticipants = totalApproved,
                subscriberCount = channel.subscriberCount,
            ),
        )
    }

    /**
     * 채널의 모든 이벤트에 대한 status 별 카운트를 `Map<eventId, Map<status, count>>` 로 정리한다.
     */
    private fun buildCountMap(channel: Channel): Map<Long, Map<ParticipationStatus, Long>> {
        val rows = eventParticipationRepository.countByChannelGroupedByStatus(channel)
        val result = mutableMapOf<Long, MutableMap<ParticipationStatus, Long>>()
        for (row in rows) {
            val eventId = (row[0] as Number).toLong()
            val status = row[1] as ParticipationStatus
            val count = (row[2] as Number).toLong()
            result.getOrPut(eventId) { mutableMapOf() }[status] = count
        }
        return result
    }
}
