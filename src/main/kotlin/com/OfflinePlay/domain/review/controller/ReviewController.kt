package com.contenido.domain.review.controller

import com.contenido.domain.review.dto.CreateReviewRequest
import com.contenido.domain.review.dto.EventReviewSummary
import com.contenido.domain.review.dto.ReviewResponse
import com.contenido.domain.review.dto.UpdateReviewRequest
import com.contenido.domain.review.service.ReviewService
import com.contenido.global.response.ApiResponse
import com.contenido.global.response.PageResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 이벤트 후기 (별점 + 본문) API.
 *
 *  - POST   /api/v1/events/{eventId}/reviews          : 본인 후기 작성 (USED 티켓 필수)
 *  - GET    /api/v1/events/{eventId}/reviews          : 이벤트별 후기 페이지 (비로그인 OK)
 *  - GET    /api/v1/events/{eventId}/reviews/me       : 본인이 이미 작성했는지 확인 (없으면 null)
 *  - GET    /api/v1/events/{eventId}/reviews/summary  : 평균/건수 집계 (hero 표시용)
 *  - PATCH  /api/v1/reviews/{reviewId}                : 본인 수정
 *  - DELETE /api/v1/reviews/{reviewId}                : 본인 + ADMIN 삭제
 */
@RestController
@RequestMapping("/api/v1")
class ReviewController(
    private val reviewService: ReviewService,
) {

    @PostMapping("/events/{eventId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
        @Valid @RequestBody request: CreateReviewRequest,
    ): ApiResponse<ReviewResponse> =
        ApiResponse.created(reviewService.createReview(userId, eventId, request), "후기가 작성되었습니다.")

    @GetMapping("/events/{eventId}/reviews")
    fun list(
        @PathVariable eventId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<ReviewResponse>> {
        val pageResult = reviewService.listEventReviews(eventId, page, size)
        return ApiResponse.ok(PageResponse.of(pageResult))
    }

    @GetMapping("/events/{eventId}/reviews/me")
    fun myReview(
        @AuthenticationPrincipal userId: Long,
        @PathVariable eventId: Long,
    ): ApiResponse<ReviewResponse?> =
        ApiResponse.ok(reviewService.getMyReview(userId, eventId))

    @GetMapping("/events/{eventId}/reviews/summary")
    fun summary(
        @PathVariable eventId: Long,
    ): ApiResponse<EventReviewSummary> =
        ApiResponse.ok(reviewService.summaryForEvent(eventId))

    @PatchMapping("/reviews/{reviewId}")
    fun update(
        @AuthenticationPrincipal userId: Long,
        @PathVariable reviewId: Long,
        @Valid @RequestBody request: UpdateReviewRequest,
    ): ApiResponse<ReviewResponse> =
        ApiResponse.ok(reviewService.updateReview(userId, reviewId, request), "후기가 수정되었습니다.")

    @DeleteMapping("/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal userId: Long,
        @PathVariable reviewId: Long,
    ) {
        reviewService.deleteReview(userId, reviewId)
    }
}
