package com.contenido.domain.region.service

import com.contenido.domain.region.dto.SidoResponse
import com.contenido.domain.region.dto.SigunguResponse
import com.contenido.domain.region.repository.RegionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * PR147 — region tree 조회. seed (V16) 가 영구 데이터이므로 단순 fetch.
 */
@Service
@Transactional(readOnly = true)
class RegionService(
    private val regionRepository: RegionRepository,
) {

    /**
     * 모든 시도 + 각 시도의 시군구 nested 응답. frontend RegionPicker 가 한 번 호출 후 캐싱.
     */
    fun getSidoTree(): List<SidoResponse> {
        val sidos = regionRepository.findByLevelOrderByCodeAsc(1)
        return sidos.map { sido ->
            SidoResponse(
                code = sido.code,
                name = sido.name,
                sigunguList = regionRepository.findByParentCodeOrderByNameAsc(sido.code)
                    .map(SigunguResponse::from),
            )
        }
    }
}
