package com.contenido.domain.content.dto

import com.contenido.domain.content.entity.ContentStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateContentRequest(
    @field:NotBlank(message = "제목은 필수입니다.")
    @field:Size(max = 100, message = "제목은 100자 이하여야 합니다.")
    val title: String,

    @field:NotBlank(message = "설명은 필수입니다.")
    val description: String,

    val thumbnailUrl: String? = null,
)

data class UpdateContentRequest(
    @field:Size(max = 100, message = "제목은 100자 이하여야 합니다.")
    val title: String? = null,

    val description: String? = null,

    val thumbnailUrl: String? = null,
)

data class ContentResponse(
    val id: Long,
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val creatorId: Long,
    val creatorNickname: String,
    val status: ContentStatus,
    val viewCount: Long,
    val createdAt: LocalDateTime,
)

