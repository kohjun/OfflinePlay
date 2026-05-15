package com.contenido.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Toss Payments 설정.
 *
 *  - [enabled] : 활성화 여부. false 면 [com.contenido.domain.payment.gateway.MockPaymentGateway] 가
 *                빈으로 등록되어 sandbox 키 없이도 confirm 흐름을 검증할 수 있다.
 *                운영 배포 시 반드시 true + 유효한 [secretKey] 와 함께 설정해야 한다.
 *  - [secretKey] : Toss 가 발급한 secret key. **운영 비밀이므로 env var (TOSS_SECRET_KEY) 로만** 주입.
 *  - [clientKey] : Toss SDK 가 사용하는 client key. front-end 가 직접 사용하지만 BE 가 응답에
 *                  실어 주는 편이 안전 (env 한 곳에서 관리).
 *  - [apiBaseUrl] : Toss API 베이스 URL. sandbox = `https://api.tosspayments.com` (sandbox secretKey 와 함께).
 *  - [webhookSignatureRequired] : true 면 `/api/v1/payments/webhook` 호출에 `Toss-Signature` 헤더
 *                  HMAC 검증을 강제한다. 운영 배포는 반드시 true. local/CI 는 false 가 디폴트.
 */
@ConfigurationProperties(prefix = "payment.toss")
data class TossPaymentProperties(
    val enabled: Boolean = false,
    val secretKey: String = "",
    val clientKey: String = "",
    val apiBaseUrl: String = "https://api.tosspayments.com",
    val webhookSignatureRequired: Boolean = false,
)
