package com.contenido.domain.interest.repository

import com.contenido.domain.interest.entity.Interest
import org.springframework.data.jpa.repository.JpaRepository

/**
 * PR147 — 관심사 카탈로그 read-only access. seed 는 V16 migration.
 */
interface InterestRepository : JpaRepository<Interest, Long> {

    fun findBySlug(slug: String): Interest?

    /** 카탈로그 전체 정렬 (category asc, displayOrder asc). */
    fun findAllByOrderByCategoryAscDisplayOrderAsc(): List<Interest>

    fun findByIdIn(ids: Collection<Long>): List<Interest>
}
