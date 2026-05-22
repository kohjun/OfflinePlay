package com.contenido.domain.user.dto

import com.contenido.domain.user.entity.ProfileVisibility
import com.contenido.domain.user.entity.UserRole
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * PR144 — 공개 프로필 / 본인 확장 프로필 DTO.
 *
 * 기존 [UserProfileResponse] (UserDtos.kt — 계정 정보 응답: email/phone/role/createdAt) 와 이름이
 * 겹치지 않도록 본 파일의 DTO 는 `Public*` / `My*` prefix 를 사용한다.
 */

/**
 * 공개 프로필 응답. visibility=PRIVATE 이면 [bio]/[avatarUrl]/[regionSido]/[regionSigungu] 모두 null.
 */
data class PublicProfileResponse(
    val userId: Long,
    val nickname: String,
    val role: UserRole,
    val avatarUrl: String?,
    val bio: String?,
    val regionSido: String?,
    val regionSigungu: String?,
    val visibility: ProfileVisibility,
    val joinedAt: LocalDateTime,
)

/**
 * 본인 확장 프로필 응답. private 가시성이라도 모든 필드를 그대로 반환한다.
 */
data class MyProfileResponse(
    val userId: Long,
    val nickname: String,
    val role: UserRole,
    val avatarUrl: String?,
    val bio: String?,
    val regionSido: String?,
    val regionSigungu: String?,
    val visibility: ProfileVisibility,
    val joinedAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
)

/**
 * 본인 확장 프로필 부분 갱신. null 필드는 변경하지 않는다.
 *
 *  - bio / avatarUrl : 빈 문자열을 보내면 "지움" (null 저장). null 자체는 변경 없음.
 *  - regionSido / regionSigungu : 위와 동일.
 *  - visibility : null 이면 변경 없음. 값이 들어오면 enum 으로 변환되며 잘못된 값은 400.
 */
data class UpdateMyProfileRequest(
    @field:Size(max = 500)
    val bio: String? = null,

    @field:Size(max = 500)
    val avatarUrl: String? = null,

    @field:Size(max = 50)
    val regionSido: String? = null,

    @field:Size(max = 50)
    val regionSigungu: String? = null,

    val visibility: ProfileVisibility? = null,
) {
    /** 어떤 필드도 들어오지 않은 경우 — service 가 일찍 return 할 수 있게 도와준다. */
    fun isNoop(): Boolean =
        bio == null && avatarUrl == null && regionSido == null && regionSigungu == null && visibility == null
}
