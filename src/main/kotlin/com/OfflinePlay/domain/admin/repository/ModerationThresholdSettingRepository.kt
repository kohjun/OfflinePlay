package com.contenido.domain.admin.repository

import com.contenido.domain.admin.entity.ModerationThresholdSetting
import com.contenido.domain.report.entity.ReportTargetType
import org.springframework.data.jpa.repository.JpaRepository

interface ModerationThresholdSettingRepository :
    JpaRepository<ModerationThresholdSetting, ReportTargetType>
