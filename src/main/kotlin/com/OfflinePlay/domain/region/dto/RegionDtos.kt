package com.contenido.domain.region.dto

import com.contenido.domain.region.entity.Region

/**
 * PR147 — region tree response. sido 1건 + 그 sigungu 리스트.
 *  - 비로그인도 접근 가능 — frontend 가 RegionPicker 의 cascade 옵션을 캐싱.
 */
data class SidoResponse(
    val code: String,
    val name: String,
    val sigunguList: List<SigunguResponse>,
)

data class SigunguResponse(
    val code: String,
    val name: String,
    val parentCode: String,
) {
    companion object {
        fun from(r: Region) = SigunguResponse(
            code = r.code,
            name = r.name,
            parentCode = r.parent?.code ?: "",
        )
    }
}
