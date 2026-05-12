package com.contenido.domain.channel.controller

import com.contenido.domain.channel.dto.AddStaffRequest
import com.contenido.domain.channel.dto.ChannelMemberResponse
import com.contenido.domain.channel.service.ChannelMemberService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 채널 운영팀(OWNER/STAFF) 관리. OWNER 또는 ADMIN 만 접근 가능 — 서비스 레이어에서 검증.
 */
@RestController
@RequestMapping("/api/v1/channels/{channelId}/members")
class ChannelMemberController(
    private val channelMemberService: ChannelMemberService,
) {

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
    ): ApiResponse<List<ChannelMemberResponse>> =
        ApiResponse.ok(channelMemberService.listMembers(userId, channelId))

    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    fun addStaff(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: AddStaffRequest,
    ): ApiResponse<ChannelMemberResponse> =
        ApiResponse.created(channelMemberService.addStaff(userId, channelId, request), "스태프를 추가했어요.")

    @DeleteMapping("/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    fun remove(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @PathVariable memberId: Long,
    ) {
        channelMemberService.removeMember(userId, channelId, memberId)
    }
}
