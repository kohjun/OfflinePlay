package com.contenido.domain.event.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 이벤트 참가 신청 — 상태 머신 기반 (PENDING → APPROVED|REJECTED|CANCELED).
 *
 * (event, participant) 조합은 unique 이므로 재신청은 새 행이 아니라 기존 행의
 * [reapply] 로 처리한다.
 */
@Entity
@Table(
    name = "event_participations",
    uniqueConstraints = [UniqueConstraint(columnNames = ["event_id", "participant_id"])],
)
class EventParticipation(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: Event,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    val participant: User,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ParticipationStatus = ParticipationStatus.PENDING

    /**
     * 가장 최근 신청 시각. 컬럼명은 기존 스키마와 호환되도록 `joined_at` 을 유지한다.
     * REJECTED/CANCELED 에서 재신청(PENDING 복구) 시 갱신된다.
     */
    @Column(name = "joined_at", nullable = false)
    var joinedAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "reviewed_at")
    var reviewedAt: LocalDateTime? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    var reviewedBy: User? = null

    @Column(name = "reject_reason", length = 500)
    var rejectReason: String? = null

    fun approve(reviewer: User) {
        status = ParticipationStatus.APPROVED
        reviewedAt = LocalDateTime.now()
        reviewedBy = reviewer
        rejectReason = null
    }

    /**
     * 유료 이벤트 결제 성공 시 자동 승인. 결제 자체가 owner 의 사전 승인을 대체하므로
     * [reviewedBy] 는 null 로 남긴다 (system approval). 신청자 관리 화면이 reviewedBy null
     * 인 row 를 "결제 자동 승인" 으로 표시할지는 후속 UX 결정.
     */
    fun approveByPayment() {
        status = ParticipationStatus.APPROVED
        reviewedAt = LocalDateTime.now()
        reviewedBy = null
        rejectReason = null
    }

    fun reject(reviewer: User, reason: String?) {
        status = ParticipationStatus.REJECTED
        reviewedAt = LocalDateTime.now()
        reviewedBy = reviewer
        rejectReason = reason?.takeIf { it.isNotBlank() }
    }

    fun cancel() {
        status = ParticipationStatus.CANCELED
    }

    /** REJECTED/CANCELED 상태에서 다시 신청할 때 호출한다. */
    fun reapply() {
        status = ParticipationStatus.PENDING
        joinedAt = LocalDateTime.now()
        reviewedAt = null
        reviewedBy = null
        rejectReason = null
    }
}
