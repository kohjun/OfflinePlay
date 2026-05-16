package com.contenido.domain.report.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.entity.Comment
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.report.dto.CreateReportRequest
import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.review.entity.Review
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.ReportAlreadyExistsException
import com.contenido.global.exception.ReportTargetNotFoundException
import com.contenido.global.exception.SelfReportNotAllowedException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
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
 * ReportService.createReport 의 PR48 강화 (대상 존재 검증 / 본인 차단 / 중복 차단) 가
 * 모든 targetType (CHANNEL/POST/EVENT/COMMENT/REVIEW) 에 대해 동일하게 작동하는지 검증.
 */
@ExtendWith(MockKExtension::class)
class ReportServiceTest {

    @MockK lateinit var reportRepository: ReportRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var postRepository: PostRepository
    @MockK lateinit var commentRepository: CommentRepository
    @MockK lateinit var reviewRepository: ReviewRepository

    private lateinit var service: ReportService

    @BeforeEach
    fun setUp() {
        service = ReportService(
            reportRepository = reportRepository,
            userRepository = userRepository,
            channelRepository = channelRepository,
            eventRepository = eventRepository,
            postRepository = postRepository,
            commentRepository = commentRepository,
            reviewRepository = reviewRepository,
        )
    }

    // ── REVIEW (PR48 신규) ───────────────────────────────────────────────────

    @Test
    fun `createReport REVIEW 정상 케이스 — 저장되고 ReportResponse 반환`() {
        val reporter = createUser(id = 2L)
        val author = createUser(id = 3L)  // 후기 작성자 (reporter 와 다른 사람)
        val event = createEvent(id = 100L)
        val review = createReview(id = 50L, event = event, author = author, rating = 1, content = "스팸")
        val captured = slot<Report>()

        every { userRepository.findById(2L) } returns Optional.of(reporter)
        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportRepository.existsByReporterAndTargetTypeAndTargetId(
                reporter, ReportTargetType.REVIEW, 50L,
            )
        } returns false
        every { reportRepository.save(capture(captured)) } answers {
            captured.captured.also {
                ReflectionTestUtils.setField(it, "id", 7L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
            }
        }

        val response = service.createReport(
            userId = 2L,
            request = CreateReportRequest(ReportTargetType.REVIEW, 50L, "부적절한 후기"),
        )

        assertThat(response.id).isEqualTo(7L)
        assertThat(response.targetType).isEqualTo(ReportTargetType.REVIEW)
        assertThat(response.targetId).isEqualTo(50L)
        // createReport 응답에는 target preview 미노출 (Admin 응답에서만 채움).
        assertThat(response.targetPreview).isNull()
        assertThat(response.targetRating).isNull()
    }

    @Test
    fun `createReport 존재하지 않는 REVIEW 는 ReportTargetNotFoundException`() {
        val reporter = createUser(id = 2L)
        every { userRepository.findById(2L) } returns Optional.of(reporter)
        every { reviewRepository.findById(999L) } returns Optional.empty()

        assertThrows<ReportTargetNotFoundException> {
            service.createReport(2L, CreateReportRequest(ReportTargetType.REVIEW, 999L, "x"))
        }
        verify(exactly = 0) { reportRepository.save(any()) }
    }

    @Test
    fun `createReport 본인 REVIEW 는 SelfReportNotAllowedException`() {
        val reporter = createUser(id = 2L)
        val event = createEvent(id = 100L)
        val myReview = createReview(id = 50L, event = event, author = reporter, rating = 5, content = "내 글")

        every { userRepository.findById(2L) } returns Optional.of(reporter)
        every { reviewRepository.findById(50L) } returns Optional.of(myReview)

        assertThrows<SelfReportNotAllowedException> {
            service.createReport(2L, CreateReportRequest(ReportTargetType.REVIEW, 50L, "x"))
        }
        verify(exactly = 0) { reportRepository.save(any()) }
    }

    @Test
    fun `createReport 같은 reporter 가 같은 REVIEW 를 또 신고하면 ReportAlreadyExistsException`() {
        val reporter = createUser(id = 2L)
        val author = createUser(id = 3L)
        val event = createEvent(id = 100L)
        val review = createReview(id = 50L, event = event, author = author, rating = 1, content = "스팸")

        every { userRepository.findById(2L) } returns Optional.of(reporter)
        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportRepository.existsByReporterAndTargetTypeAndTargetId(
                reporter, ReportTargetType.REVIEW, 50L,
            )
        } returns true

