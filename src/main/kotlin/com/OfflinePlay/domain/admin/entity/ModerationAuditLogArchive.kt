package com.contenido.domain.admin.entity

import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * PR66 — 오래된 운영 감사 로그를 hard delete 하지 않고 이쪽으로 이동한다. append-only,
 * read-only (application 측에서 update/delete 메서드 노출 금지).
 *
 *  - [originalId] : 원래 active 테이블에 있던 row 의 PK.
 *  - [actorNicknameSnapshot] : archive 시점의 actor nickname 을 박는다. 사용자 nickname 변경
 *    후에도 archive 가 시점의 컨텍스트를 잃지 않게.
 *  - [archivedBy] : archive 를 실행한 ADMIN. system actor 가 도입되면 nullable 로 승격될 수
 *    있으나 본 PR 은 수동 archive 만이라 항상 non-null.
 */
@Entity
@Table(
    name = "moderation_audit_log_archive",
    indexes = [
        Index(name = "idx_moderation_audit_log_archive_original_created_at", columnList = "original_created_at"),
        Index(name = "idx_moderation_audit_log_archive_action", columnList = "action,original_created_at"),
        Index(name = "idx_moderation_audit_log_archive_target", columnList = "target_type,target_id"),
        Index(name = "idx_moderation_audit_log_archive_archived_at", columnList = "archived_at"),
    ],
)
@EntityListeners(AuditingEntityListener::class)
class ModerationAuditLogArchive(

    @Column(name = "original_id", nullable = false, unique = true)
    val originalId: Long,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    val actor: User,

    @Column(name = "actor_nickname_snapshot", nullable = false, length = 64)
    val actorNicknameSnapshot: String,

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

    @Column(name = "original_created_at", nullable = false)
    val originalCreatedAt: LocalDateTime,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by", nullable = false)
    val archivedBy: User,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @CreatedDate
    @Column(name = "archived_at", nullable = false, updatable = false)
    lateinit var archivedAt: LocalDateTime
        protected set
}
