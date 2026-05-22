package com.contenido.domain.user.service

import com.contenido.domain.interest.repository.InterestRepository
import com.contenido.domain.interest.repository.UserInterestRepository
import com.contenido.domain.region.repository.RegionRepository
import com.contenido.domain.user.dto.UpdateMyProfileRequest
import com.contenido.domain.user.entity.ProfileVisibility
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserProfile
import com.contenido.domain.user.repository.UserProfileRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.UserNotFoundException
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
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
 * PR144 — UserProfileService 단위 테스트.
 *  - row 없는 사용자 응답 (visibility=PUBLIC + 모든 필드 null)
 *  - PRIVATE 가시성에서 공개 응답이 민감 필드 hide
 *  - PATCH 첫 호출 시 lazy create
 *  - 빈 문자열 → null (지움)
 *  - no-op 요청은 row 생성 안 함
 *  - deleted user / 미존재 사용자 가드
 */
@ExtendWith(MockKExtension::class)
class UserProfileServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var profileRepository: UserProfileRepository
    @MockK(relaxed = true) lateinit var regionRepository: RegionRepository
    @MockK(relaxed = true) lateinit var userInterestRepository: UserInterestRepository
    @MockK(relaxed = true) lateinit var interestRepository: InterestRepository

    private lateinit var service: UserProfileService

    @BeforeEach
    fun setUp() {
        service = UserProfileService(
            userRepository,
            profileRepository,
            regionRepository,
            userInterestRepository,
            interestRepository,
        )
        // PR147 — 기존 PR144 케이스들이 region/interest 를 사용하지 않으므로 relaxed mock 으로 빈 응답.
        every { userInterestRepository.findByUserId(any()) } returns emptyList()
        every { interestRepository.findByIdIn(any()) } returns emptyList()
    }

    @Test
    fun `getPublicProfile — row 없는 사용자는 모든 확장 필드가 null + visibility PUBLIC`() {
        val user = createUser(1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { profileRepository.findByUserId(1L) } returns null

        val response = service.getPublicProfile(1L)

        assertThat(response.userId).isEqualTo(1L)
        assertThat(response.nickname).isEqualTo(user.nickname)
        assertThat(response.bio).isNull()
        assertThat(response.avatarUrl).isNull()
        assertThat(response.regionSido).isNull()
        assertThat(response.visibility).isEqualTo(ProfileVisibility.PUBLIC)
    }

    @Test
    fun `getPublicProfile — PRIVATE 가시성은 bio avatar region 모두 숨김`() {
        val user = createUser(1L)
        val profile = profile(user) {
            bio = "hello"
            avatarUrl = "https://example.com/me.png"
            regionSido = "서울특별시"
            regionSigungu = "종로구"
            visibility = ProfileVisibility.PRIVATE
        }
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { profileRepository.findByUserId(1L) } returns profile

        val response = service.getPublicProfile(1L)

        assertThat(response.bio).isNull()
        assertThat(response.avatarUrl).isNull()
        assertThat(response.regionSido).isNull()
        assertThat(response.regionSigungu).isNull()
        assertThat(response.visibility).isEqualTo(ProfileVisibility.PRIVATE)
        // 식별 필드는 PRIVATE 라도 노출.
        assertThat(response.nickname).isEqualTo(user.nickname)
    }

    @Test
    fun `getPublicProfile — deleted user 는 UserNotFoundException`() {
        val user = createUser(1L).apply { softDelete() }
        every { userRepository.findById(1L) } returns Optional.of(user)

        assertThatThrownBy { service.getPublicProfile(1L) }
            .isInstanceOf(UserNotFoundException::class.java)
    }

    @Test
    fun `getPublicProfile — 존재하지 않는 사용자는 UserNotFoundException`() {
        every { userRepository.findById(99L) } returns Optional.empty()
        assertThatThrownBy { service.getPublicProfile(99L) }
            .isInstanceOf(UserNotFoundException::class.java)
    }

    @Test
    fun `getMyProfile — row 없는 사용자도 정상 응답 (lazy create 안 함)`() {
        val user = createUser(1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { profileRepository.findByUserId(1L) } returns null

        val response = service.getMyProfile(1L)

        assertThat(response.visibility).isEqualTo(ProfileVisibility.PUBLIC)
        assertThat(response.bio).isNull()
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `getMyProfile — deleted user 는 DeletedUserException`() {
        val user = createUser(1L).apply { softDelete() }
        every { userRepository.findById(1L) } returns Optional.of(user)

        assertThatThrownBy { service.getMyProfile(1L) }
            .isInstanceOf(DeletedUserException::class.java)
    }

    @Test
    fun `updateMyProfile — 첫 PATCH 시 lazy create + 필드 채워짐`() {
        val user = createUser(1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { profileRepository.findByUser(user) } returns null
        val saved = slot<UserProfile>()
        every { profileRepository.save(capture(saved)) } answers {
            val captured = saved.captured
            ReflectionTestUtils.setField(captured, "id", 10L)
            ReflectionTestUtils.setField(captured, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(captured, "updatedAt", LocalDateTime.now())
            captured
        }

        val response = service.updateMyProfile(
            1L,
            UpdateMyProfileRequest(
                bio = "안녕하세요",
                avatarUrl = "https://example.com/me.png",
                regionSido = "서울특별시",
                visibility = ProfileVisibility.MEMBERS,
            ),
        )

        assertThat(response.bio).isEqualTo("안녕하세요")
        assertThat(response.avatarUrl).isEqualTo("https://example.com/me.png")
        assertThat(response.regionSido).isEqualTo("서울특별시")
        assertThat(response.visibility).isEqualTo(ProfileVisibility.MEMBERS)
        assertThat(saved.captured.user.id).isEqualTo(1L)
    }

    @Test
    fun `updateMyProfile — 빈 문자열은 null 로 저장 (지움)`() {
        val user = createUser(1L)
        val existing = profile(user) { bio = "old"; avatarUrl = "old"; regionSido = "old" }
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { profileRepository.findByUser(user) } returns existing

        val response = service.updateMyProfile(
            1L,
            UpdateMyProfileRequest(bio = "", avatarUrl = "   ", regionSido = ""),
        )

        assertThat(response.bio).isNull()
        assertThat(response.avatarUrl).isNull()
        assertThat(response.regionSido).isNull()
        verify(exactly = 0) { profileRepository.save(any()) } // 기존 row 갱신만, 새 row 생성 안 함
    }

    @Test
    fun `updateMyProfile — 모든 필드 null 이면 no-op (lazy create 도 안 함)`() {
        val user = createUser(1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { profileRepository.findByUserId(1L) } returns null

        val response = service.updateMyProfile(1L, UpdateMyProfileRequest())

        assertThat(response.bio).isNull()
        verify(exactly = 0) { profileRepository.save(any()) }
        verify(exactly = 0) { profileRepository.findByUser(any()) }
    }

    @Test
    fun `updateMyProfile — null 필드는 기존 값 유지`() {
        val user = createUser(1L)
        val existing = profile(user) {
            bio = "keep"
            avatarUrl = "keep-avatar"
            visibility = ProfileVisibility.MEMBERS
        }
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { profileRepository.findByUser(user) } returns existing

        val response = service.updateMyProfile(
            1L,
            UpdateMyProfileRequest(regionSido = "부산광역시"),
        )

        assertThat(response.bio).isEqualTo("keep")
        assertThat(response.avatarUrl).isEqualTo("keep-avatar")
        assertThat(response.visibility).isEqualTo(ProfileVisibility.MEMBERS)
        assertThat(response.regionSido).isEqualTo("부산광역시")
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun createUser(id: Long): User =
        User("u$id@test.com", "pwd", "닉네임$id", "01000000$id").apply {
            ReflectionTestUtils.setField(this, "id", id)
            ReflectionTestUtils.setField(this, "createdAt", LocalDateTime.now().minusDays(30))
            ReflectionTestUtils.setField(this, "updatedAt", LocalDateTime.now())
        }

    private fun profile(user: User, init: UserProfile.() -> Unit = {}): UserProfile {
        val p = UserProfile(user = user).apply(init)
        ReflectionTestUtils.setField(p, "id", 50L)
        ReflectionTestUtils.setField(p, "createdAt", LocalDateTime.now().minusDays(1))
        ReflectionTestUtils.setField(p, "updatedAt", LocalDateTime.now())
        return p
    }
}
