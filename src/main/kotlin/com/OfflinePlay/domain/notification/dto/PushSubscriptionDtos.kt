package com.contenido.domain.notification.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * PR139 — 브라우저가 PushManager.subscribe() 로 받은 endpoint + keys 를 그대로 backend 에 보낸다.
 *
 *  - `endpoint`        : PG/벤더 URL. 매우 길 수 있어 길이 제약은 DB(TEXT) 가 담당하지만, 너무 짧으면
 *                        invalid payload 로 본다. payload 전체 길이는 컨트롤러가 따로 검증한다.
 *  - `keys.p256dh`     : base64url string, 88 자 내외. Web Push 표준.
 *  - `keys.auth`       : base64url string, 24 자 내외.
 *  - `userAgent`       : optional. UI 가 디바이스 식별에 사용.
 */
data class PushSubscriptionRequest(
    @field:NotBlank
    val endpoint: String,

    @field:Valid
    val keys: PushSubscriptionKeysRequest,

    @field:Size(max = 500)
    val userAgent: String? = null,
)

data class PushSubscriptionKeysRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val p256dh: String,

    @field:NotBlank
    @field:Size(max = 255)
    val auth: String,
)

data class PushSubscriptionUnsubscribeRequest(
    @field:NotBlank
    val endpoint: String,
)

data class PushSubscriptionResponse(
    val id: Long,
    val enabled: Boolean,
    val userAgent: String?,
    val lastSeenAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
