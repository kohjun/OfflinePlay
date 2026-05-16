package com.contenido.domain.review.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.EventStatus
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.review.dto.CreateReviewRequest
import com.contenido.domain.review.dto.UpdateReviewRequest
import com.contenido.domain.review.entity.Review
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.EventNotFoundException
import com.contenido.global.exception.ReviewAlreadyExistsException
import com.contenido.global.exception.ReviewNotAllowedException
import com.contenido.global.exception.ReviewNotFoundException
import com.contenido.global.exception.UnauthorizedException
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

@ExtendWith(MockKExtension::class)
class ReviewServiceTest {

    @MockK lateinit var reviewRepository: ReviewRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var ticketRepository: TicketRepository

    private lateinit var service: ReviewService

    @BeforeEach
    fun setUp() {
        service = ReviewService(reviewRepository, eventRepository, userRepository, ticketRepository)
    }

    // ── createReview ──────────────────────────────────────────────────────────

    @Test
    fun `createReview USED 티켓 보유자가 처음 작성하면 정상 저장`() {
        val author = createUser(id = 2L)
        val event = createEventWithChannel(id = 100L)
        val captured = slot<Review>()

        every { userRepository.findById(2L) } returns Optional.of(author)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every {
            ticketRepository.existsByEventAndBuyerAndStatusIn(event, author, listOf(TicketStatus.USED))
        } returns true
        every { reviewRepository.findByEventAndAuthor(event, author) } returns Optional.empty()
        every { reviewRepository.save(capture(captured)) } answers {
            // 실제 JPA 라면 영속화 후 id/createdAt/updatedAt 가 채워진다. 단위 테스트에서는
            // AuditingEntityListener 가 동작하지 않으므로 fixture 헬퍼와 같은 방식으로 직접 세팅.
            captured.captured.also {
                ReflectionTestUtils.setField(it, "id", 777L)
                val now = LocalDateTime.now()
                ReflectionTestUtils.setField(it, "createdAt", now)
                ReflectionTestUtils.setField(it, "updatedAt", now)
            }
        }

        val response = service.createReview(2L, 100L, CreateReviewRequest(rating = 5, content = "최고였어요"))

        assertThat(response.id).isEqualTo(777L)
        assertThat(response.rating).isEqualTo(5)
        assertThat(response.content).isEqualTo("최고였어요")
        assertThat(captured.captured.event).isEqualTo(event)
        assertThat(captured.captured.author).isEqualTo(author)
    }

    @Test
    fun `createReview USED 티켓 없는 사용자는 ReviewNotAllowedException`() {
        val author = createUser(id = 2L)
        val event = createEventWithChannel(id = 100L)

        every { userRepository.findById(2L) } returns Optional.of(author)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every {
            ticketRepository.existsByEventAndBuyerAndStatusIn(event, author, listOf(TicketStatus.USED))
        } returns false

        assertThrows<ReviewNotAllowedException> {
            service.createReview(2L, 100L, CreateReviewRequest(5, "좋아요"))
        }
        verify(exactly = 0) { reviewRepository.save(any()) }
    }

    @Test
    fun `createReview 같은 이벤트에 이미 리뷰가 있으면 ReviewAlreadyExistsException`() {
        val author = createUser(id = 2L)
        val event = createEventWithChannel(id = 100L)
        val existing = Review(event = event, author = author, rating = 4, content = "이전")

        every { userRepository.findById(2L) } returns Optional.of(author)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every {
            ticketRepository.existsByEventAndBuyerAndStatusIn(event, author, listOf(TicketStatus.USED))
        } returns true
        every { reviewRepository.findByEventAndAuthor(event, author) } returns Optional.of(existing)

        assertThrows<ReviewAlreadyExistsException> {
            service.createReview(2L, 100L, CreateReviewRequest(5, "다시"))
        }
        verify(exactly = 0) { reviewRepository.save(any()) }
    }

    @Test
    fun `createReview 이벤트 미존재는 EventNotFoundException`() {
        every { userRepository.findById(2L) } returns Optional.of(createUser(id = 2L))
        every { eventRepository.findById(999L) } returns Optional.empty()

        assertThrows<EventNotFoundException> {
            service.createReview(2L, 999L, CreateReviewRequest(5, "x"))
        }
    }

    // ── updateReview ──────────────────────────────────────────────────────────

    @Test
    fun `updateReview 본인이면 rating + content 갱신`() {
        val author = createUser(id = 2L)
        val event = createEventWithChannel(id = 100L)
        val review = createReviewFixture(event = event, author = author, rating = 3, content = "그저 그래요", id = 50L)

        every { reviewRepository.findById(50L) } returns Optional.of(review)

        service.updateReview(2L, 50L, UpdateReviewRequest(rating = 5, content = "다시 보니 좋아요"))

        assertThat(review.rating).isEqualTo(5)
        assertThat(review.content).isEqualTo("다시 보니 좋아요")
    }

    @Test
    fun `updateReview 본인이 아니면 UnauthorizedException (rating 변화 없음)`() {
        val author = createUser(id = 2L)
        val event = createEventWithChannel(id = 100L)
        val review = Review(event = event, author = author, rating = 3, content = "그저 그래요")
            .apply { ReflectionTestUtils.setField(this, "id", 50L) }

        every { reviewRepository.findById(50L) } returns Optional.of(review)

        assertThrows<UnauthorizedException> {
            service.updateReview(99L, 50L, UpdateReviewRequest(rating = 1, content = "악의적 변경"))
        }
        assertThat(review.rating).isEqualTo(3)  // 변화 없음
    }

