package com.contenido.domain.event.service

import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.event.dto.EventChatHistoryResponse
import com.contenido.domain.event.dto.EventChatMessageResponse
import com.contenido.domain.event.dto.SendEventChatMessageRequest
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventChatMessage
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventChatMessageRepository
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.notification.service.SseEmitterService
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.EventChatAnnouncementForbiddenException
import com.contenido.global.exception.EventNotFoundException
import com.contenido.global.exception.EventRoomAccessDeniedException
import com.contenido.global.exception.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * PR160 — 이벤트룸 채팅 (text only MVP).
 *
 * 입장 권한:
 *  - 채널 owner / STAFF / ADMIN
 *  - 해당 이벤트의 APPROVED 참가자 + (무료 또는 ticket NOT IN CANCELED/REFUNDED)
 *  그 외 → [EventRoomAccessDeniedException] (403)
 *
 * 공지 메시지 (isAnnouncement=true):
 *  - owner / STAFF / ADMIN 만 허용. 일반 참가자가 시도 → [EventChatAnnouncementForbiddenException].
 *  - 저장 후 `NotificationService.notify(EVENT_ANNOUNCEMENT)` 로 push 발송 (preference 필터 적용).
 *  - 일반 메시지는 SSE broadcast 만 — push 알림 없음.
 *
 * 실시간 fan-out: [SseEmitterService.broadcast] 로 룸 멤버 user id 묶음에 SSE 'event-chat' event 전송.
 */
