package com.contenido.domain.region.entity

import jakarta.persistence.*

/**
 * PR147 — 한국 행정구역 (시/도, 시/군/구).
 *
 *  - code        : 행정안전부 법정동 코드 prefix. PK. 시도 = 2자리 / 시군구 = 5자리.
 *  - parent_code : 시군구는 상위 시도의 code (2자리) — self-FK.
 *  - level       : 1=시도, 2=시군구.
 *
 * 본 entity 는 read-only — seed migration (V16) 으로 데이터를 주입한다.
 * 응용 entity (UserProfile / Event) 에서 `@ManyToOne Region` 로 참조.
 */
@Entity
@Table(
    name = "regions",
    indexes = [
        Index(name = "idx_regions_parent_level", columnList = "parent_code, level"),
    ],
)
class Region(

    @Id
    @Column(length = 10)
    val code: String,

    @Column(nullable = false, length = 50)
    val name: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_code")
    val parent: Region? = null,

    @Column(nullable = false)
    val level: Int,
)
