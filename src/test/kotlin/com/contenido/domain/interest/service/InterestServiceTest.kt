package com.contenido.domain.interest.service

import com.contenido.domain.interest.dto.UpdateMyInterestsRequest
import com.contenido.domain.interest.entity.Interest
import com.contenido.domain.interest.entity.UserInterest
import com.contenido.domain.interest.repository.InterestRepository
import com.contenido.domain.interest.repository.UserInterestRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UserNotFoundException
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

/**
 * PR147 — InterestService 단위 테스트.
 *  - listAll: 카탈로그 정렬
 *  - updateMine: set semantics — toAdd / toRemove delta 만 처리
 *  - 잘못된 interest id 무시
 *  - 빈 리스트 (전부 해제)
 */
@ExtendWith(MockKExtension::class)
class InterestServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var interestRepository: InterestRepository
    @MockK lateinit var userInterestRepository: UserInterestRepository

    private lateinit var service: InterestService

    @BeforeEach
    fun setUp() {
        service = InterestService(userRepository, interestRepository, userInterestRepository)
    }

    @Test
    fun `listAll — repository 가 정렬된 결과를 그대로 응답`() {
        every { interestRepository.findAllByOrderByCategoryAscDisplayOrderAsc() } returns listOf(
            interest(1L, "HIKING", "등산", "ACTIVITY", 10),
            interest(2L, "MOVIE", "영화", "CULTURE", 10),
        )

        val response = service.listAll()

        assertThat(response).hasSize(2)
        assertThat(response[0].slug).isEqualTo("HIKING")
        assertThat(response[1].slug).isEqualTo("MOVIE")
    }

    @Test
    fun `updateMine — toAdd 만 save, toRemove 만 delete`() {
        val user = user(1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        // 카탈로그에서 들어온 id 모두 valid 라고 가정.
        every { interestRepository.findByIdIn(listOf(10L, 20L, 30L)) } returns listOf(
            interest(10L), interest(20L), interest(30L),
        )
        // 현재 사용자가 가진 관심사: 20, 99 (99 는 valid 카탈로그에서 제거됨, delete 대상)
        every { userInterestRepository.findByUserId(1L) } returnsMany listOf(
            // 첫 호출 (delta 계산용)
            listOf(
                UserInterest(userId = 1L, interestId = 20L),
                UserInterest(userId = 1L, interestId = 99L),
            ),
            // 두 번째 호출 (listMine 응답)
            listOf(
                UserInterest(userId = 1L, interestId = 10L),
                UserInterest(userId = 1L, interestId = 20L),
                UserInterest(userId = 1L, interestId = 30L),
            ),
        )
        // delta listMine 의 두 번째 호출에서 id → catalog 매핑.
        every { interestRepository.findByIdIn(listOf(10L, 20L, 30L)) } returns listOf(
            interest(10L), interest(20L), interest(30L),
        )

        val deletedSlot = slot<Collection<Long>>()
        every {
            userInterestRepository.deleteByUserIdAndInterestIdIn(1L, capture(deletedSlot))
        } returns 1
        val savedSlot = slot<List<UserInterest>>()
        every { userInterestRepository.saveAll(capture(savedSlot)) } answers { savedSlot.captured }

        val response = service.updateMine(1L, UpdateMyInterestsRequest(listOf(10L, 20L, 30L)))

        // 99 는 삭제, 10/30 은 추가, 20 은 그대로.
        assertThat(deletedSlot.captured).containsExactly(99L)
        assertThat(savedSlot.captured.map { it.interestId }).containsExactlyInAnyOrder(10L, 30L)
        assertThat(response.map { it.id }).containsExactlyInAnyOrder(10L, 20L, 30L)
    }

    @Test
    fun `updateMine — 빈 리스트는 현재 관심사 모두 해제`() {
        val user = user(1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userInterestRepository.findByUserId(1L) } returnsMany listOf(
            listOf(UserInterest(userId = 1L, interestId = 10L), UserInterest(userId = 1L, interestId = 20L)),
            emptyList(),
        )
        val deletedSlot = slot<Collection<Long>>()
        every {
            userInterestRepository.deleteByUserIdAndInterestIdIn(1L, capture(deletedSlot))
        } returns 2

        val response = service.updateMine(1L, UpdateMyInterestsRequest(emptyList()))

        assertThat(deletedSlot.captured).containsExactlyInAnyOrder(10L, 20L)
        assertThat(response).isEmpty()
        verify(exactly = 0) { userInterestRepository.saveAll(any<Collection<UserInterest>>()) }
    }

    @Test
    fun `updateMine — 잘못된 id 는 silently 무시`() {
        val user = user(1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        // 들어온 [10, 999] 중 10 만 카탈로그에 존재.
        every { interestRepository.findByIdIn(listOf(10L, 999L)) } returns listOf(interest(10L))
        every { userInterestRepository.findByUserId(1L) } returnsMany listOf(
            emptyList(),
            listOf(UserInterest(userId = 1L, interestId = 10L)),
        )
        every { interestRepository.findByIdIn(listOf(10L)) } returns listOf(interest(10L))
        every { userInterestRepository.saveAll(any<Collection<UserInterest>>()) } answers { firstArg<List<UserInterest>>() }

        val response = service.updateMine(1L, UpdateMyInterestsRequest(listOf(10L, 999L)))

        assertThat(response.map { it.id }).containsExactly(10L)
        // 999 는 어디에도 저장되지 않는다 — relaxed 가 아닌 strict mock 이라 호출되면 실패.
    }

    @Test
    fun `미존재 사용자는 UserNotFoundException`() {
        every { userRepository.findById(99L) } returns Optional.empty()
        assertThatThrownBy { service.listMine(99L) }
            .isInstanceOf(UserNotFoundException::class.java)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun user(id: Long): User =
        User("u$id@test.com", "pwd", "닉네임$id", "01000000$id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now().minusDays(30))
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun interest(
        id: Long,
        slug: String = "I$id",
        label: String = "라벨$id",
        category: String = "ACTIVITY",
        displayOrder: Int = id.toInt(),
    ): Interest = Interest(slug = slug, label = label, category = category, displayOrder = displayOrder).apply {
        ReflectionTestUtils.setField(this, "id", id)
        ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now())
    }
}
