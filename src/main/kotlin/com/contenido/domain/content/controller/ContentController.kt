package com.contenido.domain.content.controller

import com.contenido.domain.content.dto.ContentPageResponse
import com.contenido.domain.content.dto.ContentResponse
import com.contenido.domain.content.dto.CreateContentRequest
import com.contenido.domain.content.dto.UpdateContentRequest
import com.contenido.domain.content.service.ContentService
import com.contenido.global.response.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/contents")
class ContentController(
    private val contentService: ContentService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CREATOR')")
    fun createContent(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: CreateContentRequest,
    ): ApiResponse<ContentResponse> {
        return ApiResponse.created(contentService.createContent(userId, request), "콘텐츠가 등록되었습니다.")
    }

    @GetMapping
    fun getContents(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ): ApiResponse<ContentPageResponse> {
        return ApiResponse.ok(contentService.getContents(page, size))
    }

    @GetMapping("/{id}")
    fun getContent(
        @PathVariable id: Long,
    ): ApiResponse<ContentResponse> {
        return ApiResponse.ok(contentService.getContent(id))
    }

    @PatchMapping("/{id}")
    fun updateContent(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateContentRequest,
    ): ApiResponse<ContentResponse> {
        return ApiResponse.ok(contentService.updateContent(userId, id, request), "콘텐츠가 수정되었습니다.")
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteContent(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ) {
        contentService.deleteContent(userId, id)
    }
}
