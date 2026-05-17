package com.contenido.domain.admin.service

import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.test.util.ReflectionTestUtils
import java.util.Optional

/**
 * PR69 — system actor service 동작 검증.
 *
 *  - email 로 lookup → 있으면 그대로 반환 (V9 seed 가 깔린 prod 경로).
 *  - 없으면 same constants 로 1회 생성 (test/local fallback). UNIQUE(email) 가 다중 생성 안전판.
 *  - role 은 PARTICIPANT — ADMIN 등 권한 격상 금지.
 *  - password 는 bcrypt 가 매칭 못 하는 sentinel.
 */
@ExtendWith(MockKExtension::class)
class SystemActorServiceTest {

    @MockK lateinit var userRepository: UserRepository

    private lateinit var service: SystemActorService

    @BeforeEach
    fun setUp() {
        service = SystemActorService(userRepository)
    }

    @Test
    fun `getSystemActor - V9 seed 가 있으면 그대로 반환 + save 호출 안 함`() {
        val existing = User(
            email = SystemActorService.SYSTEM_ACTOR_EMAIL,
            password = SystemActorService.SYSTEM_ACTOR_PASSWORD_PLACEHOLDER,
            nickname = SystemActorService.SYSTEM_ACTOR_NICKNAME,
            phoneNumber = SystemActorService.SYSTEM_ACTOR_PHONE,
        ).apply { ReflectionTestUtils.setField(this, "id", 1L) }
        every { userRepository.findByEmail(SystemActorService.SYSTEM_ACTOR_EMAIL) } returns
            Optional.of(existing)

        val result = service.getSystemActor()

        assertThat(result).isSameAs(existing)
        assertThat(result.nickname).isEqualTo("System")
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `getSystemActor - lookup 실패 시 동일 상수로 새로 생성 (fallback)`() {
        every { userRepository.findByEmail(SystemActorService.SYSTEM_ACTOR_EMAIL) } returns
            Optional.empty()
        val saved = slot<User>()
        every { userRepository.save(capture(saved)) } answers {
            saved.captured.also { ReflectionTestUtils.setField(it, "id", 42L) }
        }

        val result = service.getSystemActor()

        assertThat(result.id).isEqualTo(42L)
        assertThat(saved.captured.email).isEqualTo("system@contenido.local")
        assertThat(saved.captured.nickname).isEqualTo("System")
        assertThat(saved.captured.password).isEqualTo("__SYSTEM_ACTOR_NO_LOGIN__")
        assertThat(saved.captured.phoneNumber).isEqualTo("00000000000")
    }

    @Test
    fun `getSystemActor - 생성된 row 의 role 은 PARTICIPANT (ADMIN 격상 금지)`() {
        every { userRepository.findByEmail(SystemActorService.SYSTEM_ACTOR_EMAIL) } returns
            Optional.empty()
        val saved = slot<User>()
        every { userRepository.save(capture(saved)) } answers { saved.captured }

        service.getSystemActor()

        assertThat(saved.captured.role).isEqualTo(UserRole.PARTICIPANT)
    }

    @Test
    fun `getSystemActorId - 같은 actor 의 id 반환`() {
        val existing = User(
            email = SystemActorService.SYSTEM_ACTOR_EMAIL,
            password = SystemActorService.SYSTEM_ACTOR_PASSWORD_PLACEHOLDER,
            nickname = SystemActorService.SYSTEM_ACTOR_NICKNAME,
            phoneNumber = SystemActorService.SYSTEM_ACTOR_PHONE,
        ).apply { ReflectionTestUtils.setField(this, "id", 1L) }
        every { userRepository.findByEmail(any()) } returns Optional.of(existing)

        assertThat(service.getSystemActorId()).isEqualTo(1L)
    }
}
