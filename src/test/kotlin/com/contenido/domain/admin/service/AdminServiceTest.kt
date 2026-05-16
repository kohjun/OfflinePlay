package com.contenido.domain.admin.service

import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.ReportAlreadyProcessedException
import com.contenido.global.exception.ReportNotFoundException
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
class AdminServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var reportRepository: ReportRepository
    @MockK lateinit var eventRepository: com.contenido.domain.event.repository.EventRepository
    @MockK lateinit var postRepository: com.contenido.domain.post.repository.PostRepository
    @MockK lateinit var commentRepository: com.contenido.domain.interaction.repository.CommentRepository
    @MockK lateinit var reviewRepository: com.contenido.domain.review.repository.ReviewRepository

    private lateinit var adminService: AdminService

    @BeforeEach
    fun setUp() {
        adminService = AdminService(
            userRepository = userRepository,
            channelRepository = channelRepository,
            reportRepository = reportRepository,
            eventRepository = eventRepository,
            postRepository = postRepository,
            commentRepository = commentRepository,
            reviewRepository = reviewRepository,
        )
        // PR48: toResponseWithPreview 가 모든 resolve/dismiss/getReports 경로에서 호출되므로
        // 기본 stub — 대상 미존재 시 preview/rating 모두 null.
        every { channelRepository.findById(any()) } returns Optional.empty()
        every { eventRepository.findById(any()) } returns Optional.empty()
        every { postRepository.findById(any()) } returns Optional.empty()
        every { commentRepository.findById(any()) } returns Optional.empty()
        every { reviewRepository.findById(any()) } returns Optional.empty()
    }

    // ── resolveReport ─────────────────────────────────────────────────────────

    @Test
    fun `resolveReport PENDING 신고를 RESOLVED 로 전환`() {
        val report = createReport(id = 1L, status = ReportStatus.PENDING)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        val result = adminService.resolveReport(1L)

        assertThat(result.status).isEqualTo(ReportStatus.RESOLVED)
        assertThat(report.status).isEqualTo(ReportStatus.RESOLVED)
    }

    @Test
    fun `resolveReport 존재하지 않는 신고 예외`() {
        every { reportRepository.findById(99L) } returns Optional.empty()

        assertThrows<ReportNotFoundException> { adminService.resolveReport(99L) }
    }

    @Test
    fun `resolveReport 이미 RESOLVED 인 신고 예외`() {
        val report = createReport(id = 1L, status = ReportStatus.RESOLVED)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        assertThrows<ReportAlreadyProcessedException> { adminService.resolveReport(1L) }
    }

    @Test
    fun `resolveReport 이미 DISMISSED 인 신고 예외`() {
        val report = createReport(id = 1L, status = ReportStatus.DISMISSED)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        assertThrows<ReportAlreadyProcessedException> { adminService.resolveReport(1L) }
    }

    // ── dismissReport ─────────────────────────────────────────────────────────

    @Test
    fun `dismissReport PENDING 신고를 DISMISSED 로 전환`() {
        val report = createReport(id = 1L, status = ReportStatus.PENDING)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        val result = adminService.dismissReport(1L)

        assertThat(result.status).isEqualTo(ReportStatus.DISMISSED)
        assertThat(report.status).isEqualTo(ReportStatus.DISMISSED)
    }

    @Test
    fun `dismissReport 존재하지 않는 신고 예외`() {
        every { reportRepository.findById(99L) } returns Optional.empty()

        assertThrows<ReportNotFoundException> { adminService.dismissReport(99L) }
    }

    @Test
    fun `dismissReport 이미 처리된 신고 예외`() {
        val report = createReport(id = 1L, status = ReportStatus.RESOLVED)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        assertThrows<ReportAlreadyProcessedException> { adminService.dismissReport(1L) }
    }

    // ── PR48: targetPreview / targetRating 매핑 ──────────────────────────────

    @Test
    fun `resolveReport REVIEW 면 targetPreview 와 targetRating 이 채워진다`() {
        val report = createReport(id = 7L, status = ReportStatus.PENDING, targetType = ReportTargetType.REVIEW, targetId = 55L)
        val author = createUser(id = 99L)
        // Review 는 production entity 를 그대로 — channel/event 까지 만들지 말고 mock 이 findById 결과만 돌려주면 됨.
        val event = com.contenido.domain.event.entity.Event(
            channel = com.contenido.domain.channel.entity.Channel(
                createUser(id = 1L), "채널", "설명", com.contenido.domain.channel.entity.ChannelCategory.MUSIC,
            ),
            title = "이벤트", description = "d", location = "서울",
            mainImageUrl = "https://e.com/x.jpg",
            startAt = LocalDateTime.now(), endAt = LocalDateTime.now(),
            maxParticipants = 10, participationFee = 0L,
            refundPolicy = "정책", detailContent = "detail",
        )
        val review = com.contenido.domain.review.entity.Review(
            event = event, author = author, rating = 1, content = "신고 대상 후기 본문",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 55L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }

        every { reportRepository.findById(7L) } returns Optional.of(report)
        every { reviewRepository.findById(55L) } returns Optional.of(review)

        val result = adminService.resolveReport(7L)

        assertThat(result.targetType).isEqualTo(ReportTargetType.REVIEW)
        assertThat(result.targetPreview).isEqualTo("신고 대상 후기 본문")
        assertThat(result.targetRating).isEqualTo(1)
    }

    @Test
    fun `resolveReport 대상이 이미 삭제됐으면 targetPreview 와 targetRating 은 null`() {
        val report = createReport(id = 8L, status = ReportStatus.PENDING, targetType = ReportTargetType.REVIEW, targetId = 999L)
        every { reportRepository.findById(8L) } returns Optional.of(report)
        // setUp 의 기본 stub: reviewRepository.findById(any()) returns Optional.empty()

        val result = adminService.resolveReport(8L)

        assertThat(result.targetPreview).isNull()
        assertThat(result.targetRating).isNull()
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun createUser(id: Long = 1L): User {
        val user = User("reporter@test.com", "pwd", "reporter", "01012345678")
            .apply { updateRole(UserRole.PARTICIPANT) }
        ReflectionTestUtils.setField(user, "id", id)
        ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(user, "updatedAt", LocalDateTime.now())
        return user
    }

    private fun createReport(
        id: Long,
        status: ReportStatus,
        targetType: ReportTargetType = ReportTargetType.CHANNEL,
        targetId: Long = 100L,
    ): Report {
        val report = Report(
            reporter = createUser(),
            targetType = targetType,
            targetId = targetId,
            reason = "부적절한 내용",
        )
        ReflectionTestUtils.setField(report, "id", id)
        ReflectionTestUtils.setField(report, "status", status)
        ReflectionTestUtils.setField(report, "createdAt", LocalDateTime.now())
        return report
    }
}
