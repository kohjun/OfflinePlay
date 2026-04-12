package com.contenido.domain.creator.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

enum class ApplicationStatus {
    PENDING, APPROVED, REJECTED
}

@Entity
@Table(name = "creator_applications")
@EntityListeners(AuditingEntityListener::class)
class CreatorApplication(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applicant_id", nullable = false)
    val applicant: User,

    @Column(nullable = false, length = 1000)
    val reason: String,

    @Column(name = "portfolio_url", length = 500)
    val portfolioUrl: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ApplicationStatus = ApplicationStatus.PENDING
        protected set

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: LocalDateTime
        protected set

    @Column(name = "reviewed_at")
    var reviewedAt: LocalDateTime? = null
        protected set

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    var reviewedBy: User? = null
        protected set

    fun approve(admin: User) {
        this.status = ApplicationStatus.APPROVED
        this.reviewedAt = LocalDateTime.now()
        this.reviewedBy = admin
    }

    fun reject(admin: User) {
        this.status = ApplicationStatus.REJECTED
        this.reviewedAt = LocalDateTime.now()
        this.reviewedBy = admin
    }
}