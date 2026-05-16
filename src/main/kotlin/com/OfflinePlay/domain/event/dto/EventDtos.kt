package com.contenido.domain.event.dto

import com.contenido.domain.event.entity.ContentType
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.entity.ParticipationStatus
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateEventRequest(
    @field:NotBlank(message = "이벤트 제목은 필수입니다.")
    val title: String,

    @field:NotBlank(message = "이벤트 설명은 필수입니다.")
    val description: String,

    @field:NotBlank(message = "장소는 필수입니다.")
    val location: String,

    @field:NotBlank(message = "대표 이미지는 필수입니다.")
    val mainImageUrl: String,

    @field:NotNull(message = "시작 시간은 필수입니다.")
    @field:Future(message = "시작 시간은 현재 이후여야 합니다.")
    val startAt: LocalDateTime,

    @field:NotNull(message = "종료 시간은 필수입니다.")
    @field:Future(message = "종료 시간은 현재 이후여야 합니다.")
    val endAt: LocalDateTime,

    @field:NotNull(message = "최대 참여 인원은 필수입니다.")
    @field:Positive(message = "최대 참여 인원은 양수여야 합니다.")
    val maxParticipants: Int,

    @field:NotNull(message = "참가비는 필수입니다.")
    @field:PositiveOrZero(message = "참가비는 0 이상이어야 합니다.")
    val participationFee: Long,

    @field:NotBlank(message = "환불 정책은 필수입니다.")
    val refundPolicy: String,

    @field:NotBlank(message = "이벤트 상세 내용은 필수입니다.")
    val detailContent: String,

    /**
     * 콘텐츠 유형(ORIGINAL/CLASSIC/SPECIAL). 신규 클라이언트는 반드시 지정하지만
     * 구버전 호환을 위해 nullable로 둔다. null이면 서비스에서 SPECIAL로 보정한다.
     */
    val contentType: ContentType? = null,
)

/**
 * 이벤트 수정 요청. 모든 필드 optional 이며 null 은 "변경하지 않음" 의미.
 *
 * 안전 정책:
 *  - participationFee: 이미 발급된 티켓이 있으면 [com.contenido.global.exception.EventHasIssuedTicketsException].
 *  - maxParticipants: 현재 참가자 수 미만으로는 줄일 수 없음
 *    ([com.contenido.global.exception.MaxParticipantsBelowCurrentException]).
 *  - startAt/endAt: 함께 보내야 검증이 자연스럽지만 서비스가 결합 후 (start < end) 만 확인.
 */
data class UpdateEventRequest(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val mainImageUrl: String? = null,
    val startAt: LocalDateTime? = null,
    val endAt: LocalDateTime? = null,
    val maxParticipants: Int? = null,
    val participationFee: Long? = null,
    val refundPolicy: String? = null,
    val detailContent: String? = null,
    val contentType: ContentType? = null,
)

data class EventResponse(
    val id: Long,
    val channelId: Long,
    val channelName: String,
    /** 채널 owner(기획자) 사용자 ID — 프론트가 owner 여부를 판별해 신청자 관리 UI를 노출할 때 사용. */
    val channelOwnerId: Long,
    val title: String,
    val description: String,
    val location: String,
    val mainImageUrl: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val maxParticipants: Int,
    val currentParticipants: Int,
    val participationFee: Long,
    val refundPolicy: String,
    val detailContent: String,
    val status: EventStatus,
    val contentType: ContentType?,
    val createdAt: LocalDateTime,
    /** PR46 — 이벤트 후기 집계. 후기 0건이면 averageRating=null, reviewCount=0. */
    val averageRating: Double? = null,
    val reviewCount: Long = 0L,
)

/**
 * 참가자 본인이 자기 참가 상태를 조회/갱신할 때 사용.
 *
 *  - ticketId/ticketStatus 는 APPROVED 이고 무료 티켓이 발급된 경우에만 채워진다.
 *    EventDetailPage 가 sticky CTA 옆에 "티켓 보기" 보조 버튼을 노출할 때 사용한다.
 */
data class ParticipationResponse(
    val id: Long,
    val eventId: Long,
    val status: ParticipationStatus,
    val joinedAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
    val rejectReason: String?,
    val ticketId: Long? = null,
    val ticketStatus: com.contenido.domain.ticket.entity.TicketStatus? = null,
)

/**
 * 기획자가 신청자 목록을 볼 때 사용.
 *
 *  - ticketId/ticketStatus 는 APPROVED 이고 무료 티켓이 발급된 경우에만 채워진다.
 *    신청자 관리 카드에서 "티켓 확인" 진입점에 사용한다.
 */
data class ParticipationApplicantResponse(
    val id: Long,
    val participantId: Long,
    val nickname: String,
    val status: ParticipationStatus,
    val joinedAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
    val rejectReason: String?,
    val ticketId: Long? = null,
    val ticketStatus: com.contenido.domain.ticket.entity.TicketStatus? = null,
)

/** 거절 사유. blank 또는 null 허용 — 사유 없이 거절 가능. */
data class RejectParticipationRequest(
    @field:Size(max = 500, message = "거절 사유는 500자 이하여야 합니다.")
    val reason: String? = null,
)

/**
 * MY 페이지 "내 신청/티켓" 한 행. 이벤트 + 참가 상태 + (있다면) 티켓 정보 + (있다면) 결제 정보.
 *
 * 결제 필드 (PR44):
 *  - 무료 티켓: 모두 null.
 *  - 유료 티켓: orderId 는 prepare 시 발급된 idempotencyKey (사용자 주문번호로 노출).
 *    paidAmount 는 PaymentAttempt.amount — 환불 후에도 원본 결제 금액을 그대로 표시.
 *    provider 는 결제 수단 (MOCK / TOSS 등).
 */
data class MyParticipationItemResponse(
    val participationId: Long,
    val eventId: Long,
    val eventTitle: String,
    val channelId: Long,
    val channelName: String,
    val mainImageUrl: String,
    val startAt: LocalDateTime,
    val location: String,
    val participationFee: Long,
    val status: ParticipationStatus,
    val requestedAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
    val rejectReason: String?,
    val ticketId: Long?,
    val ticketStatus: com.contenido.domain.ticket.entity.TicketStatus?,
    // 결제 정보 (티켓이 무료이거나 결제 미연결인 경우 모두 null).
    val paymentAttemptId: Long?,
    val orderId: String?,
    val paidAmount: Long?,
    val paymentProvider: com.contenido.domain.payment.entity.PaymentProvider?,
)
