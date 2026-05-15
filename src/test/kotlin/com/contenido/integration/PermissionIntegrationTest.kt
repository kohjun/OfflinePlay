package com.contenido.integration

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventParticipation
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.repository.NotificationRepository
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.search.service.SearchSyncService
import com.contenido.domain.ticket.entity.Ticket
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * 권한 통합 테스트.
 *
 * 서비스 레이어 단위 테스트가 비즈니스 규칙을 커버한다면, 이 테스트는 web/security 계층까지
 * 거쳐 실제 JWT/Spring Security 가 권한 분기를 통과/차단하는지 검증한다.
 *
 * 커버 범위:
 *  - 이벤트 수정 : owner / ADMIN 200, PARTICIPANT / 비owner CREATOR 403
 *  - 스태프 추가 : owner 201, PARTICIPANT 403
 *  - 체크인     : STAFF / owner 200, buyer 본인 403, 무관 CREATOR 403
 *  - 공지 수정/삭제: author 200/204, PARTICIPANT / 비author CREATOR 403
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var channelRepository: ChannelRepository
    @Autowired lateinit var channelMemberRepository: ChannelMemberRepository
    @Autowired lateinit var channelSubscriptionRepository: ChannelSubscriptionRepository
    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var eventParticipationRepository: EventParticipationRepository
    @Autowired lateinit var ticketRepository: TicketRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var notificationRepository: NotificationRepository
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean lateinit var redisTemplate: RedisTemplate<String, String>
    // SearchSyncService 는 private + @TransactionalEventListener 라 relaxed mock 으로 no-op.
    @MockkBean(relaxed = true) lateinit var searchSyncService: SearchSyncService
    @MockkBean(relaxed = true) lateinit var elasticsearchOperations: ElasticsearchOperations
    // NotificationService.notify 는 @Async — 실제로 두면 별도 스레드에서 user 삭제 후 notifications
    // insert 가 발생해 tearDown 의 FK 위반을 일으킨다. 권한 테스트 자체는 알림 부수효과를 검증하지
    // 않으므로 relaxed mock 으로 차단.
    @MockkBean(relaxed = true) lateinit var notificationService: NotificationService

    private lateinit var owner: User
    private lateinit var staffUser: User
    private lateinit var participant: User
    private lateinit var admin: User
    private lateinit var otherCreator: User

    private lateinit var ownerToken: String
    private lateinit var staffToken: String
    private lateinit var participantToken: String
    private lateinit var adminToken: String
    private lateinit var otherCreatorToken: String

    private lateinit var channel: Channel
    private lateinit var event: Event
    private lateinit var ticket: Ticket
    private lateinit var post: Post

    @BeforeEach
    fun setUp() {
        val valueOps = io.mockk.mockk<ValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.set(any(), any(), any(), any()) } just Runs
        every { valueOps.get(any<String>()) } returns null
        every { redisTemplate.delete(any<String>()) } returns true

        owner = saveUser("owner@test.com", "오너", "01011110000", UserRole.CREATOR)
        staffUser = saveUser("staff@test.com", "스태프", "01022220000", UserRole.CREATOR)
        participant = saveUser("participant@test.com", "참가자", "01033330000", UserRole.PARTICIPANT)
        admin = saveUser("admin@test.com", "관리자", "01044440000", UserRole.ADMIN)
        otherCreator = saveUser("other@test.com", "타기획자", "01055550000", UserRole.CREATOR)

        ownerToken = jwtTokenProvider.generateAccessToken(owner.id, "CREATOR")
        staffToken = jwtTokenProvider.generateAccessToken(staffUser.id, "CREATOR")
        participantToken = jwtTokenProvider.generateAccessToken(participant.id, "PARTICIPANT")
        adminToken = jwtTokenProvider.generateAccessToken(admin.id, "ADMIN")
        otherCreatorToken = jwtTokenProvider.generateAccessToken(otherCreator.id, "CREATOR")

        channel = channelRepository.save(
            Channel(owner, "권한 테스트 채널", "설명", ChannelCategory.MUSIC)
        )
        // ChannelService.createChannel 은 owner 를 ChannelMember(OWNER)로 자동 등록하지만,
        // 여기서는 Channel 을 직접 저장하므로 STAFF row 와 함께 명시 등록한다.
        channelMemberRepository.save(ChannelMember(channel, owner, ChannelMemberRole.OWNER))
        channelMemberRepository.save(ChannelMember(channel, staffUser, ChannelMemberRole.STAFF))

        event = eventRepository.save(
            Event(
                channel = channel,
                title = "테스트 이벤트",
                description = "설명",
                location = "서울",
                mainImageUrl = "https://example.com/img.jpg",
                startAt = LocalDateTime.now().plusDays(1),
                endAt = LocalDateTime.now().plusDays(2),
                maxParticipants = 10,
                participationFee = 0L,
                refundPolicy = "전액 환불",
                detailContent = "디테일",
            )
        )

        // participant 가 APPROVED 받고 PAID 티켓 보유 — 체크인 시나리오 준비.
        val approved = EventParticipation(event = event, participant = participant).apply {
            status = ParticipationStatus.APPROVED
        }
        eventParticipationRepository.save(approved)
        ticket = ticketRepository.save(
            Ticket(event = event, buyer = participant, price = 0L, status = TicketStatus.PAID),
        )

        post = postRepository.save(
            Post(channel = channel, author = owner, title = "공지 제목", content = "공지 내용"),
        )
    }

    @AfterEach
    fun tearDown() {
        // FK 순서: 깊은 dependent 먼저. 체크인 성공 케이스가 TICKET_CHECKED_IN 알림 row 를
        // 만들기 때문에 users 삭제 전에 notifications 도 비워야 한다.
        notificationRepository.deleteAll()
        ticketRepository.deleteAll()
        eventParticipationRepository.deleteAll()
        eventRepository.deleteAll()
        postRepository.deleteAll()
        channelMemberRepository.deleteAll()
        channelSubscriptionRepository.deleteAll()
        channelRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun saveUser(email: String, nickname: String, phone: String, role: UserRole): User =
        userRepository.save(
            User(email, passwordEncoder.encode("password123"), nickname, phone)
                .apply { updateRole(role) },
        )

    private fun checkInBody() =
        objectMapper.writeValueAsString(mapOf("checkInCode" to "CONTENIDO-${ticket.id}-${event.id}"))

    // ── 이벤트 수정 권한 ─────────────────────────────────────────────────────────

    @Test
    fun `PATCH events 200 owner 가 수정 가능`() {
        mockMvc.perform(
            patch("/api/v1/events/${event.id}")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"오너 수정"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.title").value("오너 수정"))
    }

    @Test
    fun `PATCH events 200 ADMIN 도 수정 가능`() {
        mockMvc.perform(
            patch("/api/v1/events/${event.id}")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"관리자 수정"}"""),
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `PATCH events 403 PARTICIPANT 는 수정 불가`() {
        mockMvc.perform(
            patch("/api/v1/events/${event.id}")
                .header("Authorization", "Bearer $participantToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"안됨"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `PATCH events 403 비owner CREATOR 는 수정 불가`() {
        mockMvc.perform(
            patch("/api/v1/events/${event.id}")
                .header("Authorization", "Bearer $otherCreatorToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"안됨"}"""),
        ).andExpect(status().isForbidden)
    }

    // ── 스태프 추가 권한 ─────────────────────────────────────────────────────────

    @Test
    fun `POST channels members staff 201 owner`() {
        val candidate = saveUser("candidate@test.com", "후보", "01066660000", UserRole.PARTICIPANT)
        mockMvc.perform(
            post("/api/v1/channels/${channel.id}/members/staff")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"${candidate.email}"}"""),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `POST channels members staff 403 PARTICIPANT 는 추가 불가`() {
        mockMvc.perform(
            post("/api/v1/channels/${channel.id}/members/staff")
                .header("Authorization", "Bearer $participantToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"someone@test.com"}"""),
        ).andExpect(status().isForbidden)
    }

    // ── 체크인 권한 ─────────────────────────────────────────────────────────────

    @Test
    fun `POST tickets check-in 200 STAFF 가능`() {
        mockMvc.perform(
            post("/api/v1/tickets/check-in")
                .header("Authorization", "Bearer $staffToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody()),
        ).andExpect(status().isOk)
    }

    @Test
    fun `POST tickets check-in 200 owner 가능`() {
        mockMvc.perform(
            post("/api/v1/tickets/check-in")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody()),
        ).andExpect(status().isOk)
    }

    @Test
    fun `POST tickets check-in 403 buyer 본인은 불가`() {
        mockMvc.perform(
            post("/api/v1/tickets/check-in")
                .header("Authorization", "Bearer $participantToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody()),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST tickets check-in 403 무관 CREATOR 는 불가`() {
        mockMvc.perform(
            post("/api/v1/tickets/check-in")
                .header("Authorization", "Bearer $otherCreatorToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody()),
        ).andExpect(status().isForbidden)
    }

    // ── 공지 수정/삭제 권한 ────────────────────────────────────────────────────

    @Test
    fun `PATCH posts 200 author 가 수정 가능`() {
        mockMvc.perform(
            patch("/api/v1/channels/${channel.id}/posts/${post.id}")
                .header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"수정됨"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.title").value("수정됨"))
    }

    @Test
    fun `PATCH posts 403 PARTICIPANT 는 수정 불가`() {
        mockMvc.perform(
            patch("/api/v1/channels/${channel.id}/posts/${post.id}")
                .header("Authorization", "Bearer $participantToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"안됨"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `PATCH posts 403 비author CREATOR 는 수정 불가`() {
        mockMvc.perform(
            patch("/api/v1/channels/${channel.id}/posts/${post.id}")
                .header("Authorization", "Bearer $otherCreatorToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"안됨"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `DELETE posts 204 author 가 삭제 가능`() {
        mockMvc.perform(
            delete("/api/v1/channels/${channel.id}/posts/${post.id}")
                .header("Authorization", "Bearer $ownerToken"),
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE posts 403 PARTICIPANT 는 삭제 불가`() {
        mockMvc.perform(
            delete("/api/v1/channels/${channel.id}/posts/${post.id}")
                .header("Authorization", "Bearer $participantToken"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `DELETE posts 403 비author CREATOR 는 삭제 불가`() {
        mockMvc.perform(
            delete("/api/v1/channels/${channel.id}/posts/${post.id}")
                .header("Authorization", "Bearer $otherCreatorToken"),
        ).andExpect(status().isForbidden)
    }
}
