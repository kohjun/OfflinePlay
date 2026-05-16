package com.contenido.domain.report.service

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
import com.contenido.domain.report.dto.CreateReportAppealRequest
import com.contenido.domain.report.dto.ReviewReportAppealRequest
import com.contenido.domain.report.entity.ReportAppeal
import com.contenido.domain.report.entity.ReportAppealStatus
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.report.repository.ReportAppealRepository
import com.contenido.domain.review.entity.Review
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.AppealAlreadyExistsException
import com.contenido.global.exception.AppealCooldownActiveException
import com.contenido.global.exception.AppealNotAllowedException
import com.contenido.global.exception.ReportAppealAlreadyProcessedException
import com.contenido.global.exception.ReportAppealNotFoundException
import com.contenido.global.exception.ReportTargetNotFoundException
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

/**
 * ReportAppealService 커버리지 (PR52).
 *
 * 검증 축:
 *  - createAppeal: hidden + 본인 + 중복 차단
 *  - approveAppeal: 대상 unhide + APPROVED
 *  - rejectAppeal: hidden 유지 + REJECTED + rejectReason
 *  - 권한 (REVIEW/COMMENT/POST/EVENT/CHANNEL) 일부
 */
@ExtendWith(MockKExtension::class)
class ReportAppealServiceTest {

    @MockK lateinit var reportAppealRepository: ReportAppealRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var postRepository: PostRepository
    @MockK lateinit var commentRepository: CommentRepository
    @MockK lateinit var reviewRepository: ReviewRepository
    // PR61 — approveAppeal/rejectAppeal 가 audit 를 기록.
    @MockK(relaxed = true)
    lateinit var moderationAuditLogService: com.contenido.domain.admin.service.ModerationAuditLogService

    private lateinit var service: ReportAppealService

