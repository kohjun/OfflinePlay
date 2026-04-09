package com.contenido.domain.event.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.event.dto.CreateEventRequest
import com.contenido.domain.event.dto.EventResponse
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.search.service.SearchSyncService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class EventService(
    private val eventRepository: EventRepository,
    private val eventParticipationRepository: EventParticipationRepository,
    private val channelRepository: ChannelRepository,
    private val channelSubscriptionRepository: ChannelSubscriptionRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val searchSyncService: SearchSyncService,
) {

    @Transactional
    fun createEvent(userId: Long, channelId: Long, request: CreateEventRequest): EventResponse {
        val user = findActiveUser(userId)
        val channel = findChannel(channelId)

        if (channel.owner.id != userId) throw UnauthorizedException()

        val event = eventRepository.save(
            Event(
                channel = channel,
                title = request.title,
                description = request.description,
                thumbnailUrl = request.thumbnailUrl,
                startAt = request.startAt,
                endAt = request.endAt,
                maxParticipants = request.maxParticipants,
            )
        )

        searchSyncService.syncEvent(event)

        // 채널 구독자 전원에게 NEW_EVENT 알림
        val subscriberIds = channelSubscriptionRepository.findByChannel(channel)
            .map { it.subscriber.id }
        runCatching {
            notificationService.notify(
                receiverIds = subscriberIds,
                type = NotificationType.NEW_EVENT,
                title = "${channel.name}에 새 이벤트가 등록되었습니다.",
                message = event.title,
                targetType = "events",
                targetId = event.id,
            )
        }

        return event.toResponse()
    }

    fun getEvents(channelId: Long, page: Int, size: Int): Page<EventResponse> {
        val channel = findChannel(channelId)
        return eventRepository.findByChannelOrderByStartAtDesc(channel, PageRequest.of(page, size))
            .map { it.toResponse() }
    }

    fun getEvent(eventId: Long): EventResponse =
        findEvent(eventId).toResponse()

    @Transactional
    fun joinEvent(userId: Long, eventId: Long) {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)

        if (eventParticipationRepository.existsByEventAndParticipant(event, user)) {
            throw AlreadyJoinedException()
        }
        if (event.isFull()) throw EventFullException()

        eventParticipationRepository.save(EventParticipation(event = event, participant = user))
        event.increaseParticipant()
    }

    @Transactional
    fun cancelJoin(userId: Long, eventId: Long) {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)

        if (!eventParticipationRepository.existsByEventAndParticipant(event, user)) return

        eventParticipationRepository.deleteByEventAndParticipant(event, user)
        if (event.currentParticipants > 0) event.currentParticipants--
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun findChannel(channelId: Long): Channel =
        channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }

    private fun findEvent(eventId: Long): Event =
        eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }

    private fun Event.toResponse() = EventResponse(
        id = id,
        channelId = channel.id,
        channelName = channel.name,
        title = title,
        description = description,
        thumbnailUrl = thumbnailUrl,
        startAt = startAt,
        endAt = endAt,
        maxParticipants = maxParticipants,
        currentParticipants = currentParticipants,
        status = status,
        createdAt = createdAt,
    )
}
