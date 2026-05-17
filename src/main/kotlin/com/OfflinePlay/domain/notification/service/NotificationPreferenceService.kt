package com.contenido.domain.notification.service

import com.contenido.domain.notification.dto.NotificationPreferenceResponse
import com.contenido.domain.notification.dto.UpdateNotificationPreferencesRequest
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.entity.UserNotificationPreference
import com.contenido.domain.notification.repository.UserNotificationPreferenceRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UserNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR95 — 사용자별 NotificationType 수신 선호 관리.
 *
 * 정책:
 *  - row 가 없는 NotificationType 은 enabled = true 로 응답한다 (fail-open + 회귀 방지).
 *  - 같은 type 이 request 에 중복으로 들어오면 마지막 값으로 upsert (단순화).
 *  - request 에 없는 type 은 기존 DB row 를 건드리지 않는다 (partial update).
 *  - [isEnabled] 는 row 부재 / 조회 예외 모두 true 로 fallback — 알림 발송이 preference 조회
 *    문제로 사라지지 않도록 fail-open.
 *  - 본 PR 은 audit 기록 X (개인 설정 영역).
 */
@Service
@Transactional(readOnly = true)
class NotificationPreferenceService(
    private val userRepository: UserRepository,
    private val preferenceRepository: UserNotificationPreferenceRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 모든 [NotificationType] 에 대한 응답을 반환. row 가 없는 type 은 enabled=true / updatedAt=null.
     * row 가 있는 type 은 row.enabled + row.updatedAt (PR104).
     */
    fun getMyPreferences(userId: Long): List<NotificationPreferenceResponse> {
        userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        val saved = preferenceRepository.findByUserId(userId).associateBy { it.notificationType }
        return NotificationType.values().map { type ->
            val row = saved[type]
            NotificationPreferenceResponse(
                type = type,
                enabled = row?.enabled ?: true,
                updatedAt = row?.updatedAt,
            )
        }
    }

    /**
     * type 별 upsert. request 에 없는 type 은 그대로 둔다. 같은 type 중복은 마지막 값.
     * 응답은 갱신 후의 전체 preference 목록 (모든 type).
     */
    @Transactional
    fun updateMyPreferences(
        userId: Long,
        request: UpdateNotificationPreferencesRequest,
    ): List<NotificationPreferenceResponse> {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (request.preferences.isEmpty()) return getMyPreferences(userId)

        // 같은 type 중복 → 마지막 값 채택 (Map 으로 자연스럽게 dedupe).
        val incoming: Map<NotificationType, Boolean> = request.preferences
            .mapNotNull { item ->
                val type = item.type ?: return@mapNotNull null
                val enabled = item.enabled ?: return@mapNotNull null
                type to enabled
            }
            .toMap()

        val existing = preferenceRepository.findByUser(user).associateBy { it.notificationType }
        incoming.forEach { (type, enabled) ->
            val row = existing[type]
            if (row != null) {
                row.update(enabled)
            } else {
                preferenceRepository.save(
                    UserNotificationPreference(
                        user = user,
                        notificationType = type,
                        enabled = enabled,
                    ),
                )
            }
        }
        return getMyPreferences(userId)
    }

    /**
     * 알림 발송 흐름이 호출. row 가 없거나 조회 자체가 실패하면 true (fail-open).
     */
    fun isEnabled(userId: Long, type: NotificationType): Boolean {
        return runCatching {
            preferenceRepository.findByUserIdAndNotificationType(userId, type)?.enabled ?: true
        }.getOrElse { ex ->
            log.warn("notification preference lookup failed userId={} type={} — defaulting to enabled", userId, type, ex)
            true
        }
    }
}
