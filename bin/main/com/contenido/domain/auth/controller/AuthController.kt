package com.contenido.domain.auth.controller

import com.contenido.domain.auth.dto.*
import com.contenido.domain.auth.service.AuthService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) {

    /**
     * POST /api/v1/auth/signup
     * 신규 회원가입. 성공 시 201 Created 반환.
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(
        @Valid @RequestBody request: SignupRequest,
    ): ApiResponse<SignupResponse> {
        val result = authService.signup(request)
        return ApiResponse.created(result, "회원가입이 완료되었습니다.")
    }

    /**
     * POST /api/v1/auth/login
     * 이메일/비밀번호 로그인. Access Token + Refresh Token 반환.
     */
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): ApiResponse<TokenResponse> {
        val result = authService.login(request)
        return ApiResponse.ok(result, "로그인에 성공했습니다.")
    }

    /**
     * POST /api/v1/auth/reissue
     * Refresh Token 으로 Access Token 재발급.
     */
    @PostMapping("/reissue")
    fun reissue(
        @Valid @RequestBody request: TokenReissueRequest,
    ): ApiResponse<TokenResponse> {
        val result = authService.reissue(request)
        return ApiResponse.ok(result, "토큰이 재발급되었습니다.")
    }

    /**
     * POST /api/v1/auth/logout
     * Redis 에서 Refresh Token 삭제. 인증 필요.
     */
    @PostMapping("/logout")
    fun logout(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<Nothing> {
        authService.logout(userId)
        return ApiResponse.ok(null, "로그아웃 되었습니다.")
    }
}
