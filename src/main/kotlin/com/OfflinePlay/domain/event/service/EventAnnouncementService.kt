package com.contenido.domain.event.service

import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.event.dto.CreateEventAnnouncementRequest
import com.contenido.domain.event.dto.EventAnnouncementResponse
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventAnnouncement
import com.contenido.domain.event.entity.EventAnnouncementImage
import com.contenido.domain.event.entity.EventAnnouncementRead
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventAnnouncementImageRepository
import com.contenido.domain.event.repository.EventAnnouncementReadRepository
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
import com.contenido.global.exception.NotificationNotFoundException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

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
    private val readRepository: EventAnnouncementReadRepository,
    private val imageRepository: EventAnnouncementImageRepository,
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

        // PR152 — 첨부 이미지 저장 (최대 3장, DTO bean validation 이 미리 거른다).
        val savedImages = if (request.imageUrls.isNotEmpty()) {
            val rows = request.imageUrls
                .take(3)
                .mapIndexed { index, url ->
                    EventAnnouncementImage(
                        announcementId = saved.id,
                        url = url,
                        displayOrder = index,
                    )
                }
            imageRepository.saveAll(rows)
            rows.map { it.url }
        } else emptyList()

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

        return EventAnnouncementResponse.from(saved, read = false, imageUrls = savedImages)
    }

    fun list(userId: Long, eventId: Long): List<EventAnnouncementResponse> {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)
        ensureCanRead(user, event)
        val items = announcementRepository.findByEventOrderByCreatedAtDesc(event)
        if (items.isEmpty()) return emptyList()

        // PR151 — pinned 상단 고정 + viewer 의 read 여부 추가.
        val readIds = readRepository.findByUserIdAndAnnouncementIdIn(userId, items.map { it.id })
            .map { it.announcementId }
            .toSet()
        // PR152 — 첨부 이미지 묶음 (N+1 회피).
        val imagesByAnnouncement = imageRepository
            .findByAnnouncementIdInOrderByAnnouncementIdAscDisplayOrderAsc(items.map { it.id })
            .groupBy({ it.announcementId }, { it.url })

        return items
            .sortedWith(
                compareByDescending<EventAnnouncement> { it.pinnedAt != null }
                    .thenByDescending { it.createdAt },
            )
            .map {
                EventAnnouncementResponse.from(
                    announcement = it,
                    read = it.id in readIds,
                    imageUrls = imagesByAnnouncement[it.id] ?: emptyList(),
                )
            }
    }

    /**
     * PR151 — pin 토글. 같은 이벤트의 기존 pinned 는 자동 해제 (한 이벤트 동시 1건만).
     *  - pinned=true 요청: 기존 pinned 해제 후 본 announcement 만 pin.
     *  - pinned=false 요청: 본 announcement 만 unpin (다른 row 영향 없음).
     */
    @Transactional
    fun setPinned(
        userId: Long,
        eventId: Long,
        announcementId: Long,
        pinned: Boolean,
    ): EventAnnouncementResponse {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)
        ensureCanWrite(user, event)

        val target = announcementRepository.findById(announcementId)
            .orElseThrow { NotificationNotFoundException() }
        if (target.event.id != event.id) throw NotificationNotFoundException()

        if (pinned) {
            // 같은 이벤트의 기존 pinned 모두 해제 (보통 0 또는 1건).
            announcementRepository.findByEventAndPinnedAtIsNotNull(event)
                .filter { it.id != target.id }
                .forEach { it.unpin() }
            target.pin()
        } else {
            target.unpin()
        }
        return EventAnnouncementResponse.from(target, read = isReadByUser(target.id, userId))
    }

    /**
     * PR151 — read receipt 멱등 upsert. 권한 가드: ensureCanRead.
     *  - row 가 이미 있으면 readAt 만 갱신.
     */
    @Transactional
    fun markAsRead(userId: Long, eventId: Long, announcementId: Long) {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)
        ensureCanRead(user, event)

        val target = announcementRepository.findById(announcementId)
            .orElseThrow { NotificationNotFoundException() }
        if (target.event.id != event.id) throw NotificationNotFoundException()

        val now = LocalDateTime.now()
        val existing = readRepository.findById(
            com.contenido.domain.event.entity.EventAnnouncementReadId(
                announcementId = announcementId, userId = userId,
            ),
        ).orElse(null)
        if (existing != null) {
            existing.readAt = now
        } else {
            readRepository.save(
                EventAnnouncementRead(
                    announcementId = announcementId,
                    userId = userId,
                    readAt = now,
                ),
            )
        }
    }

    fun unreadCount(userId: Long, eventId: Long): Long {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)
        ensureCanRead(user, event)
        return readRepository.countUnreadByEventIdAndUserId(eventId = event.id, userId = userId)
    }

    private fun isReadByUser(announcementId: Long, userId: Long): Boolean =
        readRepository.findById(
            com.contenido.domain.event.entity.EventAnnouncementReadId(
                announcementId = announcementId, userId = userId,
            ),
        ).isPresent

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
