package com.contenido.domain.creator.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.entity.ChannelCategory
import com.contenido.domain.channel.entity.ChannelMember
import com.contenido.domain.channel.entity.ChannelMemberRole
import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.payment.repository.PaymentAttemptRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UnauthorizedException
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
 * PR153 — CreatorAnalyticsService 단위 테스트.
 *  - 빈 채널 0 응답
 *  - 다중 이벤트 합산 + grossRevenue desc 정렬
 *  - non-owner / non-STAFF / non-ADMIN 차단
 *  - STAFF / ADMIN 허용
 */
@ExtendWith(MockKExtension::class)
class CreatorAnalyticsServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var channelRepository: ChannelRepository
    @MockK lateinit var channelMemberRepository: ChannelMemberRepository
    @MockK lateinit var paymentAttemptRepository: PaymentAttemptRepository

    private lateinit var service: CreatorAnalyticsService

    @BeforeEach
    fun setUp() {
        service = CreatorAnalyticsService(
            userRepository, channelRepository, channelMemberRepository, paymentAttemptRepository,
        )
    }

    @Test
    fun `빈 채널 — 모든 금액 0`() {
        val owner = user(1L)
        val channel = channel(10L, owner)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { paymentAttemptRepository.aggregateChannelAnalytics(10L, null, null) } returns emptyList()

        val response = service.getChannelAnalytics(1L, 10L, null, null)

        assertThat(response.grossRevenue).isEqualTo(0L)
        assertThat(response.refundedAmount).isEqualTo(0L)
        assertThat(response.netRevenue).isEqualTo(0L)
        assertThat(response.events).isEmpty()
    }

    @Test
    fun `다중 이벤트 합산 + grossRevenue desc 정렬`() {
        val owner = user(1L)
        val channel = channel(10L, owner)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        // [eventId, title, gross, refunded, partial, fullCnt, paidCnt]
        every { paymentAttemptRepository.aggregateChannelAnalytics(10L, null, null) } returns listOf(
            row(100L, "작은 이벤트", 30_000L, 10_000L, 0L, 1L, 3L),
            row(200L, "큰 이벤트", 500_000L, 50_000L, 50_000L, 0L, 20L),
            row(300L, "중간 이벤트", 100_000L, 0L, 0L, 0L, 5L),
        )

        val response = service.getChannelAnalytics(1L, 10L, null, null)

        assertThat(response.grossRevenue).isEqualTo(630_000L)
        assertThat(response.refundedAmount).isEqualTo(60_000L)
        assertThat(response.netRevenue).isEqualTo(570_000L)
        assertThat(response.partialRefundAmount).isEqualTo(50_000L)
        assertThat(response.fullRefundCount).isEqualTo(1L)
        assertThat(response.paidAttemptCount).isEqualTo(28L)
        // 정렬: 500_000 → 100_000 → 30_000
        assertThat(response.events.map { it.eventId }).containsExactly(200L, 300L, 100L)
        assertThat(response.events[0].netRevenue).isEqualTo(450_000L)
    }

    @Test
    fun `non-owner 가 다른 채널 호출하면 UnauthorizedException`() {
        val owner = user(1L)
        val intruder = user(2L)
        val channel = channel(10L, owner)
        every { userRepository.findById(2L) } returns Optional.of(intruder)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { channelMemberRepository.findByChannelAndUser(channel, intruder) } returns Optional.empty()

        assertThatThrownBy { service.getChannelAnalytics(2L, 10L, null, null) }
            .isInstanceOf(UnauthorizedException::class.java)
    }

    @Test
    fun `STAFF 는 허용`() {
        val owner = user(1L)
        val staff = user(2L)
        val channel = channel(10L, owner)
        every { userRepository.findById(2L) } returns Optional.of(staff)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { channelMemberRepository.findByChannelAndUser(channel, staff) } returns Optional.of(
            ChannelMember(channel = channel, user = staff, role = ChannelMemberRole.STAFF),
        )
        every { paymentAttemptRepository.aggregateChannelAnalytics(10L, null, null) } returns emptyList()

        val response = service.getChannelAnalytics(2L, 10L, null, null)

        assertThat(response.channelId).isEqualTo(10L)
    }

    @Test
    fun `ADMIN 은 owner 아니어도 허용`() {
        val owner = user(1L)
        val admin = user(99L, UserRole.ADMIN)
        val channel = channel(10L, owner)
        every { userRepository.findById(99L) } returns Optional.of(admin)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { paymentAttemptRepository.aggregateChannelAnalytics(10L, null, null) } returns emptyList()

        val response = service.getChannelAnalytics(99L, 10L, null, null)

        assertThat(response.channelId).isEqualTo(10L)
    }

    @Test
    fun `날짜 필터 from to 가 query 로 전달`() {
        val owner = user(1L)
        val channel = channel(10L, owner)
        val from = LocalDateTime.of(2026, 5, 1, 0, 0)
        val to = LocalDateTime.of(2026, 6, 1, 0, 0)
        every { userRepository.findById(1L) } returns Optional.of(owner)
        every { channelRepository.findById(10L) } returns Optional.of(channel)
        every { paymentAttemptRepository.aggregateChannelAnalytics(10L, from, to) } returns emptyList()

        val response = service.getChannelAnalytics(1L, 10L, from, to)

        assertThat(response.from).isEqualTo(from)
        assertThat(response.to).isEqualTo(to)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun user(id: Long, role: UserRole = UserRole.PARTICIPANT): User =
        User("u$id@test.com", "pwd", "닉네임$id", "01000000$id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "role", role)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now().minusDays(30))
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun channel(id: Long, owner: User): Channel =
        Channel(owner, "ch-$id", "desc", ChannelCategory.MUSIC).apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun row(
        eventId: Long,
        title: String,
        gross: Long,
        refunded: Long,
        partial: Long,
        fullCnt: Long,
        paidCnt: Long,
    ): Array<Any> = arrayOf(eventId, title, gross, refunded, partial, fullCnt, paidCnt)
}
