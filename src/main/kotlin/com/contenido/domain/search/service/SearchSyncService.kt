package com.contenido.domain.search.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.channel.repository.ChannelRepository
import com.contenido.domain.event.entity.Event
import com.contenido.domain.event.repository.EventRepository
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.repository.PostRepository
import com.contenido.domain.search.document.ChannelDocument
import com.contenido.domain.search.document.ContentDocument
import com.contenido.domain.search.repository.ChannelSearchRepository
import com.contenido.domain.search.repository.ContentSearchRepository
import com.contenido.global.event.ChannelSyncEvent
import com.contenido.global.event.ContentSyncAction
import com.contenido.global.event.ContentSyncEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class SearchSyncService(
    private val channelSearchRepository: ChannelSearchRepository,
    private val contentSearchRepository: ContentSearchRepository,
    private val channelRepository: ChannelRepository,
    private val postRepository: PostRepository,
    private val eventRepository: EventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handleChannelSync(event: ChannelSyncEvent) {
        channelRepository.findById(event.channelId).ifPresent { syncChannel(it) }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handleContentSync(event: ContentSyncEvent) {
        when (event.action) {
            ContentSyncAction.SYNC -> when (event.sourceType) {
                "POST" -> postRepository.findById(event.entityId).ifPresent { syncPost(it) }
                "EVENT" -> eventRepository.findById(event.entityId).ifPresent { syncEvent(it) }
            }
            ContentSyncAction.DELETE -> deleteContent(event.sourceType, event.entityId)
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun syncChannel(channel: Channel) {
        runCatching {
            channelSearchRepository.save(channel.toDocument())
        }.onFailure { log.warn("[ES] syncChannel failed: channelId=${channel.id}", it) }
    }

    private fun syncEvent(event: Event) {
        runCatching {
            contentSearchRepository.save(event.toDocument())
        }.onFailure { log.warn("[ES] syncEvent failed: eventId=${event.id}", it) }
    }

    private fun syncPost(post: Post) {
        runCatching {
            contentSearchRepository.save(post.toDocument())
        }.onFailure { log.warn("[ES] syncPost failed: postId=${post.id}", it) }
    }

    private fun deleteContent(sourceType: String, id: Long) {
        runCatching {
            contentSearchRepository.deleteById("${sourceType}_$id")
        }.onFailure { log.warn("[ES] deleteContent failed: ${sourceType}_$id", it) }
    }

    private fun Channel.toDocument() = ChannelDocument(
        id = id.toString(),
        name = name,
        description = description,
        category = category.name,
        categoryDisplayName = category.displayName,
        ownerNickname = owner.nickname,
        subscriberCount = subscriberCount,
        thumbnailUrl = thumbnailUrl,
        createdAt = createdAt.toString(),
    )

    private fun Event.toDocument() = ContentDocument(
        id = "EVENT_$id",
        sourceType = "EVENT",
        channelId = channel.id,
        channelName = channel.name,
        category = channel.category.name,
        title = title,
        description = description,
        authorNickname = channel.owner.nickname,
        viewCount = 0,
        likeCount = likeCount,
        createdAt = createdAt.toString(),
    )

    private fun Post.toDocument() = ContentDocument(
        id = "POST_$id",
        sourceType = "POST",
        channelId = channel.id,
        channelName = channel.name,
        category = channel.category.name,
        title = title,
        description = content,
        authorNickname = author.nickname,
        viewCount = viewCount,
        likeCount = likeCount,
        createdAt = createdAt.toString(),
    )
}
