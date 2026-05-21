package com.contenido.domain.notification.service

import com.contenido.global.config.PushNotificationProperties
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Subscription
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.Security

/**
 * PR140 — nl.martijndwars:web-push 를 사용해 실제 push 발송. VAPID 키 미설정이면 disabled() 반환.
 *
 * BouncyCastle Provider 는 EC 키 처리에 필요하므로 클래스 로드 시 1회 등록한다.
 * (Spring 이 빈 한 번만 만들기 때문에 멱등 — 이미 등록돼 있으면 noop.)
 */
@Component
class LibraryWebPushSender(
    private val properties: PushNotificationProperties,
) : WebPushSender {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val pushService: PushService? by lazy {
        if (!properties.enabled) {
            log.info("[Push] VAPID 키 미설정 — push 발송 비활성화 (로컬/CI 안전 모드).")
            null
        } else {
            runCatching {
                PushService(properties.publicKey, properties.privateKey, properties.subject)
            }.onFailure { e ->
                log.error("[Push] PushService 초기화 실패: {}", e.message)
            }.getOrNull()
        }
    }

    override fun send(
        endpoint: String,
        p256dh: String,
        auth: String,
        payload: ByteArray,
    ): WebPushSendResult {
        val service = pushService ?: return WebPushSendResult.disabled()
        val subscription = Subscription(endpoint, Subscription.Keys(p256dh, auth))
        return runCatching {
            // library 의 Notification(subscription, String) 시그니처를 사용. payload 는 UTF-8 String.
            val response = service.send(Notification(subscription, String(payload, Charsets.UTF_8)))
            WebPushSendResult(statusCode = response.statusLine.statusCode)
        }.getOrElse { e ->
            log.warn("[Push] 발송 예외 endpoint={} err={}", endpoint, e.message)
            WebPushSendResult.failure(e.message ?: e::class.simpleName ?: "unknown")
        }
    }
}
