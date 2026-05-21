package com.contenido.domain.notification.service

/**
 * PR140 — Web Push 전송 추상화.
 *
 * 구현체는 nl.martijndwars:web-push (운영) 또는 NoOp (로컬/테스트). 호출자가 외부 라이브러리 타입에
 * 의존하지 않도록 status code 만 노출한다.
 */
interface WebPushSender {
    fun send(endpoint: String, p256dh: String, auth: String, payload: ByteArray): WebPushSendResult
}

/**
 * 발송 결과. 호출자가 처리하는 두 가지 핵심 분기:
 *  - [isSuccess]   = HTTP 2xx → 정상.
 *  - [isExpired]   = HTTP 404/410 → 구독을 disable 해야 한다 (브라우저가 unsubscribe 한 상태).
 *  - 그 외          = 일시적 실패. 로깅하고 다음 구독으로 넘어간다.
 */
data class WebPushSendResult(
    val statusCode: Int,
    val errorMessage: String? = null,
) {
    val isSuccess: Boolean get() = statusCode in 200..299
    val isExpired: Boolean get() = statusCode == 404 || statusCode == 410

    companion object {
        /** library 가 status code 를 주기 전에 예외로 실패한 경우. statusCode = -1. */
        fun failure(message: String): WebPushSendResult = WebPushSendResult(-1, message)
        const val DISABLED = -2
        fun disabled(): WebPushSendResult = WebPushSendResult(DISABLED, "vapid key not configured")
    }
}
