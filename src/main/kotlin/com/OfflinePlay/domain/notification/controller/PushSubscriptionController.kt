package com.contenido.domain.notification.controller

import com.contenido.domain.notification.dto.PushSubscriptionRequest
import com.contenido.domain.notification.dto.PushSubscriptionResponse
import com.contenido.domain.notification.dto.PushSubscriptionUnsubscribeRequest
import com.contenido.domain.notification.service.PushSubscriptionService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/**
 * PR139 — Web Push 구독 등록/해제/조회.
 *
 * 모든 엔드포인트는 인증 사용자 전용 (`anyRequest().authenticated()`). 실제 push 발송은 PR140 에서.
 */
@RestController
@RequestMapping("/api/v1/push/subscriptions")
class PushSubscriptionController(
    private val pushSubscriptionService: PushSubscriptionService,
) {

    /** 새 구독 등록 또는 기존 endpoint credential 갱신. */
    @PostMapping
    fun subscribe(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: PushSubscriptionRequest,
    ): ApiResponse<PushSubscriptionResponse> {
        return ApiResponse.ok(pushSubscriptionService.subscribe(userId, request), "푸시 구독을 등록했습니다.")
    }

    /**
     * 명시적 구독 해지 — row hard delete. 410/expired 같은 self-healing 은 PR140 에서 별도로 처리.
     * 응답 message 가 삭제된 row 수를 알려준다 (0 이면 이미 없었던 endpoint).
     */
    @DeleteMapping
    fun unsubscribe(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: PushSubscriptionUnsubscribeRequest,
    ): ApiResponse<Int> {
        val removed = pushSubscriptionService.unsubscribe(userId, request.endpoint)
        return ApiResponse.ok(removed, "푸시 구독을 해지했습니다.")
    }

    /** 내 활성/비활성 구독 전체. UI 가 디바이스 목록을 보여줄 때 사용. */
    @GetMapping("/me")
    fun listMine(
        @AuthenticationPrincipal userId: Long,
    ): ApiResponse<List<PushSubscriptionResponse>> {
        return ApiResponse.ok(pushSubscriptionService.listMine(userId))
    }
}
