package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminBanChannelRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.review.entity.Review
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.TargetAlreadyHiddenException
import com.contenido.global.exception.TargetNotHiddenException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR88 — `AdminChannelBanService` 의 단위 테스트.
 *
 * PR87 에서 facade(`AdminModerationService`) 로부터 분리된 채널 ban/unban 책임은
 * 다음 시나리오로 검증한다 (PR58/59/61 정책 그대로):
 *  - ban 성공 → channel hide+deactivate + cascade hide + 새로 숨긴 row 수 카운트
 *  - 이미 hidden 채널 ban 재시도 → [TargetAlreadyHiddenException]
 *  - 미존재 채널 ban → [ChannelNotFoundException]
 *  - unban 성공 → channel unhide + activate (소속 콘텐츠는 자동 unhide 안 됨)
 *  - hidden 아닌 채널 unban → [TargetNotHiddenException]
 *  - PR59 — owner 에게 CHANNEL_BANNED / CHANNEL_UNBANNED 알림 발송
 *  - PR59 — 알림 실패가 ban 트랜잭션을 깨뜨리지 않음 (best-effort)
 *  - PR61 — CHANNEL_BANNED / CHANNEL_UNBANNED audit 기록 (cascade 카운트 포함)
 */
@ExtendWith(MockKExtension::class)
class AdminChannelBanServiceTest {

    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var postRepository: PostRepository
    @MockK lateinit var reviewRepository: ReviewRepository
    @MockK(relaxed = true) lateinit var notificationService: NotificationService
    @MockK(relaxed = true) lateinit var moderationAuditLogService: ModerationAuditLogService

    private lateinit var service: AdminChannelBanService

    private val ACTOR_ID: Long = 99L

    @BeforeEach
    fun setUp() {
        service = AdminChannelBanService(
            channelRepository = channelRepository,
            eventRepository = eventRepository,
            postRepository = postRepository,
            reviewRepository = reviewRepository,
            notificationService = notificationService,
            moderationAuditLogService = moderationAuditLogService,
        )
    }

    // ── ban / unban core ────────────────────────────────────────────────────

