package com.contenido.domain.auth.service

import com.contenido.domain.auth.dto.*
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import com.contenido.global.jwt.JwtTokenProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

@Service
@Transactional(readOnly = true)
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val redisTemplate: RedisTemplate<String, String>,
    @Value("\${jwt.access-token-expiration}") private val accessTokenExpiration: Long,
    @Value("\${jwt.refresh-token-expiration}") private val refreshTokenExpiration: Long,
) {

    companion object {
        private const val REFRESH_TOKEN_PREFIX = "RT:"
    }

    @Transactional
    fun signup(request: SignupRequest): SignupResponse {
        if (userRepository.existsByEmail(request.email)) {
            throw DuplicateEmailException()
        }
        if (userRepository.existsByNickname(request.nickname)) {
            throw DuplicateNicknameException()
        }

        val user = userRepository.save(
            User(
                email = request.email,
                password = passwordEncoder.encode(request.password),
                nickname = request.nickname,
                phoneNumber = request.phoneNumber,
            )
        )

        return SignupResponse(
            userId = user.id,
            email = user.email,
            nickname = user.nickname,
        )
    }

    fun login(request: LoginRequest): TokenResponse {
        val user = userRepository.findByEmail(request.email)
            .orElseThrow { InvalidCredentialsException() }

        if (user.isDeleted) throw DeletedUserException()

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw InvalidCredentialsException()
        }

        return issueTokens(user)
    }

    fun reissue(request: TokenReissueRequest): TokenResponse {
        // 1. Refresh Token 자체 유효성 검증
        jwtTokenProvider.validateToken(request.refreshToken)

        val userId = jwtTokenProvider.getUserIdFromToken(request.refreshToken)

        // 2. Redis 에 저장된 토큰 확인 (Token Rotation 검증)
        val storedToken = redisTemplate.opsForValue().get("$REFRESH_TOKEN_PREFIX$userId")

        // 만약 Redis에 토큰이 없거나, 보관된 토큰과 요청된 토큰이 다르다면
        // 누군가 이미 탈취된 토큰으로 재발급을 받아 Redis 토큰을 교체했음을 의미함
        if (storedToken == null || storedToken != request.refreshToken) {
            // 탈취가 의심되므로 해당 유저의 세션 전체를 강제 삭제 (로그아웃 처리)
            redisTemplate.delete("$REFRESH_TOKEN_PREFIX$userId")
            throw TokenReusedException()
        }

        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException() }

        if (user.isDeleted) {throw DeletedUserException()}

        // 3. 정상 요청인 경우 issueTokens 내부에서 
        // 새로운 Access Token과 새로운 Refresh Token을 발급하고 Redis에 새 값으로 덮어씀 (Rotation)
        return issueTokens(user)
    }

    fun logout(userId: Long) {
        redisTemplate.delete("$REFRESH_TOKEN_PREFIX$userId")
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun issueTokens(user: User): TokenResponse {
        val role = user.role.name
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, role)
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.id, role)

        // Refresh Token → Redis (TTL = refreshTokenExpiration ms → 초 변환)
        redisTemplate.opsForValue().set(
            "$REFRESH_TOKEN_PREFIX${user.id}",
            refreshToken,
            refreshTokenExpiration,
            TimeUnit.MILLISECONDS,
        )

        return TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = accessTokenExpiration / 1000,
        )
    }
}
