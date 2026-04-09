package com.contenido.domain.content.repository

import com.contenido.domain.content.entity.Content
import com.contenido.domain.content.entity.ContentStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ContentRepository : JpaRepository<Content, Long> {

    fun findAllByStatusOrderByCreatedAtDesc(status: ContentStatus, pageable: Pageable): Page<Content>

    fun findByIdAndStatusNot(id: Long, status: ContentStatus): Optional<Content>
}
