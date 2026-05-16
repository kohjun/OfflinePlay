package com.contenido.global.exception

import org.springframework.http.HttpStatus

sealed class ContENIDOException(
    val status: HttpStatus,
    override val message: String,
) : RuntimeException(message)

// --- Auth ---
class DuplicateEmailException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."
)

class DuplicateNicknameException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."
)
class FileUploadException(detail: String = "파일 업로드에 실패했습니다.") : ContENIDOException(
    HttpStatus.INTERNAL_SERVER_ERROR, detail
)
 
class InvalidFileTypeException(allowedTypes: String = "JPEG, PNG, WebP, GIF") : ContENIDOException(
    HttpStatus.BAD_REQUEST, "허용되지 않는 파일 형식입니다. 허용 형식: $allowedTypes"
)
 
class FileSizeExceededException(maxMb: Int = 10) : ContENIDOException(
    HttpStatus.BAD_REQUEST, "파일 크기가 허용 용량(${maxMb}MB)을 초과했습니다."
)
 
class AlreadyCreatorException(
    message: String = "이미 크리에이터입니다.") : RuntimeException(message)
class DuplicateApplicationException(
    message: String = "이미 신청 중입니다.") : RuntimeException(message)
class TokenReusedException : ContENIDOException(
    HttpStatus.UNAUTHORIZED,
    "비정상적인 토큰 재사용이 감지되었습니다. 모든 기기에서 로그아웃됩니다."
)
class InvalidCredentialsException : ContENIDOException(
    HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."
)

class InvalidSignupRoleException : ContENIDOException(
    HttpStatus.BAD_REQUEST,
    "회원가입 시 역할은 PARTICIPANT 또는 CREATOR 만 선택할 수 있습니다."
)

class UserNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."
)

// --- Notification ---
class NotificationNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 알림입니다."
)

// --- Interaction ---
class CommentNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 댓글입니다."
)

class InvalidTargetTypeException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "올바르지 않은 대상 타입입니다."
)

// --- Event ---
class EventNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 이벤트입니다."
)

class EventFullException : ContENIDOException(
    HttpStatus.CONFLICT, "이벤트 참여 인원이 가득 찼습니다."
)

class AlreadyJoinedException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 참여한 이벤트입니다."
)

class InvalidEventDateRangeException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "이벤트 종료 시간은 시작 시간 이후여야 합니다."
)

class EventClosedException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 종료된 이벤트입니다."
)

class OwnerCannotApplyException : ContENIDOException(
    HttpStatus.CONFLICT, "본인이 운영하는 채널의 이벤트에는 신청할 수 없습니다."
)

class ParticipationNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "참가 신청 내역을 찾을 수 없습니다."
)

class ParticipationNotPendingException : ContENIDOException(
    HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없는 신청입니다."
)

class EventAlreadyStartedException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 시작된 이벤트는 취소할 수 없습니다."
)

class EventHasIssuedTicketsException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 발급된 티켓이 있어 참가비를 변경할 수 없습니다."
)

class MaxParticipantsBelowCurrentException : ContENIDOException(
    HttpStatus.CONFLICT, "현재 참가자 수보다 적게 정원을 줄일 수 없습니다."
)

class TicketAlreadyUsedException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 사용된 티켓은 취소할 수 없습니다."
)

// --- Post ---
class PostNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 게시물입니다."
)

class UnauthorizedException : ContENIDOException(
    HttpStatus.FORBIDDEN, "권한이 없습니다."
)

// --- Channel ---
class DuplicateChannelException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 채널이 존재합니다."
)

class ChannelNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 채널입니다."
)

class AlreadySubscribedException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 구독 중인 채널입니다."
)

class NotSubscribedException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "구독 중인 채널이 아닙니다."
)

// --- Content ---
class ContentNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 콘텐츠입니다."
)

class UnauthorizedContentAccessException : ContENIDOException(
    HttpStatus.FORBIDDEN, "해당 콘텐츠에 대한 권한이 없습니다."
)

class NotCreatorException : ContENIDOException(
    HttpStatus.FORBIDDEN, "CREATOR 권한이 없습니다."
)

// --- JWT ---
class InvalidTokenException(detail: String = "유효하지 않은 토큰입니다.") : ContENIDOException(
    HttpStatus.UNAUTHORIZED, detail
)

class ExpiredTokenException : ContENIDOException(
    HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."
)

class DeletedUserException : ContENIDOException(
    HttpStatus.FORBIDDEN, "탈퇴한 사용자입니다."
)

// --- Admin / Report ---
class ReportNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 신고입니다."
)

class ReportAlreadyProcessedException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 처리된 신고입니다."
)

/** 신고 대상(targetType + targetId) 조회 실패. PR48. */
class ReportTargetNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "신고 대상을 찾을 수 없습니다."
)

/** 본인이 작성/소유한 대상을 본인이 신고. PR48. */
class SelfReportNotAllowedException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "본인이 작성한 대상은 신고할 수 없습니다."
)

/** 같은 reporter 가 같은 (targetType, targetId) 를 또 신고. PR48. */
class ReportAlreadyExistsException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 신고한 대상입니다."
)

// --- Report appeal (PR52) ---
/** 자동 숨김된 상태가 아닌 대상에 이의 제기를 시도. */
class TargetNotHiddenException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "숨김 처리된 대상에 대해서만 이의 제기를 할 수 있습니다."
)

/** PR54 — 이미 숨김 처리된 대상에 ADMIN 이 또 hide 시도. */
class TargetAlreadyHiddenException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 숨김 처리된 대상입니다."
)

