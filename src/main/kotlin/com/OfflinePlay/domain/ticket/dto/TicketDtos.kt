package com.contenido.domain.ticket.dto

import com.contenido.domain.ticket.entity.TicketStatus
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

/**
 * 체크인 코드 기반 체크인 요청. 형식은 `CONTENIDO-{ticketId}-{eventId}`.
 */
data class CheckInByCodeRequest(
    @field:NotBlank(message = "체크인 코드는 필수입니다.")
    val checkInCode: String,
)

/**
 * 참가자 티켓 상세. MVP 단계에서는 결제/실제 QR 검증 없이 화면 표시용 데이터만 내려준다.
 *
 *  - checkInCode : 결정형 문자열 (예: `CONTENIDO-{ticketId}-{eventId}`).
 *    실제 보안 QR (서명/만료 포함) 은 후속 과제 — 현재는 현장에서 스태프가 눈으로
 *    대조하는 용도.
 */
/**
 * 이벤트별 체크인 현황. 기획자/스태프가 현장에서 누가 체크인했는지 한눈에 본다.
 */
data class EventCheckInSummaryResponse(
    val eventId: Long,
    val eventTitle: String,
    val issuedCount: Int,
    val checkedInCount: Int,
    val notCheckedInCount: Int,
    val tickets: List<EventCheckInTicket>,
)

data class EventCheckInTicket(
    val ticketId: Long,
    val buyerId: Long,
    val buyerNickname: String,
    val status: TicketStatus,
    val purchasedAt: LocalDateTime,
    val usedAt: LocalDateTime?,
)

data class TicketDetailResponse(
    val ticketId: Long,
    val ticketStatus: TicketStatus,
    val eventId: Long,
    val eventTitle: String,
    val channelId: Long,
    val channelName: String,
    val mainImageUrl: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val location: String,
    val participationFee: Long,
    val buyerId: Long,
    val buyerNickname: String,
    val purchasedAt: LocalDateTime,
    val checkInCode: String,
    /** PAID → USED 로 전환된 시각. USED 상태가 아니면 null. */
    val usedAt: LocalDateTime? = null,
)
