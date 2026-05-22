package com.contenido.domain.user.service

import com.contenido.domain.user.dto.MyProfileResponse
import com.contenido.domain.user.dto.PublicProfileResponse
import com.contenido.domain.user.dto.UpdateMyProfileRequest
import com.contenido.domain.user.entity.ProfileVisibility
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserProfile
import com.contenido.domain.user.repository.UserProfileRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.DeletedUserException
import com.contenido.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR144 — 공개 프로필 조회 + 본인 확장 프로필 조회/갱신.
 *
 * 정책:
 *  - row 가 없는 사용자는 visibility=PUBLIC + 모든 필드 null 인 응답 (lazy create — 첫 PATCH 시점에만
 *    row 생성).
 *  - 공개 프로필은 deleted user / PRIVATE 가시성에서 민감 필드 제거.
 *  - PATCH 는 모든 필드 null 이면 no-op (현재 응답 그대로 반환).
 *
 * interests / region_code (PR147) 와 manner summary (PR146) 는 본 PR 범위 밖 — 후속 PR 이 응답에 추가.
 */
@Service
@Transactional(readOnly = true)
class UserProfileService(
    private val userRepository: UserRepository,
    private val profileRepository: UserProfileRepository,
) {

    fun getPublicProfile(userId: Long): PublicProfileResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw UserNotFoundException()
        val profile = profileRepository.findByUserId(userId)
        return toPublicResponse(user, profile)
    }

    fun getMyProfile(userId: Long): MyProfileResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        val profile = profileRepository.findByUserId(userId)
        return toMyResponse(user, profile)
    }

    @Transactional
    fun updateMyProfile(userId: Long, request: UpdateMyProfileRequest): MyProfileResponse {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()

        if (request.isNoop()) {
            // 어떤 필드도 들어오지 않은 호출은 새 row 도 만들지 않는다 — 멱등.
            return toMyResponse(user, profileRepository.findByUserId(userId))
        }

        val profile = profileRepository.findByUser(user)
            ?: profileRepository.save(UserProfile(user = user))

        request.bio?.let { profile.bio = it.ifBlank { null } }
        request.avatarUrl?.let { profile.avatarUrl = it.ifBlank { null } }
        request.regionSido?.let { profile.regionSido = it.ifBlank { null } }
        request.regionSigungu?.let { profile.regionSigungu = it.ifBlank { null } }
        request.visibility?.let { profile.visibility = it }

        return toMyResponse(user, profile)
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private fun toPublicResponse(user: User, profile: UserProfile?): PublicProfileResponse {
        val visibility = profile?.visibility ?: ProfileVisibility.PUBLIC
        val isPrivate = visibility == ProfileVisibility.PRIVATE
        return PublicProfileResponse(
            userId = user.id,
            nickname = user.nickname,
            role = user.role,
            avatarUrl = profile?.avatarUrl?.takeUnless { isPrivate },
            bio = profile?.bio?.takeUnless { isPrivate },
            regionSido = profile?.regionSido?.takeUnless { isPrivate },
            regionSigungu = profile?.regionSigungu?.takeUnless { isPrivate },
            visibility = visibility,
            joinedAt = user.createdAt,
        )
    }

    private fun toMyResponse(user: User, profile: UserProfile?): MyProfileResponse =
        MyProfileResponse(
            userId = user.id,
            nickname = user.nickname,
            role = user.role,
            avatarUrl = profile?.avatarUrl,
            bio = profile?.bio,
            regionSido = profile?.regionSido,
            regionSigungu = profile?.regionSigungu,
            visibility = profile?.visibility ?: ProfileVisibility.PUBLIC,
            joinedAt = user.createdAt,
            updatedAt = profile?.updatedAt,
        )
}
