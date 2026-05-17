package com.contenido.domain.admin.service

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLog
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
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

/**
 * PR88 — `AdminModerationStatsService` 의 단위 테스트.
 *
 * PR87 에서 facade 로부터 분리된 운영 지표(PR57) 책임:
 *  - 기본 30일 + day granularity → series 31 bucket
 *  - reports + appeals + hides 가 같은 날 → 누적 카운트
 *  - appealReviewedAt 기준으로 approved / rejected 카운트 분리
 *  - riskyChannels 채널별 hidden 누적을 RISK / WATCH 등급으로 분류
 *  - 범위 밖 데이터는 series 에 포함되지 않음
 */
@ExtendWith(MockKExtension::class)
class AdminModerationStatsServiceTest {

    @MockK lateinit var reviewRepository: ReviewRepository
    @MockK lateinit var commentRepository: CommentRepository
    @MockK lateinit var postRepository: PostRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var reportRepository: ReportRepository
    @MockK lateinit var reportAppealRepository: ReportAppealRepository
    @MockK lateinit var moderationAuditLogRepository: ModerationAuditLogRepository
    @MockK lateinit var systemActorService: SystemActorService

    private lateinit var service: AdminModerationStatsService

    @BeforeEach
    fun setUp() {
        service = AdminModerationStatsService(
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
        // 모든 범위 조회가 비어 있으면 시계열은 0 만 채워진다.
        every { reportRepository.findByCreatedAtBetween(any(), any()) } returns emptyList()
        every { reportAppealRepository.findByCreatedAtBetween(any(), any()) } returns emptyList()
        every { reportAppealRepository.findByReviewedAtBetween(any(), any()) } returns emptyList()
        every { reviewRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
        every { commentRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
        every { postRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
        every { eventRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
        every { channelRepository.findByHiddenAtBetween(any(), any()) } returns emptyList()
        // riskyChannels 빌더용 — 현재 시점 hidden 5도메인.
        every { reviewRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { commentRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { postRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { eventRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        every { channelRepository.findByHiddenAtIsNotNullOrderByHiddenAtDesc() } returns emptyList()
        // PR93 — actor stats 기본 stub.
        every { moderationAuditLogRepository.findByCreatedAtBetween(any(), any()) } returns emptyList()
        every { systemActorService.getSystemActorId() } returns -1L
    }

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

    // ── PR93 — Actor stats ──────────────────────────────────────────────────

    @Test
    fun `getActorStats 기본 30일 범위 + default limit 10`() {
        val response = service.getActorStats(from = null, to = null, limit = null)

        assertThat(response.limit).isEqualTo(10)
        assertThat(response.items).isEmpty()
        // from = to - 30 days (now 기준).
        val days = java.time.Duration.between(response.from, response.to).toDays()
        assertThat(days).isEqualTo(30L)
    }

    @Test
    fun `getActorStats actor 별 group 과 action 분류가 정확하다`() {
        val now = LocalDateTime.now()
        val admin = createUser(id = 1L, role = UserRole.ADMIN, nickname = "운영자A")
        val logs = listOf(
            createAuditLog(id = 1L, actor = admin, action = ModerationAuditAction.TARGET_HIDDEN, createdAt = now.minusDays(1)),
            createAuditLog(id = 2L, actor = admin, action = ModerationAuditAction.TARGET_HIDDEN, createdAt = now.minusDays(2)),
            createAuditLog(id = 3L, actor = admin, action = ModerationAuditAction.TARGET_UNHIDDEN, createdAt = now.minusDays(3)),
            createAuditLog(id = 4L, actor = admin, action = ModerationAuditAction.CHANNEL_BANNED, createdAt = now.minusDays(4)),
            createAuditLog(id = 5L, actor = admin, action = ModerationAuditAction.CHANNEL_UNBANNED, createdAt = now.minusDays(5)),
            createAuditLog(id = 6L, actor = admin, action = ModerationAuditAction.APPEAL_APPROVED, createdAt = now.minusDays(6)),
            createAuditLog(id = 7L, actor = admin, action = ModerationAuditAction.APPEAL_REJECTED, createdAt = now.minusDays(7)),
            createAuditLog(id = 8L, actor = admin, action = ModerationAuditAction.REPORT_RESOLVED, createdAt = now.minusDays(8)),
            createAuditLog(id = 9L, actor = admin, action = ModerationAuditAction.REPORT_DISMISSED, createdAt = now.minusDays(9)),
            createAuditLog(id = 10L, actor = admin, action = ModerationAuditAction.THRESHOLD_UPDATED, createdAt = now.minusDays(10)),
        )
        every { moderationAuditLogRepository.findByCreatedAtBetween(any(), any()) } returns logs

        val response = service.getActorStats(from = null, to = null, limit = null)

        assertThat(response.items).hasSize(1)
        val item = response.items[0]
        assertThat(item.actorId).isEqualTo(1L)
        assertThat(item.actorNickname).isEqualTo("운영자A")
        assertThat(item.actorSystem).isFalse()
        assertThat(item.totalActionCount).isEqualTo(10L)
        assertThat(item.hideCount).isEqualTo(2L)
        assertThat(item.unhideCount).isEqualTo(1L)
        assertThat(item.channelBanCount).isEqualTo(1L)
        assertThat(item.channelUnbanCount).isEqualTo(1L)
        assertThat(item.appealDecisionCount).isEqualTo(2L)
        assertThat(item.reportDecisionCount).isEqualTo(2L)
        assertThat(item.thresholdUpdateCount).isEqualTo(1L)
        assertThat(item.archiveCount).isZero()
    }

    @Test
    fun `getActorStats 여러 actor가 있을 때 totalActionCount 내림차순 정렬`() {
        val now = LocalDateTime.now()
        val a = createUser(id = 1L, role = UserRole.ADMIN, nickname = "A")
        val b = createUser(id = 2L, role = UserRole.ADMIN, nickname = "B")
        val c = createUser(id = 3L, role = UserRole.ADMIN, nickname = "C")
        val logs = listOf(
            createAuditLog(1L, a, ModerationAuditAction.TARGET_HIDDEN, now.minusDays(1)),
            createAuditLog(2L, a, ModerationAuditAction.TARGET_HIDDEN, now.minusDays(2)),
            createAuditLog(3L, b, ModerationAuditAction.TARGET_HIDDEN, now.minusDays(3)),
            createAuditLog(4L, b, ModerationAuditAction.CHANNEL_BANNED, now.minusDays(4)),
            createAuditLog(5L, b, ModerationAuditAction.REPORT_RESOLVED, now.minusDays(5)),
            createAuditLog(6L, c, ModerationAuditAction.TARGET_HIDDEN, now.minusDays(6)),
        )
        every { moderationAuditLogRepository.findByCreatedAtBetween(any(), any()) } returns logs

        val response = service.getActorStats(null, null, null)

        assertThat(response.items).extracting<Long> { it.actorId }.containsExactly(2L, 1L, 3L)
        assertThat(response.items[0].totalActionCount).isEqualTo(3L) // B
        assertThat(response.items[1].totalActionCount).isEqualTo(2L) // A
        assertThat(response.items[2].totalActionCount).isEqualTo(1L) // C
    }

    @Test
    fun `getActorStats system actor 는 actorSystem true 로 표시된다`() {
        val now = LocalDateTime.now()
        val systemUser = createUser(id = 999L, nickname = "System")
        every { systemActorService.getSystemActorId() } returns 999L
        every { moderationAuditLogRepository.findByCreatedAtBetween(any(), any()) } returns listOf(
            createAuditLog(1L, systemUser, ModerationAuditAction.AUDIT_LOGS_ARCHIVED, now.minusDays(1)),
        )

        val response = service.getActorStats(null, null, null)

        assertThat(response.items).hasSize(1)
        val item = response.items[0]
        assertThat(item.actorId).isEqualTo(999L)
        assertThat(item.actorSystem).isTrue()
        assertThat(item.archiveCount).isEqualTo(1L)
    }

    @Test
    fun `getActorStats limit 은 1과 50 사이로 clamp 된다`() {
        val now = LocalDateTime.now()
        val logs = (1..3L).map { i ->
            val u = createUser(id = i, nickname = "u$i")
            createAuditLog(i, u, ModerationAuditAction.TARGET_HIDDEN, now.minusDays(i))
        }
        every { moderationAuditLogRepository.findByCreatedAtBetween(any(), any()) } returns logs

        val r0 = service.getActorStats(null, null, 0)
        assertThat(r0.limit).isEqualTo(1)
        assertThat(r0.items).hasSize(1)

        val rNeg = service.getActorStats(null, null, -5)
        assertThat(rNeg.limit).isEqualTo(1)

        val rHigh = service.getActorStats(null, null, 999)
        assertThat(rHigh.limit).isEqualTo(50)
        // 실제 row 가 3개라 3개만.
        assertThat(rHigh.items).hasSize(3)

        val r2 = service.getActorStats(null, null, 2)
        assertThat(r2.limit).isEqualTo(2)
        assertThat(r2.items).hasSize(2)
    }

    @Test
    fun `getActorStats 범위 밖 row 는 repository 가 제외하므로 응답에 포함되지 않는다`() {
        // 명시 from/to 를 좁게 전달.
        val from = LocalDateTime.now().minusDays(2)
        val to = LocalDateTime.now()
        every { moderationAuditLogRepository.findByCreatedAtBetween(from, to) } returns emptyList()

        val response = service.getActorStats(from, to, null)

        assertThat(response.from).isEqualTo(from)
        assertThat(response.to).isEqualTo(to)
        assertThat(response.items).isEmpty()
    }

    private fun createAuditLog(
        id: Long,
        actor: User,
        action: ModerationAuditAction,
        createdAt: LocalDateTime,
    ): ModerationAuditLog = ModerationAuditLog(actor = actor, action = action).apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", createdAt)
    }
}
