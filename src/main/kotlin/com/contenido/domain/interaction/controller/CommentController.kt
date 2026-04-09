package com.contenido.domain.interaction.controller

import com.contenido.domain.interaction.dto.CommentResponse
import com.contenido.domain.interaction.dto.CreateCommentRequest
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.interaction.service.CommentService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
class CommentController(
    private val commentService: CommentService,
) {

    @PostMapping("/api/v1/{targetType}/{targetId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createComment(
        @AuthenticationPrincipal userId: Long,
        @PathVariable targetType: TargetType,
        @PathVariable targetId: Long,
        @Valid @RequestBody request: CreateCommentRequest,
    ): ApiResponse<CommentResponse> {
        return ApiResponse.created(
            commentService.createComment(userId, targetType, targetId, request),
            "댓글이 등록되었습니다.",
        )
    }

    @GetMapping("/api/v1/{targetType}/{targetId}/comments")
    fun getComments(
        @PathVariable targetType: TargetType,
        @PathVariable targetId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<Page<CommentResponse>> {
        return ApiResponse.ok(commentService.getComments(targetType, targetId, page, size))
    }

    @DeleteMapping("/api/v1/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(
        @AuthenticationPrincipal userId: Long,
        @PathVariable commentId: Long,
    ) {
        commentService.deleteComment(userId, commentId)
    }

    @PostMapping("/api/v1/comments/{commentId}/like")
    fun likeComment(
        @AuthenticationPrincipal userId: Long,
        @PathVariable commentId: Long,
    ): ApiResponse<CommentResponse> {
        return ApiResponse.ok(commentService.likeComment(userId, commentId), "댓글에 좋아요를 눌렀습니다.")
    }
}
