package com.contenido.domain.event.service

import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.event.dto.CreateEventAnnouncementRequest
import com.contenido.domain.event.dto.EventAnnouncementResponse
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventAnnouncement
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventAnnouncementRepository
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.EventNotFoundException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR141 — 이벤트 공지 생성 / 조회 / 알림 fan-out.
 *
 * 권한 정책:
 *  - 작성: 이벤트 채널 owner / 채널 STAFF / ADMIN.
 *  - 목록 조회: 위 + 해당 이벤트의 APPROVED 참가자.
 *
 * 알림 수신자(active 참가자):
 *  - participation.status = APPROVED
 *  - 티켓이 있으면 PAID / USED / PARTIALLY_REFUNDED 만 (CANCELED / REFUNDED 제외).
 *  - REJECTED / CANCELED participation 은 항상 제외.
 *
 * NotificationService 가 preference 와 push dispatch 를 책임지므로, 본 서비스는 단순히
 * 수신자 ID 묶음만 계산해 [NotificationService.notify] 를 호출한다 (best-effort).
 */
@Service
@Transactional(readOnly = true)
class EventAnnouncementService(
    private val announcementRepository: EventAnnouncementRepository,
    private val eventRepository: EventRepository,
    private val participationRepository: EventParticipationRepository,
    private val ticketRepository: TicketRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(
        userId: Long,
        eventId: Long,
        request: CreateEventAnnouncementRequest,
    ): EventAnnouncementResponse {
        val author = findActiveUser(userId)
        val event = findEvent(eventId)
        ensureCanWrite(author, event)

        val saved = announcementRepository.save(
            EventAnnouncement(
                event = event,
                author = author,
                title = request.title.trim(),
                content = request.content.trim(),
            ),
        )

        runCatching {
            val receivers = activeParticipantIds(event)
            if (receivers.isNotEmpty()) {
                notificationService.notify(
                    receiverIds = receivers,
                    type = NotificationType.EVENT_ANNOUNCEMENT,
                    title = "[공지] ${event.title}",
                    message = saved.title,
                    targetType = "events",
                    targetId = event.id,
                )
            }
        }.onFailure { e ->
            log.warn("[announcement] notify failed eventId={} err={}", event.id, e.message)
        }

        return EventAnnouncementResponse.from(saved)
    }

    fun list(userId: Long, eventId: Long): List<EventAnnouncementResponse> {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)
        ensureCanRead(user, event)
        return announcementRepository.findByEventOrderByCreatedAtDesc(event)
            .map(EventAnnouncementResponse::from)
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    /**
     * 활성 참가자 user id 묶음.
     *
     *  - APPROVED 만 후보.
     *  - 유료 이벤트면 buyer 의 최근 티켓 상태가 CANCELED/REFUNDED 면 제외.
     *  - 무료 이벤트(participationFee = 0) 면 티켓 검증 없이 그대로 통과.
     */
    private fun activeParticipantIds(event: Event): List<Long> {
        val approvedParticipants = participationRepository.findByEventOrderByJoinedAtDesc(event)
            .filter { it.status == ParticipationStatus.APPROVED }
        if (approvedParticipants.isEmpty()) return emptyList()

        if (event.participationFee <= 0L) {
            return approvedParticipants.map { it.participant.id }
        }

        val approvedBuyerIds = approvedParticipants.map { it.participant.id }
        val ticketsByBuyer = ticketRepository.findByEventAndBuyerIdIn(event, approvedBuyerIds)
            .groupBy { it.buyer.id }
            .mapValues { (_, tickets) -> tickets.maxByOrNull { it.purchasedAt }!! }

        return approvedParticipants.mapNotNull { participation ->
            val buyerId = participation.participant.id
            val ticket = ticketsByBuyer[buyerId]
            when {
                ticket == null -> null  // 유료 이벤트에서 티켓이 없는 APPROVED 는 비정상이지만 안전한 쪽으로 제외
                ticket.status == TicketStatus.CANCELED || ticket.status == TicketStatus.REFUNDED -> null
                else -> buyerId
            }
        }
    }

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun findEvent(eventId: Long): Event =
        eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }

    /** owner / 채널 STAFF 멤버 / ADMIN 만 공지 작성 가능. */
    private fun ensureCanWrite(user: User, event: Event) {
        if (user.role == UserRole.ADMIN) return
        if (event.channel.owner.id == user.id) return
        val isStaff = channelMemberRepository.findByChannelAndUser(event.channel, user).isPresent
        if (!isStaff) throw UnauthorizedException()
    }

    /** 작성자 자격 + 해당 이벤트 APPROVED 참가자라면 조회 가능. */
    private fun ensureCanRead(user: User, event: Event) {
        if (user.role == UserRole.ADMIN) return
        if (event.channel.owner.id == user.id) return
        val isStaff = channelMemberRepository.findByChannelAndUser(event.channel, user).isPresent
        if (isStaff) return
        val isApprovedParticipant = participationRepository
            .findByEventAndParticipant(event, user)
            .map { it.status == ParticipationStatus.APPROVED }
            .orElse(false)
        if (!isApprovedParticipant) throw UnauthorizedException()
    }
}
