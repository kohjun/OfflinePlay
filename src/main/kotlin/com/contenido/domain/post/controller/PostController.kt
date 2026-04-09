package com.contenido.domain.post.controller

import com.contenido.domain.post.dto.CreatePostRequest
import com.contenido.domain.post.dto.PostResponse
import com.contenido.domain.post.dto.UpdatePostRequest
import com.contenido.domain.post.service.PostService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/channels/{channelId}/posts")
class PostController(
    private val postService: PostService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createPost(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: CreatePostRequest,
    ): ApiResponse<PostResponse> {
        return ApiResponse.created(postService.createPost(userId, channelId, request), "게시물이 등록되었습니다.")
    }

    @GetMapping
    fun getPosts(
        @PathVariable channelId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ): ApiResponse<Page<PostResponse>> {
        return ApiResponse.ok(postService.getPosts(channelId, page, size))
    }

    @GetMapping("/{postId}")
    fun getPost(
        @PathVariable channelId: Long,
        @PathVariable postId: Long,
    ): ApiResponse<PostResponse> {
        return ApiResponse.ok(postService.getPost(postId))
    }

    @PatchMapping("/{postId}")
    fun updatePost(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @PathVariable postId: Long,
        @Valid @RequestBody request: UpdatePostRequest,
    ): ApiResponse<PostResponse> {
        return ApiResponse.ok(postService.updatePost(userId, postId, request), "게시물이 수정되었습니다.")
    }

    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePost(
        @AuthenticationPrincipal userId: Long,
        @PathVariable channelId: Long,
        @PathVariable postId: Long,
    ) {
        postService.deletePost(userId, postId)
    }
}
