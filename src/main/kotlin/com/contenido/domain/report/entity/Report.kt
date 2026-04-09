package com.contenido.domain.report.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

enum class ReportTargetType { CHANNEL, POST, EVENT, COMMENT }

enum class ReportStatus { PENDING, RESOLVED }

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
}
