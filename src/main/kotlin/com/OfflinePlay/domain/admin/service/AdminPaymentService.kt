package com.contenido.domain.admin.service

import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.payment.dto.AdminForcedRefundRequest
import com.contenido.domain.payment.dto.AdminForcedRefundResponse
import com.contenido.domain.payment.service.PaymentService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR106 — ADMIN 전용 결제/환불 운영 도구 진입점.
 *
 * 현재 책임 1가지:
 *  - 강제 환불 (`forceRefund`) — 일반 사용자/owner 환불 경로(`PaymentService.refundPaymentByTicket`)
 *    의 deadline / USED 가드를 우회한다. 노쇼 보상, 행사 취소, 운영 개입 등 시작 후·체크인 후 환불
 *    케이스 처리용.
 *
 * 트랜잭션:
 *  - `@Transactional` 로 환불 cascade + audit 기록을 같은 트랜잭션에 묶는다. audit 실패가 환불도
 *    rollback 시키도록 — 운영 추적성 우선 (PR61 hide audit 정책과 동일).
 *
 * 분리 이유:
 *  - 도메인 cross dependency 를 admin 패키지가 흡수. payment 패키지는 audit 도메인을 모르고,
 *    admin 패키지가 두 서비스를 wiring 한다 (기존 AdminModerationService 패턴과 동일).
 */
@Service
class AdminPaymentService(
    private val paymentService: PaymentService,
    private val moderationAuditLogService: ModerationAuditLogService,
) {

    /**
     * USED / 시작 후 PAID 티켓을 강제 환불한다. 권한 / 상태 가드 / 금액 검증은
     * [PaymentService.forceRefundByAdmin] 가 담당. 본 메서드는 환불 결과를 받아
     * [ModerationAuditAction.TICKET_FORCED_REFUNDED] audit row 1건을 같은 트랜잭션에 기록한다.
     *
     * audit `afterValue` payload (`Map<String, Any?>`):
     *   기존 (PR106): `{ ticketId, paymentAttemptId, ticketStatus, amount }`
     *   PR134 추가  : `refundAmount` / `refundedAmount` / `remainingRefundableAmount` / `fullRefund`
     *
     * 기존 4 필드는 호환을 위해 그대로 유지 — PR115 / PR130 / PR131 의 enrichment / CSV 가 같은
     * 필드 이름을 본다. 새 4 필드는 PR126 `paymentRefundContext` 와 같은 의미라 후속 enrichment 가
     * 일관된 shape 로 읽는다.
     *
     * `targetType` 은 null — `ReportTargetType` 에 TICKET 이 없다. afterValue 의 ticketId 가 검색
     * 키 역할을 한다 (audit 조회 화면에서 reason 검색으로도 찾을 수 있음).
     */
    @Transactional
    fun forceRefund(
        adminUserId: Long,
        ticketId: Long,
        request: AdminForcedRefundRequest,
    ): AdminForcedRefundResponse {
        val refundResponse = paymentService.forceRefundByAdmin(
            adminUserId = adminUserId,
            ticketId = ticketId,
            reason = request.reason,
            amount = request.amount,
        )
        val fullRefund = refundResponse.remainingRefundableAmount == 0L
        // PR134 — 이번 호출에서 환불된 금액. response 자체는 누적값만 들고 있어서 호출 전후 차이를
        // 다시 계산하기 어려운데, request.amount 가 있으면 그 값이 곧 이번 환불액. null 이면 항상
        // full cascade 라 누적 환불액 = attempt.amount = response.amount.
        val refundAmount = request.amount ?: refundResponse.amount
        moderationAuditLogService.record(
            actorId = adminUserId,
            action = ModerationAuditAction.TICKET_FORCED_REFUNDED,
            targetType = null,
            targetId = null,
            afterValue = mapOf(
                "ticketId" to refundResponse.ticketId,
                "paymentAttemptId" to refundResponse.paymentAttemptId,
                "ticketStatus" to refundResponse.ticketStatus.name,
                "amount" to refundResponse.amount,
                "refundAmount" to refundAmount,
                "refundedAmount" to refundResponse.refundedAmount,
                "remainingRefundableAmount" to refundResponse.remainingRefundableAmount,
                "fullRefund" to fullRefund,
            ),
            reason = request.reason,
        )
        return AdminForcedRefundResponse(
            ticketId = refundResponse.ticketId,
            ticketStatus = refundResponse.ticketStatus,
            paymentAttemptId = refundResponse.paymentAttemptId,
            provider = refundResponse.provider,
            amount = refundResponse.amount,
            refundedAt = refundResponse.refundedAt,
            providerPaymentKey = refundResponse.providerPaymentKey,
            refundReason = request.reason,
            refundedAmount = refundResponse.refundedAmount,
            remainingRefundableAmount = refundResponse.remainingRefundableAmount,
            fullRefund = fullRefund,
        )
    }
}
