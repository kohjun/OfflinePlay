package com.contenido.domain.interaction.repository

import com.contenido.domain.interaction.entity.Comment
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface CommentRepository : JpaRepository<Comment, Long> {

    fun findByTargetTypeAndTargetIdAndParentCommentIsNullOrderByCreatedAtAsc(
        targetType: TargetType,
        targetId: Long,
        pageable: Pageable,
    ): Page<Comment>

    /** PR51 — 자동 숨김 제외 버전. 사용자 댓글 목록 조회 진입점. */
    fun findByTargetTypeAndTargetIdAndParentCommentIsNullAndHiddenAtIsNullOrderByCreatedAtAsc(
        targetType: TargetType,
        targetId: Long,
        pageable: Pageable,
    ): Page<Comment>

    fun findByParentCommentId(parentCommentId: Long): List<Comment>

    /** PR51 — 답글 조회에서 자동 숨김 제외. */
    fun findByParentCommentIdAndHiddenAtIsNull(parentCommentId: Long): List<Comment>

    /** PR53 — 작성자 본인의 자동 숨김 댓글. */
    fun findByAuthorAndHiddenAtIsNotNullOrderByHiddenAtDesc(author: User): List<Comment>

    /** PR55 — Admin moderation queue 빌드. 작성자 무관, 모든 hidden 댓글. */
    fun findByHiddenAtIsNotNullOrderByHiddenAtDesc(): List<Comment>
}
