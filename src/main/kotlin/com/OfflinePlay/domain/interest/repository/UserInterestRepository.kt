package com.contenido.domain.interest.repository

import com.contenido.domain.interest.entity.UserInterest
import com.contenido.domain.interest.entity.UserInterestId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserInterestRepository : JpaRepository<UserInterest, UserInterestId> {

    fun findByUserId(userId: Long): List<UserInterest>

    @Modifying
    @Query("DELETE FROM UserInterest ui WHERE ui.userId = :userId AND ui.interestId IN :interestIds")
    fun deleteByUserIdAndInterestIdIn(
        @Param("userId") userId: Long,
        @Param("interestIds") interestIds: Collection<Long>,
    ): Int
}
