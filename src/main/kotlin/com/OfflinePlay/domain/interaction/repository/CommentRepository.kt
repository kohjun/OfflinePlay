package com.contenido.domain.interaction.repository

import com.contenido.domain.interaction.entity.Comment
import com.contenido.domain.interaction.entity.TargetType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CommentRepository : JpaRepository<Comment, Long> {

    fun findByTargetTypeAndTargetIdAndParentCommentIsNullOrderByCreatedAtAsc(
        targetType: TargetType,
        targetId: Long,
        pageable: Pageable,
    ): Page<Comment>

    fun findByParentCommentId(parentCommentId: Long): List<Comment>
}
