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
