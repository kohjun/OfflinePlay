package com.contenido.domain.admin.service

import com.contenido.domain.admin.dto.ForcedRefundAuditContextResponse
import com.contenido.domain.admin.dto.ModerationAuditLogResponse
import com.contenido.domain.admin.dto.PaymentRefundAuditContextResponse
import com.contenido.domain.admin.entity.ModerationAuditAction
import com.contenido.domain.admin.entity.ModerationAuditLog
import com.contenido.domain.admin.repository.ModerationAuditLogRepository
import com.contenido.domain.admin.repository.ModerationAuditLogSpecs
import com.contenido.domain.report.entity.ReportTargetType
import com.contenido.domain.ticket.repository.TicketRepository
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.ModerationAuditLogNotFoundException
import com.contenido.global.exception.UserNotFoundException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    /**
     * PR115 — `TICKET_FORCED_REFUNDED` audit row 의 detail enrichment 만을 위해 주입. list / CSV /
     * archive 응답은 enrich 하지 않으므로 N+1 부담 없음.
     */
    private val ticketRepository: TicketRepository,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        /**
         * PR63 — CSV export 시 한 번에 반환하는 최대 행 수. 운영자가 한 화면에서 끝까지 훑을 수
         * 있는 양 + 백엔드 메모리 보호. 더 많은 데이터가 필요하면 필터를 더 좁히도록 유도.
         */
        const val MAX_EXPORT_ROWS = 1000

        /** PR63 CSV 행의 createdAt 직렬화 형식. ISO_LOCAL_DATE_TIME 이면 Excel 도 잘 인식. */
        private val CSV_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /** PR63 CSV 헤더 (RFC 4180). 컬럼 순서 변경 시 운영자 외부 도구가 깨질 수 있으니 신중. */
        const val CSV_HEADER =
            "id,createdAt,actorId,actorNickname,action,targetType,targetId,reason,beforeValue,afterValue"

        /** RFC 4180 line terminator. */
        private const val CSV_LINE_TERMINATOR = "\r\n"

        /**
         * PR126 — user refund audit detail enrichment 대상 action 집합. 두 action 모두 같은
         * payload shape (PR122 의 audit JSON) 을 쓰므로 동일 helper 로 처리.
         */
        private val PAYMENT_REFUND_ACTIONS = setOf(
            ModerationAuditAction.PAYMENT_PARTIALLY_REFUNDED,
            ModerationAuditAction.PAYMENT_REFUNDED,
        )
    }

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

    /**
     * PR63 — 단건 상세. id 가 없으면 [ModerationAuditLogNotFoundException].
     * 응답 shape 은 list 와 동일 [ModerationAuditLogResponse].
     *
     * PR115 — `TICKET_FORCED_REFUNDED` 인 row 에 한해 `forcedRefundContext` 를 채워 buyer/event/
     * channel 정보를 함께 반환한다. list / CSV / archive 응답은 enrich 하지 않는다.
     *
     * PR126 — `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` 인 row 에 한해 `paymentRefundContext`
     * 도 함께 채워 동일한 buyer/event/channel 정보 + 환불 금액(this call / cumulative / remaining) +
     * fullRefund 플래그를 반환. 두 컨텍스트는 서로 배타적이라 한 row 가 둘 다 채워지는 일은 없다.
     */
    fun get(id: Long): ModerationAuditLogResponse {
        val log = moderationAuditLogRepository.findById(id)
            .orElseThrow { ModerationAuditLogNotFoundException() }
        return log.toResponse(enrichRefundContexts = true)
    }

    /**
     * PR63 — list 와 동일 필터로 RFC 4180 CSV 직렬화. 최대 [MAX_EXPORT_ROWS] 건.
     * 정렬은 createdAt DESC. comma / quote / newline 포함 필드는 "..." 로 wrap + quote escape.
     *
     * 컨트롤러가 헤더에 X-Export-Limit 을 그대로 노출. BOM 은 붙이지 않는다 — 운영 도구가
     * UTF-8 을 가정한다고 보고 단순 유지. Excel 호환이 더 중요해지면 후속 PR.
     */
    fun exportToCsv(
        action: ModerationAuditAction? = null,
        targetType: ReportTargetType? = null,
        targetId: Long? = null,
        actorId: Long? = null,
        from: String? = null,
        to: String? = null,
    ): String {
        val pageable = PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Direction.DESC, "createdAt"))
        val spec = ModerationAuditLogSpecs.withFilters(
            action = action,
            targetType = targetType,
            targetId = targetId,
            actorId = actorId,
            from = parseRangeBoundary(from, endOfDay = false),
            to = parseRangeBoundary(to, endOfDay = true),
        )
        val rows = moderationAuditLogRepository.findAll(spec, pageable).content
        return buildCsv(rows)
    }

    private fun buildCsv(rows: List<ModerationAuditLog>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append(CSV_LINE_TERMINATOR)
        rows.forEach { log ->
            sb.append(log.id).append(',')
            sb.append(csvEscape(log.createdAt.format(CSV_DATE_FORMAT))).append(',')
            sb.append(log.actor.id).append(',')
            sb.append(csvEscape(log.actor.nickname)).append(',')
            sb.append(log.action.name).append(',')
            sb.append(log.targetType?.name ?: "").append(',')
            sb.append(log.targetId?.toString() ?: "").append(',')
            sb.append(csvEscape(log.reason)).append(',')
            sb.append(csvEscape(log.beforeValue)).append(',')
            sb.append(csvEscape(log.afterValue))
            sb.append(CSV_LINE_TERMINATOR)
        }
        return sb.toString()
    }

    /**
     * RFC 4180 escape. null 은 빈 문자열. comma/quote/CR/LF 가 포함되면 "..." 로 감싸고
     * 내부 quote 를 "" 로 escape. 그 외는 원문 그대로.
     */
    internal fun csvEscape(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val needsQuote =
            value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\""
        else value
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

    private fun ModerationAuditLog.toResponse(enrichRefundContexts: Boolean = false) = ModerationAuditLogResponse(
        id = id,
        actorId = actor.id,
        actorNickname = actor.nickname,
        actorSystem = actor.email == SystemActorService.SYSTEM_ACTOR_EMAIL,
        action = action,
        targetType = targetType,
        targetId = targetId,
        beforeValue = beforeValue,
        afterValue = afterValue,
        reason = reason,
        createdAt = createdAt,
        forcedRefundContext = if (enrichRefundContexts && action == ModerationAuditAction.TICKET_FORCED_REFUNDED)
            buildForcedRefundContext(afterValue)
        else null,
        paymentRefundContext = if (enrichRefundContexts && action in PAYMENT_REFUND_ACTIONS)
            buildPaymentRefundContext(afterValue)
        else null,
    )

    /**
     * PR115 — `TICKET_FORCED_REFUNDED` row 의 `afterValue` JSON 을 best-effort 파싱하고 ticket 을
     * lookup 해서 운영자가 한 화면에서 buyer/event/channel 까지 확인할 수 있는 context 를 만든다.
     *
     * 정책:
     *  - 본 함수는 절대 throw 하지 않는다 — detail 자체가 enrichment 실패로 깨지면 안 됨.
     *  - afterValue 가 null / 빈 문자열 / 파싱 실패 → contextAvailable=false + 모든 필드 null.
     *  - ticketId 가 JSON 에 있으면 [TicketRepository] 로 단건 조회. 성공 시 buyer/event/channel
     *    필드를 채움 (LAZY 연관관계가 readOnly 트랜잭션 안에서 정상 fetch 됨).
     *  - ticket 조회 실패 (삭제·없음) → JSON 에서 파싱한 값만 그대로 두고 contextAvailable=false.
     *  - amount 는 backend Long. JSON 에서 정수가 아닌 경우 null.
     *  - ticketStatus 는 JSON 의 문자열 그대로 — enum 변환을 시도하지 않는다 (호환).
     */
    private fun buildForcedRefundContext(afterValue: String?): ForcedRefundAuditContextResponse {
        val empty = ForcedRefundAuditContextResponse(
            ticketId = null, paymentAttemptId = null, amount = null, ticketStatus = null,
            buyerId = null, buyerNickname = null, buyerEmail = null,
            eventId = null, eventTitle = null, channelId = null, channelName = null,
            contextAvailable = false,
        )
        if (afterValue.isNullOrBlank()) return empty
        val node: JsonNode = try {
            objectMapper.readTree(afterValue)
        } catch (e: Exception) {
            return empty
        }
        if (!node.isObject) return empty

        val ticketId = node.get("ticketId")?.takeIf { it.canConvertToLong() }?.asLong()
        val paymentAttemptId = node.get("paymentAttemptId")?.takeIf { it.canConvertToLong() }?.asLong()
        val amount = node.get("amount")?.takeIf { it.canConvertToLong() }?.asLong()
        val ticketStatusFromJson = node.get("ticketStatus")?.takeIf { it.isTextual }?.asText()

        val ticket = ticketId?.let { id ->
            runCatching { ticketRepository.findById(id).orElse(null) }.getOrNull()
        }
        if (ticket == null) {
            return ForcedRefundAuditContextResponse(
                ticketId = ticketId, paymentAttemptId = paymentAttemptId, amount = amount,
                ticketStatus = ticketStatusFromJson,
                buyerId = null, buyerNickname = null, buyerEmail = null,
                eventId = null, eventTitle = null, channelId = null, channelName = null,
                contextAvailable = false,
            )
        }
        val buyer = ticket.buyer
        val event = ticket.event
        val channel = event.channel
        return ForcedRefundAuditContextResponse(
            ticketId = ticket.id,
            paymentAttemptId = paymentAttemptId,
            amount = amount,
            // 현재 ticket.status 를 우선 (refund 후라면 REFUNDED). JSON 값은 audit 시점 snapshot 으로 fallback.
            ticketStatus = ticket.status.name,
            buyerId = buyer.id,
            buyerNickname = buyer.nickname,
            buyerEmail = buyer.email,
            eventId = event.id,
            eventTitle = event.title,
            channelId = channel.id,
            channelName = channel.name,
            contextAvailable = true,
        )
    }

    /**
     * PR126 — `PAYMENT_PARTIALLY_REFUNDED` / `PAYMENT_REFUNDED` row 의 `afterValue` JSON 을
     * best-effort 파싱 + ticket lookup. [buildForcedRefundContext] 와 정책이 동일:
     *
     *  - 본 함수는 절대 throw 하지 않는다.
     *  - afterValue null / blank / 비 object / 파싱 실패 → contextAvailable=false + 전 필드 null.
     *  - ticketId JSON 파싱 성공 + ticketRepository lookup 성공 → buyer/event/channel 채움 +
     *    contextAvailable=true.
     *  - ticket 이 사라졌거나 JSON 에 ticketId 가 없는 경우 → JSON 파싱 값만 두고
     *    contextAvailable=false.
     *  - 세 금액(refundAmount / refundedAmount / remainingRefundableAmount) 과 두 상태
     *    (ticketStatus / paymentStatus), fullRefund 플래그 — 모두 PR122 audit JSON 그대로 노출.
     *    enum 변환은 시도하지 않음 (역사적 호환 대비 문자열 그대로).
     */
    private fun buildPaymentRefundContext(afterValue: String?): PaymentRefundAuditContextResponse {
        val empty = PaymentRefundAuditContextResponse(
            ticketId = null, paymentAttemptId = null, eventId = null,
            refundAmount = null, refundedAmount = null, remainingRefundableAmount = null,
            ticketStatus = null, paymentStatus = null, fullRefund = null,
            buyerId = null, buyerNickname = null, buyerEmail = null,
            eventTitle = null, channelId = null, channelName = null,
            contextAvailable = false,
        )
        if (afterValue.isNullOrBlank()) return empty
        val node: JsonNode = try {
            objectMapper.readTree(afterValue)
        } catch (e: Exception) {
            return empty
        }
        if (!node.isObject) return empty

        val ticketId = node.get("ticketId")?.takeIf { it.canConvertToLong() }?.asLong()
        val paymentAttemptId = node.get("paymentAttemptId")?.takeIf { it.canConvertToLong() }?.asLong()
        val eventIdFromJson = node.get("eventId")?.takeIf { it.canConvertToLong() }?.asLong()
        val refundAmount = node.get("refundAmount")?.takeIf { it.canConvertToLong() }?.asLong()
        val refundedAmount = node.get("refundedAmount")?.takeIf { it.canConvertToLong() }?.asLong()
        val remainingRefundableAmount =
            node.get("remainingRefundableAmount")?.takeIf { it.canConvertToLong() }?.asLong()
        val ticketStatusFromJson = node.get("ticketStatus")?.takeIf { it.isTextual }?.asText()
        val paymentStatusFromJson = node.get("paymentStatus")?.takeIf { it.isTextual }?.asText()
        val fullRefund = node.get("fullRefund")?.takeIf { it.isBoolean }?.asBoolean()

        val ticket = ticketId?.let { id ->
            runCatching { ticketRepository.findById(id).orElse(null) }.getOrNull()
        }
        if (ticket == null) {
            return PaymentRefundAuditContextResponse(
                ticketId = ticketId, paymentAttemptId = paymentAttemptId, eventId = eventIdFromJson,
                refundAmount = refundAmount, refundedAmount = refundedAmount,
                remainingRefundableAmount = remainingRefundableAmount,
                ticketStatus = ticketStatusFromJson, paymentStatus = paymentStatusFromJson,
                fullRefund = fullRefund,
                buyerId = null, buyerNickname = null, buyerEmail = null,
                eventTitle = null, channelId = null, channelName = null,
                contextAvailable = false,
            )
        }
        val buyer = ticket.buyer
        val event = ticket.event
        val channel = event.channel
        return PaymentRefundAuditContextResponse(
            ticketId = ticket.id,
            paymentAttemptId = paymentAttemptId,
            // event row 의 실제 id 우선 — JSON snapshot 이 mismatch 일 가능성은 낮지만 ticket 기준이 정답.
            eventId = event.id,
            refundAmount = refundAmount,
            refundedAmount = refundedAmount,
            remainingRefundableAmount = remainingRefundableAmount,
            // 현재 ticket.status 우선. JSON 은 audit 시점 snapshot 으로 fallback.
            ticketStatus = ticket.status.name,
            paymentStatus = paymentStatusFromJson,
            fullRefund = fullRefund,
            buyerId = buyer.id,
            buyerNickname = buyer.nickname,
            buyerEmail = buyer.email,
            eventTitle = event.title,
            channelId = channel.id,
            channelName = channel.name,
            contextAvailable = true,
        )
    }
}
