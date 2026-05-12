package com.contenido.domain.event.controller

import com.contenido.domain.event.dto.EventResponse
import com.contenido.domain.event.dto.UpdateEventRequest
import com.contenido.domain.event.service.EventService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 채널 prefix 없이 eventId 만으로 이벤트를 조회/수정한다.
 *
 * 조회는 비로그인 허용 — SecurityConfig 의 `GET /api/v1/events/{id}` permitAll 규칙이 처리.
 * 수정은 owner 또는 ADMIN 만. 기존 `/channels/{cid}/events/{eid}` 는 그대로 유지된다.
 */
@RestController
@RequestMapping("/api/v1/events")
class EventDetailController(
    private val eventService: EventService,
) {

    @GetMapping("/{eventId}")
    fun getEvent(@PathVariable eventId: Long): ApiResponse<EventResponse> =
        ApiResponse.ok(eventService.getEvent(eventId))

    @PatchMapping("/{eventId}")
    @PreAuthorize("isAuthenticated()")
    fun updateEvent(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @Valid @RequestBody request: UpdateEventRequest,
    ): ApiResponse<EventResponse> =
        ApiResponse.ok(eventService.updateEvent(userId, eventId, request), "이벤트가 수정되었어요.")
}
