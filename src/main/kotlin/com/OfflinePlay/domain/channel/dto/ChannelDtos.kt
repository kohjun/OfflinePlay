package com.contenido.domain.channel.dto

import com.contenido.domain.channel.entity.ChannelCategory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateChannelRequest(
    @field:NotBlank(message = "채널명은 필수입니다.")
    @field:Size(max = 100, message = "채널명은 100자 이하여야 합니다.")
    val name: String,

    @field:NotBlank(message = "채널 설명은 필수입니다.")
    val description: String,

    @field:NotNull(message = "카테고리는 필수입니다.")
    val category: ChannelCategory,

    val thumbnailUrl: String? = null,
)

data class UpdateChannelRequest(
    @field:Size(max = 100, message = "채널명은 100자 이하여야 합니다.")
    val name: String? = null,

    val description: String? = null,

    val thumbnailUrl: String? = null,
)

data class ChannelResponse(
    val id: Long,
    val ownerNickname: String,
    val name: String,
    val description: String,
    val category: ChannelCategory,
    val categoryDisplayName: String,
    val thumbnailUrl: String?,
    val subscriberCount: Long,
    val createdAt: LocalDateTime,
)

data class ChannelDetailResponse(
    val id: Long,
    val ownerNickname: String,
    val name: String,
    val description: String,
    val category: ChannelCategory,
    val categoryDisplayName: String,
    val thumbnailUrl: String?,
    val subscriberCount: Long,
    val createdAt: LocalDateTime,
    val isSubscribed: Boolean,
)
