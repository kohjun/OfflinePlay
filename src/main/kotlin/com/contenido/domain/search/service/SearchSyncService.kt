package com.contenido.domain.search.service

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.event.entity.Event
import com.contenido.domain.post.entity.Post
import com.contenido.domain.search.document.ChannelDocument
import com.contenido.domain.search.document.ContentDocument
import com.contenido.domain.search.repository.ChannelSearchRepository
import com.contenido.domain.search.repository.ContentSearchRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SearchSyncService(
    private val channelSearchRepository: ChannelSearchRepository,
    private val contentSearchRepository: ContentSearchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun syncChannel(channel: Channel) {
        runCatching {
            channelSearchRepository.save(channel.toDocument())
        }.onFailure { log.warn("[ES] syncChannel failed: channelId=${channel.id}", it) }
    }

    fun syncEvent(event: Event) {
        runCatching {
            contentSearchRepository.save(event.toDocument())
        }.onFailure { log.warn("[ES] syncEvent failed: eventId=${event.id}", it) }
    }

    fun syncPost(post: Post) {
        runCatching {
            contentSearchRepository.save(post.toDocument())
        }.onFailure { log.warn("[ES] syncPost failed: postId=${post.id}", it) }
    }

    fun deleteContent(sourceType: String, id: Long) {
        runCatching {
            contentSearchRepository.deleteById("${sourceType}_$id")
        }.onFailure { log.warn("[ES] deleteContent failed: ${sourceType}_$id", it) }
    }

    // ── private ──────────────────────────────────────────────────────────────

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
