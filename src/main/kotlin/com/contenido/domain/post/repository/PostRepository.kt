package com.contenido.domain.post.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.entity.PostStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PostRepository : JpaRepository<Post, Long> {

    fun findByChannelAndStatusOrderByCreatedAtDesc(
        channel: Channel,
        status: PostStatus,
        pageable: Pageable,
    ): Page<Post>

    fun findByIdAndStatus(id: Long, status: PostStatus): Post?
}