        assertThrows<ReportAlreadyExistsException> {
            service.createReport(2L, CreateReportRequest(ReportTargetType.REVIEW, 50L, "또 신고"))
        }
        verify(exactly = 0) { reportRepository.save(any()) }
    }

    // ── 기존 타입 회귀 (POST / COMMENT / EVENT / CHANNEL) ─────────────────────

    @Test
    fun `createReport POST 정상 케이스`() {
        val reporter = createUser(id = 2L)
        val author = createUser(id = 3L)
        val channel = createChannel(id = 10L, owner = createUser(id = 1L, role = UserRole.CREATOR))
        val post = Post(channel = channel, author = author, title = "글", content = "본문")
            .apply { ReflectionTestUtils.setField(this, "id", 60L) }

        every { userRepository.findById(2L) } returns Optional.of(reporter)
        every { postRepository.findById(60L) } returns Optional.of(post)
        every {
            reportRepository.existsByReporterAndTargetTypeAndTargetId(
                reporter, ReportTargetType.POST, 60L,
            )
        } returns false
        every { reportRepository.save(any<Report>()) } answers {
            firstArg<Report>().also {
                ReflectionTestUtils.setField(it, "id", 11L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
            }
        }

        val response = service.createReport(
            2L, CreateReportRequest(ReportTargetType.POST, 60L, "광고"),
        )

        assertThat(response.targetType).isEqualTo(ReportTargetType.POST)
        assertThat(response.targetId).isEqualTo(60L)
    }

    @Test
    fun `createReport COMMENT 본인이면 차단`() {
        val reporter = createUser(id = 2L)
        val myComment = Comment(
            author = reporter,
            targetType = TargetType.EVENT,
            targetId = 100L,
            content = "내 댓글",
        ).apply { ReflectionTestUtils.setField(this, "id", 70L) }

        every { userRepository.findById(2L) } returns Optional.of(reporter)
        every { commentRepository.findById(70L) } returns Optional.of(myComment)

        assertThrows<SelfReportNotAllowedException> {
            service.createReport(2L, CreateReportRequest(ReportTargetType.COMMENT, 70L, "x"))
        }
    }

    @Test
    fun `createReport EVENT 는 channel owner 가 본인이면 SelfReportNotAllowedException`() {
        val reporter = createUser(id = 2L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = reporter)  // reporter 가 채널 owner
        val event = Event(
            channel = channel,
            title = "이벤트",
            description = "desc",
            location = "서울",
            mainImageUrl = "https://example.com/i.jpg",
            startAt = LocalDateTime.now().plusDays(1),
            endAt = LocalDateTime.now().plusDays(1).plusHours(2),
            maxParticipants = 10,
            participationFee = 0L,
            refundPolicy = "전액",
            detailContent = "detail",
        ).apply { ReflectionTestUtils.setField(this, "id", 100L) }

        every { userRepository.findById(2L) } returns Optional.of(reporter)
        every { eventRepository.findById(100L) } returns Optional.of(event)

        assertThrows<SelfReportNotAllowedException> {
            service.createReport(2L, CreateReportRequest(ReportTargetType.EVENT, 100L, "x"))
        }
    }

    @Test
    fun `createReport CHANNEL 미존재는 ReportTargetNotFoundException`() {
        val reporter = createUser(id = 2L)
        every { userRepository.findById(2L) } returns Optional.of(reporter)
        every { channelRepository.findById(404L) } returns Optional.empty()

        assertThrows<ReportTargetNotFoundException> {
            service.createReport(2L, CreateReportRequest(ReportTargetType.CHANNEL, 404L, "x"))
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun createUser(
        id: Long,
        role: UserRole = UserRole.PARTICIPANT,
        nickname: String = "user$id",
    ): User = User("u$id@test.com", "encoded", nickname, "01012345$id").apply {
        ReflectionTestUtils.setField(this, "id", id)
        updateRole(role)
    }

    private fun createChannel(id: Long, owner: User): Channel =
        Channel(owner, "채널$id", "설명", ChannelCategory.MUSIC).apply {
            ReflectionTestUtils.setField(this, "id", id)
        }

    private fun createEvent(id: Long): Event {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        return Event(
            channel = channel,
            title = "이벤트",
            description = "desc",
            location = "서울",
            mainImageUrl = "https://example.com/$id.jpg",
            startAt = LocalDateTime.now().minusDays(1),
            endAt = LocalDateTime.now().minusHours(1),
            maxParticipants = 10,
            participationFee = 0L,
            refundPolicy = "전액",
            detailContent = "detail",
            status = EventStatus.CLOSED,
        ).apply { ReflectionTestUtils.setField(this, "id", id) }
    }

    private fun createReview(id: Long, event: Event, author: User, rating: Int, content: String): Review =
        Review(event = event, author = author, rating = rating, content = content).apply {
            ReflectionTestUtils.setField(this, "id", id)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }
}