/** appeal 대상의 작성자/소유자가 아닌 사용자가 시도. */
class AppealNotAllowedException : ContENIDOException(
    HttpStatus.FORBIDDEN, "본인이 작성/소유한 대상만 이의 제기를 할 수 있습니다."
)

/** 같은 (requester, targetType, targetId) 에 이미 PENDING appeal 이 존재. */
class AppealAlreadyExistsException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 처리 대기 중인 이의 제기가 있습니다."
)

/**
 * PR56 — REJECTED 처리된 appeal 의 cooldown(7일) 이 끝나지 않은 같은 (requester, target) 에
 * 재신청 시도. 어뷰즈(거절된 appeal 을 매일 재제출) 방어.
 */
class AppealCooldownActiveException : ContENIDOException(
    HttpStatus.CONFLICT, "이의 제기는 거절 후 7일 뒤 다시 신청할 수 있습니다."
)

class ReportAppealNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 이의 제기입니다."
)

class ReportAppealAlreadyProcessedException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 처리된 이의 제기입니다."
)

class AlreadyBannedException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 처리된 대상입니다."
)

class BannedChannelException : ContENIDOException(
    HttpStatus.FORBIDDEN, "비활성화된 채널입니다."
)

// --- Channel member ---
class ChannelMemberNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 채널 멤버입니다."
)

class AlreadyChannelMemberException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 채널 멤버입니다."
)

class CannotRemoveOwnerException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "채널 소유자는 멤버에서 제외할 수 없습니다."
)

class CannotAddAdminAsStaffException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "관리자 계정은 채널 스태프로 추가할 수 없습니다."
)

// --- Ticket ---
class TicketNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 티켓입니다."
)

class TicketNotPaidException : ContENIDOException(
    HttpStatus.CONFLICT, "체크인할 수 있는 상태의 티켓이 아닙니다."
)

class BuyerCannotCheckInException : ContENIDOException(
    HttpStatus.FORBIDDEN, "본인 티켓을 직접 체크인할 수 없습니다."
)

class InvalidCheckInCodeException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "유효하지 않은 체크인 코드입니다."
)

// --- Payment ---
class FreeEventCannotPreparePaymentException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "무료 이벤트는 결제 준비를 호출할 수 없습니다."
)

class PaymentAttemptNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 결제 시도입니다."
)

class InvalidPaymentAmountException : ContENIDOException(
    HttpStatus.CONFLICT, "결제 금액이 예상과 다릅니다."
)

class InvalidPaymentOrderIdException : ContENIDOException(
    HttpStatus.CONFLICT, "결제 주문 식별자가 일치하지 않습니다."
)

class InvalidPaymentStateException : ContENIDOException(
    HttpStatus.CONFLICT, "현재 상태에서는 결제를 확정할 수 없습니다."
)

class PaymentConfirmFailedException(
    val code: String,
    detail: String,
) : ContENIDOException(HttpStatus.BAD_GATEWAY, "결제 승인에 실패했습니다: $detail")

class InvalidWebhookSignatureException(
    reason: String = "잘못된 웹훅 서명입니다.",
) : ContENIDOException(HttpStatus.UNAUTHORIZED, reason)

class WebhookMisconfiguredException : ContENIDOException(
    HttpStatus.INTERNAL_SERVER_ERROR,
    "결제 webhook 검증이 활성화됐지만 secret 이 설정되지 않았습니다.",
)

class MalformedWebhookBodyException : ContENIDOException(
    HttpStatus.BAD_REQUEST, "결제 webhook body 를 해석할 수 없습니다."
)

// --- Refund ---
class TicketAlreadyRefundedException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 환불 처리된 티켓입니다."
)

class PaymentNotRefundableException(detail: String = "현재 상태에서는 환불할 수 없습니다.") :
    ContENIDOException(HttpStatus.CONFLICT, detail)

class RefundFailedException(
    val code: String,
    detail: String,
) : ContENIDOException(HttpStatus.BAD_GATEWAY, "환불 처리에 실패했습니다: $detail")

/**
 * 이벤트가 이미 시작/종료된 뒤 환불 요청이 들어왔을 때.
 *
 * 정책 (docs/payment-refund-policy.md §11.3): 시작 시각 이후 환불 불가.
 * 노쇼/행사 취소 보상은 ADMIN 전용 운영 도구로 별도 처리 — 본 흐름에서는 막는다.
 *
 * 운영 도구(ADMIN refund override)는 본 PR 범위 밖. 별도 PR 에서
 * `refundPaymentByTicket(force = true)` 같은 옵션으로 추가 예정.
 */
class RefundDeadlinePassedException : ContENIDOException(
    HttpStatus.CONFLICT, "이벤트가 이미 시작되어 환불할 수 없습니다."
)

// --- Review / Rating ---
class ReviewNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "후기를 찾을 수 없습니다."
)

/** USED 티켓이 없는 사용자가 후기 작성 시도. */
class ReviewNotAllowedException : ContENIDOException(
    HttpStatus.FORBIDDEN, "체크인 완료한 참가자만 후기를 작성할 수 있습니다."
)

/** 같은 이벤트에 이미 후기를 작성한 사용자가 다시 POST 한 경우. UI 는 PATCH 로 유도. */
class ReviewAlreadyExistsException : ContENIDOException(
    HttpStatus.CONFLICT, "이미 후기를 작성한 이벤트입니다."
)