    @BeforeEach
    fun setUp() {
        service = ReportAppealService(
            reportAppealRepository = reportAppealRepository,
            userRepository = userRepository,
            channelRepository = channelRepository,
            eventRepository = eventRepository,
            postRepository = postRepository,
            commentRepository = commentRepository,
            reviewRepository = reviewRepository,
            moderationAuditLogService = moderationAuditLogService,
        )
        // PR56 — createAppeal 가 cooldown 검사 시 호출. 별도 케이스에서 override.
        // 기본은 "이전 appeal 없음" → cooldown 가드 통과.
        every {
            reportAppealRepository.findFirstByRequesterAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                any(), any(), any(),
            )
        } returns null
    }

    // ── createAppeal ─────────────────────────────────────────────────────────

    @Test
    fun `createAppeal hidden REVIEW 작성자 본인이면 PENDING 으로 저장`() {
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author)

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportAppealRepository.existsByRequesterAndTargetTypeAndTargetIdAndStatus(
                author, ReportTargetType.REVIEW, 50L, ReportAppealStatus.PENDING,
            )
        } returns false
        every { reportAppealRepository.save(any<ReportAppeal>()) } answers {
            firstArg<ReportAppeal>().also {
                ReflectionTestUtils.setField(it, "id", 100L)
                val now = LocalDateTime.now()
                ReflectionTestUtils.setField(it, "createdAt", now)
                ReflectionTestUtils.setField(it, "updatedAt", now)
            }
        }

        val response = service.createAppeal(
            5L, CreateReportAppealRequest(ReportTargetType.REVIEW, 50L, "오해입니다"),
        )

        assertThat(response.status).isEqualTo(ReportAppealStatus.PENDING)
        assertThat(response.targetId).isEqualTo(50L)
        assertThat(response.targetHidden).isTrue()
    }

    @Test
    fun `createAppeal hidden 이 아닌 대상은 TargetNotHiddenException`() {
        val author = createUser(id = 5L)
        // hidden 처리 안 한 review.
        val review = createReview(id = 51L, author = author)

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findById(51L) } returns Optional.of(review)

        assertThrows<TargetNotHiddenException> {
            service.createAppeal(5L, CreateReportAppealRequest(ReportTargetType.REVIEW, 51L, "x"))
        }
    }

    @Test
    fun `createAppeal 본인이 아닌 hidden REVIEW 는 AppealNotAllowedException`() {
        val author = createUser(id = 5L)
        val notAuthor = createUser(id = 99L)
        val review = createHiddenReview(id = 50L, author = author)

        every { userRepository.findById(99L) } returns Optional.of(notAuthor)
        every { reviewRepository.findById(50L) } returns Optional.of(review)

        assertThrows<AppealNotAllowedException> {
            service.createAppeal(99L, CreateReportAppealRequest(ReportTargetType.REVIEW, 50L, "남의 글"))
        }
    }

    @Test
    fun `createAppeal 같은 target 에 PENDING appeal 이 있으면 AppealAlreadyExistsException`() {
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author)

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportAppealRepository.existsByRequesterAndTargetTypeAndTargetIdAndStatus(
                author, ReportTargetType.REVIEW, 50L, ReportAppealStatus.PENDING,
            )
        } returns true

        assertThrows<AppealAlreadyExistsException> {
            service.createAppeal(5L, CreateReportAppealRequest(ReportTargetType.REVIEW, 50L, "재시도"))
        }
    }

    @Test
    fun `createAppeal 대상 자체 미존재면 ReportTargetNotFoundException`() {
        val author = createUser(id = 5L)
        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findById(404L) } returns Optional.empty()

        assertThrows<ReportTargetNotFoundException> {
            service.createAppeal(5L, CreateReportAppealRequest(ReportTargetType.REVIEW, 404L, "x"))
        }
    }

    @Test
    fun `createAppeal EVENT 는 channel owner 가 본인이어야 함`() {
        val owner = createUser(id = 5L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createHiddenEvent(id = 100L, channel = channel)

        every { userRepository.findById(5L) } returns Optional.of(owner)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every {
            reportAppealRepository.existsByRequesterAndTargetTypeAndTargetIdAndStatus(
                owner, ReportTargetType.EVENT, 100L, ReportAppealStatus.PENDING,
            )
        } returns false
        every { reportAppealRepository.save(any<ReportAppeal>()) } answers {
            firstArg<ReportAppeal>().also {
                ReflectionTestUtils.setField(it, "id", 101L)
                val now = LocalDateTime.now()
                ReflectionTestUtils.setField(it, "createdAt", now)
                ReflectionTestUtils.setField(it, "updatedAt", now)
            }
        }

        val response = service.createAppeal(
            5L, CreateReportAppealRequest(ReportTargetType.EVENT, 100L, "정당한 이벤트"),
        )

        assertThat(response.targetType).isEqualTo(ReportTargetType.EVENT)
    }

    @Test
    fun `createAppeal CHANNEL owner 본인이 hidden 채널 appeal 성공 (PR59 회귀)`() {
        val owner = createUser(id = 5L, role = UserRole.CREATOR)
        val hiddenChannel = createChannel(id = 10L, owner = owner).apply {
            hide(ReportService.AUTO_HIDE_REASON)
        }

        every { userRepository.findById(5L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(hiddenChannel)
        every {
            reportAppealRepository.existsByRequesterAndTargetTypeAndTargetIdAndStatus(
                owner, ReportTargetType.CHANNEL, 10L, ReportAppealStatus.PENDING,
            )
        } returns false
        every { reportAppealRepository.save(any<ReportAppeal>()) } answers {
            firstArg<ReportAppeal>().also {
                ReflectionTestUtils.setField(it, "id", 500L)
                val now = LocalDateTime.now()
                ReflectionTestUtils.setField(it, "createdAt", now)
                ReflectionTestUtils.setField(it, "updatedAt", now)
            }
        }

        val response = service.createAppeal(
            5L, CreateReportAppealRequest(ReportTargetType.CHANNEL, 10L, "이의 제기"),
        )

        assertThat(response.targetType).isEqualTo(ReportTargetType.CHANNEL)
        assertThat(response.status).isEqualTo(ReportAppealStatus.PENDING)
    }

    @Test
    fun `createAppeal CHANNEL 은 owner 가 본인이 아니면 AppealNotAllowed`() {
        val owner = createUser(id = 5L, role = UserRole.CREATOR)
        val intruder = createUser(id = 7L)
        val channel = createChannel(id = 10L, owner = owner).apply {
            hide(ReportService.AUTO_HIDE_REASON)
        }

        every { userRepository.findById(7L) } returns Optional.of(intruder)
        every { channelRepository.findById(10L) } returns Optional.of(channel)

        assertThrows<AppealNotAllowedException> {
            service.createAppeal(7L, CreateReportAppealRequest(ReportTargetType.CHANNEL, 10L, "남의 채널"))
        }
    }

    // ── PR56 cooldown ────────────────────────────────────────────────────────

    @Test
    fun `createAppeal REJECTED 후 7일 안 지났으면 AppealCooldownActiveException`() {
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author)
        val admin = createUser(id = 1L, role = UserRole.ADMIN)
        // 3일 전 거절 — cooldown 활성.
        val rejected = createPendingAppeal(id = 199L, requester = author).apply {
            reject(admin, "정책 위반", LocalDateTime.now().minusDays(3))
        }

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportAppealRepository.existsByRequesterAndTargetTypeAndTargetIdAndStatus(
                author, ReportTargetType.REVIEW, 50L, ReportAppealStatus.PENDING,
            )
        } returns false
        every {
            reportAppealRepository.findFirstByRequesterAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                author, ReportTargetType.REVIEW, 50L,
            )
        } returns rejected

        assertThrows<AppealCooldownActiveException> {
            service.createAppeal(5L, CreateReportAppealRequest(ReportTargetType.REVIEW, 50L, "재시도"))
        }
    }

    @Test
    fun `createAppeal REJECTED 후 7일 초과면 새 PENDING 생성 허용`() {
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author)
        val admin = createUser(id = 1L, role = UserRole.ADMIN)
        // 8일 전 거절 — cooldown 종료.
        val rejected = createPendingAppeal(id = 198L, requester = author).apply {
            reject(admin, "이전 거절", LocalDateTime.now().minusDays(8))
        }

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportAppealRepository.existsByRequesterAndTargetTypeAndTargetIdAndStatus(
                author, ReportTargetType.REVIEW, 50L, ReportAppealStatus.PENDING,
            )
        } returns false
        every {
            reportAppealRepository.findFirstByRequesterAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                author, ReportTargetType.REVIEW, 50L,
            )
        } returns rejected
        every { reportAppealRepository.save(any<ReportAppeal>()) } answers {
            firstArg<ReportAppeal>().also {
                ReflectionTestUtils.setField(it, "id", 300L)
                val now = LocalDateTime.now()
                ReflectionTestUtils.setField(it, "createdAt", now)
                ReflectionTestUtils.setField(it, "updatedAt", now)
            }
        }

        val response = service.createAppeal(
            5L, CreateReportAppealRequest(ReportTargetType.REVIEW, 50L, "8일 뒤 재시도"),
        )

        assertThat(response.status).isEqualTo(ReportAppealStatus.PENDING)
        assertThat(response.id).isEqualTo(300L)
    }

    @Test
    fun `createAppeal APPROVED 이력은 cooldown 없이 재신청 허용`() {
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author)
        val admin = createUser(id = 1L, role = UserRole.ADMIN)
        // 어제 APPROVED — 그 사이 대상이 다시 hidden 됐다고 가정. cooldown 없음.
        val approved = createPendingAppeal(id = 197L, requester = author).apply {
            approve(admin, LocalDateTime.now().minusDays(1))
        }

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportAppealRepository.existsByRequesterAndTargetTypeAndTargetIdAndStatus(
                author, ReportTargetType.REVIEW, 50L, ReportAppealStatus.PENDING,
            )
        } returns false
        every {
            reportAppealRepository.findFirstByRequesterAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                author, ReportTargetType.REVIEW, 50L,
            )
        } returns approved
        every { reportAppealRepository.save(any<ReportAppeal>()) } answers {
            firstArg<ReportAppeal>().also {
                ReflectionTestUtils.setField(it, "id", 301L)
                val now = LocalDateTime.now()
                ReflectionTestUtils.setField(it, "createdAt", now)
                ReflectionTestUtils.setField(it, "updatedAt", now)
            }
        }

        val response = service.createAppeal(
            5L, CreateReportAppealRequest(ReportTargetType.REVIEW, 50L, "다시 hidden 됐어요"),
        )

        assertThat(response.status).isEqualTo(ReportAppealStatus.PENDING)
    }

    @Test
    fun `createAppeal PENDING 중복은 cooldown 보다 먼저 차단 — AppealAlreadyExistsException`() {
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author)

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every {
            reportAppealRepository.existsByRequesterAndTargetTypeAndTargetIdAndStatus(
                author, ReportTargetType.REVIEW, 50L, ReportAppealStatus.PENDING,
            )
        } returns true

        // PENDING 중복이 먼저 검사되어 cooldown 검사로 진입하지 않는다 (findFirstBy... unstubbed 호출 없음).
        assertThrows<AppealAlreadyExistsException> {
            service.createAppeal(5L, CreateReportAppealRequest(ReportTargetType.REVIEW, 50L, "x"))
        }
    }

    // ── approveAppeal / rejectAppeal ─────────────────────────────────────────

    @Test
    fun `approveAppeal 시 대상 unhide + appeal APPROVED`() {
        val admin = createUser(id = 1L, role = UserRole.ADMIN)
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author)
        val appeal = createPendingAppeal(id = 200L, requester = author, targetType = ReportTargetType.REVIEW, targetId = 50L)

        every { userRepository.findById(1L) } returns Optional.of(admin)
        every { reportAppealRepository.findById(200L) } returns Optional.of(appeal)
        every { reviewRepository.findById(50L) } returns Optional.of(review)

        val response = service.approveAppeal(adminUserId = 1L, appealId = 200L)

        assertThat(response.status).isEqualTo(ReportAppealStatus.APPROVED)
        assertThat(response.targetHidden).isFalse()
        assertThat(review.isHidden).isFalse()
        assertThat(appeal.reviewedBy?.id).isEqualTo(1L)
        assertThat(appeal.reviewedAt).isNotNull()
    }

    @Test
    fun `rejectAppeal 시 hidden 유지 + REJECTED + rejectReason 저장`() {
        val admin = createUser(id = 1L, role = UserRole.ADMIN)
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author)
        val appeal = createPendingAppeal(id = 201L, requester = author, targetType = ReportTargetType.REVIEW, targetId = 50L)

        every { userRepository.findById(1L) } returns Optional.of(admin)
        every { reportAppealRepository.findById(201L) } returns Optional.of(appeal)
        every { reviewRepository.findById(50L) } returns Optional.of(review)

        val response = service.rejectAppeal(
            adminUserId = 1L,
            appealId = 201L,
            request = ReviewReportAppealRequest(rejectReason = "정책 위반 명백"),
        )

        assertThat(response.status).isEqualTo(ReportAppealStatus.REJECTED)
        assertThat(response.rejectReason).isEqualTo("정책 위반 명백")
        assertThat(response.targetHidden).isTrue()  // 여전히 hidden
        assertThat(review.isHidden).isTrue()
    }

    @Test
    fun `approveAppeal 이미 처리된 appeal 은 ReportAppealAlreadyProcessedException`() {
        val admin = createUser(id = 1L, role = UserRole.ADMIN)
        val author = createUser(id = 5L)
        val appeal = createPendingAppeal(id = 202L, requester = author).apply {
            approve(admin)
        }

        every { userRepository.findById(1L) } returns Optional.of(admin)
        every { reportAppealRepository.findById(202L) } returns Optional.of(appeal)

        assertThrows<ReportAppealAlreadyProcessedException> {
            service.approveAppeal(1L, 202L)
        }
    }

    @Test
    fun `approveAppeal 미존재 appeal 은 ReportAppealNotFoundException`() {
        val admin = createUser(id = 1L, role = UserRole.ADMIN)
        every { userRepository.findById(1L) } returns Optional.of(admin)
        every { reportAppealRepository.findById(999L) } returns Optional.empty()

        assertThrows<ReportAppealNotFoundException> { service.approveAppeal(1L, 999L) }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun createUser(
        id: Long,
        role: UserRole = UserRole.PARTICIPANT,
        nickname: String = "user$id",
    ): User = User("u$id@test.com", "encoded", nickname, "01012345$id").apply {
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

    private fun createHiddenEvent(id: Long, channel: Channel): Event =
        createEvent(id, channel).apply { hide(ReportService.AUTO_HIDE_REASON) }

    private fun createReview(id: Long, author: User): Review {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        return Review(event = event, author = author, rating = 4, content = "본문 $id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
        }
    }

    private fun createHiddenReview(id: Long, author: User): Review =
        createReview(id, author).apply { hide(ReportService.AUTO_HIDE_REASON) }

    private fun createPendingAppeal(
        id: Long,
        requester: User,
        targetType: ReportTargetType = ReportTargetType.REVIEW,
        targetId: Long = 50L,
    ): ReportAppeal = ReportAppeal(
        targetType = targetType,
        targetId = targetId,
        requester = requester,
        reason = "appeal $id",
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        val now = LocalDateTime.now()
        ReflectionTestUtils.setField(this, "createdAt", now)
        ReflectionTestUtils.setField(this, "updatedAt", now)
    }
}
