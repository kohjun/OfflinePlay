package com.contenido.domain.user.service

import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.ticket.entity.TicketStatus
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.dto.TrustSummaryResponse
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR145 — Trust Snapshot.
 *
 * 정책:
 *  - 기존 데이터만 사용. 새 테이블 없음.
 *  - deleted user 도 신청 이력/후기는 남아 있을 수 있어 UserNotFoundException 만 가드하고
 *    deleted 자체로는 막지 않는다 (공개 프로필이 404 인지 빈 신뢰 카드인지는 PR144 의 정책 — 본 서비스는
 *    값 계산만 책임).
 *  - 5개 query 를 직렬로 호출. user 당 ~5ms 수준. 핫패스 진입 시 캐시 도입.
 */
@Service
@Transactional(readOnly = true)
class TrustSummaryService(
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
    private val participationRepository: EventParticipationRepository,
    private val ticketRepository: TicketRepository,
    private val reviewRepository: ReviewRepository,
) {

    fun compute(userId: Long): TrustSummaryResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }

        val hosted = eventRepository.countByChannelOwner(user)
        val participated = participationRepository.countByParticipantId(userId)
        val checkedIn = ticketRepository.countByBuyerIdAndStatus(userId, TicketStatus.USED)
        val reviews = reviewRepository.countByAuthorId(userId)
        val hostAvg = reviewRepository.averageRatingByHostUserId(userId)

        return TrustSummaryResponse(
            userId = userId,
            hostedEventCount = hosted,
            participatedEventCount = participated,
            checkedInCount = checkedIn,
            reviewCount = reviews,
            averageEventRatingAsHost = hostAvg,
        )
    }
}
