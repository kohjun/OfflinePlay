package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminHideTargetRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.entity.Comment
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.entity.ReportAppeal
import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.report.service.ReportService
import com.contenido.domain.review.entity.Review
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.global.exception.ReportTargetNotFoundException
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
 * PR88 — 분리 후 남은 `AdminModerationService` facade 의 hide/unhide 책임만 검증한다.
 *
 * 채널 ban/unban / queue / stats 시나리오는 PR87 에서 분리된 sub-service 단위 테스트로 옮겨졌다:
 *  - [AdminChannelBanServiceTest]
 *  - [AdminModerationQueueServiceTest]
 *  - [AdminModerationStatsServiceTest]
 *
 * 본 파일은 다음 시나리오만 보관한다 (PR54/PR61 정책 그대로):
 *  - hideTarget REVIEW / POST / 미존재 / 이미 hidden / PENDING appeal 보존
 *  - unhideTarget hidden REVIEW / COMMENT / 미존재 / hidden 아닌 대상 / PENDING appeal 보존
 *  - hideTarget / unhideTarget audit 기록 (TARGET_HIDDEN / TARGET_UNHIDDEN)
 *
 * AdminModerationService 는 PR87 facade 라 ban/queue/stats 위임용 sub-service 도 생성자에
 * 필요. setUp 에서 real instance 로 wiring 하지만 본 테스트가 그쪽 public method 를 호출하지
 * 않으므로 sub-service 가 사용하는 별도 stub 은 필요 없다.
 */
@ExtendWith(MockKExtension::class)
class AdminModerationServiceTest {

    @MockK lateinit var reviewRepository: ReviewRepository
    @MockK lateinit var commentRepository: CommentRepository
    @MockK lateinit var postRepository: PostRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var reportRepository: ReportRepository
    @MockK lateinit var reportAppealRepository: ReportAppealRepository
    @MockK(relaxed = true) lateinit var notificationService: NotificationService
    @MockK lateinit var moderationThresholdService: ModerationThresholdService

    // PR61 — hide/unhide 가 audit 를 기록. 상세 검증은 본 클래스의 audit 섹션에서 한다.
    @MockK(relaxed = true) lateinit var moderationAuditLogService: ModerationAuditLogService

    // PR93 — facade 의 statsService 가 새로 의존하는 두 컴포넌트. hide/unhide 시나리오에선
    // 호출되지 않지만 생성자 의존성으로 필요해 relaxed mock 으로 채운다.
    @MockK(relaxed = true) lateinit var moderationAuditLogRepository: com.contenido.domain.admin.repository.ModerationAuditLogRepository
    @MockK(relaxed = true) lateinit var systemActorService: SystemActorService

    private lateinit var service: AdminModerationService

    /** PR61 — admin actor id placeholder. */
    private val ACTOR_ID: Long = 99L

    @BeforeEach
    fun setUp() {
        // PR87 — facade 는 ban/queue/stats 위임용 sub-service 가 필수 생성자 인자. 본 테스트는
        // hide/unhide 만 검증하므로 sub-service 들은 동일 mock 들로 wiring 만 해 두면 충분.
        val channelBanService = AdminChannelBanService(
            channelRepository = channelRepository,
            eventRepository = eventRepository,
            postRepository = postRepository,
            reviewRepository = reviewRepository,
            notificationService = notificationService,
            moderationAuditLogService = moderationAuditLogService,
        )
        val queueService = AdminModerationQueueService(
            reviewRepository = reviewRepository,
            commentRepository = commentRepository,
            postRepository = postRepository,
            eventRepository = eventRepository,
            channelRepository = channelRepository,
            reportRepository = reportRepository,
            reportAppealRepository = reportAppealRepository,
            moderationThresholdService = moderationThresholdService,
        )
        val statsService = AdminModerationStatsService(
            reviewRepository = reviewRepository,
            commentRepository = commentRepository,
            postRepository = postRepository,
            eventRepository = eventRepository,
            channelRepository = channelRepository,
            reportRepository = reportRepository,
            reportAppealRepository = reportAppealRepository,
            moderationAuditLogRepository = moderationAuditLogRepository,
            systemActorService = systemActorService,
        )
        service = AdminModerationService(
            reviewRepository = reviewRepository,
            commentRepository = commentRepository,
            postRepository = postRepository,
            eventRepository = eventRepository,
            channelRepository = channelRepository,
            reportRepository = reportRepository,
            reportAppealRepository = reportAppealRepository,
            moderationAuditLogService = moderationAuditLogService,
            channelBanService = channelBanService,
            queueService = queueService,
            statsService = statsService,
        )
        // hide/unhide 응답 빌딩에 항상 사용되는 기본 stub.
        every { reportRepository.countByTargetTypeAndTargetIdAndStatus(any(), any(), any()) } returns 0L
        every {
            reportAppealRepository.findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(any(), any())
        } returns null
    }

