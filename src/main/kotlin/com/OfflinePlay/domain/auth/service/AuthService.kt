package com.contenido.domain.auth.service

import com.contenido.domain.auth.dto.*
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import com.contenido.global.jwt.JwtTokenProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

/**
 * Authentication entry points and refresh-token lifecycle.
 *
 * Refresh tokens are persisted in Redis under `RT:{userId}` with a TTL equal to the
 * configured refresh-token expiration. Rotation policy:
 *
 *  - login: issue new {access, refresh}; store refresh in Redis (overwrites any prior value)
 *  - reissue: verify caller's refresh matches stored value, then issue new pair and
 *             overwrite stored refresh (rotation). On mismatch, force-delete the stored
 *             value and throw [TokenReusedException] (401) — the caller must re-login.
 *  - logout: delete stored refresh.
 *
 * Access tokens are stateless JWTs and are never persisted server-side.
 */
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

        // 가입 시 선택 가능한 역할은 PARTICIPANT / CREATOR 뿐. ADMIN은 self-issue 불가.
        // DTO Pattern 으로도 막혀 있지만 서비스에서 한 번 더 검증한다(보안 표면 축소).
        val signupRole = parseSignupRole(request.role)

        val user = userRepository.save(
            User(
                email = request.email,
                password = passwordEncoder.encode(request.password),
                nickname = request.nickname,
                phoneNumber = request.phoneNumber,
            ).apply { updateRole(signupRole) }
        )

        return SignupResponse(
            userId = user.id,
            email = user.email,
            nickname = user.nickname,
        )
    }

    private fun parseSignupRole(raw: String?): UserRole {
        if (raw.isNullOrBlank()) return UserRole.PARTICIPANT
        return when (raw.uppercase()) {
            "PARTICIPANT" -> UserRole.PARTICIPANT
            "CREATOR" -> UserRole.CREATOR
            // ADMIN 및 그 외 값은 모두 거부.
            else -> throw InvalidSignupRoleException()
        }
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
        // 1. Reject malformed or expired refresh tokens before touching Redis.
        jwtTokenProvider.validateToken(request.refreshToken)

        val userId = jwtTokenProvider.getUserIdFromToken(request.refreshToken)

        // 2. The token must match exactly what we last stored. A mismatch means either
        //    the token was stolen and the legitimate user has since rotated, or the
        //    stored entry has expired. In either case force-delete and require re-login.
        val storedToken = redisTemplate.opsForValue().get(redisKey(userId))
        if (storedToken == null || storedToken != request.refreshToken) {
            deleteRefreshToken(userId)
            throw TokenReusedException()
        }

        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()

        // 3. Rotate: issueTokens overwrites the Redis entry with the new refresh token.
        return issueTokens(user)
    }

    fun logout(userId: Long) {
        deleteRefreshToken(userId)
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun issueTokens(user: User): TokenResponse {
        val role = user.role.name
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, role)
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.id, role)

        saveRefreshToken(user.id, refreshToken)

        return TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = accessTokenExpiration / 1000,
        )
    }

    private fun redisKey(userId: Long): String = "$REFRESH_TOKEN_PREFIX$userId"

    private fun saveRefreshToken(userId: Long, refreshToken: String) {
        redisTemplate.opsForValue().set(
            redisKey(userId),
            refreshToken,
            refreshTokenExpiration,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun deleteRefreshToken(userId: Long) {
        redisTemplate.delete(redisKey(userId))
    }
}
