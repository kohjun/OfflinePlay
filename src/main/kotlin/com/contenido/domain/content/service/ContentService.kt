package com.contenido.domain.content.service

import com.contenido.domain.content.dto.ContentPageResponse
import com.contenido.domain.content.dto.ContentResponse
import com.contenido.domain.content.dto.CreateContentRequest
import com.contenido.domain.content.dto.UpdateContentRequest
import com.contenido.domain.content.entity.Content
import com.contenido.domain.content.entity.ContentStatus
import com.contenido.domain.content.repository.ContentRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ContentService(
    private val contentRepository: ContentRepository,
    private val userRepository: UserRepository,
) {

    @Transactional
    fun createContent(userId: Long, request: CreateContentRequest): ContentResponse {
        val user = findActiveUser(userId)

        if (user.role != UserRole.CREATOR) {
            throw NotCreatorException()
        }

        val content = contentRepository.save(
            Content(
                title = request.title,
                description = request.description,
                thumbnailUrl = request.thumbnailUrl,
                creator = user,
                status = ContentStatus.PUBLISHED,
            )
        )

        return content.toResponse()
    }

    fun getContents(page: Int, size: Int): ContentPageResponse {
        val pageable = PageRequest.of(page, size)
        val result = contentRepository.findAllByStatusOrderByCreatedAtDesc(ContentStatus.PUBLISHED, pageable)

        return ContentPageResponse(
            contents = result.content.map { it.toResponse() },
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            currentPage = result.number,
        )
    }

    @Transactional
    fun getContent(contentId: Long): ContentResponse {
        val content = contentRepository.findByIdAndStatusNot(contentId, ContentStatus.DELETED)
            .orElseThrow { ContentNotFoundException() }

        content.increaseViewCount()

        return content.toResponse()
    }

    @Transactional
    fun updateContent(userId: Long, contentId: Long, request: UpdateContentRequest): ContentResponse {
        findActiveUser(userId)

        val content = contentRepository.findByIdAndStatusNot(contentId, ContentStatus.DELETED)
            .orElseThrow { ContentNotFoundException() }

        if (content.creator.id != userId) {
            throw UnauthorizedContentAccessException()
        }

        request.title?.let { content.title = it }
        request.description?.let { content.description = it }
        request.thumbnailUrl?.let { content.thumbnailUrl = it }

        return content.toResponse()
    }

    @Transactional
    fun deleteContent(userId: Long, contentId: Long) {
        val user = findActiveUser(userId)

        val content = contentRepository.findByIdAndStatusNot(contentId, ContentStatus.DELETED)
            .orElseThrow { ContentNotFoundException() }

        if (content.creator.id != userId && user.role != UserRole.ADMIN) {
            throw UnauthorizedContentAccessException()
        }

        content.status = ContentStatus.DELETED
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun Content.toResponse() = ContentResponse(
        id = id,
        title = title,
        description = description,
        thumbnailUrl = thumbnailUrl,
        creatorId = creator.id,
        creatorNickname = creator.nickname,
        status = status,
        viewCount = viewCount,
        createdAt = createdAt,
    )
}
