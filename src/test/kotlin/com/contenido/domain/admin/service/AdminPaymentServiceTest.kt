package com.contenido.domain.admin.service

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.payment.dto.AdminForcedRefundRequest
import com.contenido.domain.payment.dto.RefundTicketResponse
import com.contenido.domain.payment.entity.PaymentProvider
import com.contenido.domain.payment.service.PaymentService
import com.contenido.domain.ticket.entity.TicketStatus
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * PR106 — `AdminPaymentService.forceRefund` 의 단위 테스트.
 *
 * 책임 검증:
 *  - PaymentService.forceRefundByAdmin 호출 결과를 AdminForcedRefundResponse 로 매핑
 *  - 성공 시 ModerationAuditAction.TICKET_FORCED_REFUNDED audit row 1건 기록 (actor + reason +
 *    afterValue 에 ticketId/paymentAttemptId/ticketStatus/amount)
 *  - PaymentService 가 throw 하면 audit 도 기록되지 않음 (동일 트랜잭션에서 rollback)
 */
@ExtendWith(MockKExtension::class)
class AdminPaymentServiceTest {

    @MockK lateinit var paymentService: PaymentService
    @MockK(relaxed = true) lateinit var moderationAuditLogService: ModerationAuditLogService

    private lateinit var service: AdminPaymentService

    @BeforeEach
    fun setUp() {
        service = AdminPaymentService(paymentService, moderationAuditLogService)
    }

