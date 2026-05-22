package com.contenido.domain.admin.entity

import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * Moderation audit log (PR61).
 *
 * append-only. ADMIN 운영 액션의 추적성을 보장하기 위해 원 액션과 같은 트랜잭션에서 기록.
 * 본 PR 은 기록 + 조회만 — 수정/삭제 API 는 없다.
 */
enum class ModerationAuditAction {
    /** 자동 hide 임계치 변경 (ModerationThresholdService.updateThresholds). */
    THRESHOLD_UPDATED,

    /** ADMIN 수동 hide. */
    TARGET_HIDDEN,

    /** ADMIN 수동 unhide. */
    TARGET_UNHIDDEN,

    /** 채널 제재 (ban + cascade hide). */
    CHANNEL_BANNED,

    /** 채널 제재 해제 (unhide + activate). */
    CHANNEL_UNBANNED,

    /** Appeal 승인 — 대상 unhide. */
    APPEAL_APPROVED,

    /** Appeal 거절 — hidden 유지. rejectReason 보존. */
    APPEAL_REJECTED,

    /** 신고 RESOLVED 처리. */
    REPORT_RESOLVED,

    /** 신고 DISMISSED 처리. */
    REPORT_DISMISSED,

    /**
     * PR66 — 운영자가 오래된 audit log row 를 archive table 로 이동시켰을 때 active 테이블에
     * 남기는 액션. afterValue 에 archived count / cutoffAt / 잔여 후보 수 JSON 동봉.
     */
    AUDIT_LOGS_ARCHIVED,

    /**
     * PR106 — ADMIN 이 일반 환불 경로(`refundPaymentByTicket`)로 처리 불가능한 티켓
     * (USED / 시작 후 PAID 등)을 강제 환불한 액션. targetType=null — ReportTargetType 에 TICKET
     * 이 없으므로 afterValue JSON 에 ticketId/paymentAttemptId 를 동봉한다. reason 은 운영
     * 사유(필수, 500자) 가 그대로 들어간다.
     */
    TICKET_FORCED_REFUNDED,

    /**
     * PR122 — 일반 사용자/owner/ADMIN 의 부분 환불 (`refundPaymentByTicket` 성공 + 누적 < amount).
     * actor 는 환불 요청 actorId. targetType=null + afterValue JSON 에 ticketId/paymentAttemptId/
     * eventId/refundAmount/refundedAmount/remainingRefundableAmount/ticketStatus/paymentStatus/
     * fullRefund=false 동봉. ADMIN forced refund (`forceRefundByAdmin`) 는 본 액션을 만들지 않고
     * 기존 [TICKET_FORCED_REFUNDED] 를 그대로 사용한다.
     */
    PAYMENT_PARTIALLY_REFUNDED,

    /**
     * PR122 — 일반 사용자/owner/ADMIN 의 전액 환불 (`refundPaymentByTicket` 성공 + 누적 == amount).
     * 부분 환불 누적이 결제 금액에 도달해 cascade 가 발동한 경우도 본 액션. ADMIN forced refund
     * (`forceRefundByAdmin`) 는 본 액션을 만들지 않고 기존 [TICKET_FORCED_REFUNDED] 만 기록한다.
     */
    PAYMENT_REFUNDED,

    /**
     * PR154 — 채널 owner / STAFF / ADMIN 이 이벤트 신청자 목록을 CSV 로 export 한 액션.
     * actor 는 호출자. targetType=null. afterValue JSON 에 eventId / channelId / exportedRowCount /
     * exportedAt 을 동봉 — 후속 audit detail 조회 시 누가 언제 어느 이벤트 신청자 목록을 가져갔는지
     * 추적 가능.
     *
     * 개인정보 보호 정책: 본 액션은 phone number 등 민감 데이터를 다루므로 운영 audit 가 필수다.
     */
    PARTICIPANT_EXPORTED,
}

@Entity
@Table(
    name = "moderation_audit_logs",
    indexes = [
        Index(name = "idx_moderation_audit_logs_actor", columnList = "actor_id,created_at"),
        Index(name = "idx_moderation_audit_logs_action", columnList = "action,created_at"),
        Index(name = "idx_moderation_audit_logs_target", columnList = "target_type,target_id"),
        Index(name = "idx_moderation_audit_logs_created_at", columnList = "created_at"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class ModerationAuditLog(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    val actor: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    val action: ModerationAuditAction,

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20)
    val targetType: ReportTargetType? = null,

    @Column(name = "target_id")
    val targetId: Long? = null,

    @Column(name = "before_value", columnDefinition = "TEXT")
    val beforeValue: String? = null,

    @Column(name = "after_value", columnDefinition = "TEXT")
    val afterValue: String? = null,

    @Column(length = 500)
    val reason: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set
}