@Service
@Transactional(readOnly = true)
class EventChatService(
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
    private val participationRepository: EventParticipationRepository,
    private val ticketRepository: TicketRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val chatRepository: EventChatMessageRepository,
    private val notificationService: NotificationService,
    private val sseEmitterService: SseEmitterService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
        const val SSE_EVENT_NAME = "event-chat"
    }

    /**
     * 룸 입장 확인용. 권한 없으면 throw — frontend 는 화면 자체를 hide 해야 하지만 backend 가 최종 가드.
     */
    fun assertCanEnter(userId: Long, eventId: Long): Event {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)
        ensureCanEnter(user, event)
        return event
    }

    /**
     * 최신 메시지 N건. `before` cursor 가 주어지면 그 이전 페이지.
     *  - 응답 items 는 시간 오름차순 (오래된→새것) — 카톡식.
     *  - 마지막 row 의 createdAt/id 를 nextBefore* 로 노출 — 더 과거 없으면 null.
     */
    fun history(
        userId: Long,
        eventId: Long,
        beforeCreatedAt: LocalDateTime?,
        beforeId: Long?,
        size: Int,
    ): EventChatHistoryResponse {
        val event = assertCanEnter(userId, eventId)
        val pageSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val pageable = PageRequest.of(0, pageSize)
        val descending = if (beforeCreatedAt != null && beforeId != null) {
            chatRepository.findBeforeByEvent(event, beforeCreatedAt, beforeId, pageable)
        } else {
            chatRepository.findRecentByEvent(event, pageable)
        }

        // descending 마지막 row 가 가장 과거 → 그 row 의 createdAt/id 가 다음 cursor.
        val nextCreatedAt = descending.lastOrNull()?.createdAt
        val nextId = descending.lastOrNull()?.id
        val items = descending.reversed().map(EventChatMessageResponse::from)
        val hasMore = descending.size == pageSize
        return EventChatHistoryResponse(
            items = items,
            nextBeforeCreatedAt = if (hasMore) nextCreatedAt else null,
            nextBeforeId = if (hasMore) nextId else null,
        )
    }

    /**
     * 메시지 송신.
     *  - 권한 가드 + 저장.
     *  - 룸 멤버 user id 묶음에 SSE broadcast (sender 본인 포함 — 본인 화면에도 echo 가 와야 자연스러움).
     *  - isAnnouncement=true 면 NotificationService.notify(EVENT_ANNOUNCEMENT) 추가 — preference 통과한
     *    수신자에게 push 발송. 본인은 receiver 묶음에서 제외.
     */
    @Transactional
    fun send(
        userId: Long,
        eventId: Long,
        request: SendEventChatMessageRequest,
    ): EventChatMessageResponse {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)
        ensureCanEnter(user, event)

        if (request.isAnnouncement && !isOperator(user, event)) {
            throw EventChatAnnouncementForbiddenException()
        }

        val saved = chatRepository.save(
            EventChatMessage(
                event = event,
                sender = user,
                content = request.content.trim(),
                isAnnouncement = request.isAnnouncement,
            ),
        )
        val response = EventChatMessageResponse.from(saved)

        // SSE broadcast — 룸 멤버 user id 묶음.
        val roomMemberIds = roomMemberUserIds(event)
        runCatching { sseEmitterService.broadcast(roomMemberIds, SSE_EVENT_NAME, response) }
            .onFailure { e -> log.warn("[chat] SSE broadcast failed eventId={} err={}", event.id, e.message) }

        // 공지 메시지면 추가로 push 발송. NotificationService 가 preference / push dispatch 책임.
        if (saved.isAnnouncement) {
            val receivers = roomMemberIds.filter { it != user.id }
            if (receivers.isNotEmpty()) {
                runCatching {
                    notificationService.notify(
                        receiverIds = receivers,
                        type = NotificationType.EVENT_ANNOUNCEMENT,
                        title = "[공지] ${event.title}",
                        message = saved.content.take(80),
                        targetType = "events",
                        targetId = event.id,
                    )
                }.onFailure { e -> log.warn("[chat] announcement push failed eventId={} err={}", event.id, e.message) }
            }
        }

        return response
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    /**
     * 입장 가드. owner / STAFF / ADMIN 또는 (APPROVED 참가 + 활성 ticket) 통과.
     */
    private fun ensureCanEnter(user: User, event: Event) {
        if (isOperator(user, event)) return

        // 참가자 — APPROVED + ticket 활성
        val approved = participationRepository.existsByEventAndParticipantAndStatusIn(
            event, user, listOf(ParticipationStatus.APPROVED),
        )
        if (!approved) throw EventRoomAccessDeniedException()

        // 무료 이벤트는 ticket 검증 생략. 유료 이벤트는 최근 ticket 이 CANCELED/REFUNDED 면 제외.
        if (event.participationFee > 0L) {
            val ticket = ticketRepository
                .findByEventAndBuyerIdIn(event, listOf(user.id))
                .maxByOrNull { it.purchasedAt }
            if (ticket == null) throw EventRoomAccessDeniedException()
            if (ticket.status == TicketStatus.CANCELED || ticket.status == TicketStatus.REFUNDED) {
                throw EventRoomAccessDeniedException()
            }
        }
    }

    /** owner / 채널 STAFF / ADMIN 인지. */
    private fun isOperator(user: User, event: Event): Boolean {
        if (user.role == UserRole.ADMIN) return true
        if (event.channel.owner.id == user.id) return true
        return channelMemberRepository.findByChannelAndUser(event.channel, user).isPresent
    }

    /**
     * 룸의 active 멤버 user id 묶음. push broadcast / 공지 receiver 계산.
     *  - owner + STAFF (channel_members)
     *  - APPROVED 참가자 + 활성 ticket (유료: PAID/USED/PARTIALLY_REFUNDED, 무료: 무조건)
     */
    fun roomMemberUserIds(event: Event): List<Long> {
        val ids = linkedSetOf<Long>()
        ids.add(event.channel.owner.id)
        channelMemberRepository.findByChannel(event.channel).forEach { ids.add(it.user.id) }

        val approvedParticipants = participationRepository.findByEventOrderByJoinedAtDesc(event)
            .filter { it.status == ParticipationStatus.APPROVED }
        if (event.participationFee <= 0L) {
            approvedParticipants.forEach { ids.add(it.participant.id) }
        } else {
            val approvedBuyerIds = approvedParticipants.map { it.participant.id }
            val ticketsByBuyer = if (approvedBuyerIds.isEmpty()) emptyMap()
            else ticketRepository.findByEventAndBuyerIdIn(event, approvedBuyerIds)
                .groupBy { it.buyer.id }
                .mapValues { (_, tickets) -> tickets.maxByOrNull { it.purchasedAt }!! }
            approvedParticipants.forEach { p ->
                val ticket = ticketsByBuyer[p.participant.id]
                if (ticket != null &&
                    ticket.status != TicketStatus.CANCELED &&
                    ticket.status != TicketStatus.REFUNDED
                ) {
                    ids.add(p.participant.id)
                }
            }
        }
        return ids.toList()
    }

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun findEvent(eventId: Long): Event =
        eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }
}
