package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.ModerationAuditLogResponse
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLog
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
import com.contenido.domain.admin.repository.ModerationAuditLogSpecs
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.UserNotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * Moderation 감사 로그 기록 + 조회 (PR61, PR62 에서 필터 확장).
 *
 * 기록 전략 (PR61):
 *  - 같은 트랜잭션에 기록 — audit 가 실패하면 원 액션도 rollback. 운영 액션의 추적성을 우선.
 *  - before/after 는 임의 객체 → JSON 문자열로 직렬화. null 그대로 통과.
 *  - 호출자가 actor User 를 들고 있는 경우가 많지만, controller 진입 시 `@AuthenticationPrincipal`
 *    가 Long userId 만 주므로 service 가 actorId 로 사용자 로드.
 *
 * 조회 (PR62):
 *  - 필터: action / targetType / targetId / actorId / from / to. 모두 optional, 채워진 것만 AND.
 *  - [JpaSpecificationExecutor] 로 동적 합성 — derived query 폭증 회피.
 *  - 정렬: createdAt DESC.
 *  - 날짜 입력은 ISO datetime (`2026-05-17T12:30:00`) 또는 date-only (`2026-05-17`) 둘 다 허용.
 *    date-only 일 때 `from` 은 00:00, `to` 는 23:59:59.999999999 로 확장 (inclusive range).
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
        actorId: Long? = null,
        from: String? = null,
        to: String? = null,
    ): Page<ModerationAuditLogResponse> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val spec = ModerationAuditLogSpecs.withFilters(
            action = action,
            targetType = targetType,
            targetId = targetId,
            actorId = actorId,
            from = parseRangeBoundary(from, endOfDay = false),
            to = parseRangeBoundary(to, endOfDay = true),
        )
        return moderationAuditLogRepository.findAll(spec, pageable).map { it.toResponse() }
    }

    private fun serialize(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> runCatching { objectMapper.writeValueAsString(value) }.getOrNull()
    }

    /**
     * `2026-05-17T12:30:00` → 정확히 그 시각.
     * `2026-05-17` → endOfDay=false 면 00:00, true 면 23:59:59.999999999 (inclusive range 용).
     * 빈 문자열/공백/null 은 null. 파싱 실패는 DateTimeParseException 그대로 throw → 400 매핑.
     *
     * `internal` 가시성 — 같은 모듈의 테스트가 직접 호출해 date-only 확장 동작을 검증.
     */
    internal fun parseRangeBoundary(raw: String?, endOfDay: Boolean): LocalDateTime? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if ('T' in s) {
            // ISO_LOCAL_DATE_TIME — 명시적 시각을 그대로 사용.
            LocalDateTime.parse(s)
        } else {
            // date-only — endOfDay 면 23:59:59.999999999, 아니면 자정.
            val date = try {
                LocalDate.parse(s)
            } catch (e: DateTimeParseException) {
                throw e
            }
            if (endOfDay) date.atTime(23, 59, 59, 999_999_999) else date.atStartOfDay()
        }
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
