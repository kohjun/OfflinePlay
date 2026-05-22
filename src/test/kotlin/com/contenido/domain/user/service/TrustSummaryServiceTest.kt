package com.contenido.domain.user.service

import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UserNotFoundException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR145 — TrustSummaryService 단위 테스트.
 *  - 빈 history 시 0 / null 응답
 *  - 다중 카테고리 카운트 일치
 *  - host 평균 별점이 ReviewRepository 응답 그대로
 *  - 미존재 사용자 UserNotFoundException
 */
@ExtendWith(MockKExtension::class)
class TrustSummaryServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var participationRepository: EventParticipationRepository
    @MockK lateinit var ticketRepository: TicketRepository
    @MockK lateinit var reviewRepository: ReviewRepository

    private lateinit var service: TrustSummaryService

    @BeforeEach
    fun setUp() {
        service = TrustSummaryService(
            userRepository = userRepository,
            eventRepository = eventRepository,
            participationRepository = participationRepository,
            ticketRepository = ticketRepository,
            reviewRepository = reviewRepository,
        )
    }

    @Test
    fun `빈 history — 모든 카운트 0 + averageRating null`() {
        val user = createUser(1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { eventRepository.countByChannelOwner(user) } returns 0L
        every { participationRepository.countByParticipantId(1L) } returns 0L
        every { ticketRepository.countByBuyerIdAndStatus(1L, TicketStatus.USED) } returns 0L
        every { reviewRepository.countByAuthorId(1L) } returns 0L
        every { reviewRepository.averageRatingByHostUserId(1L) } returns null

        val response = service.compute(1L)

        assertThat(response.userId).isEqualTo(1L)
        assertThat(response.hostedEventCount).isEqualTo(0L)
        assertThat(response.participatedEventCount).isEqualTo(0L)
        assertThat(response.checkedInCount).isEqualTo(0L)
        assertThat(response.reviewCount).isEqualTo(0L)
        assertThat(response.averageEventRatingAsHost).isNull()
    }

    @Test
    fun `다중 카테고리 카운트가 그대로 합산되어 응답에 반영`() {
        val user = createUser(7L)
        every { userRepository.findById(7L) } returns Optional.of(user)
        every { eventRepository.countByChannelOwner(user) } returns 3L
        every { participationRepository.countByParticipantId(7L) } returns 12L
        every { ticketRepository.countByBuyerIdAndStatus(7L, TicketStatus.USED) } returns 5L
        every { reviewRepository.countByAuthorId(7L) } returns 4L
        every { reviewRepository.averageRatingByHostUserId(7L) } returns 4.25

        val response = service.compute(7L)

        assertThat(response.hostedEventCount).isEqualTo(3L)
        assertThat(response.participatedEventCount).isEqualTo(12L)
        assertThat(response.checkedInCount).isEqualTo(5L)
        assertThat(response.reviewCount).isEqualTo(4L)
        assertThat(response.averageEventRatingAsHost).isEqualTo(4.25)
    }

    @Test
    fun `host 평균 별점이 null 이면 응답도 null`() {
        val user = createUser(7L)
        every { userRepository.findById(7L) } returns Optional.of(user)
        every { eventRepository.countByChannelOwner(user) } returns 2L
        every { participationRepository.countByParticipantId(7L) } returns 0L
        every { ticketRepository.countByBuyerIdAndStatus(7L, TicketStatus.USED) } returns 0L
        every { reviewRepository.countByAuthorId(7L) } returns 0L
        // host 가 이벤트는 운영했지만 후기가 한 건도 없는 케이스.
        every { reviewRepository.averageRatingByHostUserId(7L) } returns null

        val response = service.compute(7L)

        assertThat(response.hostedEventCount).isEqualTo(2L)
        assertThat(response.averageEventRatingAsHost).isNull()
    }

    @Test
    fun `미존재 사용자는 UserNotFoundException`() {
        every { userRepository.findById(99L) } returns Optional.empty()
        assertThatThrownBy { service.compute(99L) }
            .isInstanceOf(UserNotFoundException::class.java)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun createUser(id: Long): User =
        User("u$id@test.com", "pwd", "닉네임$id", "01000000$id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now().minusDays(30))
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }
}
