package com.contenido.domain.user.controller

import com.contenido.domain.user.dto.ChangePasswordRequest
import com.contenido.domain.user.dto.UpdateProfileRequest
import com.contenido.domain.user.dto.UserProfileResponse
import com.contenido.domain.user.service.UserService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
) {

    @GetMapping("/me")
    fun getMyProfile(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<UserProfileResponse> {
        return ApiResponse.ok(userService.getMyProfile(userId))
    }

    @PatchMapping("/me")
    fun updateProfile(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: UpdateProfileRequest,
    ): ApiResponse<UserProfileResponse> {
        return ApiResponse.ok(userService.updateProfile(userId, request), "프로필이 수정되었습니다.")
    }

    @PatchMapping("/me/password")
    fun changePassword(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ApiResponse<Nothing> {
        userService.changePassword(userId, request)
        return ApiResponse.ok("비밀번호가 변경되었습니다.")
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAccount(
        @AuthenticationPrincipal userId: Long,
    ) {
        userService.deleteAccount(userId)
    }
}
