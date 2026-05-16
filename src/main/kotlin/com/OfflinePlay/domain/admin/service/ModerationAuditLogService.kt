package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.ModerationAuditLogResponse
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLog
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UserNotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Moderation 감사 로그 기록 + 조회 (PR61).
 *
 * 기록 전략:
 *  - 같은 트랜잭션에 기록 — audit 가 실패하면 원 액션도 rollback. 운영 액션의 추적성을 우선.
 *    [@Transactional] propagation 기본값(REQUIRED) 로 호출자 트랜잭션에 합류.
 *  - before/after 는 임의 객체 → JSON 문자열로 직렬화. null 그대로 통과.
 *  - 호출자가 actor User 를 들고 있는 경우가 많지만, controller 진입 시 `@AuthenticationPrincipal`
 *    가 Long userId 만 주므로 service 가 actorId 로 사용자 로드. 추가 SELECT 1회는 audit 의 추적성
 *    가치에 비하면 무시 가능.
 */
@Service
@Transactional(readOnly = true)
class ModerationAuditLogService(
    private val moderationAuditLogRepository: ModerationAuditLogRepository,
    private val userRepository: UserRepository,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun record(
        actorId: Long,
        action: ModerationAuditAction,
        targetType: ReportTargetType? = null,
        targetId: Long? = null,
        beforeValue: Any? = null,
        afterValue: Any? = null,
        reason: String? = null,
    ): ModerationAuditLog {
        val actor = userRepository.findById(actorId).orElseThrow { UserNotFoundException() }
        val log = ModerationAuditLog(
            actor = actor,
            action = action,
            targetType = targetType,
            targetId = targetId,
            beforeValue = serialize(beforeValue),
            afterValue = serialize(afterValue),
            reason = reason?.takeIf { it.isNotBlank() }?.take(500),
        )
        return moderationAuditLogRepository.save(log)
    }

    fun list(
        page: Int,
        size: Int,
        action: ModerationAuditAction? = null,
        targetType: ReportTargetType? = null,
        targetId: Long? = null,
    ): Page<ModerationAuditLogResponse> {
        val pageable = PageRequest.of(page, size)
        val rows = when {
            action != null && targetType != null && targetId != null ->
                moderationAuditLogRepository.findByActionAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                    action, targetType, targetId, pageable,
                )
            action != null && targetType != null ->
                moderationAuditLogRepository.findByActionAndTargetTypeOrderByCreatedAtDesc(
                    action, targetType, pageable,
                )
            action != null ->
                moderationAuditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable)
            targetType != null && targetId != null ->
                moderationAuditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                    targetType, targetId, pageable,
                )
            targetType != null ->
                moderationAuditLogRepository.findByTargetTypeOrderByCreatedAtDesc(targetType, pageable)
            else ->
                moderationAuditLogRepository.findAllByOrderByCreatedAtDesc(pageable)
        }
        return rows.map { it.toResponse() }
    }

    private fun serialize(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> runCatching { objectMapper.writeValueAsString(value) }.getOrNull()
    }

    private fun ModerationAuditLog.toResponse() = ModerationAuditLogResponse(
        id = id,
        actorId = actor.id,
        actorNickname = actor.nickname,
        action = action,
        targetType = targetType,
        targetId = targetId,
        beforeValue = beforeValue,
        afterValue = afterValue,
        reason = reason,
        createdAt = createdAt,
    )
}
