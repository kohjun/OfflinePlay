package com.contenido.domain.user.service

import com.contenido.domain.auth.service.AuthService
import com.contenido.domain.user.dto.ChangePasswordRequest
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.InvalidCredentialsException
import com.contenido.global.exception.UserNotFoundException
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockKExtension::class)
class UserServiceTest {

    @MockK lateinit var userRepository: UserRepository
    @MockK lateinit var passwordEncoder: PasswordEncoder
    @MockK lateinit var authService: AuthService

    private lateinit var service: UserService

    @BeforeEach
    fun setUp() {
        service = UserService(userRepository, passwordEncoder, authService)
        every { authService.logout(any()) } just Runs
    }

    @Test
    fun `changePassword 성공 시 새 비밀번호 저장 + authService logout 호출`() {
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { passwordEncoder.matches("old-pw", "encoded-old") } returns true
        every { passwordEncoder.encode("new-pw1!") } returns "encoded-new"

        service.changePassword(userId = 1L, request = ChangePasswordRequest("old-pw", "new-pw1!"))

        assertThat(user.password).isEqualTo("encoded-new")
        verify(exactly = 1) { authService.logout(1L) }
    }

    @Test
    fun `changePassword 현재 비밀번호가 틀리면 InvalidCredentialsException + logout 호출 없음`() {
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { passwordEncoder.matches("wrong-pw", "encoded-old") } returns false

        assertThrows<InvalidCredentialsException> {
            service.changePassword(userId = 1L, request = ChangePasswordRequest("wrong-pw", "new-pw1!"))
        }
        verify(exactly = 0) { authService.logout(any()) }
        // 비밀번호도 그대로.
        assertThat(user.password).isEqualTo("encoded-old")
    }

    @Test
    fun `changePassword 존재하지 않는 사용자면 UserNotFoundException + logout 호출 없음`() {
        every { userRepository.findById(404L) } returns Optional.empty()

        assertThrows<UserNotFoundException> {
            service.changePassword(userId = 404L, request = ChangePasswordRequest("a", "b1234567"))
        }
        verify(exactly = 0) { authService.logout(any()) }
    }

    @Test
    fun `changePassword 탈퇴한 사용자면 DeletedUserException + logout 호출 없음`() {
        val user = createUser(id = 1L).also { it.softDelete() }
        every { userRepository.findById(1L) } returns Optional.of(user)

        assertThrows<DeletedUserException> {
            service.changePassword(userId = 1L, request = ChangePasswordRequest("a", "b1234567"))
        }
        verify(exactly = 0) { authService.logout(any()) }
    }

    companion object {
        fun createUser(id: Long, role: UserRole = UserRole.PARTICIPANT): User {
            val u = User("u$id@test.com", "encoded-old", "user$id", "01012345678").apply { updateRole(role) }
            ReflectionTestUtils.setField(u, "id", id)
            ReflectionTestUtils.setField(u, "createdAt", LocalDateTime.now())
            ReflectionTestUtils.setField(u, "updatedAt", LocalDateTime.now())
            return u
        }
    }
}