    @Test
    fun `forceRefund 성공 시 audit 1건 기록 + 응답 매핑`() {
        val request = AdminForcedRefundRequest(reason = "행사 취소 보상")
        val underlying = RefundTicketResponse(
            ticketId = 999L,
            ticketStatus = TicketStatus.REFUNDED,
            paymentAttemptId = 555L,
            provider = PaymentProvider.TOSS,
            amount = 30_000L,
            refundedAmount = 30_000L,
            remainingRefundableAmount = 0L,
            refundedAt = "2026-05-18T12:00:00",
            providerPaymentKey = "toss_paid_key",
        )
        every {
            paymentService.forceRefundByAdmin(
                adminUserId = 99L, ticketId = 999L,
                reason = "행사 취소 보상", amount = null,
            )
        } returns underlying

        val afterValueSlot = slot<Any>()
        every {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), capture(afterValueSlot), any())
        } answers { io.mockk.mockk(relaxed = true) }

        val response = service.forceRefund(adminUserId = 99L, ticketId = 999L, request = request)

        assertThat(response.ticketId).isEqualTo(999L)
        assertThat(response.ticketStatus).isEqualTo(TicketStatus.REFUNDED)
        assertThat(response.paymentAttemptId).isEqualTo(555L)
        assertThat(response.amount).isEqualTo(30_000L)
        assertThat(response.refundReason).isEqualTo("행사 취소 보상")
        assertThat(response.providerPaymentKey).isEqualTo("toss_paid_key")

        // audit 1건 기록 확인 (actor / action / reason 위주).
        verify(exactly = 1) {
            moderationAuditLogService.record(
                actorId = 99L,
                action = ModerationAuditAction.TICKET_FORCED_REFUNDED,
                targetType = any(),
                targetId = any(),
                beforeValue = any(),
                afterValue = any(),
                reason = "행사 취소 보상",
            )
        }
        // afterValue 가 ticketId/paymentAttemptId 등 핵심 메타를 포함하는지.
        @Suppress("UNCHECKED_CAST")
        val payload = afterValueSlot.captured as Map<String, Any?>
        assertThat(payload["ticketId"]).isEqualTo(999L)
        assertThat(payload["paymentAttemptId"]).isEqualTo(555L)
        assertThat(payload["ticketStatus"]).isEqualTo("REFUNDED")
        assertThat(payload["amount"]).isEqualTo(30_000L)
    }

    @Test
    fun `forceRefund PaymentService 예외 시 audit 도 기록 안 됨`() {
        val request = AdminForcedRefundRequest(reason = "테스트")
        every {
            paymentService.forceRefundByAdmin(any(), any(), any(), any())
        } throws com.contenido.global.exception.TicketAlreadyRefundedException()

        org.junit.jupiter.api.assertThrows<com.contenido.global.exception.TicketAlreadyRefundedException> {
            service.forceRefund(99L, 999L, request)
        }
        verify(exactly = 0) { moderationAuditLogService.record(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── PR134 — partial forced refund passthrough ────────────────────────────

    @Test
    fun `PR134 — forceRefund amount 지정 부분 환불 → audit afterValue 에 refundAmount fullRefund=false`() {
        val request = AdminForcedRefundRequest(reason = "노쇼 일부 보상", amount = 10_000L)
        val underlying = RefundTicketResponse(
            ticketId = 999L,
            ticketStatus = TicketStatus.PARTIALLY_REFUNDED,
            paymentAttemptId = 555L,
            provider = PaymentProvider.TOSS,
            amount = 30_000L,
            refundedAmount = 10_000L,
            remainingRefundableAmount = 20_000L,
            refundedAt = "2026-05-18T12:00:00",
            providerPaymentKey = "toss_paid_key",
        )
        every {
            paymentService.forceRefundByAdmin(
                adminUserId = 99L, ticketId = 999L,
                reason = "노쇼 일부 보상", amount = 10_000L,
            )
        } returns underlying
        val afterValueSlot = slot<Any>()
        every {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), capture(afterValueSlot), any())
        } answers { io.mockk.mockk(relaxed = true) }

        val response = service.forceRefund(99L, 999L, request)

        // response 의 새 필드
        assertThat(response.ticketStatus).isEqualTo(TicketStatus.PARTIALLY_REFUNDED)
        assertThat(response.refundedAmount).isEqualTo(10_000L)
        assertThat(response.remainingRefundableAmount).isEqualTo(20_000L)
        assertThat(response.fullRefund).isFalse()
        // audit afterValue 의 새 필드
        @Suppress("UNCHECKED_CAST")
        val payload = afterValueSlot.captured as Map<String, Any?>
        assertThat(payload["refundAmount"]).isEqualTo(10_000L)
        assertThat(payload["refundedAmount"]).isEqualTo(10_000L)
        assertThat(payload["remainingRefundableAmount"]).isEqualTo(20_000L)
        assertThat(payload["fullRefund"]).isEqualTo(false)
        // 호환을 위한 기존 4 필드는 유지
        assertThat(payload["ticketId"]).isEqualTo(999L)
        assertThat(payload["paymentAttemptId"]).isEqualTo(555L)
        assertThat(payload["ticketStatus"]).isEqualTo("PARTIALLY_REFUNDED")
        assertThat(payload["amount"]).isEqualTo(30_000L)
    }

    @Test
    fun `PR134 — forceRefund amount null → remaining 전액 환불 + audit fullRefund=true`() {
        // 기존 PR106 회귀: amount 미지정 시 동작이 그대로 유지되는지.
        val request = AdminForcedRefundRequest(reason = "행사 취소")
        val underlying = RefundTicketResponse(
            ticketId = 999L,
            ticketStatus = TicketStatus.REFUNDED,
            paymentAttemptId = 555L,
            provider = PaymentProvider.TOSS,
            amount = 30_000L,
            refundedAmount = 30_000L,
            remainingRefundableAmount = 0L,
            refundedAt = "2026-05-18T12:00:00",
            providerPaymentKey = "toss_paid_key",
        )
        every {
            paymentService.forceRefundByAdmin(
                adminUserId = 99L, ticketId = 999L,
                reason = "행사 취소", amount = null,
            )
        } returns underlying
        val afterValueSlot = slot<Any>()
        every {
            moderationAuditLogService.record(any(), any(), any(), any(), any(), capture(afterValueSlot), any())
        } answers { io.mockk.mockk(relaxed = true) }

        val response = service.forceRefund(99L, 999L, request)

        assertThat(response.fullRefund).isTrue()
        assertThat(response.remainingRefundableAmount).isEqualTo(0L)
        @Suppress("UNCHECKED_CAST")
        val payload = afterValueSlot.captured as Map<String, Any?>
        assertThat(payload["fullRefund"]).isEqualTo(true)
        // amount null 호출이면 refundAmount = attempt.amount (response.amount).
        assertThat(payload["refundAmount"]).isEqualTo(30_000L)
        assertThat(payload["remainingRefundableAmount"]).isEqualTo(0L)
    }
}
