package com.contenido.domain.post.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.channel.repository.ChannelSubscriptionRepository
import com.contenido.domain.notification.entity.NotificationType
import com.contenido.domain.notification.service.NotificationService
import com.contenido.domain.post.dto.CreatePostRequest
import com.contenido.domain.post.dto.PostResponse
import com.contenido.domain.post.dto.UpdatePostRequest
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.entity.PostStatus
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.search.service.SearchSyncService
import com.contenido.domain.user.entity.User
import com.contenido.domain.user.entity.UserRole
import com.contenido.domain.user.repository.UserRepository
import com.contenido.global.exception.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PostService(
    private val postRepository: PostRepository,
    private val channelRepository: ChannelRepository,
    private val channelSubscriptionRepository: ChannelSubscriptionRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val searchSyncService: SearchSyncService,
) {

    @Transactional
    fun createPost(userId: Long, channelId: Long, request: CreatePostRequest): PostResponse {
        val user = findActiveUser(userId)
        val channel = findChannel(channelId)

        if (channel.owner.id != userId) throw UnauthorizedException()

        val post = postRepository.save(
            Post(
                channel = channel,
                author = user,
                title = request.title,
                content = request.content,
                thumbnailUrl = request.thumbnailUrl,
            )
        )

        searchSyncService.syncPost(post)

        // 채널 구독자 전원에게 NEW_POST 알림
        val subscriberIds = channelSubscriptionRepository.findByChannel(channel)
            .map { it.subscriber.id }
        runCatching {
            notificationService.notify(
                receiverIds = subscriberIds,
                type = NotificationType.NEW_POST,
                title = "${channel.name}에 새 게시물이 등록되었습니다.",
                message = post.title,
                targetType = "posts",
                targetId = post.id,
            )
        }

        return post.toResponse()
    }

    fun getPosts(channelId: Long, page: Int, size: Int): Page<PostResponse> {
        val channel = findChannel(channelId)
        return postRepository.findByChannelAndStatusOrderByCreatedAtDesc(
            channel, PostStatus.PUBLISHED, PageRequest.of(page, size)
        ).map { it.toResponse() }
    }

    @Transactional
    fun getPost(postId: Long): PostResponse {
        val post = findActivePost(postId)
        post.increaseViewCount()
        return post.toResponse()
    }

    @Transactional
    fun updatePost(userId: Long, postId: Long, request: UpdatePostRequest): PostResponse {
        findActiveUser(userId)
        val post = findActivePost(postId)

        if (post.author.id != userId) throw UnauthorizedException()

        request.title?.let { post.title = it }
        request.content?.let { post.content = it }
        request.thumbnailUrl?.let { post.thumbnailUrl = it }

        return post.toResponse()
    }

    @Transactional
    fun deletePost(userId: Long, postId: Long) {
        val user = findActiveUser(userId)
        val post = findActivePost(postId)

        if (post.author.id != userId && user.role != UserRole.ADMIN) throw UnauthorizedException()

        post.status = PostStatus.DELETED
        searchSyncService.deleteContent("POST", postId)
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findActiveUser(userId: Long): User {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException() }
        if (user.isDeleted) throw DeletedUserException()
        return user
    }

    private fun findChannel(channelId: Long): Channel =
        channelRepository.findById(channelId).orElseThrow { ChannelNotFoundException() }

    private fun findActivePost(postId: Long): Post =
        postRepository.findByIdAndStatus(postId, PostStatus.PUBLISHED)
            ?: throw PostNotFoundException()

    private fun Post.toResponse() = PostResponse(
        id = id,
        channelId = channel.id,
        authorNickname = author.nickname,
        title = title,
        content = content,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        likeCount = likeCount,
        createdAt = createdAt,
    )
}
