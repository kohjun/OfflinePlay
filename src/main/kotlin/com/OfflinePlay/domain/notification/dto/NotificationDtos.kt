package com.contenido.domain.notification.dto

import com.contenido.domain.notification.entity.NotificationType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

data class NotificationResponse(
    val id: Long,
    val type: NotificationType,
    val title: String,
    val message: String,
    val targetType: String,
    val targetId: Long,
    val isRead: Boolean,
    val createdAt: LocalDateTime,
)

/**
 * PR95 — NotificationType 1건의 사용자 수신 선호.
 *
 * `enabled` true 면 알림 발송 흐름이 row 저장 + SSE 전송. false 면 발송 단계에서 skip.
 * row 가 DB 에 없는 type 은 응답 시 enabled=true 로 채워진다 (서비스 fallback).
 *
 * PR104 — `updatedAt` 은 row.updatedAt 의 lightweight signal. row 가 없는 type 은 null
 * (이 사용자는 해당 type 을 한 번도 설정한 적이 없다는 뜻 = 기본값). 본 필드는 audit 이나
 * 변경 이력을 대체하지 않는다 — 단순히 "마지막 저장 시각" 표시용.
 */
data class NotificationPreferenceResponse(
    val type: NotificationType,
    val enabled: Boolean,
    val updatedAt: java.time.LocalDateTime? = null,
)

data class UpdateNotificationPreferenceItem(
    @field:NotNull val type: NotificationType?,
    @field:NotNull val enabled: Boolean?,
)

data class UpdateNotificationPreferencesRequest(
    @field:Valid val preferences: List<UpdateNotificationPreferenceItem> = emptyList(),
)
