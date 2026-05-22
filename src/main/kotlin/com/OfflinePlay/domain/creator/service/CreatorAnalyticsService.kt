package com.contenido.domain.creator.service

import com.contenido.domain.channel.repository.ChannelMemberRepository
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.creator.dto.CreatorChannelAnalyticsResponse
import com.contenido.domain.creator.dto.CreatorEventAnalytics
import com.contenido.domain.payment.repository.PaymentAttemptRepository
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.ChannelNotFoundException
import com.contenido.global.exception.UnauthorizedException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * PR153 — Creator Studio 의 매출/환불 카드 + 이벤트별 breakdown.
 *
 * 권한:
 *  - 채널 owner (channel.owner.id == userId)
 *  - 채널 STAFF (channel_members 에 있는 사용자)
 *  - ADMIN
 *
 *  그 외는 [UnauthorizedException] (403).
 *
 * 본 서비스는 PaymentAttemptRepository.aggregateChannelAnalytics 한 query 로 channel scope 의
 * 모든 event 단위 합계를 받아온 뒤 in-memory 로 channel 합계를 계산한다 (events 개수만큼이 아니라
 * 하나의 GROUP BY 쿼리).
 */
@Service
@Transactional(readOnly = true)
class CreatorAnalyticsService(
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
    private val channelMemberRepository: ChannelMemberRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
) {

    fun getChannelAnalytics(
        userId: Long,
        channelId: Long,
        from: LocalDateTime?,
        to: LocalDateTime?,
    ): CreatorChannelAnalyticsResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val channel = channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }

        // 권한 가드: owner / STAFF / ADMIN.
        if (user.role != UserRole.ADMIN && channel.owner.id != userId) {
            val isStaff = channelMemberRepository.findByChannelAndUser(channel, user).isPresent
            if (!isStaff) throw UnauthorizedException()
        }

        val rows = paymentAttemptRepository.aggregateChannelAnalytics(channelId, from, to)
        val events = rows.map { row ->
            CreatorEventAnalytics(
                eventId = (row[0] as Number).toLong(),
                eventTitle = row[1] as String,
                grossRevenue = (row[2] as Number).toLong(),
                refundedAmount = (row[3] as Number).toLong(),
                netRevenue = (row[2] as Number).toLong() - (row[3] as Number).toLong(),
                partialRefundAmount = (row[4] as Number).toLong(),
                fullRefundCount = (row[5] as Number).toLong(),
                paidAttemptCount = (row[6] as Number).toLong(),
            )
        }.sortedByDescending { it.grossRevenue }

        return CreatorChannelAnalyticsResponse(
            channelId = channelId,
            from = from,
            to = to,
            grossRevenue = events.sumOf { it.grossRevenue },
            refundedAmount = events.sumOf { it.refundedAmount },
            netRevenue = events.sumOf { it.netRevenue },
            partialRefundAmount = events.sumOf { it.partialRefundAmount },
            fullRefundCount = events.sumOf { it.fullRefundCount },
            paidAttemptCount = events.sumOf { it.paidAttemptCount },
            events = events,
        )
    }
}
