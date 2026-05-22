package com.contenido.domain.recommendation.dto

import com.contenido.domain.event.dto.EventResponse

/**
 * PR148 — 추천 응답의 단일 row. EventResponse 위에 reasonCodes 만 추가.
 *  - reasonCodes 는 우선순위 desc 순서로 정렬. frontend 가 상위 1-2개만 chip 으로 노출 (PR149).
 *  - 비로그인 fallback 응답에서는 reasonCodes 가 SEGMENT 기반 (POPULAR / CLOSING_SOON / LATEST).
 */
data class RecommendedEventResponse(
    val event: EventResponse,
    val score: Double,
    val reasonCodes: List<String>,
)

/**
 * PR148 — 추천 응답. segment 메타데이터 + items.
 */
data class RecommendationsResponse(
    val segment: String,
    val items: List<RecommendedEventResponse>,
)

/** PR148 — segment 식별자. 비로그인 fallback 도 같은 응답 shape 를 쓴다. */
enum class RecommendationSegment {
    /** 로그인 사용자의 가중치 score 정렬. */
    RECOMMENDED,

    /** 인기 (subscriber/participant base 가 큰 채널의 이벤트). 비로그인 default. */
    POPULAR,

    /** 종료 임박. */
    CLOSING_SOON,

    /** 최신 등록. */
    LATEST,
}
