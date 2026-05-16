package com.contenido.domain.search.service

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import java.time.Duration

@ExtendWith(MockKExtension::class)
class PopularSearchServiceTest {

    @MockK lateinit var redisTemplate: RedisTemplate<String, String>
    private lateinit var zset: ZSetOperations<String, String>
    private lateinit var service: PopularSearchService

    @BeforeEach
    fun setUp() {
        @Suppress("UNCHECKED_CAST")
        zset = mockk<ZSetOperations<String, String>>()
        every { redisTemplate.opsForZSet() } returns zset
        every { redisTemplate.expire(any<String>(), any<Duration>()) } returns true
        service = PopularSearchService(redisTemplate)
    }

    @Test
    fun `recordKeyword 정규화 후 ZINCRBY 1`() {
        every { zset.incrementScore(PopularSearchService.KEY, "주말 모임", 1.0) } returns 1.0

        service.recordKeyword("  주말 모임  ")

        verify(exactly = 1) { zset.incrementScore(PopularSearchService.KEY, "주말 모임", 1.0) }
        // 첫 호출(score=1.0) 이면 TTL 부여.
        verify(exactly = 1) { redisTemplate.expire(PopularSearchService.KEY, PopularSearchService.TTL) }
    }

    @Test
    fun `recordKeyword 두 번째부터는 expire 재호출 없이 score 만 올린다`() {
        every { zset.incrementScore(PopularSearchService.KEY, "주말 모임", 1.0) } returnsMany listOf(1.0, 2.0)

        service.recordKeyword("주말 모임")
        service.recordKeyword("주말 모임")

        verify(exactly = 2) { zset.incrementScore(PopularSearchService.KEY, "주말 모임", 1.0) }
        // 첫 호출 때만 expire 호출 — 두 번째는 score>1 이라 skip.
        verify(exactly = 1) { redisTemplate.expire(PopularSearchService.KEY, PopularSearchService.TTL) }
    }

    @Test
    fun `recordKeyword 대문자 키워드는 소문자로 정규화`() {
        every { zset.incrementScore(PopularSearchService.KEY, "running crew", 1.0) } returns 1.0

        service.recordKeyword("Running CREW")

        verify(exactly = 1) { zset.incrementScore(PopularSearchService.KEY, "running crew", 1.0) }
    }

    @Test
    fun `recordKeyword MAX 40자 초과는 자른다 (DoS 방어)`() {
        val longKeyword = "a".repeat(80)
        val expected = "a".repeat(40)
        every { zset.incrementScore(PopularSearchService.KEY, expected, 1.0) } returns 1.0

        service.recordKeyword(longKeyword)

        verify(exactly = 1) { zset.incrementScore(PopularSearchService.KEY, expected, 1.0) }
    }

    @Test
    fun `recordKeyword 빈 문자열은 무시 (incrementScore 호출 없음)`() {
        service.recordKeyword("")
        service.recordKeyword("   ")

        verify(exactly = 0) { zset.incrementScore(any(), any<String>(), any()) }
        verify(exactly = 0) { redisTemplate.expire(any<String>(), any<Duration>()) }
    }

    @Test
    fun `topKeywords 빈 결과면 빈 리스트`() {
        every { zset.reverseRangeWithScores(PopularSearchService.KEY, 0, 9) } returns null

        val result = service.topKeywords(10)

        assertThat(result).isEmpty()
    }

    @Test
    fun `topKeywords 점수 내림차순 정렬된 결과 반환 + score 는 Long 으로 변환`() {
        val t1 = mockTuple("주말 모임", 42.0)
        val t2 = mockTuple("러닝", 30.0)
        val t3 = mockTuple("와인", 15.0)
        every {
            zset.reverseRangeWithScores(PopularSearchService.KEY, 0, 9)
        } returns linkedSetOf(t1, t2, t3)

        val result = service.topKeywords(10)

        assertThat(result).hasSize(3)
        assertThat(result[0].keyword).isEqualTo("주말 모임")
        assertThat(result[0].score).isEqualTo(42L)
        assertThat(result[2].keyword).isEqualTo("와인")
        assertThat(result[2].score).isEqualTo(15L)
    }

    @Test
    fun `topKeywords limit 음수는 1로 limit 100은 MAX_LIMIT(50)으로 clamp`() {
        every { zset.reverseRangeWithScores(PopularSearchService.KEY, 0, any()) } returns emptySet()

        service.topKeywords(-5)
        service.topKeywords(100)

        // limit=-5 → 1 → 인덱스 0..0 (즉 0 0 으로 호출)
        verify(exactly = 1) { zset.reverseRangeWithScores(PopularSearchService.KEY, 0, 0) }
        // limit=100 → MAX_LIMIT(50) → 인덱스 0..49
        verify(exactly = 1) { zset.reverseRangeWithScores(PopularSearchService.KEY, 0, 49) }
    }

    private fun mockTuple(value: String, score: Double): ZSetOperations.TypedTuple<String> {
        val t = mockk<ZSetOperations.TypedTuple<String>>()
        every { t.value } returns value
        every { t.score } returns score
        return t
    }
}
