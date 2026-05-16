package com.contenido.domain.search.service

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 인기 검색어 ranking — Redis sorted set 으로 keyword → 누적 검색 횟수 를 관리한다.
 *
 * 데이터 구조:
 *  - key   : "search:popular:7d"  (rolling 7-day window — 첫 trigger 시 TTL 7d 부여)
 *  - score : 누적 검색 횟수
 *  - member: 정규화된 keyword (소문자 + trim, 길이 1..40 클램프, 비ASCII 허용)
 *
 * 노트:
 *  - "rolling" 은 sorted set 단일 키 + TTL 로 근사한다. 정확한 슬라이딩 윈도우 (예: 시간대별 버킷
 *    합산) 는 트래픽이 늘면 별도 PR 에서. 본 MVP 는 한 키가 만료되면 자연히 ranking 이 초기화된다.
 *  - 동시성: ZINCRBY 는 원자적이라 별도 락 불필요.
 *  - Redis 연결 실패 시 호출자(ExploreService) 가 runCatching 으로 swallow — 검색 흐름은 멈추지 않는다.
 *  - 빈 키워드/너무 긴 키워드는 record 단계에서 잘라낸다 (DoS / 쓰레기 데이터 방어).
 */
@Service
class PopularSearchService(
    private val redisTemplate: RedisTemplate<String, String>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val KEY = "search:popular:7d"
        val TTL: Duration = Duration.ofDays(7)
        const val MAX_KEYWORD_LENGTH = 40
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
    }

    /**
     * 키워드 검색 1회를 기록. ZINCRBY 1. 첫 호출에서 TTL 도 함께 부여한다.
     * 정규화: trim → lowercase → MAX_KEYWORD_LENGTH 자 자르기.
     */
    fun recordKeyword(rawKeyword: String) {
        val normalized = normalize(rawKeyword) ?: return
        val zset = redisTemplate.opsForZSet()
        val newScore = zset.incrementScore(KEY, normalized, 1.0)
        // 첫 트리거에서만 TTL 부여 — 이미 TTL 이 있는 키에 expire 를 다시 걸지 않기 위해 score==1 일 때만.
        if (newScore != null && newScore <= 1.0) {
            redisTemplate.expire(KEY, TTL)
        }
    }

    /**
     * 상위 [limit] 개 키워드. 점수 내림차순.
     * limit < 1 이면 [DEFAULT_LIMIT], > [MAX_LIMIT] 이면 [MAX_LIMIT] 으로 clamp.
     */
    fun topKeywords(limit: Int = DEFAULT_LIMIT): List<PopularKeyword> {
        val clamped = limit.coerceIn(1, MAX_LIMIT).toLong()
        val tuples = redisTemplate.opsForZSet().reverseRangeWithScores(KEY, 0, clamped - 1)
            ?: return emptyList()
        return tuples.mapNotNull { t ->
            val value = t.value ?: return@mapNotNull null
            PopularKeyword(keyword = value, score = (t.score ?: 0.0).toLong())
        }
    }

    /**
     * 운영 도구 / 테스트용 — 특정 keyword 의 score 초기화. 평소엔 호출하지 않는다.
     */
    fun resetKeyword(keyword: String) {
        val normalized = normalize(keyword) ?: return
        redisTemplate.opsForZSet().remove(KEY, normalized)
    }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lowered = trimmed.lowercase()
        return if (lowered.length > MAX_KEYWORD_LENGTH) lowered.substring(0, MAX_KEYWORD_LENGTH) else lowered
    }

    data class PopularKeyword(val keyword: String, val score: Long)
}
