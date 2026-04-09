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
class AlreadyCreatorException(
    message: String = "이미 크리에이터입니다.") : RuntimeException(message)
class DuplicateApplicationException(
    message: String = "이미 신청 중입니다.") : RuntimeException(message)
class TokenReusedException(
    message: String = "비정상적인 토큰 재사용이 감지되었습니다. 모든 기기에서 로그아웃됩니다.") : RuntimeException(message)
class InvalidCredentialsException : ContENIDOException(
    HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."
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
