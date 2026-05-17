package com.contenido.domain.event.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.event.dto.CreateEventRequest
import com.contenido.domain.event.dto.EventResponse
import com.contenido.domain.event.dto.MyParticipationItemResponse
import com.contenido.domain.event.dto.ParticipationApplicantResponse
import com.contenido.domain.event.dto.ParticipationResponse
import com.contenido.domain.event.dto.UpdateEventRequest
import com.contenido.domain.event.entity.ContentType
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.ticket.service.TicketService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.event.ContentSyncAction
import com.contenido.global.event.ContentSyncEvent
import com.contenido.global.exception.*
import java.time.LocalDateTime
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class EventService(
    private val eventRepository: EventRepository,
    private val eventParticipationRepository: EventParticipationRepository,
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val channelSubscriptionRepository: ChannelSubscriptionRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val ticketService: TicketService,
    private val ticketRepository: TicketRepository,
    private val paymentAttemptRepository: com.contenido.domain.payment.repository.PaymentAttemptRepository,
    private val reviewRepository: ReviewRepository,
    private val publisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createEvent(userId: Long, channelId: Long, request: CreateEventRequest): EventResponse {
        findActiveUser(userId)
        val channel = findChannel(channelId)

        if (channel.owner.id != userId) throw UnauthorizedException()

        if (!request.endAt.isAfter(request.startAt)) {
            throw InvalidEventDateRangeException()
        }

        val event = eventRepository.save(
            Event(
                channel = channel,
                title = request.title,
                description = request.description,
                location = request.location,
                mainImageUrl = request.mainImageUrl,
                startAt = request.startAt,
                endAt = request.endAt,
                maxParticipants = request.maxParticipants,
                participationFee = request.participationFee,
                refundPolicy = request.refundPolicy,
                detailContent = request.detailContent,
                contentType = request.contentType ?: ContentType.SPECIAL,
            )
        )

        publisher.publishEvent(ContentSyncEvent(ContentSyncAction.SYNC, "EVENT", event.id))

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
        // PR51 — 자동 숨김된 이벤트는 사용자 목록 조회에서 제외.
        val events = eventRepository.findByChannelAndHiddenAtIsNullOrderByStartAtDesc(channel, PageRequest.of(page, size))
        val ratingMap = ratingsByEventIds(events.content.map { it.id })
        return events.map { e ->
            val r = ratingMap[e.id]
            e.toResponse(averageRating = r?.first, reviewCount = r?.second ?: 0L)
        }
    }

    fun getEvent(eventId: Long): EventResponse {
        val event = findEvent(eventId)
        // PR51 — 자동 숨김된 이벤트 단건 조회는 NotFound 로 처리.
        if (event.isHidden) throw EventNotFoundException()
        return event.toResponse(
            averageRating = reviewRepository.averageRatingByEventId(eventId),
            reviewCount = reviewRepository.countByEvent(event),
        )
    }

    /**
     * 이벤트 수정. 채널 owner 또는 ADMIN 만 허용. 모든 필드 nullable — null 은 변경하지 않음.
     *
     * 안전 정책:
     *  - participationFee 변경은 발급된 티켓이 1건이라도 있으면 거부.
     *  - maxParticipants 는 현재 참가자 수보다 작게 줄일 수 없음.
     *  - startAt/endAt 중 한쪽만 와도 결합 후 (start < end) 만 검증.
     */
    @Transactional
    fun updateEvent(userId: Long, eventId: Long, request: UpdateEventRequest): EventResponse {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)

        if (event.channel.owner.id != userId && user.role != UserRole.ADMIN) {
            throw UnauthorizedException()
        }

        val newStart = request.startAt ?: event.startAt
        val newEnd = request.endAt ?: event.endAt
        if (!newEnd.isAfter(newStart)) throw InvalidEventDateRangeException()

        request.maxParticipants?.let { newMax ->
            if (newMax < event.currentParticipants) {
                throw MaxParticipantsBelowCurrentException()
            }
            if (newMax < 1) throw MaxParticipantsBelowCurrentException()
            event.maxParticipants = newMax
        }
        request.participationFee?.let { newFee ->
            // 이미 티켓이 발급된 이벤트는 참가비 변경 차단. PAID/USED 어떤 상태든 위험.
            val anyTicket = ticketRepository.findByEvent(event).isNotEmpty()
            if (anyTicket && newFee != event.participationFee) {
                throw EventHasIssuedTicketsException()
            }
            if (newFee < 0) throw EventHasIssuedTicketsException()
            event.participationFee = newFee
        }

        request.title?.let { event.title = it }
        request.description?.let { event.description = it }
        request.location?.let { event.location = it }
        request.mainImageUrl?.let { event.mainImageUrl = it }
        request.refundPolicy?.let { event.refundPolicy = it }
        request.detailContent?.let { event.detailContent = it }
        request.contentType?.let { event.contentType = it }
        event.startAt = newStart
        event.endAt = newEnd

        publisher.publishEvent(ContentSyncEvent(ContentSyncAction.SYNC, "EVENT", event.id))

        return event.toResponse(
            averageRating = reviewRepository.averageRatingByEventId(event.id),
            reviewCount = reviewRepository.countByEvent(event),
        )
    }

    // ─── Participation: 신청 → 승인/거절 워크플로 ─────────────────────────────────

    /**
     * 참가 신청. PENDING 상태로 저장되며 Event.currentParticipants 는 변하지 않는다
     * (승인 시점에만 증가). 이미 PENDING/APPROVED 신청이 있으면 [AlreadyJoinedException].
     * REJECTED/CANCELED 상태가 있으면 같은 row 를 PENDING 으로 복구한다.
     */
    @Retryable(retryFor = [OptimisticLockingFailureException::class], maxAttempts = 3)
    @Transactional
    fun applyForEvent(userId: Long, eventId: Long): ParticipationResponse {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)

        if (event.status == EventStatus.CLOSED) throw EventClosedException()
        if (event.isFull()) throw EventFullException()
        if (event.channel.owner.id == userId) throw OwnerCannotApplyException()

        val existing = eventParticipationRepository.findByEventAndParticipant(event, user)
        val participation = if (existing.isPresent) {
            val p = existing.get()
            when (p.status) {
                ParticipationStatus.PENDING, ParticipationStatus.APPROVED ->
                    throw AlreadyJoinedException()
                ParticipationStatus.REJECTED, ParticipationStatus.CANCELED -> {
                    p.reapply()
                    p
                }
            }
        } else {
            eventParticipationRepository.save(EventParticipation(event = event, participant = user))
        }

        // 채널 owner + STAFF 에게 새 참가 신청 알림. 재신청도 새 신청으로 본다. 실패는 비치명.
        runCatching {
            notificationService.notify(
                receiverIds = ownerAndStaffIds(event.channel),
                type = NotificationType.PARTICIPATION_REQUESTED,
                title = "새 참가 신청",
                message = "${user.nickname}님이 ${event.title}에 참가 신청했어요.",
                targetType = "events",
                targetId = event.id,
            )
        }.onFailure { e ->
            log.warn("[applyForEvent] owner/staff notify failed: {}", e.message)
        }

        return participation.toResponse()
    }

    /**
     * 참가자 본인이 신청을 취소.
     *
     *  - PENDING : 즉시 CANCELED 로 전환 (정원/티켓 영향 없음).
     *  - APPROVED (무료): 이벤트 시작 전이고 티켓이 USED 가 아닐 때만 허용.
     *    - CANCELED 전환 + Event.currentParticipants 감소
     *    - 연결된 PAID 티켓이 있으면 CANCELED 로 전환
     *    - 채널 owner + STAFF 에게 PARTICIPATION_CANCELED 알림 (best-effort, ADMIN 제외)
     *  - APPROVED (유료): [PaidParticipationCancelRequiresRefundException].
     *    cancelMyApplication 은 PG 환불을 트리거하지 않으므로, 유료 결제는 ticket refund
     *    endpoint 로만 취소·환불해야 한다. 그대로 취소를 허용하면 티켓이 CANCELED 로 잠겨
     *    이후 환불이 PaymentNotRefundableException 으로 거부된다 (= 결제만 못 돌려받음).
     *  - REJECTED/CANCELED: [ParticipationNotPendingException] (이미 종료된 상태).
     */
    @Transactional
    fun cancelMyApplication(userId: Long, eventId: Long): ParticipationResponse {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)

        val p = eventParticipationRepository.findByEventAndParticipant(event, user)
            .orElseThrow { ParticipationNotFoundException() }

        when (p.status) {
            ParticipationStatus.PENDING -> {
                p.cancel()
            }
            ParticipationStatus.APPROVED -> {
                if (event.participationFee > 0L) {
                    throw PaidParticipationCancelRequiresRefundException()
                }
                if (!event.startAt.isAfter(LocalDateTime.now())) {
                    throw EventAlreadyStartedException()
                }
                val ticket = ticketRepository
                    .findByBuyerAndEventIdIn(user, listOf(event.id))
                    .maxByOrNull { it.purchasedAt }
                if (ticket != null && ticket.status == TicketStatus.USED) {
                    throw TicketAlreadyUsedException()
                }

                p.cancel()
                event.decreaseParticipant()
                if (ticket != null && ticket.status == TicketStatus.PAID) {
                    ticket.cancel()
                }

                runCatching {
                    notificationService.notify(
                        receiverIds = ownerAndStaffIds(event.channel),
                        type = NotificationType.PARTICIPATION_CANCELED,
                        title = "참가자가 취소했어요",
                        message = "${user.nickname}님이 ${event.title} 참가를 취소했어요.",
                        targetType = "events",
                        targetId = event.id,
                    )
                }.onFailure { e ->
                    log.warn("[cancelMyApplication] owner/staff notify failed: {}", e.message)
                }
            }
            ParticipationStatus.REJECTED, ParticipationStatus.CANCELED -> {
                throw ParticipationNotPendingException()
            }
        }
        return p.toResponse()
    }

    /**
     * 참가자가 자신의 신청 상태를 조회한다. 신청 이력이 없으면 null.
     *
     * APPROVED 인 경우 가장 최근 티켓을 함께 묶어 반환한다 — EventDetailPage 가
     * "티켓 보기" 보조 버튼을 노출할 때 사용한다. APPROVED 가 아니면 ticket 필드는 null.
     */
    fun getMyParticipation(userId: Long, eventId: Long): ParticipationResponse? {
        val user = findActiveUser(userId)
        val event = findEvent(eventId)
        val participation = eventParticipationRepository.findByEventAndParticipant(event, user)
            .orElse(null) ?: return null

        val ticket = if (participation.status == ParticipationStatus.APPROVED) {
            ticketRepository.findByBuyerAndEventIdIn(user, listOf(event.id))
                .maxByOrNull { it.purchasedAt }
        } else null

        return participation.toResponse().copy(
            ticketId = ticket?.id,
            ticketStatus = ticket?.status,
        )
    }

    /**
     * MY 페이지 "내 신청/티켓" 목록. 신청 시각 내림차순. 결제 PG 미연동이므로 티켓이 없을 수 있다.
     *
     * N+1 회피: 페이지 내 eventId 묶음으로 티켓을 한 번에 조회한 뒤 가장 최근 티켓을 골라 zip 한다.
     */
    fun getMyParticipations(userId: Long, page: Int, size: Int): Page<MyParticipationItemResponse> {
        val user = findActiveUser(userId)
        val pageable = PageRequest.of(page, size)
        val participations = eventParticipationRepository
            .findByParticipantOrderByJoinedAtDesc(user, pageable)

        val eventIds = participations.content.map { it.event.id }
        val ticketByEventId: Map<Long, Ticket> = if (eventIds.isEmpty()) {
            emptyMap()
        } else {
            ticketRepository.findByBuyerAndEventIdIn(user, eventIds)
                .groupBy { it.event.id }
                .mapValues { (_, tickets) ->
                    // 한 이벤트에 환불 후 재구매 등으로 여러 티켓이 있을 수 있어 가장 최근 티켓을 우선 노출한다.
                    tickets.maxByOrNull { it.purchasedAt }!!
                }
        }

        // PR44: 결제 내역 표시를 위해 PaymentAttempt 도 묶음 조회한다 (N+1 회피).
        // 무료 티켓 / 결제 미연결 티켓은 attempt 가 없어 결과에 포함되지 않는다.
        val attemptByTicketId: Map<Long, com.contenido.domain.payment.entity.PaymentAttempt> =
            ticketByEventId.values.takeIf { it.isNotEmpty() }
                ?.let { tickets -> paymentAttemptRepository.findByTicketIn(tickets) }
                ?.associateBy { it.ticket!!.id }
                ?: emptyMap()

        return participations.map { p ->
            val ticket = ticketByEventId[p.event.id]
            val attempt = ticket?.let { attemptByTicketId[it.id] }
            MyParticipationItemResponse(
                participationId = p.id,
                eventId = p.event.id,
                eventTitle = p.event.title,
                channelId = p.event.channel.id,
                channelName = p.event.channel.name,
                mainImageUrl = p.event.mainImageUrl,
                startAt = p.event.startAt,
                location = p.event.location,
                participationFee = p.event.participationFee,
                status = p.status,
                requestedAt = p.joinedAt,
                reviewedAt = p.reviewedAt,
                rejectReason = p.rejectReason,
                ticketId = ticket?.id,
                ticketStatus = ticket?.status,
                paymentAttemptId = attempt?.id,
                orderId = attempt?.idempotencyKey,
                paidAmount = attempt?.amount,
                paymentProvider = attempt?.provider,
            )
        }
    }

    /**
     * 기획자/관리자만 호출 가능. 신청 시각 내림차순 정렬.
     *
     * APPROVED 신청자에 한해 ticket 정보를 묶어 반환한다 (buyerIds 묶음 1쿼리 — N+1 회피).
     * PENDING/REJECTED/CANCELED 는 ticket 필드가 null.
     */
    fun listApplicants(requesterId: Long, eventId: Long): List<ParticipationApplicantResponse> {
        val event = findEvent(eventId)
        ensureCanReview(requesterId, event)

        val applicants = eventParticipationRepository.findByEventOrderByJoinedAtDesc(event)

        // APPROVED 인 참가자 ID 만 모아 한 번에 티켓 조회.
        val approvedBuyerIds = applicants
            .filter { it.status == ParticipationStatus.APPROVED }
            .map { it.participant.id }
        val ticketByBuyer: Map<Long, Ticket> = if (approvedBuyerIds.isEmpty()) {
            emptyMap()
        } else {
            ticketRepository.findByEventAndBuyerIdIn(event, approvedBuyerIds)
                .groupBy { it.buyer.id }
                .mapValues { (_, tickets) ->
                    // 환불 후 재발급 등 여러 티켓이 있을 수 있어 가장 최근 티켓을 우선.
                    tickets.maxByOrNull { it.purchasedAt }!!
                }
        }

        return applicants.map { p ->
            val ticket = if (p.status == ParticipationStatus.APPROVED) ticketByBuyer[p.participant.id] else null
            p.toApplicantResponse().copy(
                ticketId = ticket?.id,
                ticketStatus = ticket?.status,
            )
        }
    }

    /**
     * 기획자가 PENDING 신청을 승인.
     *  - 정원이 차 있으면 [EventFullException]
     *  - status APPROVED + reviewedAt/By 세팅 + Event.currentParticipants ++
     *  - TicketService 로 무료 티켓 발급 시도 (실패는 로그만 남기고 무시 — 결제 PG 미연동)
     *  - 참가자에게 PARTICIPATION_APPROVED 알림 (best-effort)
     */
    @Retryable(retryFor = [OptimisticLockingFailureException::class], maxAttempts = 3)
    @Transactional
    fun approveParticipation(
        requesterId: Long,
        eventId: Long,
        participationId: Long,
    ): ParticipationResponse {
        val event = findEvent(eventId)
        val reviewer = ensureCanReview(requesterId, event)

        val p = eventParticipationRepository.findById(participationId)
            .orElseThrow { ParticipationNotFoundException() }
        if (p.event.id != event.id) throw ParticipationNotFoundException()
        if (p.status != ParticipationStatus.PENDING) throw ParticipationNotPendingException()
        if (event.isFull()) throw EventFullException()

        p.approve(reviewer)
        event.increaseParticipant()

        // 결제 PG 연동 전까지는 무료 티켓 발급 skeleton 만 호출. 실패는 비치명.
        runCatching {
            ticketService.issueFreeTicket(p.participant.id, event.id)
        }.onFailure { e ->
            log.warn("[approveParticipation] free-ticket issue failed: {}", e.message)
        }

        runCatching {
            notificationService.notify(
                receiverIds = listOf(p.participant.id),
                type = NotificationType.PARTICIPATION_APPROVED,
                title = "참가 신청이 승인되었어요",
                message = "${event.title} 이벤트 참가가 확정되었습니다.",
                targetType = "events",
                targetId = event.id,
            )
        }

        return p.toResponse()
    }

    @Transactional
    fun rejectParticipation(
        requesterId: Long,
        eventId: Long,
        participationId: Long,
        reason: String?,
    ): ParticipationResponse {
        val event = findEvent(eventId)
        val reviewer = ensureCanReview(requesterId, event)

        val p = eventParticipationRepository.findById(participationId)
            .orElseThrow { ParticipationNotFoundException() }
        if (p.event.id != event.id) throw ParticipationNotFoundException()
        if (p.status != ParticipationStatus.PENDING) throw ParticipationNotPendingException()

        p.reject(reviewer, reason)

        val msg = p.rejectReason ?: "${event.title} 이벤트 참가 신청이 거절되었습니다."
        runCatching {
            notificationService.notify(
                receiverIds = listOf(p.participant.id),
                type = NotificationType.PARTICIPATION_REJECTED,
                title = "참가 신청이 거절되었어요",
                message = msg,
                targetType = "events",
                targetId = event.id,
            )
        }

        return p.toResponse()
    }

    // ─── Legacy aliases — 기존 /channels/{cid}/events/{eid}/join 엔드포인트 호환용 ─

    /** 기존 endpoint 호환. 새 PENDING 시맨틱으로 동작한다 (currentParticipants 증가 없음). */
    @Transactional
    fun joinEvent(userId: Long, eventId: Long) {
        applyForEvent(userId, eventId)
    }

    /** 기존 endpoint 호환. PENDING 신청만 취소. */
    @Transactional
    fun cancelJoin(userId: Long, eventId: Long) {
        cancelMyApplication(userId, eventId)
    }

    // ─── private ────────────────────────────────────────────────────────────────

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun findChannel(channelId: Long): Channel =
        channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }

    /**
     * 채널 owner + STAFF 멤버의 사용자 ID 를 중복 없이 반환.
     * ADMIN 은 운영 모니터링용이라 자동 전파에 포함하지 않는다.
     */
    private fun ownerAndStaffIds(channel: Channel): List<Long> {
        val ids = linkedSetOf<Long>()
        ids.add(channel.owner.id)
        channelMemberRepository.findByChannel(channel).forEach { ids.add(it.user.id) }
        return ids.toList()
    }

    private fun findEvent(eventId: Long): Event =
        eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }

    /** 채널 owner 또는 ADMIN 만 신청자 관리/승인 가능. 아니면 403. */
    private fun ensureCanReview(requesterId: Long, event: Event): User {
        val user = findActiveUser(requesterId)
        if (event.channel.owner.id != requesterId && user.role != UserRole.ADMIN) {
            throw UnauthorizedException()
        }
        return user
    }

    private fun Event.toResponse(
        averageRating: Double? = null,
        reviewCount: Long = 0L,
    ) = EventResponse(
        id = id,
        channelId = channel.id,
        channelName = channel.name,
        channelOwnerId = channel.owner.id,
        title = title,
        description = description,
        location = location,
        mainImageUrl = mainImageUrl,
        startAt = startAt,
        endAt = endAt,
        maxParticipants = maxParticipants,
        currentParticipants = currentParticipants,
        participationFee = participationFee,
        refundPolicy = refundPolicy,
        detailContent = detailContent,
        status = status,
        contentType = contentType,
        createdAt = createdAt,
        averageRating = averageRating,
        reviewCount = reviewCount,
    )

    /**
     * PR47 — eventId 묶음에 대한 (averageRating, reviewCount) 매핑을 batch 로 조회.
     * 후기가 0건인 이벤트는 결과에 포함되지 않으므로 caller 가 null 처리.
     * eventIds 가 비어 있으면 즉시 빈 map.
     */
    private fun ratingsByEventIds(eventIds: List<Long>): Map<Long, Pair<Double?, Long>> {
        if (eventIds.isEmpty()) return emptyMap()
        return reviewRepository.aggregateByEventIds(eventIds).associate { row ->
            val id = (row[0] as Number).toLong()
            val avg = (row[1] as? Number)?.toDouble()
            val cnt = (row[2] as Number).toLong()
            id to (avg to cnt)
        }
    }

    private fun EventParticipation.toResponse() = ParticipationResponse(
        id = id,
        eventId = event.id,
        status = status,
        joinedAt = joinedAt,
        reviewedAt = reviewedAt,
        rejectReason = rejectReason,
    )

    private fun EventParticipation.toApplicantResponse() = ParticipationApplicantResponse(
        id = id,
        participantId = participant.id,
        nickname = participant.nickname,
        status = status,
        joinedAt = joinedAt,
        reviewedAt = reviewedAt,
        rejectReason = rejectReason,
    )
}
