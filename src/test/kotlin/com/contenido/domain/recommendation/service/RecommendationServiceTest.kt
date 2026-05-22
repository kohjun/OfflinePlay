package com.contenido.domain.recommendation.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelSubscription
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interest.entity.EventInterest
import com.contenido.domain.interest.entity.UserInterest
import com.contenido.domain.interest.repository.EventInterestRepository
import com.contenido.domain.interest.repository.UserInterestRepository
import com.contenido.domain.recommendation.dto.RecommendationSegment
import com.contenido.domain.region.entity.Region
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.entity.ProfileVisibility
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserProfile
import com.contenido.domain.user.repository.UserProfileRepository
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

/**
 * PR148 — RecommendationService.
 *
 * 가중치 score (interest*3 + region*2 + subscribed*2 + recency*1.5 + rating*1) 의 정렬 안정성과
 * 비로그인 fallback 동작을 검증.
 */
@ExtendWith(MockKExtension::class)
class RecommendationServiceTest {

    @MockK lateinit var eventRepository: EventRepository
    @MockK lateinit var userProfileRepository: UserProfileRepository
    @MockK lateinit var userInterestRepository: UserInterestRepository
    @MockK lateinit var eventInterestRepository: EventInterestRepository
    @MockK lateinit var channelSubscriptionRepository: ChannelSubscriptionRepository
    @MockK lateinit var reviewRepository: ReviewRepository

    private lateinit var service: RecommendationService

    @BeforeEach
    fun setUp() {
        service = RecommendationService(
            eventRepository = eventRepository,
            userProfileRepository = userProfileRepository,
            userInterestRepository = userInterestRepository,
            eventInterestRepository = eventInterestRepository,
            channelSubscriptionRepository = channelSubscriptionRepository,
            reviewRepository = reviewRepository,
        )
    }

    @Test
    fun `비로그인 — POPULAR fallback (currentParticipants desc)`() {
        val owner = user(1L)
        val ch = channel(10L, owner)
        val e1 = event(100L, ch, currentParticipants = 5)
        val e2 = event(101L, ch, currentParticipants = 20)
        every { eventRepository.findRecommendationCandidates(any(), any()) } returns PageImpl(listOf(e1, e2))
        every { reviewRepository.aggregateByEventIds(any()) } returns emptyList()

        val response = service.recommend(userId = null)

        assertThat(response.segment).isEqualTo("POPULAR")
        assertThat(response.items.map { it.event.id }).containsExactly(101L, 100L) // current desc
        assertThat(response.items[0].reasonCodes).containsExactly("POPULAR")
    }

    @Test
    fun `로그인 — INTEREST_MATCH 가 score 상위`() {
        val owner = user(1L)
        val viewer = user(2L)
        val ch = channel(10L, owner)
        val matched = event(100L, ch)
        val unmatched = event(101L, ch)
        every { eventRepository.findRecommendationCandidates(any(), any()) } returns
            PageImpl(listOf(matched, unmatched))
        // viewer 는 interest=[1,2] 를 가짐. matched 이벤트도 interest 1.
        every { userInterestRepository.findByUserId(2L) } returns listOf(
            UserInterest(userId = 2L, interestId = 1L),
            UserInterest(userId = 2L, interestId = 2L),
        )
        every { eventInterestRepository.findByEventIdIn(listOf(100L, 101L)) } returns listOf(
            EventInterest(eventId = 100L, interestId = 1L),
        )
        every { userProfileRepository.findByUserId(2L) } returns null
        every { channelSubscriptionRepository.findBySubscriberId(2L) } returns emptyList()
        every { reviewRepository.aggregateByEventIds(any()) } returns emptyList()

        val response = service.recommend(userId = 2L)

        assertThat(response.segment).isEqualTo("RECOMMENDED")
        // matched 만 score > 0 — unmatched 는 popular fallback 아님 (이 케이스는 score > 0 인 row 존재).
        assertThat(response.items.first().event.id).isEqualTo(100L)
        assertThat(response.items.first().reasonCodes).contains("INTEREST_MATCH")
    }

    @Test
    fun `로그인 — region 정확 매칭 + subscribed channel 둘 다 가산`() {
        val owner = user(1L)
        val viewer = user(2L)
        val ch = channel(10L, owner)
        val event100 = event(100L, ch).apply {
            ReflectionTestUtils.setField(this, "region", region("11110", "종로구"))
        }
        every { eventRepository.findRecommendationCandidates(any(), any()) } returns PageImpl(listOf(event100))
        every { userInterestRepository.findByUserId(2L) } returns emptyList()
        val profile = UserProfile(user = viewer).apply {
            visibility = ProfileVisibility.PUBLIC
            region = region("11110", "종로구")
        }
        every { userProfileRepository.findByUserId(2L) } returns profile
        every { channelSubscriptionRepository.findBySubscriberId(2L) } returns listOf(
            ChannelSubscription(subscriber = viewer, channel = ch),
        )
        every { eventInterestRepository.findByEventIdIn(any()) } returns emptyList()
        every { reviewRepository.aggregateByEventIds(any()) } returns emptyList()

        val response = service.recommend(userId = 2L)

        assertThat(response.items.first().reasonCodes).contains("NEAR_YOU", "SUBSCRIBED_CHANNEL")
        // region * 2 + subscribed * 2 = 4. recency 도 7일 이내라 가산.
        assertThat(response.items.first().score).isGreaterThanOrEqualTo(4.0)
    }

