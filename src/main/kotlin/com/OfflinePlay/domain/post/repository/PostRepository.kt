package com.contenido.domain.post.repository

import com.contenido.domain.channel.entity.Channel
import com.contenido.domain.post.entity.Post
import com.contenido.domain.post.entity.PostStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PostRepository : JpaRepository<Post, Long> {

    fun findByChannelAndStatusOrderByCreatedAtDesc(
        channel: Channel,
        status: PostStatus,
        pageable: Pageable,
    ): Page<Post>

    fun findByIdAndStatus(id: Long, status: PostStatus): Post?

    fun findByChannel(channel: Channel): List<Post>

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + :delta WHERE p.id = :id")
    fun updateLikeCount(@Param("id") id: Long, @Param("delta") delta: Int)

    @Query("SELECT COALESCE(SUM(p.viewCount), 0) FROM Post p WHERE p.channel = :channel AND p.status = 'PUBLISHED'")
    fun sumViewCountByChannel(@Param("channel") channel: Channel): Long

    @Query("SELECT COALESCE(SUM(p.likeCount), 0) FROM Post p WHERE p.channel = :channel AND p.status = 'PUBLISHED'")
    fun sumLikeCountByChannel(@Param("channel") channel: Channel): Long
}
