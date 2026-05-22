package com.contenido.domain.user.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.user.dto.CreateMannerFeedbackRequest
import com.contenido.domain.user.entity.MannerFeedback
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.MannerFeedbackRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.MannerFeedbackAlreadyExistsException
import com.contenido.global.exception.MannerFeedbackBeforeEventEndedException
import com.contenido.global.exception.MannerFeedbackNotAllowedException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR146 — MannerFeedbackService 단위 테스트.
 *  - 이벤트 종료 전 차단
 *  - 본인-자기 평가 차단
 *  - host-host / participant-participant 페어 차단
 *  - 중복 차단
 *  - 3건 미만 시 summary null
 */
@ExtendWith(MockKExtension::class)
class MannerFeedbackServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var participationRepository: EventParticipationRepository
    @MockK lateinit var feedbackRepository: MannerFeedbackRepository

    private lateinit var service: MannerFeedbackService

    @BeforeEach
    fun setUp() {
        service = MannerFeedbackService(
            userRepository = userRepository,
            eventRepository = eventRepository,
            participationRepository = participationRepository,
            feedbackRepository = feedbackRepository,
        )
    }

    @Test
    fun `host 가 APPROVED 참가자에게 작성 성공`() {
        val host = user(1L)
        val participant = user(10L)
        val channel = channel(owner = host)
        val event = event(channel = channel, endAt = LocalDateTime.now().minusHours(1))
        stubBasic(host, participant, event)
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(
                event, host, listOf(ParticipationStatus.APPROVED),
            )
        } returns false
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(
                event, participant, listOf(ParticipationStatus.APPROVED),
            )
        } returns true
        every {
            feedbackRepository.existsByReviewerIdAndRevieweeIdAndEventId(1L, 10L, event.id)
        } returns false
        val saved = slot<MannerFeedback>()
        every { feedbackRepository.save(capture(saved)) } answers {
            val captured = saved.captured
            ReflectionTestUtils.setField(captured, "id", 99L)
            ReflectionTestUtils.setField(captured, "createdAt", LocalDateTime.now())
            captured
        }

        val result = service.create(
            1L, event.id,
            CreateMannerFeedbackRequest(revieweeId = 10L, rating = 5, tags = listOf("FRIENDLY", "PUNCTUAL"), comment = "감사"),
        )

        assertThat(result.id).isEqualTo(99L)
        assertThat(saved.captured.rating).isEqualTo(5)
        assertThat(saved.captured.tags).containsExactly("FRIENDLY", "PUNCTUAL")
    }

    @Test
    fun `참가자가 host 에게 작성도 가능 (반대 방향)`() {
        val host = user(1L)
        val participant = user(10L)
        val channel = channel(owner = host)
        val event = event(channel = channel, endAt = LocalDateTime.now().minusHours(1))
        stubBasic(participant, host, event)
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(
                event, participant, listOf(ParticipationStatus.APPROVED),
            )
        } returns true
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(
                event, host, listOf(ParticipationStatus.APPROVED),
            )
        } returns false
        every {
            feedbackRepository.existsByReviewerIdAndRevieweeIdAndEventId(10L, 1L, event.id)
        } returns false
        every { feedbackRepository.save(any()) } answers {
            firstArg<MannerFeedback>().also {
                ReflectionTestUtils.setField(it, "id", 100L)
                ReflectionTestUtils.setField(it, "createdAt", LocalDateTime.now())
            }
        }

        val result = service.create(
            10L, event.id,
            CreateMannerFeedbackRequest(revieweeId = 1L, rating = 4),
        )

        assertThat(result.id).isEqualTo(100L)
    }

    @Test
    fun `이벤트가 아직 끝나지 않았으면 차단`() {
        val host = user(1L)
        val participant = user(10L)
        val event = event(channel = channel(owner = host), endAt = LocalDateTime.now().plusHours(1))
        stubBasic(host, participant, event)

        assertThatThrownBy {
            service.create(1L, event.id, CreateMannerFeedbackRequest(revieweeId = 10L, rating = 5))
        }.isInstanceOf(MannerFeedbackBeforeEventEndedException::class.java)
    }

    @Test
    fun `본인-자기 평가는 NotAllowed`() {
        val host = user(1L)
        every { userRepository.findById(1L) } returns Optional.of(host)
        every { eventRepository.findById(50L) } returns Optional.of(
            event(channel = channel(owner = host), endAt = LocalDateTime.now().minusHours(1)),
        )

        assertThatThrownBy {
            service.create(1L, 50L, CreateMannerFeedbackRequest(revieweeId = 1L, rating = 5))
        }.isInstanceOf(MannerFeedbackNotAllowedException::class.java)
    }

    @Test
    fun `host 와 host (둘 다 host 아님) — 어느 쪽도 host 가 아니면 NotAllowed`() {
        val host = user(1L)
        val a = user(10L)
        val b = user(11L)
        val event = event(channel = channel(owner = host), endAt = LocalDateTime.now().minusHours(1))
        stubBasic(a, b, event)
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(event, a, any())
        } returns true
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(event, b, any())
        } returns true

        // 둘 다 참가자라 host-participant 페어가 아님 → forbidden.
        assertThatThrownBy {
            service.create(10L, event.id, CreateMannerFeedbackRequest(revieweeId = 11L, rating = 5))
        }.isInstanceOf(MannerFeedbackNotAllowedException::class.java)
    }

    @Test
    fun `같은 reviewer+reviewee+event 중복은 AlreadyExists`() {
        val host = user(1L)
        val participant = user(10L)
        val event = event(channel = channel(owner = host), endAt = LocalDateTime.now().minusHours(1))
        stubBasic(host, participant, event)
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(event, host, any())
        } returns false
        every {
            participationRepository.existsByEventAndParticipantAndStatusIn(event, participant, any())
        } returns true
        every {
            feedbackRepository.existsByReviewerIdAndRevieweeIdAndEventId(1L, 10L, event.id)
        } returns true

        assertThatThrownBy {
            service.create(1L, event.id, CreateMannerFeedbackRequest(revieweeId = 10L, rating = 5))
        }.isInstanceOf(MannerFeedbackAlreadyExistsException::class.java)
    }

    @Test
    fun `getSummary — 누적 3건 미만이면 null`() {
        every { feedbackRepository.countByRevieweeId(7L) } returns 2L
        assertThat(service.getSummary(7L)).isNull()
    }

    @Test
    fun `getSummary — 3건 이상이면 평균 + topTags 응답`() {
        val host = user(1L)
        val target = user(7L)
        every { feedbackRepository.countByRevieweeId(7L) } returns 4L
        every { feedbackRepository.averageRatingByRevieweeId(7L) } returns 4.5
        every { feedbackRepository.findByRevieweeId(7L) } returns listOf(
            feedback(host, target, tags = listOf("FRIENDLY", "PUNCTUAL")),
            feedback(host, target, tags = listOf("FRIENDLY", "POLITE")),
            feedback(host, target, tags = listOf("FRIENDLY")),
            feedback(host, target, tags = listOf("PUNCTUAL", "POLITE")),
        )

        val summary = service.getSummary(7L)

        assertThat(summary).isNotNull
        assertThat(summary!!.averageRating).isEqualTo(4.5)
        assertThat(summary.count).isEqualTo(4L)
        // FRIENDLY 3 / PUNCTUAL 2 / POLITE 2 → 빈도 desc, 동률은 사전순.
        assertThat(summary.topTags).containsExactly("FRIENDLY", "POLITE", "PUNCTUAL")
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun stubBasic(reviewer: User, reviewee: User, event: Event) {
        every { userRepository.findById(reviewer.id) } returns Optional.of(reviewer)
        every { userRepository.findById(reviewee.id) } returns Optional.of(reviewee)
        every { eventRepository.findById(event.id) } returns Optional.of(event)
    }

    private fun user(id: Long): User =
        User("u$id@test.com", "pwd", "닉네임$id", "01000000$id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now().minusDays(30))
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun channel(id: Long = 100L, owner: User): Channel =
        Channel(owner, "ch-$id", "desc", ChannelCategory.MUSIC).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun event(id: Long = 200L, channel: Channel, endAt: LocalDateTime): Event = Event(
        channel = channel,
        title = "ev",
        description = "d",
        location = "l",
        mainImageUrl = "img",
        startAt = endAt.minusHours(2),
        endAt = endAt,
        maxParticipants = 50,
        participationFee = 0L,
        refundPolicy = "rp",
        detailContent = "dc",
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
    }

    private fun feedback(reviewer: User, reviewee: User, tags: List<String>): MannerFeedback =
        MannerFeedback(
            reviewer = reviewer,
            reviewee = reviewee,
            event = event(channel = channel(owner = reviewer), endAt = LocalDateTime.now().minusHours(1)),
            rating = 5,
            tags = tags,
        ).apply {
            ReflectionTestUtils.setField(this, "id", tags.hashCode().toLong())
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        }
}
