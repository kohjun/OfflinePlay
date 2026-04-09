package com.contenido.domain.interaction.service

import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.interaction.dto.LikeResponse
import com.contenido.domain.interaction.entity.Like
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.interaction.repository.LikeRepository
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class LikeService(
    private val likeRepository: LikeRepository,
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val eventRepository: EventRepository,
) {

    @Transactional
    fun toggleLike(userId: Long, targetType: TargetType, targetId: Long): LikeResponse {
        val user = findActiveUser(userId)
        val alreadyLiked = likeRepository.existsByUserAndTargetTypeAndTargetId(user, targetType, targetId)

        if (alreadyLiked) {
            likeRepository.deleteByUserAndTargetTypeAndTargetId(user, targetType, targetId)
            syncLikeCount(targetType, targetId, delta = -1)
        } else {
            likeRepository.save(Like(user = user, targetType = targetType, targetId = targetId))
            syncLikeCount(targetType, targetId, delta = +1)
        }

        val likeCount = likeRepository.countByTargetTypeAndTargetId(targetType, targetId)

        return LikeResponse(
            targetType = targetType,
            targetId = targetId,
            likeCount = likeCount,
            isLiked = !alreadyLiked,
        )
    }

    fun getLikeStatus(userId: Long?, targetType: TargetType, targetId: Long): LikeResponse {
        val isLiked = userId?.let { uid ->
            val user = userRepository.findById(uid).orElse(null)
            user?.let { likeRepository.existsByUserAndTargetTypeAndTargetId(it, targetType, targetId) } ?: false
        } ?: false

        val likeCount = likeRepository.countByTargetTypeAndTargetId(targetType, targetId)

        return LikeResponse(
            targetType = targetType,
            targetId = targetId,
            likeCount = likeCount,
            isLiked = isLiked,
        )
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun syncLikeCount(targetType: TargetType, targetId: Long, delta: Int) {
        when (targetType) {
            TargetType.POST -> {
                val post = postRepository.findById(targetId).orElseThrow { PostNotFoundException() }
                post.likeCount = (post.likeCount + delta).coerceAtLeast(0)
            }
            TargetType.EVENT -> {
                val event = eventRepository.findById(targetId).orElseThrow { EventNotFoundException() }
                event.likeCount = (event.likeCount + delta).coerceAtLeast(0)
            }
            TargetType.COMMENT -> { /* Comment likeCount은 CommentService에서 관리 */ }
        }
    }
}
