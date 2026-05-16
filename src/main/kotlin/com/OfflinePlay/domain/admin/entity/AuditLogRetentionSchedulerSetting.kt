package com.contenido.domain.admin.entity

import com.contenido.domain.user.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * PR68 — audit log retention archive scheduler 설정. 단일 row (id=1) 패턴.
 *
 *  - 기본 enabled=false. 운영자가 명시적으로 true 로 토글해야 archive job 이 동작.
 *  - cron 식은 Spring 6 spring-context 의 default 6-field 형식 (`초 분 시 일 월 요일`).
 *  - [updatedBy] : 마지막으로 토글/설정한 ADMIN. scheduler 가 archive 를 실행할 때 archive
 *    table 의 `archived_by` 자리로 재사용 (system actor 대체).
 */
@Entity
@Table(name = "audit_log_retention_scheduler_settings")
class AuditLogRetentionSchedulerSetting(

    @Id
    val id: Long = SINGLE_ROW_ID,

    @Column(nullable = false)
    var enabled: Boolean,

    @Column(nullable = false, length = 64)
    var cron: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    var updatedBy: User? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(enabled: Boolean, cron: String, updatedBy: User?, at: LocalDateTime = LocalDateTime.now()) {
        this.enabled = enabled
        this.cron = cron
        this.updatedBy = updatedBy
        this.updatedAt = at
    }

    companion object {
        const val SINGLE_ROW_ID: Long = 1L
    }
}
