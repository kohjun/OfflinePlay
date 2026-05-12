package com.contenido.domain.impact.repository

import com.contenido.domain.impact.entity.ImpactIndex
import com.contenido.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ImpactIndexRepository : JpaRepository<ImpactIndex, Long> {

    fun findByPlanner(planner: User): Optional<ImpactIndex>
}
