package com.contenido.global.config

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [PaymentHardeningCheck] 단위 테스트.
 *
 * SpringBoot 컨텍스트 없이 직접 인스턴스를 만들어 verify() 동작만 검증한다 —
 * yaml/profile 의존성을 피하기 위함.
 */
class PaymentHardeningCheckTest {

    @Test
    fun `enabled=false + required=false + secret 비어 있어도 통과 (로컬 디폴트)`() {
        val check = PaymentHardeningCheck(
            TossPaymentProperties(
                enabled = false,
                webhookSignatureRequired = false,
                secretKey = "",
            ),
        )
        // 예외가 던져지지 않으면 성공.
        check.verify()
    }

    @Test
    fun `enabled=true + secret 있으면 통과`() {
        val check = PaymentHardeningCheck(
            TossPaymentProperties(
                enabled = true,
                webhookSignatureRequired = true,
                secretKey = "test_sk_xxx",
            ),
        )
        check.verify()
    }

    @Test
    fun `enabled=true + secret 비어 있으면 IllegalStateException (PG 호출 불가)`() {
        val check = PaymentHardeningCheck(
            TossPaymentProperties(
                enabled = true,
                webhookSignatureRequired = false,
                secretKey = "",
            ),
        )
        assertThatThrownBy { check.verify() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("payment.toss.enabled=true")
            .hasMessageContaining("secretKey")
    }

    @Test
    fun `required=true + secret 비어 있으면 IllegalStateException (webhook 검증 불가)`() {
        val check = PaymentHardeningCheck(
            TossPaymentProperties(
                enabled = false,
                webhookSignatureRequired = true,
                secretKey = "",
            ),
        )
        assertThatThrownBy { check.verify() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("webhook-signature-required=true")
    }

    @Test
    fun `enabled=true + required=true + secret 비어 있으면 두 violation 모두 메시지에 노출`() {
        val check = PaymentHardeningCheck(
            TossPaymentProperties(
                enabled = true,
                webhookSignatureRequired = true,
                secretKey = "",
            ),
        )
        assertThatThrownBy { check.verify() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("payment.toss.enabled=true")
            .hasMessageContaining("webhook-signature-required=true")
            .hasMessageContaining("TOSS_SECRET_KEY")
    }
}
