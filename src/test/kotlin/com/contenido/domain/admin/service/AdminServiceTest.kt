package com.contenido.domain.admin.service

import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.report.entity.Report
import com.contenido.domain.report.entity.ReportStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportRepository
import com.contenido.domain.report.service.ReportService
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
    // PR61 — resolve/dismiss 가 audit log 를 기록. 본 테스트는 audit 호출 자체는 무시 (record 가
    // 정상 동작한다고 가정). audit-specific 동작은 ModerationAuditLogServiceTest 에서 검증.
    @MockK(relaxed = true) lateinit var moderationAuditLogService: ModerationAuditLogService

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
            moderationAuditLogService = moderationAuditLogService,
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

        val result = adminService.resolveReport(99L, 1L)

        assertThat(result.status).isEqualTo(ReportStatus.RESOLVED)
        assertThat(report.status).isEqualTo(ReportStatus.RESOLVED)
    }

    @Test
    fun `resolveReport 존재하지 않는 신고 예외`() {
        every { reportRepository.findById(99L) } returns Optional.empty()

        assertThrows<ReportNotFoundException> { adminService.resolveReport(99L, 99L) }
    }

    @Test
    fun `resolveReport 이미 RESOLVED 인 신고 예외`() {
        val report = createReport(id = 1L, status = ReportStatus.RESOLVED)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        assertThrows<ReportAlreadyProcessedException> { adminService.resolveReport(99L, 1L) }
    }

    @Test
    fun `resolveReport 이미 DISMISSED 인 신고 예외`() {
        val report = createReport(id = 1L, status = ReportStatus.DISMISSED)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        assertThrows<ReportAlreadyProcessedException> { adminService.resolveReport(99L, 1L) }
    }

    // ── dismissReport ─────────────────────────────────────────────────────────

    @Test
    fun `dismissReport PENDING 신고를 DISMISSED 로 전환`() {
        val report = createReport(id = 1L, status = ReportStatus.PENDING)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        val result = adminService.dismissReport(99L, 1L)

        assertThat(result.status).isEqualTo(ReportStatus.DISMISSED)
        assertThat(report.status).isEqualTo(ReportStatus.DISMISSED)
    }

    @Test
    fun `dismissReport 존재하지 않는 신고 예외`() {
        every { reportRepository.findById(99L) } returns Optional.empty()

        assertThrows<ReportNotFoundException> { adminService.dismissReport(99L, 99L) }
    }

    @Test
    fun `dismissReport 이미 처리된 신고 예외`() {
        val report = createReport(id = 1L, status = ReportStatus.RESOLVED)
        every { reportRepository.findById(1L) } returns Optional.of(report)

        assertThrows<ReportAlreadyProcessedException> { adminService.dismissReport(99L, 1L) }
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

        val result = adminService.resolveReport(99L, 7L)

        assertThat(result.targetType).isEqualTo(ReportTargetType.REVIEW)
        assertThat(result.targetPreview).isEqualTo("신고 대상 후기 본문")
        assertThat(result.targetRating).isEqualTo(1)
    }

    @Test
    fun `resolveReport 대상이 이미 삭제됐으면 targetPreview 와 targetRating 은 null`() {
        val report = createReport(id = 8L, status = ReportStatus.PENDING, targetType = ReportTargetType.REVIEW, targetId = 999L)
        every { reportRepository.findById(8L) } returns Optional.of(report)
        // setUp 의 기본 stub: reviewRepository.findById(any()) returns Optional.empty()

        val result = adminService.resolveReport(99L, 8L)

        assertThat(result.targetPreview).isNull()
        assertThat(result.targetRating).isNull()
        // PR51 — 대상 없으면 hidden context 도 false.
        assertThat(result.targetHidden).isFalse()
        assertThat(result.autoModerated).isFalse()
    }

    // ── PR51: targetHidden / autoModerated context ──────────────────────────

    @Test
    fun `resolveReport 자동 숨김된 REVIEW 면 targetHidden=true, autoModerated=true`() {
        val report = createReport(
            id = 9L, status = ReportStatus.PENDING,
            targetType = ReportTargetType.REVIEW, targetId = 60L,
        )
        val author = createUser(id = 99L)
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
            event = event, author = author, rating = 2, content = "본문",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 60L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
            hide(ReportService.AUTO_HIDE_REASON)
        }

        every { reportRepository.findById(9L) } returns Optional.of(report)
        every { reviewRepository.findById(60L) } returns Optional.of(review)

        val result = adminService.resolveReport(99L, 9L)

        assertThat(result.targetHidden).isTrue()
        assertThat(result.autoModerated).isTrue()
        assertThat(result.targetPreview).isEqualTo("본문")
    }

    @Test
    fun `resolveReport 수동 hide (자동 사유 아님) 인 REVIEW 면 targetHidden=true, autoModerated=false`() {
        val report = createReport(
            id = 10L, status = ReportStatus.PENDING,
            targetType = ReportTargetType.REVIEW, targetId = 61L,
        )
        val author = createUser(id = 99L)
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
            event = event, author = author, rating = 2, content = "본문",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 61L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
            hide("운영자 수동 검토")  // 자동 사유와 다른 reason
        }

        every { reportRepository.findById(10L) } returns Optional.of(report)
        every { reviewRepository.findById(61L) } returns Optional.of(review)

        val result = adminService.resolveReport(99L, 10L)

        assertThat(result.targetHidden).isTrue()
        assertThat(result.autoModerated).isFalse()  // 자동 사유 아니므로 false
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
