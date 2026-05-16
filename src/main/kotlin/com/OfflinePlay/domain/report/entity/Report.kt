package com.contenido.domain.report.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * 신고 대상 타입.
 *
 *  - CHANNEL  : 채널 단위 신고 (어뷰즈/사칭/스팸)
 *  - POST     : 채널 게시글
 *  - EVENT    : 이벤트 본문 / 정책 오용
 *  - COMMENT  : 이벤트 댓글
 *  - REVIEW   : 이벤트 후기 (PR48 추가) — 별점 조작 / 부적절 본문
 *
 * 새 타입 추가 시 ReportService.createReport 의 타깃 검증 + AdminService 의 preview 매핑을
 * 함께 갱신.
 */
enum class ReportTargetType { CHANNEL, POST, EVENT, COMMENT, REVIEW }

enum class ReportStatus { PENDING, RESOLVED, DISMISSED }

@Entity
@Table(name = "reports")
@EntityListeners(AuditingEntityListener::class)
class Report(

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    val reporter: User,

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    val targetType: ReportTargetType,

    @Column(name = "target_id", nullable = false)
    val targetId: Long,

    @Column(nullable = false, length = 500)
    val reason: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReportStatus = ReportStatus.PENDING
        protected set

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    fun resolve() {
        status = ReportStatus.RESOLVED
    }

    fun dismiss() {
        status = ReportStatus.DISMISSED
    }

    val isPending: Boolean
        get() = status == ReportStatus.PENDING
}
