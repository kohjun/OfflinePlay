package com.contenido.domain.notification.repository

import com.contenido.domain.notification.entity.UserPushSubscription
import com.contenido.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository

/**
 * PR139 — Web Push 구독 저장소.
 *
 *  - 발송 흐름(PR140) 은 `findByUserIdInAndEnabledTrue` 로 다수 사용자 활성 구독을 묶음 조회.
 *  - 같은 endpoint upsert 는 `findByUserAndEndpointHash` 로 매칭 후 갱신.
 *  - 410/expired 발송 실패는 `findByEndpointHash` 로 단건 disable.
 */
interface UserPushSubscriptionRepository : JpaRepository<UserPushSubscription, Long> {

    fun findByUserAndEnabledTrue(user: User): List<UserPushSubscription>

    fun findByUserIdAndEnabledTrue(userId: Long): List<UserPushSubscription>

    fun findByUserIdInAndEnabledTrue(userIds: Collection<Long>): List<UserPushSubscription>

    fun findByUserAndEndpointHash(user: User, endpointHash: String): UserPushSubscription?

    fun findByEndpointHash(endpointHash: String): UserPushSubscription?

    fun findByUser(user: User): List<UserPushSubscription>

    fun deleteByUserAndEndpointHash(user: User, endpointHash: String): Long
}
