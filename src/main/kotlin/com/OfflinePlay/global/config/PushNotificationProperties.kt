package com.contenido.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * PR140 — Web Push VAPID 설정.
 *
 *  - publicKey/privateKey 가 모두 채워져 있을 때만 실제 발송 시도. 둘 중 하나라도 비어 있으면
 *    "비활성" 으로 보고 dispatch 호출은 즉시 no-op 으로 끝난다 (로컬/CI 안전 디폴트).
 *  - subject 는 VAPID `sub` claim — mailto: 또는 https URL 이어야 RFC8292 호환.
 *  - private key 는 운영에서만 env var 로 주입. 파일/리포지토리에 절대 commit 금지.
 */
@ConfigurationProperties(prefix = "push.vapid")
data class PushNotificationProperties(
    var publicKey: String = "",
    var privateKey: String = "",
    var subject: String = "mailto:no-reply@contenido.local",
) {
    val enabled: Boolean
        get() = publicKey.isNotBlank() && privateKey.isNotBlank()
}
