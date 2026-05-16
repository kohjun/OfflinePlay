package com.contenido.domain.creator.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.creator.dto.AppealStatusView
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.entity.Comment
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.repository.PostRepository
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
import com.contenido.domain.user.repository.UserRepository
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
 * CreatorModerationService 커버리지 (PR53).
 *
 * 핵심 검증:
 *  - 본인 권한 hidden 콘텐츠가 5개 도메인 모두 통합 응답에 포함된다.
 *  - 타인의 hidden 콘텐츠는 repository 필터 단에서 차단된다 (다른 user 로 호출 시 빈 결과).
 *  - pending appeal 이 있으면 appealStatus=PENDING + appealId 가 채워진다.
 *  - pending report count 가 row 별로 ReportRepository 카운트 그대로 전달된다.
 */
@ExtendWith(MockKExtension::class)
class CreatorModerationServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var reviewRepository: ReviewRepository
    @MockK lateinit var commentRepository: CommentRepository
    @MockK lateinit var postRepository: PostRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var reportRepository: ReportRepository
    @MockK lateinit var reportAppealRepository: ReportAppealRepository

    private lateinit var service: CreatorModerationService

    @BeforeEach
    fun setUp() {
        service = CreatorModerationService(
            userRepository = userRepository,
            reviewRepository = reviewRepository,
            commentRepository = commentRepository,
            postRepository = postRepository,
            eventRepository = eventRepository,
            channelRepository = channelRepository,
            reportRepository = reportRepository,
            reportAppealRepository = reportAppealRepository,
        )
        // 기본 stub: 모든 repository 가 빈 결과. 각 테스트에서 필요한 항목만 override.
        every {
            reportRepository.countByTargetTypeAndTargetIdAndStatus(any(), any(), ReportStatus.PENDING)
        } returns 0L
        every {
            reportAppealRepository.findFirstByRequesterAndTargetTypeAndTargetIdOrderByCreatedAtDesc(any(), any(), any())
        } returns null
        every { reviewRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(any()) } returns emptyList()
        every { commentRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(any()) } returns emptyList()
        every { postRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(any()) } returns emptyList()
        every { eventRepository.findHiddenByChannelOwner(any()) } returns emptyList()
        every { channelRepository.findByOwnerAndHiddenAtIsNotNullOrderByHiddenAtDesc(any()) } returns emptyList()
    }

    @Test
    fun `listMyHidden 가 본인 REVIEW 자동 숨김을 반환한다 — 신고 카운트 + appealStatus NONE`() {
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author, hiddenAt = LocalDateTime.now())

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(author) } returns listOf(review)
        every {
            reportRepository.countByTargetTypeAndTargetIdAndStatus(ReportTargetType.REVIEW, 50L, ReportStatus.PENDING)
        } returns 4L

        val items = service.listMyHidden(5L)

        assertThat(items).hasSize(1)
        val item = items[0]
        assertThat(item.targetType).isEqualTo(ReportTargetType.REVIEW)
        assertThat(item.targetId).isEqualTo(50L)
        assertThat(item.pendingReportCount).isEqualTo(4L)
        assertThat(item.appealStatus).isEqualTo(AppealStatusView.NONE)
        assertThat(item.appealId).isNull()
    }

    @Test
    fun `listMyHidden 가 본인 COMMENT 자동 숨김을 반환한다`() {
        val author = createUser(id = 5L)
        val comment = Comment(
            author = author,
            targetType = TargetType.EVENT,
            targetId = 100L,
            content = "숨겨진 댓글",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 70L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
            hide(ReportService.AUTO_HIDE_REASON)
        }

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { commentRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(author) } returns listOf(comment)

        val items = service.listMyHidden(5L)

        assertThat(items).hasSize(1)
        assertThat(items[0].targetType).isEqualTo(ReportTargetType.COMMENT)
        assertThat(items[0].targetId).isEqualTo(70L)
    }

    @Test
    fun `listMyHidden 가 본인 POST 자동 숨김을 반환한다`() {
        val author = createUser(id = 5L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = author)
        val post = Post(channel = channel, author = author, title = "공지", content = "본문")
            .apply {
                ReflectionTestUtils.setField(this, "id", 60L)
                val now = LocalDateTime.now()
                ReflectionTestUtils.setField(this, "createdAt", now)
                ReflectionTestUtils.setField(this, "updatedAt", now)
                hide(ReportService.AUTO_HIDE_REASON)
            }

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { postRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(author) } returns listOf(post)

        val items = service.listMyHidden(5L)

        assertThat(items).hasSize(1)
        assertThat(items[0].targetType).isEqualTo(ReportTargetType.POST)
        assertThat(items[0].targetTitle).isEqualTo("공지")
    }

    @Test
    fun `listMyHidden 가 채널 owner 의 hidden EVENT 를 반환한다`() {
        val owner = createUser(id = 5L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createHiddenEvent(id = 100L, channel = channel)

        every { userRepository.findById(5L) } returns Optional.of(owner)
        every { eventRepository.findHiddenByChannelOwner(owner) } returns listOf(event)

        val items = service.listMyHidden(5L)

        assertThat(items).hasSize(1)
        assertThat(items[0].targetType).isEqualTo(ReportTargetType.EVENT)
        assertThat(items[0].targetId).isEqualTo(100L)
    }

    @Test
    fun `listMyHidden 가 owner 의 hidden CHANNEL 을 반환한다`() {
        val owner = createUser(id = 5L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner).apply {
            hide(ReportService.AUTO_HIDE_REASON)
        }

        every { userRepository.findById(5L) } returns Optional.of(owner)
        every { channelRepository.findByOwnerAndHiddenAtIsNotNullOrderByHiddenAtDesc(owner) } returns listOf(channel)

        val items = service.listMyHidden(5L)

        assertThat(items).hasSize(1)
        assertThat(items[0].targetType).isEqualTo(ReportTargetType.CHANNEL)
        assertThat(items[0].targetId).isEqualTo(10L)
    }

    @Test
    fun `listMyHidden 다른 사용자로 호출하면 타인의 hidden 콘텐츠는 빈 결과`() {
        // owner 가 만든 review/post 등이 DB 에 있어도, intruder 로 호출하면 repository 필터 단에서 막힘.
        val intruder = createUser(id = 99L)
        every { userRepository.findById(99L) } returns Optional.of(intruder)
        // 기본 stub 이 모든 repository 빈 결과 반환 — intruder 에게는 author/owner 매칭되는 row 없음.

        val items = service.listMyHidden(99L)

        assertThat(items).isEmpty()
    }

    @Test
    fun `listMyHidden pending appeal 있는 row 는 appealStatus=PENDING + appealId 채움`() {
        val author = createUser(id = 5L)
        val review = createHiddenReview(id = 50L, author = author, hiddenAt = LocalDateTime.now())
        val pendingAppeal = ReportAppeal(
            targetType = ReportTargetType.REVIEW,
            targetId = 50L,
            requester = author,
            reason = "appeal",
        ).apply {
            ReflectionTestUtils.setField(this, "id", 999L)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
            // status 는 default PENDING
        }

        every { userRepository.findById(5L) } returns Optional.of(author)
        every { reviewRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(author) } returns listOf(review)
        every {
            reportAppealRepository.findFirstByRequesterAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                author, ReportTargetType.REVIEW, 50L,
            )
        } returns pendingAppeal

        val items = service.listMyHidden(5L)

        assertThat(items[0].appealStatus).isEqualTo(AppealStatusView.PENDING)
        assertThat(items[0].appealId).isEqualTo(999L)
    }

    @Test
    fun `listMyHidden 결과는 hiddenAt 내림차순으로 정렬된다`() {
        val author = createUser(id = 5L)
        val newer = createHiddenReview(id = 51L, author = author, hiddenAt = LocalDateTime.now())
        val older = createHiddenReview(id = 50L, author = author, hiddenAt = LocalDateTime.now().minusDays(1))

        every { userRepository.findById(5L) } returns Optional.of(author)
        // repository 가 hiddenAt desc 로 줘도, service 가 5도메인 통합 후 다시 정렬하므로 의도 검증.
        every { reviewRepository.findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(author) } returns
            listOf(older, newer) // 일부러 역순으로 줘도 서비스가 정렬

        val items = service.listMyHidden(5L)

        assertThat(items.map { it.targetId }).containsExactly(51L, 50L)
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

    private fun createHiddenReview(id: Long, author: User, hiddenAt: LocalDateTime): Review {
        val owner = createUser(id = 1L, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        val event = createEvent(id = 100L, channel = channel)
        return Review(event = event, author = author, rating = 4, content = "본문 $id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            val now = LocalDateTime.now()
            ReflectionTestUtils.setField(this, "createdAt", now)
            ReflectionTestUtils.setField(this, "updatedAt", now)
            hide(ReportService.AUTO_HIDE_REASON)
            // 시간 명시 컨트롤 위해 hide 이후 override.
            ReflectionTestUtils.setField(this, "hiddenAt", hiddenAt)
        }
    }
}
