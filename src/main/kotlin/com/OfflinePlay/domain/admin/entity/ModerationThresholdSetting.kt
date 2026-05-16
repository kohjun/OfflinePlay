package com.contenido.domain.admin.entity

import com.contenido.domain.report.entity.ReportTargetType
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 자동 hide 임계치 영속화 (PR60).
 *
 * PR51 의 ReportService.AUTO_HIDE_THRESHOLDS 상수를 ADMIN 이 운영 중 조정할 수 있게 DB 로 옮긴
 * row. target_type 별 1 row, threshold_value 는 1..100 (service 가 validation).
 */
@Entity
@Table(name = "moderation_threshold_settings")
class ModerationThresholdSetting(

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20)
    val targetType: ReportTargetType,

    @Column(name = "threshold_value", nullable = false)
    var thresholdValue: Int,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(newValue: Int) {
        thresholdValue = newValue
        updatedAt = LocalDateTime.now()
    }
}
