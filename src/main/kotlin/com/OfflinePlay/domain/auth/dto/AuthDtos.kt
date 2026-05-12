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

    /**
     * 가입 시 사용자가 선택한 역할.
     *
     *  - null / 미지정 : PARTICIPANT 로 기본 가입
     *  - "PARTICIPANT" : 참가자
     *  - "CREATOR"     : 기획자 (가입 직후 채널 생성 가능)
     *
     * "ADMIN" 은 자가 발급이 불가능하므로 패턴 단계에서 차단되고, 서비스 레이어에서도
     * 이중 검증한다(보안 표면 최소화).
     */
    @field:Pattern(
        regexp = "PARTICIPANT|CREATOR",
        message = "역할은 PARTICIPANT 또는 CREATOR 중 하나여야 합니다.",
    )
    val role: String? = null,
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
