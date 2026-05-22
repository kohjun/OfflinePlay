package com.contenido.domain.event.controller

import com.contenido.domain.event.dto.EventChatHistoryResponse
import com.contenido.domain.event.dto.EventChatMessageResponse
import com.contenido.domain.event.dto.SendEventChatMessageRequest
import com.contenido.domain.event.service.EventChatService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

/**
 * PR160 — 이벤트룸 채팅 endpoint.
 *
 *  - `GET /messages?beforeCreatedAt=&beforeId=&size=` — 히스토리. cursor 페이징.
 *  - `POST /messages` — 메시지 송신 (text only). 일반 / 공지 둘 다 본 endpoint.
 *  - `GET /can-enter` — 권한 가드만 빠르게 체크. 200 OK 면 입장 가능.
 *
 * 실시간 수신은 별도 endpoint 가 아니라 `/api/v1/notifications/connect` (PR139 SSE) 의
 * `event-chat` named event 로 흘러간다.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/chat")
class EventChatController(
    private val eventChatService: EventChatService,
) {

    @GetMapping("/can-enter")
    fun canEnter(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<Boolean> {
        eventChatService.assertCanEnter(userId, eventId)
        return ApiResponse.ok(true)
    }

    @GetMapping("/messages")
    fun history(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        beforeCreatedAt: LocalDateTime?,
        @RequestParam(required = false) beforeId: Long?,
        @RequestParam(defaultValue = "50") size: Int,
    ): ApiResponse<EventChatHistoryResponse> {
        return ApiResponse.ok(
            eventChatService.history(userId, eventId, beforeCreatedAt, beforeId, size),
        )
    }

    @PostMapping("/messages")
    fun send(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @Valid @RequestBody request: SendEventChatMessageRequest,
    ): ApiResponse<EventChatMessageResponse> {
        return ApiResponse.created(eventChatService.send(userId, eventId, request))
    }
}
