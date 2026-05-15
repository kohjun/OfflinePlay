package com.contenido.global.config

import com.contenido.domain.payment.gateway.MockPaymentGateway
import com.contenido.domain.payment.gateway.PaymentGateway
import com.contenido.domain.payment.gateway.TossPaymentGateway
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * PaymentGateway 빈 선정 규칙.
 *
 *  - `payment.toss.enabled=true` 이면 → [TossPaymentGateway] (운영 + sandbox 키 있는 dev).
 *  - 그 외 (기본값 false, 또는 키 누락) → [MockPaymentGateway] (로컬/CI). 운영 yml 에는
 *    반드시 `payment.toss.enabled=true` 로 설정해야 한다.
 *
 * 빈 이름은 둘 다 `paymentGateway` 로 통일 — PaymentService 가 정확히 하나만 주입받는다.
 */
@Configuration
@EnableConfigurationProperties(TossPaymentProperties::class)
class PaymentConfig {

    /**
     * Toss confirm 호출에 쓰는 RestClient.
     *
     * 운영 SLA 가 정해지면 timeout/retry 를 별도 정책으로 분리. 현재는 짧은 connect 와
     * 보수적인 read timeout 만 잡아 둔다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun paymentRestClient(): RestClient =
        RestClient.builder()
            .requestFactory(
                org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(3).toMillis().toInt())
                    setReadTimeout(Duration.ofSeconds(10).toMillis().toInt())
                }
            )
            .build()

    @Bean
    @ConditionalOnProperty(name = ["payment.toss.enabled"], havingValue = "true")
    fun tossPaymentGateway(
        properties: TossPaymentProperties,
        paymentRestClient: RestClient,
    ): PaymentGateway = TossPaymentGateway(properties, paymentRestClient)

    @Bean
    @ConditionalOnProperty(name = ["payment.toss.enabled"], havingValue = "false", matchIfMissing = true)
    fun mockPaymentGateway(): PaymentGateway = MockPaymentGateway()
}
