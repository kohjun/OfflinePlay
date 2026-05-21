package com.contenido.domain.notification.service

import com.contenido.domain.notification.entity.Notification
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.entity.UserPushSubscription
import com.contenido.domain.notification.repository.UserPushSubscriptionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * PR140 — NotificationService 가 저장한 row + SSE 이후, 같은 receiver 의 활성 Web Push 구독으로
 * push 를 발송한다.
 *
 * 정책:
 *  - notification row 와 같은 트랜잭션 안에서 호출되지만, 발송 자체는 외부 HTTP 라 best-effort.
 *    개별 구독 실패는 warn 로그 + 다음 구독으로 진행. 함수 전체에서 예외를 던지지 않는다.
 *  - 410/404 → subscription.enabled = false (별도 REQUIRES_NEW 트랜잭션). 사용자의 명시적 해지는
 *    PushSubscriptionService 가 hard delete; 본 경로는 soft disable 만 사용한다.
 *  - VAPID 키 미설정이면 WebPushSender 가 disabled() 반환 — push 흐름 자체가 no-op.
 *  - payload 는 service worker 의 push handler 와 일치하는 JSON 키만 채운다. (title/body/type/...)
 */
@Service
class PushNotificationService(
    private val subscriptionRepository: UserPushSubscriptionRepository,
    private val sender: WebPushSender,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 다수 receiver 의 활성 구독에 일괄 발송. notification row 한 건당 receiver 1명을 매핑하는
     * NotificationService 의 호출 시그니처에 맞춰, 본 메서드는 (notification, receiverId) 쌍을 받는다.
     */
    fun dispatch(notifications: List<Notification>) {
        if (notifications.isEmpty()) return
        val receiverIds = notifications.map { it.receiver.id }.distinct()
        val subscriptions = runCatching {
            subscriptionRepository.findByUserIdInAndEnabledTrue(receiverIds)
        }.getOrElse { e ->
            log.warn("[Push] 구독 조회 실패 — 발송 skip: {}", e.message)
            return
        }
        if (subscriptions.isEmpty()) return

        val byUser = subscriptions.groupBy { it.user.id }
        notifications.forEach { notification ->
            val subs = byUser[notification.receiver.id] ?: return@forEach
            val payload = encodePayload(notification)
            subs.forEach { sub -> sendOne(sub, payload) }
        }
    }

    /**
     * 단건 발송 + 결과 처리. 외부에 예외를 누출하지 않는다.
     */
    private fun sendOne(subscription: UserPushSubscription, payload: ByteArray) {
        val result = runCatching {
            sender.send(
                endpoint = subscription.endpoint,
                p256dh = subscription.p256dh,
                auth = subscription.auth,
                payload = payload,
            )
        }.getOrElse { e ->
            log.warn("[Push] sender 예외 endpoint={} err={}", subscription.endpoint, e.message)
            return
        }

        when {
            result.statusCode == WebPushSendResult.DISABLED -> {
                // VAPID 미설정 → 침묵 (이미 LibraryWebPushSender 가 한 번 로그함).
            }
            result.isSuccess -> {
                runCatching { touchSeen(subscription.id) }
                    .onFailure { e -> log.debug("[Push] last_seen 갱신 실패: {}", e.message) }
            }
            result.isExpired -> {
                log.info(
                    "[Push] expired endpoint — disabling subscriptionId={} status={}",
                    subscription.id, result.statusCode,
                )
                runCatching { disableSubscription(subscription.id) }
                    .onFailure { e -> log.warn("[Push] disable 실패 subscriptionId={} err={}", subscription.id, e.message) }
            }
            else -> {
                log.warn(
                    "[Push] 발송 실패 subscriptionId={} status={} err={}",
                    subscription.id, result.statusCode, result.errorMessage,
                )
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun disableSubscription(subscriptionId: Long) {
        subscriptionRepository.findById(subscriptionId).ifPresent { it.disable() }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun touchSeen(subscriptionId: Long) {
        subscriptionRepository.findById(subscriptionId).ifPresent { it.touchSeen() }
    }

    /**
     * Service worker 의 push 핸들러가 그대로 읽는 JSON 페이로드.
     * 키 이름은 frontend/public/sw.js 와 utils/notificationMeta.ts 가 함께 사용한다.
     */
    fun encodePayload(notification: Notification): ByteArray {
        val url = defaultUrlFor(notification.targetType, notification.targetId, notification.type)
        val map = mapOf(
            "title" to notification.title,
            "body" to notification.message,
            "type" to notification.type.name,
            "targetType" to notification.targetType,
            "targetId" to notification.targetId,
            "url" to url,
            "notificationId" to notification.id,
        )
        return objectMapper.writeValueAsBytes(map)
    }

    /**
     * frontend 의 utils/notificationMeta.pathForNotification 과 호환되는 fallback 라우팅.
     * viewerRole 분기는 backend 가 모르므로 PARTICIPANT 기본 경로를 보낸다 — SW notificationclick
     * 시 라우터가 추가 보정한다.
     */
    private fun defaultUrlFor(targetType: String, targetId: Long, type: NotificationType): String {
        return when (targetType) {
            "events" -> "/events/$targetId"
            "channels" -> when (type) {
                NotificationType.NEW_POST -> "/channels/$targetId?tab=posts"
                NotificationType.CHANNEL_BANNED -> "/my"
                else -> "/channels/$targetId"
            }
            "tickets" -> "/tickets/$targetId"
            "creator-applications" -> "/my"
            else -> "/notifications"
        }
    }
}
