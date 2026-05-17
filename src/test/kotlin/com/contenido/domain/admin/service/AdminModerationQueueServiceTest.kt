package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.AdminModerationPriority
import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.repository.EventRepository
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
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR88 — `AdminModerationQueueService` 의 단위 테스트.
 *
 * PR87 에서 facade 로부터 분리된 통합 moderation queue(PR55) 책임:
 *  - PENDING report 만 있는 target → LOW
 *  - hidden target → HIGH
 *  - PENDING appeal 있는 target → HIGH + appeal 컨텍스트
 *  - 같은 target 의 report + appeal + hidden → row 1개로 merge
 *  - 임계치 70% 이상 누적 PENDING report → MEDIUM (PR60 thresholdFor 연계)
 *  - HIGH > MEDIUM > LOW 우선순위 정렬
 *  - targetType / hidden / priority 필터
 */
@ExtendWith(MockKExtension::class)
class AdminModerationQueueServiceTest {

    @MockK lateinit var reviewRepository: ReviewRepository
    @MockK lateinit var commentRepository: CommentRepository
    @MockK lateinit var postRepository: PostRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var reportRepository: ReportRepository
    @MockK lateinit var reportAppealRepository: ReportAppealRepository
    @MockK lateinit var moderationThresholdService: ModerationThresholdService

    private lateinit var service: AdminModerationQueueService

    @BeforeEach
    fun setUp() {
        service = AdminModerationQueueService(
            reviewRepository = reviewRepository,
            commentRepository = commentRepository,
            postRepository = postRepository,
            eventRepository = eventRepository,
            channelRepository = channelRepository,
            reportRepository = reportRepository,
            reportAppealRepository = reportAppealRepository,
            moderationThresholdService = moderationThresholdService,
        )
        // PR60 — computePriority 가 DB 임계치를 조회하므로 PR51 default 로 stub.
        every { moderationThresholdService.thresholdFor(ReportTargetType.REVIEW) } returns 3
        every { moderationThresholdService.thresholdFor(ReportTargetType.COMMENT) } returns 3
        every { moderationThresholdService.thresholdFor(ReportTargetType.POST) } returns 5
        every { moderationThresholdService.thresholdFor(ReportTargetType.EVENT) } returns 5
        every { moderationThresholdService.thresholdFor(ReportTargetType.CHANNEL) } returns 7
        // 각 source 가 비어 있으면 queue 도 빈 결과.
        every { reportRepository.findByStatusOrderByCreatedAtDesc(any()) } returns emptyList()
        every { reportAppealRepository.findByStatusOrderByCreatedAtDesc(any<ReportAppealStatus>()) } returns emptyList()
        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { commentRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { postRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { eventRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { channelRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
    }

    // ── priority 계산 ───────────────────────────────────────────────────────

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

    // ── filters ────────────────────────────────────────────────────────────

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

    // ── fixtures ───────────────────────────────────────────────────────────

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
}
