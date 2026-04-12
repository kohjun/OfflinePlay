package com.contenido.domain.interaction.service

import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.dto.CommentResponse
import com.contenido.domain.interaction.dto.CreateCommentRequest
import com.contenido.domain.interaction.entity.Comment
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.interaction.repository.CommentRepository
import com.contenido.domain.interaction.repository.LikeRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import com.contenido.global.util.HtmlSanitizer
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CommentService(
    private val commentRepository: CommentRepository,
    private val likeRepository: LikeRepository,
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val eventRepository: EventRepository,
    private val notificationService: NotificationService,
) {

    @Transactional
    fun createComment(
        userId: Long,
        targetType: TargetType,
        targetId: Long,
        request: CreateCommentRequest,
    ): CommentResponse {
        val user = findActiveUser(userId)

        val parentComment = request.parentCommentId?.let {
            commentRepository.findById(it).orElseThrow { CommentNotFoundException() }
        }

        val comment = commentRepository.save(
            Comment(
                author = user,
                targetType = targetType,
                targetId = targetId,
                content = HtmlSanitizer.sanitize(request.content),
                parentComment = parentComment,
            )
        )

        // 게시물/이벤트 작성자에게 NEW_COMMENT 알림 (자기 자신 제외)
        val ownerId = resolveTargetOwnerId(targetType, targetId)
        if (ownerId != null && ownerId != userId) {
            runCatching {
                notificationService.notify(
                    receiverIds = listOf(ownerId),
                    type = NotificationType.NEW_COMMENT,
                    title = "새 댓글이 달렸습니다.",
                    message = comment.content.take(50),
                    targetType = targetType.pathSegment,
                    targetId = targetId,
                )
            }
        }

        return comment.toResponse(emptyList())
    }

    fun getComments(targetType: TargetType, targetId: Long, page: Int, size: Int): Page<CommentResponse> {
        val pageable = PageRequest.of(page, size)
        return commentRepository
            .findByTargetTypeAndTargetIdAndParentCommentIsNullOrderByCreatedAtAsc(targetType, targetId, pageable)
            .map { comment ->
                val replies = commentRepository.findByParentCommentId(comment.id)
                    .map { it.toResponse(emptyList()) }
                comment.toResponse(replies)
            }
    }

    @Transactional
    fun deleteComment(userId: Long, commentId: Long) {
        val comment = commentRepository.findById(commentId)
            .orElseThrow { CommentNotFoundException() }

        if (comment.author.id != userId) throw UnauthorizedException()
        if (comment.isDeleted) return

        comment.softDelete()
    }

    @Transactional
    fun likeComment(userId: Long, commentId: Long): CommentResponse {
        val user = findActiveUser(userId)
        val comment = commentRepository.findById(commentId)
            .orElseThrow { CommentNotFoundException() }

        val alreadyLiked = likeRepository.existsByUserAndTargetTypeAndTargetId(user, TargetType.COMMENT, commentId)
        if (!alreadyLiked) {
            likeRepository.save(
                com.contenido.domain.interaction.entity.Like(
                    user = user,
                    targetType = TargetType.COMMENT,
                    targetId = commentId,
                )
            )
            comment.likeCount++

            // 댓글 작성자에게 NEW_LIKE 알림 (자기 자신 제외)
            if (comment.author.id != userId) {
                runCatching {
                    notificationService.notify(
                        receiverIds = listOf(comment.author.id),
                        type = NotificationType.NEW_LIKE,
                        title = "내 댓글에 좋아요가 달렸습니다.",
                        message = comment.content.take(50),
                        targetType = TargetType.COMMENT.pathSegment,
                        targetId = commentId,
                    )
                }
            }
        }

        val replies = commentRepository.findByParentCommentId(comment.id).map { it.toResponse(emptyList()) }
        return comment.toResponse(replies)
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun resolveTargetOwnerId(targetType: TargetType, targetId: Long): Long? = when (targetType) {
        TargetType.POST -> postRepository.findById(targetId).orElse(null)?.author?.id
        TargetType.EVENT -> eventRepository.findById(targetId).orElse(null)?.channel?.owner?.id
        TargetType.COMMENT -> null
    }

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun Comment.toResponse(replies: List<CommentResponse>) = CommentResponse(
        id = id,
        authorNickname = if (isDeleted) "알 수 없음" else author.nickname,
        content = if (isDeleted) "삭제된 댓글입니다." else content,
        likeCount = likeCount,
        parentCommentId = parentComment?.id,
        replies = replies,
        createdAt = createdAt,
        isDeleted = isDeleted,
    )
}
