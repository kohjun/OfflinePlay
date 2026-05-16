package com.contenido.domain.review.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateReviewRequest(
    @field:Min(1, message = "별점은 1 이상이어야 합니다.")
    @field:Max(5, message = "별점은 5 이하여야 합니다.")
    val rating: Int,

    @field:NotBlank(message = "후기 본문은 비어 있을 수 없습니다.")
    @field:Size(max = 1000, message = "후기는 1000자 이하여야 합니다.")
    val content: String,
)

data class UpdateReviewRequest(
    @field:Min(1, message = "별점은 1 이상이어야 합니다.")
    @field:Max(5, message = "별점은 5 이하여야 합니다.")
    val rating: Int,

    @field:NotBlank(message = "후기 본문은 비어 있을 수 없습니다.")
    @field:Size(max = 1000, message = "후기는 1000자 이하여야 합니다.")
    val content: String,
)

data class ReviewResponse(
    val id: Long,
    val eventId: Long,
    val authorId: Long,
    val authorNickname: String,
    val rating: Int,
    val content: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/**
 * 이벤트 단건 hero 등에 노출할 후기 집계.
 * 후기가 0건이면 averageRating=null, reviewCount=0.
 */
data class EventReviewSummary(
    val averageRating: Double?,
    val reviewCount: Long,
)