    // ── hide ────────────────────────────────────────────────────────────────

    @Test
    fun `hideTarget REVIEW 성공 — entity hide + 응답에 hidden=true`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author)

        every { reviewRepository.findById(50L) } returns Optional.of(review)

        val response = service.hideTarget(
            ACTOR_ID, ReportTargetType.REVIEW, 50L, AdminHideTargetRequest("정책 위반 명백"),
        )

        assertThat(review.isHidden).isTrue()
        assertThat(review.hiddenReason).isEqualTo("정책 위반 명백")
        assertThat(response.hidden).isTrue()
        assertThat(response.hiddenReason).isEqualTo("정책 위반 명백")
        assertThat(response.targetType).isEqualTo(ReportTargetType.REVIEW)
    }

    @Test
    fun `hideTarget 이미 hidden 인 대상에 또 호출하면 TargetAlreadyHiddenException`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author).apply {
            hide(ReportService.AUTO_HIDE_REASON)
        }

        every { reviewRepository.findById(50L) } returns Optional.of(review)

        assertThrows<TargetAlreadyHiddenException> {
            service.hideTarget(ACTOR_ID, ReportTargetType.REVIEW, 50L, AdminHideTargetRequest("덮어쓰기 시도"))
        }
        // 기존 사유 보존.
        assertThat(review.hiddenReason).isEqualTo(ReportService.AUTO_HIDE_REASON)
    }

    @Test
    fun `hideTarget 미존재 대상이면 ReportTargetNotFoundException`() {
        every { reviewRepository.findById(404L) } returns Optional.empty()

        assertThrows<ReportTargetNotFoundException> {
            service.hideTarget(ACTOR_ID, ReportTargetType.REVIEW, 404L, AdminHideTargetRequest("x"))
        }
    }

    @Test
    fun `hideTarget POST targetType 분기 — entity 가 hide 처리됨`() {
        val author = createUser(id = 5L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = author)
        val post = Post(channel = channel, author = author, title = "공지", content = "본문")
            .apply {
                ReflectionTestUtils.setField(this, "id", 60L)
                val now = LocalDateTime.now()
                ReflectionTestUtils.setField(this, "createdAt", now)
                ReflectionTestUtils.setField(this, "updatedAt", now)
            }

        every { postRepository.findById(60L) } returns Optional.of(post)

        val response = service.hideTarget(
            ACTOR_ID, ReportTargetType.POST, 60L, AdminHideTargetRequest("스팸 광고"),
        )

        assertThat(post.isHidden).isTrue()
        assertThat(response.targetType).isEqualTo(ReportTargetType.POST)
        assertThat(response.targetTitle).isEqualTo("공지")
    }

    @Test
    fun `hideTarget 이 PENDING appeal 을 자동으로 reject 하지 않는다`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author)
        val pendingAppeal = ReportAppeal(
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
            requester = author,
            reason = "오해입니다",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 200L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }

        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportAppealRepository.findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                ReportTargetType.REVIEW, 50L,
            )
        } returns pendingAppeal

        val response = service.hideTarget(
            ACTOR_ID, ReportTargetType.REVIEW, 50L, AdminHideTargetRequest("수동 hide"),
        )

        // PR54 정책: 수동 hide 가 appeal 상태를 자동 변경하지 않는다.
        assertThat(pendingAppeal.status).isEqualTo(ReportAppealStatus.PENDING)
        assertThat(response.latestAppealStatus).isEqualTo(ReportAppealStatus.PENDING)
    }

    // ── unhide ──────────────────────────────────────────────────────────────

    @Test
    fun `unhideTarget hidden REVIEW 성공 — entity unhide + 응답 hidden=false`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author).apply {
            hide(ReportService.AUTO_HIDE_REASON)
        }

        every { reviewRepository.findById(50L) } returns Optional.of(review)

        val response = service.unhideTarget(ACTOR_ID, ReportTargetType.REVIEW, 50L)

        assertThat(review.isHidden).isFalse()
        assertThat(review.hiddenReason).isNull()
        assertThat(response.hidden).isFalse()
    }

    @Test
    fun `unhideTarget hidden 이 아닌 대상에 호출하면 TargetNotHiddenException`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author)

        every { reviewRepository.findById(50L) } returns Optional.of(review)

        assertThrows<TargetNotHiddenException> {
            service.unhideTarget(ACTOR_ID, ReportTargetType.REVIEW, 50L)
        }
    }

    @Test
    fun `unhideTarget 미존재 대상이면 ReportTargetNotFoundException`() {
        every { channelRepository.findById(999L) } returns Optional.empty()

        assertThrows<ReportTargetNotFoundException> {
            service.unhideTarget(ACTOR_ID, ReportTargetType.CHANNEL, 999L)
        }
    }

    @Test
    fun `unhideTarget 이 PENDING appeal 을 자동으로 approve 하지 않는다`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author).apply {
            hide(ReportService.AUTO_HIDE_REASON)
        }
        val pendingAppeal = ReportAppeal(
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
            requester = author,
            reason = "오해입니다",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 201L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }

        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportAppealRepository.findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                ReportTargetType.REVIEW, 50L,
            )
        } returns pendingAppeal

        val response = service.unhideTarget(ACTOR_ID, ReportTargetType.REVIEW, 50L)

        // PR54 정책: 수동 unhide 가 appeal 자동 승인하지 않는다.
        assertThat(pendingAppeal.status).isEqualTo(ReportAppealStatus.PENDING)
        assertThat(response.latestAppealStatus).isEqualTo(ReportAppealStatus.PENDING)
    }

    @Test
    fun `unhideTarget COMMENT 분기 — entity 가 unhide 처리됨`() {
        val author = createUser(id = 5L)
        val comment = Comment(
            author = author,
            targetType = TargetType.EVENT,
            targetId = 100L,
            content = "댓글",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 70L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
            hide(ReportService.AUTO_HIDE_REASON)
        }

        every { commentRepository.findById(70L) } returns Optional.of(comment)

        val response = service.unhideTarget(ACTOR_ID, ReportTargetType.COMMENT, 70L)

        assertThat(comment.isHidden).isFalse()
        assertThat(response.targetType).isEqualTo(ReportTargetType.COMMENT)
    }

    // ── PR61 audit ──────────────────────────────────────────────────────────

    @Test
    fun `hideTarget 성공 시 TARGET_HIDDEN audit 기록 (actor + target + reason)`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author)
        every { reviewRepository.findById(50L) } returns Optional.of(review)

        service.hideTarget(
            ACTOR_ID, ReportTargetType.REVIEW, 50L, AdminHideTargetRequest("정책 위반"),
        )

        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = ACTOR_ID,
                action = ModerationAuditAction.TARGET_HIDDEN,
                targetType = ReportTargetType.REVIEW,
                targetId = 50L,
                beforeValue = null,
                afterValue = null,
                reason = "정책 위반",
            )
        }
    }

    @Test
    fun `unhideTarget 성공 시 TARGET_UNHIDDEN audit 기록 (직전 hide 사유 보존)`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author).apply {
            hide("부적절 콘텐츠")
        }
        every { reviewRepository.findById(50L) } returns Optional.of(review)

        service.unhideTarget(ACTOR_ID, ReportTargetType.REVIEW, 50L)

        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = ACTOR_ID,
                action = ModerationAuditAction.TARGET_UNHIDDEN,
                targetType = ReportTargetType.REVIEW,
                targetId = 50L,
                beforeValue = null,
                afterValue = null,
                reason = "부적절 콘텐츠",
            )
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

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

    private fun createReview(id: Long, author: User): Review {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        return Review(event = event, author = author, rating = 4, content = "본문").apply {
            ReflectionTestUtils.setField(this, "id", id)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }
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
