package com.contenido.domain.recommendation.service

import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.event.dto.EventResponse
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interest.repository.EventInterestRepository
import com.contenido.domain.interest.repository.UserInterestRepository
import com.contenido.domain.recommendation.dto.RecommendationSegment
import com.contenido.domain.recommendation.dto.RecommendationsResponse
import com.contenido.domain.recommendation.dto.RecommendedEventResponse
import com.contenido.domain.review.repository.ReviewRepository
import com.contenido.domain.user.repository.UserProfileRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.math.max

/**
 * PR148 — 개인화 추천 + 비로그인 fallback segment.
 *
 * 정책:
 *  - 후보 풀: hidden=false, status != CLOSED, 정원 여유, startAt >= now. LIMIT = candidateLimit (default 100).
 *  - 로그인: 가중치 score = `interestMatch*3 + regionMatch*2 + subscribedChannelBoost*2 + recency*1.5 + ratingBoost*1`.
 *  - 비로그인 / 매칭 0건: segment fallback (POPULAR / CLOSING_SOON / LATEST).
 *  - 정렬 안정성: `ORDER BY score DESC, id DESC` (in-memory) — 동률 시 id 큰 쪽이 먼저 (최근 생성 우선).
 */
@Service
@Transactional(readOnly = true)
class RecommendationService(
    private val eventRepository: EventRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userInterestRepository: UserInterestRepository,
    private val eventInterestRepository: EventInterestRepository,
    private val channelSubscriptionRepository: ChannelSubscriptionRepository,
    private val reviewRepository: ReviewRepository,
) {

    companion object {
        const val DEFAULT_CANDIDATE_LIMIT = 100
        const val DEFAULT_RESULT_SIZE = 20

        // 가중치 — score 계산식. PR149 에서 reason chip 우선순위에도 같은 순서.
        const val WEIGHT_INTEREST = 3.0
        const val WEIGHT_REGION = 2.0
        const val WEIGHT_SUBSCRIBED = 2.0
        const val WEIGHT_RECENCY = 1.5
        const val WEIGHT_RATING = 1.0
    }

    /**
     * 추천 entry point.
     *  - [userId] null → 비로그인. [RecommendationSegment.POPULAR] 로 fallback.
     *  - [segment] null + userId 존재 → RECOMMENDED (가중치 score).
     *  - [segment] 명시 → 해당 segment 만 fallback 정렬.
     */
    fun recommend(
        userId: Long?,
        segment: RecommendationSegment? = null,
        size: Int = DEFAULT_RESULT_SIZE,
    ): RecommendationsResponse {
        val now = LocalDateTime.now()
        val effectiveSize = size.coerceIn(1, 50)
        val candidateLimit = max(DEFAULT_CANDIDATE_LIMIT, effectiveSize * 2)
        val candidates = eventRepository.findRecommendationCandidates(
            now = now,
            pageable = PageRequest.of(0, candidateLimit),
        ).content

        // segment 명시 또는 비로그인 → fallback 분기. 로그인 + segment=null → RECOMMENDED.
        val actualSegment = segment ?: if (userId != null) RecommendationSegment.RECOMMENDED
        else RecommendationSegment.POPULAR

        return when (actualSegment) {
            RecommendationSegment.RECOMMENDED -> recommended(userId, candidates, effectiveSize, now)
            RecommendationSegment.POPULAR -> popular(candidates, effectiveSize)
            RecommendationSegment.CLOSING_SOON -> closingSoon(candidates, effectiveSize, now)
            RecommendationSegment.LATEST -> latest(candidates, effectiveSize)
        }
    }

    // ─── segment 별 정렬 ────────────────────────────────────────────────────────

    private fun recommended(
        userId: Long?,
        candidates: List<Event>,
        size: Int,
        now: LocalDateTime,
    ): RecommendationsResponse {
        if (userId == null || candidates.isEmpty()) {
            return popular(candidates, size)
        }
        val userInterestIds = userInterestRepository.findByUserId(userId)
            .map { it.interestId }
            .toSet()
        val userRegionCode = userProfileRepository.findByUserId(userId)?.region?.code
        val subscribedChannelIds = channelSubscriptionRepository.findBySubscriberId(userId)
            .map { it.channel.id }
            .toSet()
        val eventInterests = eventInterestRepository.findByEventIdIn(candidates.map { it.id })
            .groupBy({ it.eventId }, { it.interestId })
        val ratingMap = if (candidates.isEmpty()) emptyMap()
        else reviewRepository.aggregateByEventIds(candidates.map { it.id }).associate { row ->
            (row[0] as Number).toLong() to ((row[1] as? Number)?.toDouble() ?: 0.0)
        }

        val scored = candidates.map { event ->
            val reasons = mutableListOf<String>()
            var score = 0.0

            // interestMatch — 카운트만큼 가중. 매칭이 한 건이라도 있으면 reason 표기.
            val ei = eventInterests[event.id]?.toSet() ?: emptySet()
            val matched = ei.intersect(userInterestIds)
            if (matched.isNotEmpty()) {
                score += WEIGHT_INTEREST * matched.size
                reasons += "INTEREST_MATCH"
            }

            // regionMatch — 시군구(5자리) 정확 매칭 또는 시도(2자리) 매칭.
            val eventRegionCode = event.region?.code
            if (eventRegionCode != null && userRegionCode != null) {
                val sameExact = eventRegionCode == userRegionCode
                val sameSido = eventRegionCode.take(2) == userRegionCode.take(2)
                if (sameExact) {
                    score += WEIGHT_REGION
                    reasons += "NEAR_YOU"
                } else if (sameSido) {
                    score += WEIGHT_REGION * 0.5
                    reasons += "NEAR_YOU"
                }
            }

            // subscribedChannelBoost
            if (event.channel.id in subscribedChannelIds) {
                score += WEIGHT_SUBSCRIBED
                reasons += "SUBSCRIBED_CHANNEL"
            }

            // recency — startAt 이 7일 이내일수록 가산.
            val daysToStart = java.time.Duration.between(now, event.startAt).toDays().coerceAtLeast(0)
            if (daysToStart <= 7L) {
                val w = WEIGHT_RECENCY * (1.0 - (daysToStart / 7.0))
                if (w > 0) {
                    score += w
                    reasons += "CLOSING_SOON"
                }
            }

            // ratingBoost — 평균 4.0 이상에 가산.
            val rating = ratingMap[event.id] ?: 0.0
            if (rating >= 4.0) {
                score += WEIGHT_RATING * ((rating - 4.0) / 1.0)
                reasons += "TOP_RATED"
            }

            scoredItem(event, score, reasons, ratingMap)
        }

        // score 0 인 row 만 남는다면 popular fallback. 그 외는 score desc + id desc.
        val nonZero = scored.filter { it.score > 0.0 }
        if (nonZero.isEmpty()) return popular(candidates, size)

        val items = nonZero
            .sortedWith(compareByDescending<RecommendedEventResponse> { it.score }.thenByDescending { it.event.id })
            .take(size)
        return RecommendationsResponse(
            segment = RecommendationSegment.RECOMMENDED.name,
            items = items,
        )
    }

    private fun popular(candidates: List<Event>, size: Int): RecommendationsResponse {
        // popular 의 단순 정의: currentParticipants desc + id desc. 후속에 channel subscriber count 등을 합칠 수 있음.
        val ratingMap = if (candidates.isEmpty()) emptyMap()
        else reviewRepository.aggregateByEventIds(candidates.map { it.id }).associate { row ->
            (row[0] as Number).toLong() to ((row[1] as? Number)?.toDouble() ?: 0.0)
        }
        val items = candidates
            .sortedWith(compareByDescending<Event> { it.currentParticipants }.thenByDescending { it.id })
            .take(size)
            .map { scoredItem(it, it.currentParticipants.toDouble(), listOf("POPULAR"), ratingMap) }
        return RecommendationsResponse(segment = RecommendationSegment.POPULAR.name, items = items)
    }

    private fun closingSoon(
        candidates: List<Event>,
        size: Int,
        now: LocalDateTime,
    ): RecommendationsResponse {
        val ratingMap = if (candidates.isEmpty()) emptyMap()
        else reviewRepository.aggregateByEventIds(candidates.map { it.id }).associate { row ->
            (row[0] as Number).toLong() to ((row[1] as? Number)?.toDouble() ?: 0.0)
        }
        val items = candidates
            .sortedWith(compareBy<Event> { it.startAt }.thenByDescending { it.id })
            .take(size)
            .map {
                val daysToStart = java.time.Duration.between(now, it.startAt).toDays().coerceAtLeast(0)
                // 가까울수록 점수 높음 — 단순 7일 normalize.
                val score = (7.0 - daysToStart.coerceAtMost(7L)).coerceAtLeast(0.0)
                scoredItem(it, score, listOf("CLOSING_SOON"), ratingMap)
            }
        return RecommendationsResponse(segment = RecommendationSegment.CLOSING_SOON.name, items = items)
    }

    private fun latest(candidates: List<Event>, size: Int): RecommendationsResponse {
        val ratingMap = if (candidates.isEmpty()) emptyMap()
        else reviewRepository.aggregateByEventIds(candidates.map { it.id }).associate { row ->
            (row[0] as Number).toLong() to ((row[1] as? Number)?.toDouble() ?: 0.0)
        }
        val items = candidates
            .sortedWith(compareByDescending<Event> { it.createdAt }.thenByDescending { it.id })
            .take(size)
            .map { scoredItem(it, it.id.toDouble(), listOf("LATEST"), ratingMap) }
        return RecommendationsResponse(segment = RecommendationSegment.LATEST.name, items = items)
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun scoredItem(
        event: Event,
        score: Double,
        reasons: List<String>,
        ratingMap: Map<Long, Double>,
    ): RecommendedEventResponse {
        val rating = ratingMap[event.id]
        return RecommendedEventResponse(
            event = EventResponse(
                id = event.id,
                channelId = event.channel.id,
                channelName = event.channel.name,
                channelOwnerId = event.channel.owner.id,
                title = event.title,
                description = event.description,
                location = event.location,
                mainImageUrl = event.mainImageUrl,
                startAt = event.startAt,
                endAt = event.endAt,
                maxParticipants = event.maxParticipants,
                currentParticipants = event.currentParticipants,
                participationFee = event.participationFee,
                refundPolicy = event.refundPolicy,
                detailContent = event.detailContent,
                status = event.status,
                contentType = event.contentType,
                createdAt = event.createdAt,
                averageRating = rating?.takeIf { it > 0.0 },
                reviewCount = 0L,
            ),
            score = score,
            reasonCodes = reasons,
        )
    }
}
