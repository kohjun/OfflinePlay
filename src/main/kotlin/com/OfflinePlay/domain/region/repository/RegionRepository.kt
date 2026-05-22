package com.contenido.domain.region.repository

import com.contenido.domain.region.entity.Region
import org.springframework.data.jpa.repository.JpaRepository

/**
 * PR147 — 행정구역 read-only access. seed 는 V16 migration.
 */
interface RegionRepository : JpaRepository<Region, String> {

    /** 시도 17건 (level=1, parent=null). UI 의 cascade picker 1차 옵션. */
    fun findByLevelOrderByCodeAsc(level: Int): List<Region>

    /** 특정 시도의 시군구 목록. parent_code 기준. */
    fun findByParentCodeOrderByNameAsc(parentCode: String): List<Region>
}
