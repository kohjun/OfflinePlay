package com.contenido.domain.ticket.service

import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.ticket.dto.EventCheckInSummaryResponse
import com.contenido.domain.ticket.dto.EventCheckInTicket
import com.contenido.domain.ticket.dto.TicketDetailResponse
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.BuyerCannotCheckInException
import com.contenido.global.exception.EventNotFoundException
import com.contenido.global.exception.InvalidCheckInCodeException
import com.contenido.global.exception.TicketNotFoundException
import com.contenido.global.exception.TicketNotPaidException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Ticket 도메인 진입점.
 *
 * 결제(PG) 연동은 후속 PR에서 추가한다. 현재는 free-issue / state 전환만 노출하여
 * 프론트/관리자 도구가 ticket 모델을 다룰 수 있도록 한다.
 *
 * TODO(payment-integration):
 *  - issueTicket() 시 결제 게이트웨이 호출 및 결제 성공 후 PAID 상태로 저장
 *  - refund() 시 PG 환불 API 호출 후 REFUNDED 전환
 *  - 이벤트 취소/환불 정책 검증 (refundPolicy 기반)
 */
@Service
@Transactional(readOnly = true)
class TicketService(
    private val ticketRepository: TicketRepository,
    private val eventRepository: EventRepository,
    private val userRepository: UserRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val notificationService: NotificationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun issueFreeTicket(userId: Long, eventId: Long): Long {
        val buyer = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }

        val ticket = ticketRepository.save(
            Ticket(
                event = event,
                buyer = buyer,
                price = event.participationFee,
                status = TicketStatus.PAID,
            )
        )

        // buyer 에게 TICKET_ISSUED 알림 — best-effort.
        runCatching {
            notificationService.notify(
                receiverIds = listOf(buyer.id),
                type = NotificationType.TICKET_ISSUED,
                title = "티켓이 발급되었어요",
                message = "${event.title} 티켓이 도착했어요. 입장 시 사용해주세요.",
                targetType = "tickets",
                targetId = ticket.id,
            )
        }.onFailure { e ->
            log.warn("[issueFreeTicket] buyer notify failed: {}", e.message)
        }

        return ticket.id
    }

    /**
     * 결제 webhook PAID 처리 시 유료 티켓 발급.
     *
     * 호출 시점은 `PaymentService.handleWebhook(PAID)` 한 곳뿐 — PaymentAttempt 의
     * 멱등성/중복 검증을 그쪽에서 끝낸 뒤 진입한다고 가정한다 (정원/owner 검증 불필요).
     *
     * paidAmount 는 PaymentAttempt 가 prepare 시점에 스냅샷한 금액. event.participationFee
     * 가 그 사이에 바뀌어도 스냅샷 금액으로 발급한다.
     */
    @Transactional
    fun issuePaidTicket(userId: Long, eventId: Long, paidAmount: Long): Ticket {
        val buyer = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }

        val ticket = ticketRepository.save(
            Ticket(
                event = event,
                buyer = buyer,
                price = paidAmount,
                status = TicketStatus.PAID,
            )
        )

        runCatching {
            notificationService.notify(
                receiverIds = listOf(buyer.id),
                type = NotificationType.TICKET_ISSUED,
                title = "결제가 완료되었어요",
                message = "${event.title} 티켓이 발급되었어요. 입장 시 사용해주세요.",
                targetType = "tickets",
                targetId = ticket.id,
            )
        }.onFailure { e ->
            log.warn("[issuePaidTicket] buyer notify failed: {}", e.message)
        }

        return ticket
    }

    fun myTickets(userId: Long): List<Ticket> {
        val buyer = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        return ticketRepository.findByBuyer(buyer)
    }

    /**
     * 티켓 패스/QR 화면용 단건 조회.
     *
     *  - buyer 본인 또는 ADMIN 만 조회 가능. 그 외는 [UnauthorizedException].
     *  - 없는 ticketId 는 [TicketNotFoundException].
     *  - checkInCode 는 결정형(`CONTENIDO-{ticketId}-{eventId}`) — 보안 QR 은 후속 TODO.
     */
    fun getTicketDetail(viewerId: Long, ticketId: Long): TicketDetailResponse {
        val viewer = userRepository.findById(viewerId).orElseThrow { UserNotFoundException() }
        val ticket = ticketRepository.findById(ticketId).orElseThrow { TicketNotFoundException() }

        val isBuyer = ticket.buyer.id == viewerId
        val isAdmin = viewer.role == UserRole.ADMIN
        if (!isBuyer && !isAdmin) throw UnauthorizedException()

        return ticket.toDetailResponse()
    }

    /**
     * 현장 체크인 — PAID → USED 전환 + usedAt 기록.
     *
     * 권한:
     *  - ADMIN 가능
     *  - 이벤트 채널 owner 가능
     *  - 채널 STAFF(ChannelMember) 가능
     *  - buyer 본인은 자기 티켓을 직접 체크인할 수 없다 → [BuyerCannotCheckInException]
     *  - 그 외 사용자는 [UnauthorizedException]
     *
     * 상태:
     *  - PAID 가 아니면 [TicketNotPaidException] (USED/REFUNDED/CANCELED 모두 거절)
     */
    @Transactional
    fun checkInTicket(viewerId: Long, ticketId: Long): TicketDetailResponse {
        val viewer = userRepository.findById(viewerId).orElseThrow { UserNotFoundException() }
        val ticket = ticketRepository.findById(ticketId).orElseThrow { TicketNotFoundException() }

        if (ticket.buyer.id == viewerId) throw BuyerCannotCheckInException()
        if (!canCheckIn(viewer, ticket)) throw UnauthorizedException()

        if (ticket.status != TicketStatus.PAID) throw TicketNotPaidException()

        ticket.markUsed()

        // buyer 에게 TICKET_CHECKED_IN 알림 — best-effort.
        runCatching {
            notificationService.notify(
                receiverIds = listOf(ticket.buyer.id),
                type = NotificationType.TICKET_CHECKED_IN,
                title = "체크인이 완료되었어요",
                message = "${ticket.event.title} 입장이 확인되었습니다.",
                targetType = "tickets",
                targetId = ticket.id,
            )
        }.onFailure { e ->
            log.warn("[checkInTicket] buyer notify failed: {}", e.message)
        }

        return ticket.toDetailResponse()
    }

    /**
     * 체크인 코드 기반 체크인. 코드는 `CONTENIDO-{ticketId}-{eventId}` 형식.
     *
     *  - 형식이 깨졌거나 ticketId 가 매핑 안 되면 [InvalidCheckInCodeException].
     *  - eventId 가 ticket.event.id 와 다르면 [InvalidCheckInCodeException] (위변조 방지).
     *  - 그 외 모든 권한/상태 검증은 [checkInTicket] 에 위임.
     *
     * @Transactional 자체 — 같은 클래스에서 checkInTicket 을 호출하면 Spring 프록시를 거치지 못해
     * 내부 @Transactional 이 적용되지 않으므로 이 메서드 자체에 트랜잭션을 건다.
     */
    @Transactional
    fun checkInByCode(viewerId: Long, rawCode: String): TicketDetailResponse {
        val code = rawCode.trim()
        val match = CHECK_IN_CODE_PATTERN.matchEntire(code)
            ?: throw InvalidCheckInCodeException()
        val ticketId = match.groupValues[1].toLongOrNull() ?: throw InvalidCheckInCodeException()
        val claimedEventId = match.groupValues[2].toLongOrNull() ?: throw InvalidCheckInCodeException()

        val ticket = ticketRepository.findById(ticketId).orElseThrow { InvalidCheckInCodeException() }
        if (ticket.event.id != claimedEventId) throw InvalidCheckInCodeException()

        return checkInTicket(viewerId, ticketId)
    }

    companion object {
        private val CHECK_IN_CODE_PATTERN = Regex("^CONTENIDO-(\\d+)-(\\d+)$")
    }

    /**
     * 이벤트별 체크인 현황. ADMIN / channel owner / channel STAFF 만 조회 가능.
     */
    fun getEventCheckIns(viewerId: Long, eventId: Long): EventCheckInSummaryResponse {
        val viewer = userRepository.findById(viewerId).orElseThrow { UserNotFoundException() }
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }

        val canView = viewer.role == UserRole.ADMIN ||
            event.channel.owner.id == viewer.id ||
            channelMemberRepository.existsByChannelAndUser(event.channel, viewer)
        if (!canView) throw UnauthorizedException()

        val tickets = ticketRepository.findByEvent(event)
        val issued = tickets.size
        val used = tickets.count { it.status == TicketStatus.USED }
        // 정원 카운트 X — 미입장 = 발급 후 PAID 상태로 남아 있는 티켓.
        val pending = tickets.count { it.status == TicketStatus.PAID }

        return EventCheckInSummaryResponse(
            eventId = event.id,
            eventTitle = event.title,
            issuedCount = issued,
            checkedInCount = used,
            notCheckedInCount = pending,
            tickets = tickets
                .sortedByDescending { it.usedAt ?: it.purchasedAt }
                .map {
                    EventCheckInTicket(
                        ticketId = it.id,
                        buyerId = it.buyer.id,
                        buyerNickname = it.buyer.nickname,
                        status = it.status,
                        purchasedAt = it.purchasedAt,
                        usedAt = it.usedAt,
                    )
                },
        )
    }

    private fun canCheckIn(viewer: User, ticket: Ticket): Boolean {
        if (viewer.role == UserRole.ADMIN) return true
        val channel = ticket.event.channel
        // 1) 채널 owner 는 ChannelMember(OWNER) row 가 누락된 레거시 데이터에서도 항상 허용.
        if (channel.owner.id == viewer.id) return true
        // 2) STAFF/추가 OWNER 는 ChannelMember 테이블로 확인.
        return channelMemberRepository.existsByChannelAndUser(channel, viewer)
    }

    private fun Ticket.toDetailResponse(): TicketDetailResponse {
        val ev = event
        return TicketDetailResponse(
            ticketId = id,
            ticketStatus = status,
            eventId = ev.id,
            eventTitle = ev.title,
            channelId = ev.channel.id,
            channelName = ev.channel.name,
            mainImageUrl = ev.mainImageUrl,
            startAt = ev.startAt,
            endAt = ev.endAt,
            location = ev.location,
            participationFee = price,
            buyerId = buyer.id,
            buyerNickname = buyer.nickname,
            purchasedAt = purchasedAt,
            checkInCode = "CONTENIDO-$id-${ev.id}",
            usedAt = usedAt,
        )
    }
}
