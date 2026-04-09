package com.contenido.domain.user.dto

import com.contenido.domain.user.entity.UserRole
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class UserProfileResponse(
    val userId: Long,
    val email: String,
    val nickname: String,
    val phoneNumber: String,
    val role: UserRole,
    val createdAt: LocalDateTime,
)

data class UpdateProfileRequest(
    @field:Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
    val nickname: String? = null,

    @field:Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 10~11자리 숫자여야 합니다.")
    val phoneNumber: String? = null,
)

data class ChangePasswordRequest(
    val currentPassword: String,

    @field:Size(min = 8, max = 20, message = "비밀번호는 8~20자여야 합니다.")
    val newPassword: String,
)
