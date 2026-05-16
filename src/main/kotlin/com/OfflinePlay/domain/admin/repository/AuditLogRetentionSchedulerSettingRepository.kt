package com.contenido.domain.admin.repository

import com.contenido.domain.admin.entity.AuditLogRetentionSchedulerSetting
import org.springframework.data.jpa.repository.JpaRepository

interface AuditLogRetentionSchedulerSettingRepository :
    JpaRepository<AuditLogRetentionSchedulerSetting, Long>
