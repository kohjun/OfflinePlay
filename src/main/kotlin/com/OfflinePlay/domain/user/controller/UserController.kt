package com.contenido.domain.user.controller

import com.contenido.domain.user.dto.ChangePasswordRequest
import com.contenido.domain.user.dto.MyProfileResponse
import com.contenido.domain.user.dto.PublicProfileResponse
import com.contenido.domain.user.dto.TrustSummaryResponse
import com.contenido.domain.user.dto.UpdateMyProfileRequest
import com.contenido.domain.user.dto.UpdateProfileRequest
import com.contenido.domain.user.dto.UserProfileResponse
import com.contenido.domain.user.service.TrustSummaryService
import com.contenido.domain.user.service.UserProfileService
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
    private val userProfileService: UserProfileService,
    private val trustSummaryService: TrustSummaryService,
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

    /**
     * PR144 — 공개 프로필 조회. visibility=PRIVATE 이면 nickname/role/joinedAt 만 반환.
     * 본 endpoint 는 인증 필수 (SecurityConfig 의 `anyRequest().authenticated()` 적용).
     */
    @GetMapping("/{userId}/profile")
    fun getPublicProfile(
        @PathVariable userId: Long,
    ): ApiResponse<PublicProfileResponse> {
        return ApiResponse.ok(userProfileService.getPublicProfile(userId))
    }

    /**
     * PR144 — 본인 확장 프로필 조회. private 가시성이라도 모든 필드 그대로 반환.
     * 기존 `GET /me` (계정 정보) 와 별도 — bio/avatar/region 같은 확장 필드만 다룬다.
     */
    @GetMapping("/me/profile")
    fun getMyExtendedProfile(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<MyProfileResponse> {
        return ApiResponse.ok(userProfileService.getMyProfile(userId))
    }

    /**
     * PR144 — 본인 확장 프로필 갱신. row 가 없으면 lazy create.
     *  - 빈 문자열 → null 저장 (지움)
     *  - null 필드 → 변경 없음
     *  - 모든 필드 null → no-op (row 생성도 안 함)
     */
    @PatchMapping("/me/profile")
    fun updateMyExtendedProfile(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: UpdateMyProfileRequest,
    ): ApiResponse<MyProfileResponse> {
        return ApiResponse.ok(
            userProfileService.updateMyProfile(userId, request),
            "프로필이 수정되었습니다.",
        )
    }

    /**
     * PR145 — 사용자 신뢰 요약. 기존 데이터(이벤트/참가/티켓/후기) 를 즉시 집계.
     * 누구나 조회 가능 (인증 필요). 캐시 없음.
     */
    @GetMapping("/{userId}/trust-summary")
    fun getTrustSummary(
        @PathVariable userId: Long,
    ): ApiResponse<TrustSummaryResponse> {
        return ApiResponse.ok(trustSummaryService.compute(userId))
    }
}
