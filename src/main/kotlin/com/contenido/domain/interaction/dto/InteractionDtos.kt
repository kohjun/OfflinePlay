package com.contenido.domain.interaction.dto

import com.contenido.domain.interaction.entity.TargetType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateCommentRequest(
    @field:NotBlank(message = "댓글 내용은 필수입니다.")
    @field:Size(max = 500, message = "댓글은 500자 이하여야 합니다.")
    val content: String,

    val parentCommentId: Long? = null,
)

data class CommentResponse(
    val id: Long,
    val authorNickname: String,
    val content: String,
    val likeCount: Long,
    val parentCommentId: Long?,
    val replies: List<CommentResponse>,
    val createdAt: LocalDateTime,
    val isDeleted: Boolean,
)

data class LikeResponse(
    val targetType: TargetType,
    val targetId: Long,
    val likeCount: Long,
    val isLiked: Boolean,
)
