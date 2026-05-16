package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.ModerationThresholdResponse
import com.contenido.domain.admin.dto.UpdateModerationThresholdsRequest
import com.contenido.domain.admin.entity.ModerationThresholdSetting
import com.contenido.domain.admin.repository.ModerationThresholdSettingRepository
import com.contenido.domain.report.entity.ReportTargetType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 자동 hide 임계치 조회/갱신 (PR60).
 *
 * PR51 에서 [com.contenido.domain.report.service.ReportService.AUTO_HIDE_THRESHOLDS] 상수로
 * 박혀 있던 임계치를 DB 로 옮긴다. ADMIN 이 운영 지표(PR57) 를 보고 임계치를 운영 중 조정.
 *
 * 정책:
 *  - DB row 가 없으면 [DEFAULTS] 로 fallback — V4 seed 가 늘 깔리지만, 누락/롤백 시 안전판.
 *  - 변경은 partial update. null 필드는 변경하지 않음.
 *  - 변경 즉시 이후 신고부터 적용. 기존 hidden 상태는 retroactive 재계산하지 않음.
 *  - 1..100 범위 validation 은 controller [@Valid] 가 1차로 잡고, service 가 2차 가드.
 *  - audit log 는 본 PR 범위 밖 — 후속 PR.
 */
@Service
@Transactional(readOnly = true)
class ModerationThresholdService(
    private val moderationThresholdSettingRepository: ModerationThresholdSettingRepository,
) {

    companion object {
        /**
         * PR51 default 값과 동일. V4 seed 와 1:1 매치. DB miss 시 안전한 fallback.
         */
        val DEFAULTS: Map<ReportTargetType, Int> = mapOf(
            ReportTargetType.REVIEW to 3,
            ReportTargetType.COMMENT to 3,
            ReportTargetType.POST to 5,
            ReportTargetType.EVENT to 5,
            ReportTargetType.CHANNEL to 7,
        )

        private const val MIN_VALUE = 1
        private const val MAX_VALUE = 100
    }

    /** 5개 targetType 전부 반환 — DB row 없으면 default 로 채움. UI 가 한 번에 그려야 하므로 항상 5개. */
    fun getThresholds(): List<ModerationThresholdResponse> {
        val byType: Map<ReportTargetType, Int> = moderationThresholdSettingRepository.findAll()
            .associate { it.targetType to it.thresholdValue }
        return ReportTargetType.entries.map { type ->
            ModerationThresholdResponse(
                targetType = type,
                threshold = byType[type] ?: DEFAULTS.getValue(type),
            )
        }
    }

    /**
     * 부분 갱신. null 인 필드는 변경하지 않음. 모든 필드가 null 이면 no-op.
     * row 가 아직 없는 targetType 은 새로 만든다 (V4 seed 누락/롤백 안전판).
     */
    @Transactional
    fun updateThresholds(request: UpdateModerationThresholdsRequest): List<ModerationThresholdResponse> {
        val updates: Map<ReportTargetType, Int> = buildMap {
            request.review?.let { put(ReportTargetType.REVIEW, it) }
            request.comment?.let { put(ReportTargetType.COMMENT, it) }
            request.post?.let { put(ReportTargetType.POST, it) }
            request.event?.let { put(ReportTargetType.EVENT, it) }
            request.channel?.let { put(ReportTargetType.CHANNEL, it) }
        }
        if (updates.isNotEmpty()) {
            updates.forEach { (type, value) ->
                require(value in MIN_VALUE..MAX_VALUE) {
                    "임계치는 ${MIN_VALUE}~${MAX_VALUE} 사이여야 합니다."
                }
                val existing = moderationThresholdSettingRepository.findById(type).orElse(null)
                if (existing != null) {
                    existing.update(value)
                } else {
                    moderationThresholdSettingRepository.save(
                        ModerationThresholdSetting(targetType = type, thresholdValue = value),
                    )
                }
            }
        }
        return getThresholds()
    }

    /**
     * ReportService.maybeAutoHide / AdminModerationService.computePriority 가 호출하는 핫패스.
     * DB row 없으면 default fallback. 트랜잭션 readOnly 이므로 호출자 트랜잭션에 그대로 합류.
     */
    fun thresholdFor(targetType: ReportTargetType): Int {
        return moderationThresholdSettingRepository.findById(targetType)
            .map { it.thresholdValue }
            .orElseGet { DEFAULTS.getValue(targetType) }
    }
}
