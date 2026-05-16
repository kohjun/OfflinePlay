package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminBanChannelRequest
import com.contenido.domain.admin.dto.AdminHideTargetRequest
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.admin.dto.AdminModerationPriority
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.entity.Comment
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.entity.ReportAppeal
import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.report.service.ReportService
import com.contenido.domain.review.entity.Review
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.ReportTargetNotFoundException
import com.contenido.global.exception.TargetAlreadyHiddenException
import com.contenido.global.exception.TargetNotHiddenException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

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
    // PR61 — hide/unhide/ban/unban 가 audit 를 기록. 상세 검증은 별도 테스트에서.
    @MockK(relaxed = true) lateinit var moderationAuditLogService: ModerationAuditLogService

    private lateinit var service: AdminModerationService

    /** PR61 — admin actor id placeholder. */
    private val ACTOR_ID: Long = 99L

    @BeforeEach
    fun setUp() {
        service = AdminModerationService(
            reviewRepository = reviewRepository,
            commentRepository = commentRepository,
            postRepository = postRepository,
            eventRepository = eventRepository,
            channelRepository = channelRepository,
            reportRepository = reportRepository,
            reportAppealRepository = reportAppealRepository,
            notificationService = notificationService,
            moderationThresholdService = moderationThresholdService,
            moderationAuditLogService = moderationAuditLogService,
        )
        // PR60 — computePriority 가 DB 임계치를 조회하므로 PR51 default 로 stub.
        every { moderationThresholdService.thresholdFor(ReportTargetType.REVIEW) } returns 3
        every { moderationThresholdService.thresholdFor(ReportTargetType.COMMENT) } returns 3
        every { moderationThresholdService.thresholdFor(ReportTargetType.POST) } returns 5
        every { moderationThresholdService.thresholdFor(ReportTargetType.EVENT) } returns 5
        every { moderationThresholdService.thresholdFor(ReportTargetType.CHANNEL) } returns 7
        // 응답 빌딩에 항상 사용되는 기본 stub.
        every { reportRepository.countByTargetTypeAndTargetIdAndStatus(any(), any(), any()) } returns 0L
        every {
            reportAppealRepository.findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(any(), any())
        } returns null
        // PR55 queue 빌드용 — 각 source 가 비어 있으면 queue 도 빈 결과.
        every { reportRepository.findByStatusOrderByCreatedAtDesc(any()) } returns emptyList()
        every { reportAppealRepository.findByStatusOrderByCreatedAtDesc(any<ReportAppealStatus>()) } returns emptyList()
        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { commentRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { postRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { eventRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { channelRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        // PR57 stats 빌드용 — 모든 범위 조회가 비어 있으면 시계열은 0 만 채워진다.
        every { reportRepository.findByCreatedAtBetween(any(), any()) } returns emptyList()
        every { reportAppealRepository.findByCreatedAtBetween(any(), any()) } returns emptyList()
        every { reportAppealRepository.findByReviewedAtBetween(any(), any()) } returns emptyList()
        every { reviewRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
        every { commentRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
        every { postRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
        every { eventRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
        every { channelRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
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

    // ── PR55: getQueue ──────────────────────────────────────────────────────

    @Test
    fun `getQueue PENDING report 만 있는 target 은 LOW priority 로 포함`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author)
        val report = createReport(id = 1L, targetType = ReportTargetType.REVIEW, targetId = 50L, reason = "스팸")

        every { reportRepository.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING) } returns listOf(report)
        every { reviewRepository.findById(50L) } returns Optional.of(review)

        val page = service.getQueue(0, 20, null, null, null)

        assertThat(page.content).hasSize(1)
        val item = page.content[0]
        assertThat(item.targetType).isEqualTo(ReportTargetType.REVIEW)
        assertThat(item.targetId).isEqualTo(50L)
        assertThat(item.hidden).isFalse()
        assertThat(item.pendingReportCount).isEqualTo(1L)
        assertThat(item.latestReportId).isEqualTo(1L)
        assertThat(item.priority).isEqualTo(AdminModerationPriority.LOW)
    }

    @Test
    fun `getQueue hidden target 은 PENDING report 가 없어도 HIGH priority 로 포함`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author).apply {
            hide(ReportService.AUTO_HIDE_REASON)
        }

        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(review)

        val page = service.getQueue(0, 20, null, null, null)

        assertThat(page.content).hasSize(1)
        val item = page.content[0]
        assertThat(item.hidden).isTrue()
        assertThat(item.priority).isEqualTo(AdminModerationPriority.HIGH)
        assertThat(item.hiddenReason).isEqualTo(ReportService.AUTO_HIDE_REASON)
    }

    @Test
    fun `getQueue PENDING appeal 이 있는 target 은 HIGH priority + appeal 컨텍스트`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author)
        val pendingAppeal = ReportAppeal(
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
            requester = author,
            reason = "오해입니다",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 300L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }

        every {
            reportAppealRepository.findByStatusOrderByCreatedAtDesc(ReportAppealStatus.PENDING)
        } returns listOf(pendingAppeal)
        every { reviewRepository.findById(50L) } returns Optional.of(review)

        val page = service.getQueue(0, 20, null, null, null)

        assertThat(page.content).hasSize(1)
        val item = page.content[0]
        assertThat(item.priority).isEqualTo(AdminModerationPriority.HIGH)
        assertThat(item.latestAppealId).isEqualTo(300L)
        assertThat(item.latestAppealStatus).isEqualTo(ReportAppealStatus.PENDING)
    }

    @Test
    fun `getQueue 같은 target 의 report + appeal + hidden 은 row 1개로 merge 된다`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author).apply {
            hide(ReportService.AUTO_HIDE_REASON)
        }
        val r1 = createReport(id = 1L, targetType = ReportTargetType.REVIEW, targetId = 50L, reason = "first")
        val r2 = createReport(id = 2L, targetType = ReportTargetType.REVIEW, targetId = 50L, reason = "second")
        val pendingAppeal = ReportAppeal(
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
            requester = author,
            reason = "오해",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 300L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }

        // 동일 (REVIEW, 50) 키 — 3 source 모두 같은 target.
        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(review)
        every {
            reportRepository.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING)
        } returns listOf(r2, r1) // createdAt desc 가정 — r2 가 latest
        every {
            reportAppealRepository.findByStatusOrderByCreatedAtDesc(ReportAppealStatus.PENDING)
        } returns listOf(pendingAppeal)

        val page = service.getQueue(0, 20, null, null, null)

        assertThat(page.content).hasSize(1)
        val item = page.content[0]
        assertThat(item.hidden).isTrue()
        assertThat(item.pendingReportCount).isEqualTo(2L)
        assertThat(item.latestReportId).isEqualTo(2L) // 최신
        assertThat(item.latestAppealId).isEqualTo(300L)
        assertThat(item.priority).isEqualTo(AdminModerationPriority.HIGH)
    }

    @Test
    fun `getQueue 임계치 70% 이상 누적 PENDING report 는 MEDIUM`() {
        // REVIEW 임계치 3 → 70% = 2.1 → ceil 3 → 사실상 hidden 직전. 임계치 미만 (자동 hide 안 됨)
        // 이지만 70% 이상 누적된 케이스는 ReportService 임계치 정책상 실제로 불가능 (3건이면 hide).
        // 대신 POST(임계치 5) → 70% = 3.5 → ceil 4 로 검증.
        val author = createUser(id = 5L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = author)
        val post = Post(channel = channel, author = author, title = "공지", content = "본문")
            .apply {
                ReflectionTestUtils.setField(this, "id", 60L)
                val now = LocalDateTime.now()
                ReflectionTestUtils.setField(this, "createdAt", now)
                ReflectionTestUtils.setField(this, "updatedAt", now)
            }
        val reports = (1L..4L).map { i ->
            createReport(id = i, targetType = ReportTargetType.POST, targetId = 60L, reason = "r$i")
        }

        every { reportRepository.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING) } returns reports
        every { postRepository.findById(60L) } returns Optional.of(post)

        val page = service.getQueue(0, 20, null, null, null)

        assertThat(page.content).hasSize(1)
        assertThat(page.content[0].pendingReportCount).isEqualTo(4L)
        assertThat(page.content[0].priority).isEqualTo(AdminModerationPriority.MEDIUM)
    }

    @Test
    fun `getQueue 우선순위 HIGH 가 먼저 정렬된다`() {
        val author = createUser(id = 5L)
        val hiddenReview = createReview(id = 50L, author = author).apply { hide("h") }
        val anotherUser = createUser(id = 6L, nickname = "user6")
        val nonHiddenReview = createReview(id = 51L, author = anotherUser)
        val reportForNonHidden = createReport(id = 1L, targetType = ReportTargetType.REVIEW, targetId = 51L, reason = "x")

        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(hiddenReview)
        every { reportRepository.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING) } returns listOf(reportForNonHidden)
        every { reviewRepository.findById(51L) } returns Optional.of(nonHiddenReview)

        val page = service.getQueue(0, 20, null, null, null)

        assertThat(page.content).hasSize(2)
        // HIGH (50) 가 LOW (51) 보다 먼저.
        assertThat(page.content[0].targetId).isEqualTo(50L)
        assertThat(page.content[0].priority).isEqualTo(AdminModerationPriority.HIGH)
        assertThat(page.content[1].targetId).isEqualTo(51L)
        assertThat(page.content[1].priority).isEqualTo(AdminModerationPriority.LOW)
    }

    @Test
    fun `getQueue targetType filter — 다른 타입 row 는 제외`() {
        val author = createUser(id = 5L)
        val hiddenReview = createReview(id = 50L, author = author).apply { hide("h") }
        val ownerCreator = createUser(id = 6L, role = UserRole.CREATOR)
        val hiddenChannel = createChannel(id = 10L, owner = ownerCreator).apply { hide("h") }

        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(hiddenReview)
        every { channelRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(hiddenChannel)

        val page = service.getQueue(0, 20, ReportTargetType.REVIEW, null, null)

        assertThat(page.content).hasSize(1)
        assertThat(page.content[0].targetType).isEqualTo(ReportTargetType.REVIEW)
    }

    @Test
    fun `getQueue hidden=true filter — hidden 만 노출`() {
        val author = createUser(id = 5L)
        val hiddenReview = createReview(id = 50L, author = author).apply { hide("h") }
        val anotherUser = createUser(id = 6L, nickname = "user6")
        val nonHiddenReview = createReview(id = 51L, author = anotherUser)
        val reportForNonHidden = createReport(id = 1L, targetType = ReportTargetType.REVIEW, targetId = 51L, reason = "x")

        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(hiddenReview)
        every { reportRepository.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING) } returns listOf(reportForNonHidden)
        every { reviewRepository.findById(51L) } returns Optional.of(nonHiddenReview)

        val page = service.getQueue(0, 20, null, true, null)

        assertThat(page.content).hasSize(1)
        assertThat(page.content[0].targetId).isEqualTo(50L)
        assertThat(page.content[0].hidden).isTrue()
    }

    @Test
    fun `getQueue priority=HIGH filter — HIGH 만 노출`() {
        val author = createUser(id = 5L)
        val hiddenReview = createReview(id = 50L, author = author).apply { hide("h") }
        val anotherUser = createUser(id = 6L, nickname = "user6")
        val nonHiddenReview = createReview(id = 51L, author = anotherUser)
        val reportForNonHidden = createReport(id = 1L, targetType = ReportTargetType.REVIEW, targetId = 51L, reason = "x")

        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(hiddenReview)
        every { reportRepository.findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING) } returns listOf(reportForNonHidden)
        every { reviewRepository.findById(51L) } returns Optional.of(nonHiddenReview)

        val page = service.getQueue(0, 20, null, null, AdminModerationPriority.HIGH)

        assertThat(page.content).hasSize(1)
        assertThat(page.content[0].priority).isEqualTo(AdminModerationPriority.HIGH)
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

    // ── PR57: getStats ──────────────────────────────────────────────────────

    @Test
    fun `getStats 기본 30일 범위 + day granularity 응답`() {
        val response = service.getStats(from = null, to = null, granularity = null)

        assertThat(response.granularity.name).isEqualTo("DAY")
        // 30일 + 시작/끝 동일 날짜 포함 (31 bucket — toLocalDate inclusive).
        assertThat(response.series.size).isEqualTo(31)
        assertThat(response.totals.reportCount).isZero()
        assertThat(response.totals.autoHideCount).isZero()
        assertThat(response.totals.manualHideCount).isZero()
        assertThat(response.riskyChannels).isEmpty()
    }

    @Test
    fun `getStats reports + appeals + hides 가 같은 날 누적되어 카운트된다`() {
        val now = LocalDateTime.now()
        val author = createUser(id = 5L)
        val r1 = createReport(id = 1L, targetType = ReportTargetType.REVIEW, targetId = 50L, reason = "x")
            .also { ReflectionTestUtils.setField(it, "createdAt", now.minusDays(1)) }
        val r2 = createReport(id = 2L, targetType = ReportTargetType.REVIEW, targetId = 50L, reason = "y")
            .also { ReflectionTestUtils.setField(it, "createdAt", now.minusDays(1)) }
        val a1 = ReportAppeal(
            targetType = ReportTargetType.REVIEW, targetId = 50L, requester = author, reason = "appeal",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 100L)
            ReflectionTestUtils.setField(this, "createdAt", now.minusDays(1))
            ReflectionTestUtils.setField(this, "updatedAt", now.minusDays(1))
        }
        val hiddenReview = createReview(id = 51L, author = author).apply {
            hide(ReportService.AUTO_HIDE_REASON)
            ReflectionTestUtils.setField(this, "hiddenAt", now.minusDays(1))
        }
        val manualHiddenPost = run {
            val owner = createUser(id = 1L, role = UserRole.CREATOR)
            val ch = createChannel(id = 10L, owner = owner)
            Post(channel = ch, author = author, title = "글", content = "본문").apply {
                ReflectionTestUtils.setField(this, "id", 60L)
                val nowT = LocalDateTime.now()
                ReflectionTestUtils.setField(this, "createdAt", nowT)
                ReflectionTestUtils.setField(this, "updatedAt", nowT)
                hide("운영자 수동 hide")
                ReflectionTestUtils.setField(this, "hiddenAt", now.minusDays(1))
            }
        }

        every { reportRepository.findByCreatedAtBetween(any(), any()) } returns listOf(r1, r2)
        every { reportAppealRepository.findByCreatedAtBetween(any(), any()) } returns listOf(a1)
        every { reviewRepository.findByHiddenAtBetween(any(), any()) } returns listOf(hiddenReview)
        every { postRepository.findByHiddenAtBetween(any(), any()) } returns listOf(manualHiddenPost)

        val response = service.getStats(null, null, null)

        assertThat(response.totals.reportCount).isEqualTo(2L)
        assertThat(response.totals.appealSubmittedCount).isEqualTo(1L)
        assertThat(response.totals.autoHideCount).isEqualTo(1L)
        assertThat(response.totals.manualHideCount).isEqualTo(1L)

        // 어제 bucket 에 모두 들어가야 함.
        val yesterday = now.minusDays(1).toLocalDate()
        val bucket = response.series.first { it.date == yesterday }
        assertThat(bucket.reportCount).isEqualTo(2L)
        assertThat(bucket.appealSubmittedCount).isEqualTo(1L)
        assertThat(bucket.autoHideCount).isEqualTo(1L)
        assertThat(bucket.manualHideCount).isEqualTo(1L)
    }

    @Test
    fun `getStats appealReviewedAt 기준으로 approved-rejected 카운트 분리`() {
        val now = LocalDateTime.now()
        val author = createUser(id = 5L)
        val admin = createUser(id = 1L, role = UserRole.ADMIN)
        val approved = ReportAppeal(
            targetType = ReportTargetType.REVIEW, targetId = 50L, requester = author, reason = "appeal",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 200L)
            ReflectionTestUtils.setField(this, "createdAt", now.minusDays(10))
            ReflectionTestUtils.setField(this, "updatedAt", now.minusDays(2))
            approve(admin, now.minusDays(2))
        }
        val rejected = ReportAppeal(
            targetType = ReportTargetType.REVIEW, targetId = 51L, requester = author, reason = "appeal",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 201L)
            ReflectionTestUtils.setField(this, "createdAt", now.minusDays(10))
            ReflectionTestUtils.setField(this, "updatedAt", now.minusDays(2))
            reject(admin, "no", now.minusDays(2))
        }

        every { reportAppealRepository.findByReviewedAtBetween(any(), any()) } returns listOf(approved, rejected)

        val response = service.getStats(null, null, null)

        assertThat(response.totals.appealApprovedCount).isEqualTo(1L)
        assertThat(response.totals.appealRejectedCount).isEqualTo(1L)
    }

    @Test
    fun `getStats riskyChannels 가 채널별 hidden 누적을 RISK-WATCH 등급으로 분류`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channelA = createChannel(id = 10L, owner = owner) // 5건+ → RISK
        val channelB = createChannel(id = 11L, owner = owner) // 1~4건 → WATCH
        val author = createUser(id = 5L)

        // channel A: review 3건 + event 1건 + channel 자체 1건 + post 1건 = 6건 (RISK)
        val rA1 = createHiddenReviewIn(channel = channelA, id = 100L, author = author)
        val rA2 = createHiddenReviewIn(channel = channelA, id = 101L, author = author)
        val rA3 = createHiddenReviewIn(channel = channelA, id = 102L, author = author)
        val eA = createEvent(id = 110L, channel = channelA).apply { hide("x") }
        val pA = Post(channel = channelA, author = owner, title = "공지", content = "본문").apply {
            ReflectionTestUtils.setField(this, "id", 120L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
            hide("x")
        }
        channelA.hide("x")

        // channel B: review 2건 (WATCH)
        val rB1 = createHiddenReviewIn(channel = channelB, id = 200L, author = author)
        val rB2 = createHiddenReviewIn(channel = channelB, id = 201L, author = author)

        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(rA1, rA2, rA3, rB1, rB2)
        every { postRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(pA)
        every { eventRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(eA)
        every { channelRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns listOf(channelA)

        val response = service.getStats(null, null, null)

        assertThat(response.riskyChannels).hasSize(2)
        // channelA 가 더 많은 hidden count 로 먼저.
        val first = response.riskyChannels[0]
        assertThat(first.channelId).isEqualTo(10L)
        assertThat(first.hiddenCount).isEqualTo(6L) // 3 review + 1 event + 1 channel + 1 post
        assertThat(first.riskLevel.name).isEqualTo("RISK")

        val second = response.riskyChannels[1]
        assertThat(second.channelId).isEqualTo(11L)
        assertThat(second.hiddenCount).isEqualTo(2L)
        assertThat(second.riskLevel.name).isEqualTo("WATCH")
    }

    @Test
    fun `getStats 범위 밖 데이터는 series 에 포함되지 않음`() {
        // 32일 전 신고 (default 30일 범위 밖) 는 응답에 안 들어가야 함.
        val now = LocalDateTime.now()
        // service 가 어차피 findByCreatedAtBetween 호출 — 범위 밖이면 repository 가 안 넘긴다.
        // 빈 결과 stub 으로 검증.
        every { reportRepository.findByCreatedAtBetween(any(), any()) } returns emptyList()

        val response = service.getStats(null, null, null)

        assertThat(response.totals.reportCount).isZero()
        // 시작 날짜는 now - 30 days.
        assertThat(response.from.toLocalDate()).isEqualTo(now.minusDays(30).toLocalDate())
    }

    // ── PR58: banChannelForModeration / unbanChannelForModeration ───────────

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
        io.mockk.verify(exactly = 0) { eventRepository.findByChannel(any()) }
        io.mockk.verify(exactly = 0) { postRepository.findByChannel(any()) }
        io.mockk.verify(exactly = 0) { reviewRepository.findByEventChannelId(any()) }
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
        io.mockk.verify(exactly = 1) {
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

        io.mockk.verify(exactly = 1) {
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

    private fun createReport(
        id: Long,
        targetType: ReportTargetType,
        targetId: Long,
        reason: String,
        reporterId: Long = 99L,
    ): Report {
        val reporter = createUser(id = reporterId)
        return Report(reporter = reporter, targetType = targetType, targetId = targetId, reason = reason).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        }
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

    /** PR57 — 특정 채널 소속 hidden review. risky channel 집계 검증용. */
    private fun createHiddenReviewIn(channel: Channel, id: Long, author: User): Review {
        val event = createEvent(id = 100L + id, channel = channel)
        return Review(event = event, author = author, rating = 4, content = "본문 $id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
            hide(ReportService.AUTO_HIDE_REASON)
        }
    }

    // ── PR61 audit ────────────────────────────────────────────────────────────

    @Test
    fun `hideTarget 성공 시 TARGET_HIDDEN audit 기록 (actor + target + reason)`() {
        val author = createUser(id = 5L)
        val review = createReview(id = 50L, author = author)
        every { reviewRepository.findById(50L) } returns Optional.of(review)

        service.hideTarget(
            ACTOR_ID, ReportTargetType.REVIEW, 50L, AdminHideTargetRequest("정책 위반"),
        )

        io.mockk.verify(exactly = 1) {
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

        io.mockk.verify(exactly = 1) {
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

    @Test
    fun `banChannelForModeration 성공 시 CHANNEL_BANNED audit 기록 (cascade 카운트 포함)`() {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { eventRepository.findByChannel(channel) } returns emptyList()
        every { postRepository.findByChannel(channel) } returns emptyList()
        every { reviewRepository.findByEventChannelId(10L) } returns emptyList()

        service.banChannelForModeration(ACTOR_ID, 10L, AdminBanChannelRequest("운영 정책 위반"))

        io.mockk.verify(exactly = 1) {
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

        io.mockk.verify(exactly = 1) {
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
}
