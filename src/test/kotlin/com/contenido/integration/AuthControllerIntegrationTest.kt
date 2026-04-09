package com.contenido.integration

import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.domain.search.service.SearchSyncService
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean lateinit var redisTemplate: RedisTemplate<String, String>
    @MockkBean lateinit var searchSyncService: SearchSyncService
    @MockkBean lateinit var elasticsearchOperations: ElasticsearchOperations

    private lateinit var valueOps: ValueOperations<String, String>
    private val redisStore = mutableMapOf<String, String>()

    @BeforeEach
    fun setUp() {
        redisStore.clear()
        valueOps = io.mockk.mockk()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.set(any(), any(), any(), any()) } answers {
            redisStore[firstArg()] = secondArg()
        }
        every { valueOps.get(any<String>()) } answers { redisStore[firstArg()] }
        every { redisTemplate.delete(any<String>()) } answers {
            redisStore.remove(firstArg<String>())
            true
        }
        every { searchSyncService.syncChannel(any()) } just Runs
        every { searchSyncService.syncEvent(any()) } just Runs
        every { searchSyncService.syncPost(any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        userRepository.deleteAll()
    }

    @Test
    fun `POST auth signup 201 반환`() {
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signupBody()))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.email").value("test@test.com"))
    }

    @Test
    fun `POST auth signup 중복 이메일 409`() {
        // 첫 번째 가입
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signupBody()))
        ).andExpect(status().isCreated)

        // 동일 이메일 재가입
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signupBody()))
        ).andExpect(status().isConflict)
    }

    @Test
    fun `POST auth login 200 토큰 반환`() {
        // 회원 가입 후 로그인
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signupBody()))
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody()))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").exists())
            .andExpect(jsonPath("$.data.refreshToken").exists())
    }

    @Test
    fun `POST auth login 잘못된 비밀번호 401`() {
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signupBody()))
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("email" to "test@test.com", "password" to "wrongPassword")
                    )
                )
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST auth reissue 200 반환`() {
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signupBody()))
        ).andExpect(status().isCreated)

        val loginResult = mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody()))
        ).andReturn()

        val refreshToken = objectMapper.readTree(loginResult.response.contentAsString)
            .get("data").get("refreshToken").asText()

        mockMvc.perform(
            post("/api/v1/auth/reissue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").exists())
    }

    @Test
    fun `POST auth logout 200 반환`() {
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signupBody()))
        ).andExpect(status().isCreated)

        val loginResult = mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody()))
        ).andReturn()

        val accessToken = objectMapper.readTree(loginResult.response.contentAsString)
            .get("data").get("accessToken").asText()

        mockMvc.perform(
            post("/api/v1/auth/logout")
                .header("Authorization", "Bearer $accessToken")
        ).andExpect(status().isOk)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun signupBody() = mapOf(
            "email" to "test@test.com",
            "password" to "password123",
            "nickname" to "testUser",
            "phoneNumber" to "01012345678",
        )

        fun loginBody() = mapOf(
            "email" to "test@test.com",
            "password" to "password123",
        )

        fun createUser(
            email: String = "test@test.com",
            password: String,
            role: UserRole = UserRole.PARTICIPANT,
        ) = User(email, password, "testUser", role, "01012345678")
    }
}
