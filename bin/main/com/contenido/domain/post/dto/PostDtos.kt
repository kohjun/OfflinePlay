package com.contenido.domain.post.dto

import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

data class CreatePostRequest(
    @field:NotBlank(message = "제목은 필수입니다.")
    val title: String,

    @field:NotBlank(message = "내용은 필수입니다.")
    val content: String,

    val thumbnailUrl: String? = null,
)

data class UpdatePostRequest(
    val title: String? = null,
    val content: String? = null,
    val thumbnailUrl: String? = null,
)

data class PostResponse(
    val id: Long,
    val channelId: Long,
    val authorNickname: String,
    val title: String,
    val content: String,
    val thumbnailUrl: String?,
    val viewCount: Long,
    val likeCount: Long,
    val createdAt: LocalDateTime,
)
