package com.contenido.domain.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

// ── Request ──────────────────────────────────────────────────────────────────

data class SignupRequest(

    @field:Email(message = "올바른 이메일 형식이 아닙니다.")
    @field:NotBlank(message = "이메일은 필수입니다.")
    val email: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 8, max = 20, message = "비밀번호는 8~20자 사이여야 합니다.")
    val password: String,

    @field:NotBlank(message = "닉네임은 필수입니다.")
    @field:Size(min = 2, max = 20, message = "닉네임은 2~20자 사이여야 합니다.")
    val nickname: String,

    @field:NotBlank(message = "전화번호는 필수입니다.")
    @field:Pattern(
        regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$",
        message = "올바른 전화번호 형식이 아닙니다.",
    )
    val phoneNumber: String,
)

data class LoginRequest(

    @field:Email(message = "올바른 이메일 형식이 아닙니다.")
    @field:NotBlank(message = "이메일은 필수입니다.")
    val email: String,

    @field:NotBlank(message = "비밀번호는 필수입니다.")
    val password: String,
)

data class TokenReissueRequest(
    @field:NotBlank(message = "Refresh Token은 필수입니다.")
    val refreshToken: String,
)

// ── Response ─────────────────────────────────────────────────────────────────

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,         // access token 만료까지 남은 시간 (초)
)

data class SignupResponse(
    val userId: Long,
    val email: String,
    val nickname: String,
)
