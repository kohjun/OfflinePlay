package com.contenido.domain.user.service

import com.contenido.domain.auth.service.AuthService
import com.contenido.domain.user.dto.ChangePasswordRequest
import com.contenido.domain.user.dto.UpdateProfileRequest
import com.contenido.domain.user.dto.UserProfileResponse
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.DuplicateNicknameException
import com.contenido.global.exception.InvalidCredentialsException
import com.contenido.global.exception.UserNotFoundException
import com.contenido.global.util.MaskingUtil
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authService: AuthService,
) {

    fun getMyProfile(userId: Long): UserProfileResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()

        return UserProfileResponse(
            userId = user.id,
            email = user.email,
            nickname = user.nickname,
            phoneNumber = MaskingUtil.maskPhoneNumber(user.phoneNumber),
            role = user.role,
            createdAt = user.createdAt,
        )
    }

    @Transactional
    fun updateProfile(userId: Long, request: UpdateProfileRequest): UserProfileResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()

        request.nickname?.let { newNickname ->
            if (newNickname != user.nickname && userRepository.existsByNickname(newNickname)) {
                throw DuplicateNicknameException()
            }
            user.nickname = newNickname
        }
        request.phoneNumber?.let { user.phoneNumber = it }

        return UserProfileResponse(
            userId = user.id,
            email = user.email,
            nickname = user.nickname,
            phoneNumber = MaskingUtil.maskPhoneNumber(user.phoneNumber),
            role = user.role,
            createdAt = user.createdAt,
        )
    }

    @Transactional
    fun changePassword(userId: Long, request: ChangePasswordRequest) {
        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()

        if (!passwordEncoder.matches(request.currentPassword, user.password)) {
            throw InvalidCredentialsException()
        }

        user.password = passwordEncoder.encode(request.newPassword)

        // 보안상 비밀번호 변경 직후 저장된 refresh token 을 무효화한다.
        // access token 은 만료 시까지 유효하지만 reissue 가 막혀 자연 종료된다.
        // 프론트는 성공 응답을 받으면 로그아웃 + 로그인 화면 이동을 수행한다.
        authService.logout(userId)
    }

    @Transactional
    fun deleteAccount(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()

        user.softDelete()
    }
}
