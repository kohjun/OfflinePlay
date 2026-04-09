package com.contenido.domain.event.controller

import com.contenido.domain.event.dto.CreateEventRequest
import com.contenido.domain.event.dto.EventResponse
import com.contenido.domain.event.service.EventService
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse // 1. PageResponse 임포트 추가
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/channels/{channelId}/events")
class EventController(
    private val eventService: EventService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createEvent(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: CreateEventRequest,
    ): ApiResponse<EventResponse> {
        return ApiResponse.created(eventService.createEvent(userId, channelId, request), "이벤트가 생성되었습니다.")
    }

    @GetMapping
    fun getEvents(
        @PathVariable channelId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ): ApiResponse<PageResponse<EventResponse>> { // 2. Page 대신 PageResponse로 타입 변경
        val eventsPage: Page<EventResponse> = eventService.getEvents(channelId, page, size)
        
        // 3. PageResponse.of() 를 사용하여 Page 객체를 공통 포맷으로 감싸서 반환
        return ApiResponse.ok(PageResponse.of(eventsPage))
    }

    @GetMapping("/{eventId}")
    fun getEvent(
        @PathVariable channelId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<EventResponse> {
        return ApiResponse.ok(eventService.getEvent(eventId))
    }

    @PostMapping("/{eventId}/join")
    fun joinEvent(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<Nothing> {
        eventService.joinEvent(userId, eventId)
        return ApiResponse.ok("이벤트에 참여했습니다.")
    }

    @DeleteMapping("/{eventId}/join")
    fun cancelJoin(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<Nothing> {
        eventService.cancelJoin(userId, eventId)
        return ApiResponse.ok("이벤트 참여를 취소했습니다.")
    }
}