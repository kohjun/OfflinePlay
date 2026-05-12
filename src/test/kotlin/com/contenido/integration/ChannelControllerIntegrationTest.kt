package com.contenido.integration

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.search.service.SearchSyncService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.jwt.JwtTokenProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
class ChannelControllerIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var channelRepository: ChannelRepository
    @Autowired lateinit var channelMemberRepository: ChannelMemberRepository
    @Autowired lateinit var channelSubscriptionRepository: ChannelSubscriptionRepository
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean lateinit var redisTemplate: RedisTemplate<String, String>
    // SearchSyncService methods are private and invoked via @TransactionalEventListener;
    // relaxed mock silently no-ops listener invocations without exposing private API to tests.
    @MockkBean(relaxed = true) lateinit var searchSyncService: SearchSyncService
    @MockkBean(relaxed = true) lateinit var elasticsearchOperations: ElasticsearchOperations

    private lateinit var creatorUser: User
    private lateinit var participantUser: User
    private lateinit var creatorToken: String
    private lateinit var participantToken: String

    @BeforeEach
    fun setUp() {
        val valueOps = io.mockk.mockk<ValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.set(any(), any(), any(), any()) } just Runs
        every { valueOps.get(any<String>()) } returns null
        every { redisTemplate.delete(any<String>()) } returns true

        creatorUser = userRepository.save(
            User("creator@test.com", passwordEncoder.encode("password123"), "creator", "01012345678")
                .apply { updateRole(UserRole.CREATOR) }
        )
        participantUser = userRepository.save(
            User("participant@test.com", passwordEncoder.encode("password123"), "participant", "01087654321")
                .apply { updateRole(UserRole.PARTICIPANT) }
        )

        creatorToken = jwtTokenProvider.generateAccessToken(creatorUser.id, "CREATOR")
        participantToken = jwtTokenProvider.generateAccessToken(participantUser.id, "PARTICIPANT")
    }

    @AfterEach
    fun tearDown() {
        // FK order: channel_members + channel_subscriptions both reference channels;
        // channels references users. Delete deepest dependents first.
        channelMemberRepository.deleteAll()
        channelSubscriptionRepository.deleteAll()
        channelRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `POST channels 201 CREATOR 토큰`() {
        mockMvc.perform(
            post("/api/v1/channels")
                .header("Authorization", "Bearer $creatorToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createChannelBody()))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.name").value("테스트 채널"))
            .andExpect(jsonPath("$.data.ownerNickname").value("creator"))

        // owner 가 ChannelMember(OWNER)로 자동 등록되어야 한다.
        val members = channelMemberRepository.findAll()
        org.assertj.core.api.Assertions.assertThat(members).hasSize(1)
        org.assertj.core.api.Assertions.assertThat(members[0].role).isEqualTo(ChannelMemberRole.OWNER)
        org.assertj.core.api.Assertions.assertThat(members[0].user.id).isEqualTo(creatorUser.id)
    }

    @Test
    fun `POST channels 403 PARTICIPANT 토큰`() {
        mockMvc.perform(
            post("/api/v1/channels")
                .header("Authorization", "Bearer $participantToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createChannelBody()))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `GET channels id 200`() {
        val channel = channelRepository.save(
            Channel(creatorUser, "테스트 채널", "설명", ChannelCategory.MUSIC)
        )

        mockMvc.perform(get("/api/v1/channels/${channel.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(channel.id))
            .andExpect(jsonPath("$.data.name").value("테스트 채널"))
    }

    @Test
    fun `POST channels id subscribe 200`() {
        val channel = channelRepository.save(
            Channel(creatorUser, "테스트 채널", "설명", ChannelCategory.MUSIC)
        )

        mockMvc.perform(
            post("/api/v1/channels/${channel.id}/subscribe")
                .header("Authorization", "Bearer $participantToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        fun createChannelBody() = mapOf(
            "name" to "테스트 채널",
            "description" to "채널 설명",
            "category" to "MUSIC",
        )
    }
}
