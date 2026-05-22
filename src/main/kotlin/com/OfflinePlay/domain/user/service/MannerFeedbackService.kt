package com.contenido.domain.user.service

import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.entity.ParticipationStatus
import com.contenido.domain.event.repository.EventParticipationRepository
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.user.dto.CreateMannerFeedbackRequest
import com.contenido.domain.user.dto.MannerSummaryResponse
import com.contenido.domain.user.entity.MannerFeedback
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.MannerFeedbackRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.EventNotFoundException
import com.contenido.global.exception.MannerFeedbackAlreadyExistsException
import com.contenido.global.exception.MannerFeedbackBeforeEventEndedException
import com.contenido.global.exception.MannerFeedbackNotAllowedException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * PR146 — 사용자 매너 평가 작성 + 누적 요약 조회.
 *
 * 정책:
 *  - 이벤트가 끝난 뒤(endAt < now) 에만 작성 가능 — [MannerFeedbackBeforeEventEndedException].
 *  - reviewer 와 reviewee 는 한 명이 host(channel.owner), 다른 한 명이 APPROVED 참가자여야 한다.
 *    (양쪽 다 host 거나, 양쪽 다 참가자, 또는 본인-자기 평가는 [MannerFeedbackNotAllowedException].)
 *  - 같은 (reviewer, reviewee, event) 1회만 — DB UNIQUE + 사전 가드.
 *  - 공개 응답 ([getSummary]) 은 누적 3건 미만이면 null 반환.
 *
 * tag 운영 정의는 frontend 가 고정한다 — 본 service 는 tag 의미를 검증하지 않고 그대로 저장/집계한다.
 */
@Service
@Transactional(readOnly = true)
class MannerFeedbackService(
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository,
    private val participationRepository: EventParticipationRepository,
    private val feedbackRepository: MannerFeedbackRepository,
) {

    companion object {
        /** 공개 응답을 만들기 위한 최소 누적 평가 수. 그 미만이면 [getSummary] null. */
        const val MIN_PUBLIC_COUNT = 3L

        /** [MannerSummaryResponse.topTags] 가 보여줄 최대 태그 수. */
        const val TOP_TAG_LIMIT = 3
    }

    @Transactional
    fun create(
        reviewerId: Long,
        eventId: Long,
        request: CreateMannerFeedbackRequest,
    ): MannerFeedback {
        val reviewer = userRepository.findById(reviewerId).orElseThrow { UserNotFoundException() }
        val event = eventRepository.findById(eventId).orElseThrow { EventNotFoundException() }
        val reviewee = userRepository.findById(request.revieweeId)
            .orElseThrow { UserNotFoundException() }

        // 본인-자기 평가 거부.
        if (reviewerId == request.revieweeId) throw MannerFeedbackNotAllowedException()

        // 이벤트 종료 가드 — endAt 기준.
        if (!event.endAt.isBefore(LocalDateTime.now())) {
            throw MannerFeedbackBeforeEventEndedException()
        }

        // 권한 가드: 한 명은 host, 다른 한 명은 APPROVED 참가자.
        ensureHostParticipantPair(reviewer, reviewee, event)

        // 중복 가드.
        if (feedbackRepository.existsByReviewerIdAndRevieweeIdAndEventId(
                reviewerId, request.revieweeId, eventId,
            )) {
            throw MannerFeedbackAlreadyExistsException()
        }

        return feedbackRepository.save(
            MannerFeedback(
                reviewer = reviewer,
                reviewee = reviewee,
                event = event,
                rating = request.rating,
                tags = request.tags.distinct().take(20),
                comment = request.comment?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
    }

    /**
     * 사용자별 매너 요약. 누적 3건 미만이면 null — 신규 사용자가 1-2건의 평가로 노출되어 부담을
     * 주지 않게 하는 안전 장치.
     */
    fun getSummary(userId: Long): MannerSummaryResponse? {
        val count = feedbackRepository.countByRevieweeId(userId)
        if (count < MIN_PUBLIC_COUNT) return null
        val avg = feedbackRepository.averageRatingByRevieweeId(userId) ?: return null
        val topTags = feedbackRepository.findByRevieweeId(userId)
            .flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_TAG_LIMIT)
            .map { it.key }

        return MannerSummaryResponse(
            userId = userId,
            averageRating = avg,
            count = count,
            topTags = topTags,
        )
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    /**
     * 한 명은 event.channel.owner (host), 다른 한 명은 APPROVED 참가자임을 검증.
     * 양쪽 다 host 거나 양쪽 다 참가자인 경우, 또는 어느 쪽도 host/참가자가 아닌 경우는 모두 forbidden.
     */
    private fun ensureHostParticipantPair(reviewer: User, reviewee: User, event: Event) {
        val hostId = event.channel.owner.id

        val reviewerIsHost = reviewer.id == hostId
        val revieweeIsHost = reviewee.id == hostId

        val reviewerIsParticipant = participationRepository.existsByEventAndParticipantAndStatusIn(
            event, reviewer, listOf(ParticipationStatus.APPROVED),
        )
        val revieweeIsParticipant = participationRepository.existsByEventAndParticipantAndStatusIn(
            event, reviewee, listOf(ParticipationStatus.APPROVED),
        )

        val validPair = (reviewerIsHost && revieweeIsParticipant) ||
            (revieweeIsHost && reviewerIsParticipant)

        if (!validPair) throw MannerFeedbackNotAllowedException()
    }
}
