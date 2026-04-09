package com.contenido.domain.auth.service

import com.contenido.domain.auth.dto.LoginRequest
import com.contenido.domain.auth.dto.SignupRequest
import com.contenido.domain.auth.dto.TokenReissueRequest
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import com.contenido.global.jwt.JwtTokenProvider
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.TimeUnit

@ExtendWith(MockKExtension::class)
class AuthServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var passwordEncoder: PasswordEncoder
    @MockK lateinit var jwtTokenProvider: JwtTokenProvider
    @MockK lateinit var redisTemplate: RedisTemplate<String, String>
    @MockK lateinit var valueOps: ValueOperations<String, String>

    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        authService = AuthService(
            userRepository = userRepository,
            passwordEncoder = passwordEncoder,
            jwtTokenProvider = jwtTokenProvider,
            redisTemplate = redisTemplate,
            accessTokenExpiration = 1800000L,
            refreshTokenExpiration = 1209600000L,
        )
        every { redisTemplate.opsForValue() } returns valueOps
    }

    // ── signup ────────────────────────────────────────────────────────────────

    @Test
    fun `signup 성공`() {
        val request = signupRequest()
        val savedUser = createUser(id = 1L)

        every { userRepository.existsByEmail(request.email) } returns false
        every { userRepository.existsByNickname(request.nickname) } returns false
        every { passwordEncoder.encode(request.password) } returns "encodedPassword"
        every { userRepository.save(any()) } returns savedUser

        val result = authService.signup(request)

        assertThat(result.userId).isEqualTo(1L)
        assertThat(result.email).isEqualTo("test@test.com")
        assertThat(result.nickname).isEqualTo("testUser")
    }

    @Test
    fun `signup 이메일 중복 예외`() {
        val request = signupRequest()
        every { userRepository.existsByEmail(request.email) } returns true

        assertThrows<DuplicateEmailException> { authService.signup(request) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `signup 닉네임 중복 예외`() {
        val request = signupRequest()
        every { userRepository.existsByEmail(request.email) } returns false
        every { userRepository.existsByNickname(request.nickname) } returns true

        assertThrows<DuplicateNicknameException> { authService.signup(request) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    fun `login 성공`() {
        val user = createUser(id = 1L)
        every { userRepository.findByEmail("test@test.com") } returns Optional.of(user)
        every { passwordEncoder.matches("password123", "encodedPassword") } returns true
        every { jwtTokenProvider.generateAccessToken(1L, "PARTICIPANT") } returns "accessToken"
        every { jwtTokenProvider.generateRefreshToken(1L, "PARTICIPANT") } returns "refreshToken"
        every { valueOps.set(any(), any(), any(), any<TimeUnit>()) } just Runs

        val result = authService.login(LoginRequest("test@test.com", "password123"))

        assertThat(result.accessToken).isEqualTo("accessToken")
        assertThat(result.refreshToken).isEqualTo("refreshToken")
    }

    @Test
    fun `login 탈퇴한 유저 예외`() {
        val user = createUser(id = 1L).also { it.softDelete() }
        every { userRepository.findByEmail("test@test.com") } returns Optional.of(user)

        assertThrows<DeletedUserException> {
            authService.login(LoginRequest("test@test.com", "password123"))
        }
    }

    @Test
    fun `login 비밀번호 불일치 예외`() {
        val user = createUser(id = 1L)
        every { userRepository.findByEmail("test@test.com") } returns Optional.of(user)
        every { passwordEncoder.matches("wrongPassword", "encodedPassword") } returns false

        assertThrows<InvalidCredentialsException> {
            authService.login(LoginRequest("test@test.com", "wrongPassword"))
        }
    }

    // ── reissue ───────────────────────────────────────────────────────────────

    @Test
    fun `reissue 성공`() {
        val user = createUser(id = 1L)
        val refreshToken = "validRefreshToken"

        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.getUserIdFromToken(refreshToken) } returns 1L
        every { valueOps.get("RT:1") } returns refreshToken
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { jwtTokenProvider.generateAccessToken(1L, "PARTICIPANT") } returns "newAccessToken"
        every { jwtTokenProvider.generateRefreshToken(1L, "PARTICIPANT") } returns "newRefreshToken"
        every { valueOps.set(any(), any(), any(), any<TimeUnit>()) } just Runs

        val result = authService.reissue(TokenReissueRequest(refreshToken))

        assertThat(result.accessToken).isEqualTo("newAccessToken")
        assertThat(result.refreshToken).isEqualTo("newRefreshToken")
    }

    @Test
    fun `reissue Redis 토큰 불일치 예외`() {
        val refreshToken = "validRefreshToken"

        every { jwtTokenProvider.validateToken(refreshToken) } returns true
        every { jwtTokenProvider.getUserIdFromToken(refreshToken) } returns 1L
        every { valueOps.get("RT:1") } returns "differentToken"

        assertThrows<InvalidTokenException> {
            authService.reissue(TokenReissueRequest(refreshToken))
        }
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    fun `logout 성공`() {
        every { redisTemplate.delete("RT:1") } returns true

        authService.logout(1L)

        verify { redisTemplate.delete("RT:1") }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun createUser(
            id: Long = 1L,
            email: String = "test@test.com",
            password: String = "encodedPassword",
            nickname: String = "testUser",
            phoneNumber: String = "01012345678",
            role: UserRole = UserRole.PARTICIPANT,
        ): User {
            val user = User(email, password, nickname, role, phoneNumber)
            ReflectionTestUtils.setField(user, "id", id)
            ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(user, "updatedAt", LocalDateTime.now())
            return user
        }

        fun signupRequest(
            email: String = "test@test.com",
            password: String = "password123",
            nickname: String = "testUser",
            phoneNumber: String = "01012345678",
        ) = SignupRequest(email, password, nickname, phoneNumber)
    }
}
