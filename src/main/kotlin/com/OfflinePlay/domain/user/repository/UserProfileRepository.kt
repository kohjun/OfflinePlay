package com.contenido.domain.user.repository

import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserProfile
import org.springframework.data.jpa.repository.JpaRepository

/**
 * PR144 — 사용자 확장 프로필 저장소.
 *  - 1:1 by user_id UNIQUE. lazy create 정책이라 Service 가 null 응답을 직접 처리.
 */
interface UserProfileRepository : JpaRepository<UserProfile, Long> {

    fun findByUser(user: User): UserProfile?

    fun findByUserId(userId: Long): UserProfile?
}