    @Test
    fun `banChannelForModeration 성공 — channel hide+deactivate + cascade hide`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event1 = createEvent(id = 100L, channel = channel)
        val event2 = createEvent(id = 101L, channel = channel).apply { hide("기존") } // 이미 hidden
        val post = Post(channel = channel, author = owner, title = "공지", content = "본문").apply {
            ReflectionTestUtils.setField(this, "id", 60L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }
        val author = createUser(id = 5L)
        val review1 = Review(event = event1, author = author, rating = 4, content = "후기1").apply {
            ReflectionTestUtils.setField(this, "id", 50L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }

        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { eventRepository.findByChannel(channel) } returns listOf(event1, event2)
        every { postRepository.findByChannel(channel) } returns listOf(post)
        every { reviewRepository.findByEventChannelId(10L) } returns listOf(review1)

        val response = service.banChannelForModeration(ACTOR_ID, 10L, AdminBanChannelRequest("정책 위반"))

        // channel 자체.
        assertThat(channel.isHidden).isTrue()
        assertThat(channel.isActive).isFalse()
        assertThat(channel.hiddenReason).isEqualTo("정책 위반")
        // cascade: event1 새로 hide / event2 는 이미 hidden 이라 count 제외.
        assertThat(event1.isHidden).isTrue()
        assertThat(event2.isHidden).isTrue()
        assertThat(event2.hiddenReason).isEqualTo("기존") // 첫 hide 시점/사유 보존
        assertThat(post.isHidden).isTrue()
        assertThat(review1.isHidden).isTrue()

        // 새로 숨긴 row 만 count.
        assertThat(response.cascadedEventCount).isEqualTo(1)
        assertThat(response.cascadedPostCount).isEqualTo(1)
        assertThat(response.cascadedReviewCount).isEqualTo(1)
        assertThat(response.hidden).isTrue()
        assertThat(response.isActive).isFalse()
    }

    @Test
    fun `banChannelForModeration 이미 hidden 채널이면 TargetAlreadyHiddenException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner).apply { hide("기존") }

        every { channelRepository.findById(10L) } returns Optional.of(channel)

        assertThrows<TargetAlreadyHiddenException> {
            service.banChannelForModeration(ACTOR_ID, 10L, AdminBanChannelRequest("덮어쓰기"))
        }
        // 기존 사유 보존.
        assertThat(channel.hiddenReason).isEqualTo("기존")
    }

    @Test
    fun `banChannelForModeration 미존재 채널이면 ChannelNotFoundException`() {
        every { channelRepository.findById(404L) } returns Optional.empty()

        assertThrows<ChannelNotFoundException> {
            service.banChannelForModeration(ACTOR_ID, 404L, AdminBanChannelRequest("x"))
        }
    }

    @Test
    fun `unbanChannelForModeration 성공 — channel unhide + activate`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner).apply {
            hide("정책 위반"); deactivate()
        }

        every { channelRepository.findById(10L) } returns Optional.of(channel)

        val response = service.unbanChannelForModeration(ACTOR_ID, 10L)

        assertThat(channel.isHidden).isFalse()
        assertThat(channel.isActive).isTrue()
        assertThat(response.hidden).isFalse()
        assertThat(response.isActive).isTrue()
        // 소속 콘텐츠는 자동 unhide 안 됨 — repository 호출 자체 없음을 verify.
        verify(exactly = 0) { eventRepository.findByChannel(any()) }
        verify(exactly = 0) { postRepository.findByChannel(any()) }
        verify(exactly = 0) { reviewRepository.findByEventChannelId(any()) }
    }

    @Test
    fun `unbanChannelForModeration hidden 아닌 채널이면 TargetNotHiddenException`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)

        every { channelRepository.findById(10L) } returns Optional.of(channel)

        assertThrows<TargetNotHiddenException> {
            service.unbanChannelForModeration(ACTOR_ID, 10L)
        }
    }

    // ── PR59: owner notification ─────────────────────────────────────────────

    @Test
    fun `banChannelForModeration 성공 시 channel owner 에게 CHANNEL_BANNED 알림 발송`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)

        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { eventRepository.findByChannel(channel) } returns listOf(event)
        every { postRepository.findByChannel(channel) } returns emptyList()
        every { reviewRepository.findByEventChannelId(10L) } returns emptyList()

        service.banChannelForModeration(ACTOR_ID, 10L, AdminBanChannelRequest("정책 위반"))

        // owner.id 로 CHANNEL_BANNED 알림 발송. message 에 cascade 카운트 포함.
        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(1L),
                type = NotificationType.CHANNEL_BANNED,
                title = any(),
                message = match { it.contains("이벤트 1개") && it.contains("정책 위반") },
                targetType = "channels",
                targetId = 10L,
            )
        }
    }

    @Test
    fun `banChannelForModeration notification 실패해도 ban 트랜잭션은 성공`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)

        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { eventRepository.findByChannel(channel) } returns emptyList()
        every { postRepository.findByChannel(channel) } returns emptyList()
        every { reviewRepository.findByEventChannelId(10L) } returns emptyList()
        // notification 자체에서 예외 던져도 ban 흐름 깨지면 안 됨.
        every {
            notificationService.notify(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("redis down")

        val response = service.banChannelForModeration(ACTOR_ID, 10L, AdminBanChannelRequest("정책"))

        assertThat(channel.isHidden).isTrue()
        assertThat(channel.isActive).isFalse()
        assertThat(response.hidden).isTrue()
    }

    @Test
    fun `unbanChannelForModeration 성공 시 channel owner 에게 CHANNEL_UNBANNED 알림 발송`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner).apply {
            hide("정책 위반"); deactivate()
        }

        every { channelRepository.findById(10L) } returns Optional.of(channel)

        service.unbanChannelForModeration(ACTOR_ID, 10L)

        verify(exactly = 1) {
            notificationService.notify(
                receiverIds = listOf(1L),
                type = NotificationType.CHANNEL_UNBANNED,
                title = any(),
                message = any(),
                targetType = "channels",
                targetId = 10L,
            )
        }
    }

    // ── PR61 audit ──────────────────────────────────────────────────────────

    @Test
    fun `banChannelForModeration 성공 시 CHANNEL_BANNED audit 기록 (cascade 카운트 포함)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { eventRepository.findByChannel(channel) } returns emptyList()
        every { postRepository.findByChannel(channel) } returns emptyList()
        every { reviewRepository.findByEventChannelId(10L) } returns emptyList()

        service.banChannelForModeration(ACTOR_ID, 10L, AdminBanChannelRequest("운영 정책 위반"))

        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = ACTOR_ID,
                action = ModerationAuditAction.CHANNEL_BANNED,
                targetType = ReportTargetType.CHANNEL,
                targetId = 10L,
                beforeValue = null,
                afterValue = mapOf(
                    "cascadedEventCount" to 0,
                    "cascadedPostCount" to 0,
                    "cascadedReviewCount" to 0,
                ),
                reason = "운영 정책 위반",
            )
        }
    }

    @Test
    fun `unbanChannelForModeration 성공 시 CHANNEL_UNBANNED audit 기록`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner).apply { hide("선행 ban 사유") }
        every { channelRepository.findById(10L) } returns Optional.of(channel)

        service.unbanChannelForModeration(ACTOR_ID, 10L)

        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = ACTOR_ID,
                action = ModerationAuditAction.CHANNEL_UNBANNED,
                targetType = ReportTargetType.CHANNEL,
                targetId = 10L,
                beforeValue = null,
                afterValue = null,
                reason = "선행 ban 사유",
            )
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun createUser(
        id: Long,
        role: UserRole = UserRole.PARTICIPANT,
        nickname: String = "user$id",
    ): User = User("u$id@test.com", "pwd", nickname, "01012345$id").apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        updateRole(role)
    }

    private fun createChannel(id: Long, owner: User): Channel =
        Channel(owner, "채널$id", "설명", ChannelCategory.MUSIC).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun createEvent(id: Long, channel: Channel): Event = Event(
        channel = channel,
        title = "이벤트 $id",
        description = "desc",
        location = "서울",
        mainImageUrl = "https://e.com/$id.jpg",
        startAt = LocalDateTime.now().minusDays(1),
        endAt = LocalDateTime.now().minusHours(1),
        maxParticipants = 10,
        participationFee = 0L,
        refundPolicy = "전액",
        detailContent = "detail",
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
    }
}