    @Test
    fun `로그인 — score 동률 시 id 큰 쪽이 먼저 (stable tie-break)`() {
        val owner = user(1L)
        val viewer = user(2L)
        val ch = channel(10L, owner)
        // 두 이벤트 동일한 region/interest 매칭 → score 동률.
        val e100 = event(100L, ch).apply {
            ReflectionTestUtils.setField(this, "region", region("11110", "종로구"))
        }
        val e200 = event(200L, ch).apply {
            ReflectionTestUtils.setField(this, "region", region("11110", "종로구"))
        }
        every { eventRepository.findRecommendationCandidates(any(), any()) } returns PageImpl(listOf(e100, e200))
        every { userInterestRepository.findByUserId(2L) } returns emptyList()
        every { userProfileRepository.findByUserId(2L) } returns UserProfile(user = viewer).apply {
            region = region("11110", "종로구")
        }
        every { channelSubscriptionRepository.findBySubscriberId(2L) } returns emptyList()
        every { eventInterestRepository.findByEventIdIn(any()) } returns emptyList()
        every { reviewRepository.aggregateByEventIds(any()) } returns emptyList()

        val response = service.recommend(userId = 2L)

        // 동률이면 id desc → 200 이 먼저.
        assertThat(response.items.map { it.event.id }).containsExactly(200L, 100L)
    }

    @Test
    fun `로그인 — 매칭이 0건이면 POPULAR fallback`() {
        val owner = user(1L)
        val viewer = user(2L)
        val ch = channel(10L, owner)
        val far = event(100L, ch, startAt = LocalDateTime.now().plusDays(30))
        every { eventRepository.findRecommendationCandidates(any(), any()) } returns PageImpl(listOf(far))
        every { userInterestRepository.findByUserId(2L) } returns emptyList()
        every { userProfileRepository.findByUserId(2L) } returns null
        every { channelSubscriptionRepository.findBySubscriberId(2L) } returns emptyList()
        every { eventInterestRepository.findByEventIdIn(any()) } returns emptyList()
        every { reviewRepository.aggregateByEventIds(any()) } returns emptyList()

        val response = service.recommend(userId = 2L)

        assertThat(response.segment).isEqualTo("POPULAR")
    }

    @Test
    fun `segment CLOSING_SOON 명시 — startAt asc 정렬`() {
        val owner = user(1L)
        val ch = channel(10L, owner)
        val soon = event(100L, ch, startAt = LocalDateTime.now().plusDays(1))
        val later = event(101L, ch, startAt = LocalDateTime.now().plusDays(10))
        every { eventRepository.findRecommendationCandidates(any(), any()) } returns PageImpl(listOf(soon, later))
        every { reviewRepository.aggregateByEventIds(any()) } returns emptyList()

        val response = service.recommend(userId = null, segment = RecommendationSegment.CLOSING_SOON)

        assertThat(response.segment).isEqualTo("CLOSING_SOON")
        assertThat(response.items.map { it.event.id }).containsExactly(100L, 101L)
    }

    @Test
    fun `segment LATEST 명시 — createdAt desc`() {
        val owner = user(1L)
        val ch = channel(10L, owner)
        val older = event(100L, ch).apply {
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now().minusDays(5))
        }
        val newer = event(101L, ch).apply {
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now().minusHours(2))
        }
        every { eventRepository.findRecommendationCandidates(any(), any()) } returns PageImpl(listOf(older, newer))
        every { reviewRepository.aggregateByEventIds(any()) } returns emptyList()

        val response = service.recommend(userId = null, segment = RecommendationSegment.LATEST)

        assertThat(response.segment).isEqualTo("LATEST")
        assertThat(response.items.map { it.event.id }).containsExactly(101L, 100L)
    }

    @Test
    fun `candidates 가 0건이면 빈 응답`() {
        every { eventRepository.findRecommendationCandidates(any(), any()) } returns PageImpl(emptyList())
        every { reviewRepository.aggregateByEventIds(any()) } returns emptyList()

        val response = service.recommend(userId = null)

        assertThat(response.items).isEmpty()
        assertThat(response.segment).isEqualTo("POPULAR")
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun user(id: Long): User =
        User("u$id@test.com", "pwd", "닉네임$id", "01000000$id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun channel(id: Long, owner: User): Channel =
        Channel(owner, "ch-$id", "desc", ChannelCategory.MUSIC).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun event(
        id: Long,
        channel: Channel,
        startAt: LocalDateTime = LocalDateTime.now().plusDays(3),
        currentParticipants: Int = 0,
    ): Event = Event(
        channel = channel,
        title = "ev-$id",
        description = "d",
        location = "l",
        mainImageUrl = "img",
        startAt = startAt,
        endAt = startAt.plusHours(2),
        maxParticipants = 100,
        participationFee = 0L,
        refundPolicy = "rp",
        detailContent = "dc",
        currentParticipants = currentParticipants,
    ).apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
        ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
    }

    private fun region(code: String, name: String): Region {
        val parent = if (code.length == 2) null else Region(code = code.take(2), name = "_sido", parent = null, level = 1)
        val level = if (code.length == 2) 1 else 2
        return Region(code = code, name = name, parent = parent, level = level)
    }
}
