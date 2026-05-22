package com.contenido.domain.creator.service

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.service.ModerationAuditLogService
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.payment.repository.PaymentAttemptRepository
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.EventNotFoundException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import com.contenido.global.util.MaskingUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * PR154 — 신청자 CSV export.
 *
 * 권한:
 *  - 이벤트 채널 owner
 *  - 채널 STAFF
 *  - ADMIN
 *  그 외 — [UnauthorizedException] (403).
 *
 * 결과 컬럼 (1행 헤더 + N 행 데이터):
 *   `participantId,nickname,phoneMasked,status,ticketStatus,paidAmount,refundedAmount,checkedInAt`
 *
 *  - phoneMasked  : 010-****-1234 형태 (MaskingUtil). raw phone 절대 export 안 함.
 *  - paidAmount   : PaymentAttempt.amount (PAID/PARTIALLY_REFUNDED 일 때만). 무료 티켓은 0.
 *  - refundedAmount: PaymentAttempt.refundedAmount.
 *  - checkedInAt  : Ticket.usedAt — USED 일 때만.
 *
 * 호출 시 [ModerationAuditAction.PARTICIPANT_EXPORTED] audit row 1건 기록 (afterValue 에 eventId,
 * channelId, exportedRowCount). 개인정보 export 추적 정책.
 */
@Service
@Transactional(readOnly = true)
class ParticipantExportService(
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
    private val participationRepository: EventParticipationRepository,
    private val ticketRepository: TicketRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val auditLogService: ModerationAuditLogService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val HEADER = "participantId,nickname,phoneMasked,status,ticketStatus,paidAmount,refundedAmount,checkedInAt"
    }

    @Transactional
    fun exportCsv(userId: Long, eventId: Long): String {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }
        ensureCanExport(user, event)

        val participants = participationRepository.findByEventOrderByJoinedAtDesc(event)
        // ticket / paymentAttempt 묶음 fetch — N+1 회피.
        val ticketsByBuyer: Map<Long, Ticket> = if (participants.isEmpty()) emptyMap()
        else ticketRepository.findByEventAndBuyerIdIn(event, participants.map { it.participant.id })
            .groupBy { it.buyer.id }
            .mapValues { (_, list) -> list.maxByOrNull { it.purchasedAt }!! }
        val attemptsByTicketId = if (ticketsByBuyer.isEmpty()) emptyMap()
        else paymentAttemptRepository.findByTicketIn(ticketsByBuyer.values)
            .associateBy { it.ticket!!.id }

        val rows = participants.map { p ->
            val ticket = ticketsByBuyer[p.participant.id]
            val attempt = ticket?.let { attemptsByTicketId[it.id] }
            val checkedInAt = ticket?.takeIf { it.status == TicketStatus.USED }?.usedAt
            listOf(
                p.participant.id.toString(),
                csvEscape(p.participant.nickname),
                MaskingUtil.maskPhoneNumber(p.participant.phoneNumber),
                p.status.name,
                ticket?.status?.name ?: "",
                attempt?.amount?.toString() ?: "0",
                attempt?.refundedAmount?.toString() ?: "0",
                checkedInAt?.toString() ?: "",
            ).joinToString(",")
        }

        // PR154 — 개인정보 export 추적 audit row 1건. 실패해도 export 자체는 막지 않도록 try-catch (운영
        // 정책 위반 — 차라리 audit 실패가 export 실패로 이어지는 게 안전. 따라서 throw 그대로 둔다.)
        auditLogService.record(
            actorId = userId,
            action = ModerationAuditAction.PARTICIPANT_EXPORTED,
            afterValue = mapOf(
                "eventId" to event.id,
                "channelId" to event.channel.id,
                "exportedRowCount" to rows.size,
                "exportedAt" to LocalDateTime.now().toString(),
            ),
        )

        return buildString {
            append(HEADER).append('\n')
            rows.forEach { append(it).append('\n') }
        }
    }

    private fun ensureCanExport(user: com.contenido.domain.user.entity.User, event: Event) {
        if (user.role == UserRole.ADMIN) return
        if (event.channel.owner.id == user.id) return
        val isStaff = channelMemberRepository.findByChannelAndUser(event.channel, user).isPresent
        if (!isStaff) throw UnauthorizedException()
    }

    /** CSV 안전 escape — 쉼표/따옴표/줄바꿈 포함 시 따옴표 wrap. */
    private fun csvEscape(raw: String): String {
        val needsQuote = raw.contains(',') || raw.contains('"') || raw.contains('\n')
        if (!needsQuote) return raw
        val escaped = raw.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    /**
     * APPROVED 신청자 중 ticket 가 active(non-CANCELED/REFUNDED) 인 row 만 — 추후 정책 확장 자리.
     * 본 PR 은 모든 row 를 export (참가자 운영자가 신청 이력 전부 보고 싶을 수 있음).
     */
    @Suppress("unused")
    private fun isActiveTicketRow(status: ParticipationStatus, ticket: Ticket?): Boolean {
        if (status != ParticipationStatus.APPROVED) return false
        val s = ticket?.status ?: return false
        return s == TicketStatus.PAID || s == TicketStatus.USED || s == TicketStatus.PARTIALLY_REFUNDED
    }
}