    // ── deleteReview ──────────────────────────────────────────────────────────

    @Test
    fun `deleteReview 본인이면 delete 호출`() {
        val author = createUser(id = 2L)
        val event = createEventWithChannel(id = 100L)
        val review = Review(event = event, author = author, rating = 3, content = "x")
            .apply { ReflectionTestUtils.setField(this, "id", 50L) }

        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every { userRepository.findById(2L) } returns Optional.of(author)
        every { reviewRepository.delete(review) } returns Unit

        service.deleteReview(2L, 50L)

        verify(exactly = 1) { reviewRepository.delete(review) }
    }

    @Test
    fun `deleteReview ADMIN 이면 본인 아니어도 delete 호출 가능`() {
        val author = createUser(id = 2L)
        val admin = createUser(id = 99L, role = UserRole.ADMIN)
        val event = createEventWithChannel(id = 100L)
        val review = Review(event = event, author = author, rating = 3, content = "x")
            .apply { ReflectionTestUtils.setField(this, "id", 50L) }

        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { reviewRepository.delete(review) } returns Unit

        service.deleteReview(99L, 50L)

        verify(exactly = 1) { reviewRepository.delete(review) }
    }

    @Test
    fun `deleteReview CREATOR 라도 본인 글 아니면 UnauthorizedException (자기 이벤트라도)`() {
        // CREATOR 가 자기 이벤트의 별점을 임의로 삭제 못함 — 별점 조작 방어.
        val author = createUser(id = 2L)
        val creator = createUser(id = 1L, role = UserRole.CREATOR)
        val event = createEventWithChannel(id = 100L, ownerId = 1L)
        val review = Review(event = event, author = author, rating = 1, content = "별로")
            .apply { ReflectionTestUtils.setField(this, "id", 50L) }

        every { reviewRepository.findById(50L) } returns Optional.of(review)
        every { userRepository.findById(1L) } returns Optional.of(creator)

        assertThrows<UnauthorizedException> {
            service.deleteReview(1L, 50L)
        }
        verify(exactly = 0) { reviewRepository.delete(any()) }
    }

    @Test
    fun `deleteReview 미존재는 ReviewNotFoundException`() {
        every { reviewRepository.findById(404L) } returns Optional.empty()

        assertThrows<ReviewNotFoundException> {
            service.deleteReview(2L, 404L)
        }
    }

    // ── summaryForEvent ───────────────────────────────────────────────────────

    @Test
    fun `summaryForEvent 후기가 있으면 평균 + 카운트`() {
        val event = createEventWithChannel(id = 100L)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { reviewRepository.averageRatingByEventId(100L) } returns 4.3
        every { reviewRepository.countByEvent(event) } returns 7L

        val summary = service.summaryForEvent(100L)

        assertThat(summary.averageRating).isEqualTo(4.3)
        assertThat(summary.reviewCount).isEqualTo(7L)
    }

    @Test
    fun `summaryForEvent 후기 0건이면 평균 null + count 0`() {
        val event = createEventWithChannel(id = 100L)
        every { eventRepository.findById(100L) } returns Optional.of(event)
        every { reviewRepository.averageRatingByEventId(100L) } returns null
        every { reviewRepository.countByEvent(event) } returns 0L

        val summary = service.summaryForEvent(100L)

        assertThat(summary.averageRating).isNull()
        assertThat(summary.reviewCount).isZero()
    }

    @Test
    fun `summaryForEvent 이벤트 미존재여도 예외 X (null + 0 으로 graceful 응답)`() {
        every { eventRepository.findById(404L) } returns Optional.empty()

        val summary = service.summaryForEvent(404L)

        assertThat(summary.averageRating).isNull()
        assertThat(summary.reviewCount).isZero()
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

    private fun createEventWithChannel(id: Long, ownerId: Long = 1L): Event {
        val owner = createUser(id = ownerId, role = UserRole.CREATOR)
        val channel = createChannel(id = 10L, owner = owner)
        return Event(
            channel = channel,
            title = "이벤트$id",
            description = "desc",
            location = "서울",
            mainImageUrl = "https://example.com/$id.jpg",
            startAt = LocalDateTime.now().minusDays(1),
            endAt = LocalDateTime.now().minusHours(1),
            maxParticipants = 10,
            participationFee = 0L,
            refundPolicy = "정책",
            detailContent = "detail",
            status = EventStatus.CLOSED,
        ).apply {
            ReflectionTestUtils.setField(this, "id", id)
        }
    }

    /**
     * Review 픽스처 — id 와 audit 필드(createdAt/updatedAt) 까지 세팅한다.
     *
     * Review 의 createdAt/updatedAt 는 `lateinit` 으로 JPA AuditingEntityListener 가 채우는데
     * 단위 테스트엔 AuditingEntityListener 가 동작하지 않는다. service.toResponse() 가
     * 두 필드를 읽을 수 있도록 본 헬퍼가 항상 채워준다 — 실제 운영 흐름엔 영향 없음.
     */
    private fun createReviewFixture(
        event: Event,
        author: User,
        rating: Int,
        content: String,
        id: Long = 0L,
        createdAt: LocalDateTime = LocalDateTime.now(),
        updatedAt: LocalDateTime = createdAt,
    ): Review = Review(event = event, author = author, rating = rating, content = content).apply {
        if (id != 0L) ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", createdAt)
        ReflectionTestUtils.setField(this, "updatedAt", updatedAt)
    }
}
