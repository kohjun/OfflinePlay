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

class InvalidCredentialsException : ContENIDOException(
    HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."
)

class UserNotFoundException : ContENIDOException(
    HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."
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
