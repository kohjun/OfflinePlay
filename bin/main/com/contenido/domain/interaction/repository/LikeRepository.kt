package com.contenido.domain.interaction.repository

import com.contenido.domain.interaction.entity.Like
import com.contenido.domain.interaction.entity.TargetType
import com.contenido.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository

interface LikeRepository : JpaRepository<Like, Long> {

    fun existsByUserAndTargetTypeAndTargetId(user: User, targetType: TargetType, targetId: Long): Boolean

    fun countByTargetTypeAndTargetId(targetType: TargetType, targetId: Long): Long

    fun deleteByUserAndTargetTypeAndTargetId(user: User, targetType: TargetType, targetId: Long)
}
