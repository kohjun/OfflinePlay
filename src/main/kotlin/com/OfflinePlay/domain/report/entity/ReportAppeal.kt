package com.contenido.domain.report.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * 자동 숨김된 대상에 대한 이의 제기 (PR52).
 *
 *  - PENDING  : 작성자가 제출, ADMIN 검토 대기.
 *  - APPROVED : ADMIN 이 승인 — 대상 unhide. [reviewedBy] / [reviewedAt] 채워짐.
 *  - REJECTED : ADMIN 이 거절 — hidden 유지. [rejectReason] 보존.
 *
 * 같은 requester 가 같은 (targetType, targetId) 에 PENDING appeal 을 중복 생성하지
 * 못하도록 서비스 레이어에서 가드. RESOLVED/REJECTED 이력은 남기되 다시 PENDING 으로
 * 재신청은 허용 — 추가 컨텍스트로 재신청할 여지를 둔다.
 */
enum class ReportAppealStatus { PENDING, APPROVED, REJECTED }

@Entity
@Table(
    name = "report_appeals",
    indexes = [
        Index(name = "idx_report_appeals_target", columnList = "target_type,target_id,status"),
        Index(name = "idx_report_appeals_requester", columnList = "requester_id,created_at"),
        Index(name = "idx_report_appeals_status", columnList = "status,created_at"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class ReportAppeal(

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    val targetType: ReportTargetType,

    @Column(name = "target_id", nullable = false)
    val targetId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    val requester: User,

    @Column(nullable = false, length = 1000)
    val reason: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReportAppealStatus = ReportAppealStatus.PENDING
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    var reviewedBy: User? = null
        protected set

    @Column(name = "reviewed_at")
    var reviewedAt: LocalDateTime? = null
        protected set

    @Column(name = "reject_reason", length = 500)
    var rejectReason: String? = null
        protected set

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: LocalDateTime
        protected set

    val isPending: Boolean
        get() = status == ReportAppealStatus.PENDING

    fun approve(reviewer: User, at: LocalDateTime = LocalDateTime.now()) {
        status = ReportAppealStatus.APPROVED
        reviewedBy = reviewer
        reviewedAt = at
        rejectReason = null
    }

    fun reject(reviewer: User, reason: String?, at: LocalDateTime = LocalDateTime.now()) {
        status = ReportAppealStatus.REJECTED
        reviewedBy = reviewer
        reviewedAt = at
        rejectReason = reason?.takeIf { it.isNotBlank() }?.take(500)
    }
}
