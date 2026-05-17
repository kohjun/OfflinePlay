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
     * USED / 시작 후 PAID 티켓을 강제 환불한다. 권한 / 상태 가드는 [PaymentService.forceRefundByAdmin]
     * 가 담당. 본 메서드는 환불 결과를 받아 [ModerationAuditAction.TICKET_FORCED_REFUNDED] audit
     * row 1건을 같은 트랜잭션에 기록한다.
     *
     * audit `afterValue` payload (`Map<String, Any?>`):
     *   `{ "ticketId": ..., "paymentAttemptId": ..., "ticketStatus": "REFUNDED", "amount": ... }`
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
        )
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
        )
    }
}
